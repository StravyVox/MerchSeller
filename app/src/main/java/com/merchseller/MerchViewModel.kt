package com.merchseller

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.merchseller.data.*
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MerchViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.create(application).dao()
    private val uiPreferences = application.getSharedPreferences("merchseller_ui", 0)
    private val _lowStockThreshold = MutableStateFlow(uiPreferences.getInt("low_stock_threshold", 5))
    val lowStockThreshold = _lowStockThreshold.asStateFlow()
    private val catalogReload = MutableStateFlow(0)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val products: StateFlow<Result<List<Product>>?> = catalogReload.flatMapLatest {
        dao.products().map<List<Product>, Result<List<Product>>> { Result.success(it) }
            .catch { emit(Result.failure(it)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val categories = dao.categories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _draft = MutableStateFlow<List<DraftLine>>(emptyList())
    val draft: StateFlow<List<DraftLine>> = _draft.asStateFlow()
    private val saleCloseMutex = Mutex()
    private val catalogMutex = Mutex()
    private val _isClosingSale = MutableStateFlow(false)
    val isClosingSale: StateFlow<Boolean> = _isClosingSale.asStateFlow()
    private val _lastClosed = MutableStateFlow<Sale?>(null)
    val lastClosed: StateFlow<Sale?> = _lastClosed.asStateFlow()
    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update = _update.asStateFlow()

    fun retryProducts() { catalogReload.update { it + 1 } }

    fun setLowStockThreshold(value: Int) {
        if (value !in 0..1_000_000) return
        uiPreferences.edit().putInt("low_stock_threshold", value).apply()
        _lowStockThreshold.value = value
    }

    suspend fun salesPage(from: Long, toExclusive: Long, paymentName: String, query: String, limit: Int, offset: Int) =
        dao.salesPage(from, toExclusive, paymentName, query, limit, offset)

    fun analytics(from: Long, toExclusive: Long) = combine(
        dao.salesSummary(from, toExclusive),
        dao.topSellingProducts(from, toExclusive, 5),
        dao.dailySales(from, toExclusive)
    ) { summary, products, daily -> AnalyticsSnapshot(summary, products, daily) }

    fun saveProduct(product: Product, done: (Result<Unit>) -> Unit) = catalogAction(done) {
        if (product.id == 0L) dao.insertProduct(product)
        else {
            val oldPhoto = dao.product(product.id)?.photoUri
            check(dao.updateProduct(product) == 1)
            if (oldPhoto != product.photoUri) deleteUnusedPhoto(oldPhoto)
        }
    }

    fun deleteProduct(product: Product, done: (Result<Unit>) -> Unit) = catalogAction(done) {
        val photo = dao.product(product.id)?.photoUri
        dao.deleteProduct(product.id)
        _draft.update { lines -> lines.filterNot { it.product.id == product.id } }
        deleteUnusedPhoto(photo)
    }

    private suspend fun deleteUnusedPhoto(uri: String?) {
        if (uri != null && !dao.usesPhoto(uri)) withContext(Dispatchers.IO) {
            BackupPhotos(getApplication()).deleteRestored(uri)
        }
    }

    fun addCategory(name: String, done: (Result<Unit>) -> Unit) = catalogAction(done) {
        dao.saveCategory(Category(name = name.trim()))
    }

    private fun catalogAction(done: (Result<Unit>) -> Unit, action: suspend () -> Unit) = viewModelScope.launch {
        val result = try {
            catalogMutex.withLock { action() }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        done(result)
    }

    fun addSku(sku: String, done: (String?) -> Unit) = viewModelScope.launch {
        val product = dao.bySku(sku.trim())
        done(when {
            product == null -> "Товар с таким кодом не найден."
            product.stock <= 0 -> "Товара нет в наличии."
            !addToDraft(product) -> "В чек уже добавлен весь доступный остаток."
            else -> null
        })
    }
    fun addToDraft(product: Product): Boolean {
        if (saleCloseMutex.isLocked || product.stock <= 0) return false
        if ((_draft.value.firstOrNull { it.product.id == product.id }?.quantity ?: 0) >= product.stock) return false
        _draft.value = _draft.value.toMutableList().apply {
            val index = indexOfFirst { it.product.id == product.id }
            if (index < 0) add(DraftLine(product, 1))
            else this[index] = DraftLine(product, this[index].quantity + 1)
        }
        return true
    }
    fun changeDraft(productId: Long, delta: Int) {
        if (saleCloseMutex.isLocked) return
        _draft.value = _draft.value.mapNotNull {
            if (it.product.id != productId) it else (it.quantity + delta).takeIf { q -> q > 0 }?.let { q -> it.copy(quantity = q.coerceAtMost(it.product.stock)) }
        }
    }
    fun clearDraft() { _draft.value = emptyList() }
    fun closeSale(payment: PaymentType, note: String, completed: () -> Unit, failed: (String) -> Unit) = viewModelScope.launch {
        // Блокировка берётся до первого приостановленного вызова, чтобы быстрые повторные нажатия не создали второй чек.
        if (!saleCloseMutex.tryLock()) return@launch
        _isClosingSale.value = true
        try {
            val lines = _draft.value
            if (lines.isEmpty()) return@launch
            _lastClosed.value = dao.closeSale(payment, lines, note)
            _draft.value = emptyList()
            completed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed(error.message ?: "Не удалось закрыть чек")
        } finally {
            _isClosingSale.value = false
            saleCloseMutex.unlock()
        }
    }
    fun updateSale(saleId: Long, payment: PaymentType, note: String) = viewModelScope.launch { dao.updateSale(saleId, payment, note) }
    suspend fun saleLines(id: Long) = dao.lines(id)
    fun dismissReceipt() { _lastClosed.value = null }
    /** Тихо проверяет открытый репозиторий на новую стабильную версию при запуске. */
    fun checkForUpdates() = viewModelScope.launch {
        _update.value = UpdateState.Checking
        _update.value = runCatching { GitHubUpdateManager.checkForUpdate() }
            .fold({ release -> release?.let(UpdateState::Available) ?: UpdateState.Idle }, { UpdateState.Idle })
    }
    /** Загружает найденный APK и сообщает Compose экрану ход загрузки. */
    fun downloadUpdate(release: PublicRelease) = viewModelScope.launch {
        _update.value = UpdateState.Downloading(release, 0f)
        runCatching {
            GitHubUpdateManager.download(getApplication(), release) { percent ->
                _update.update { current ->
                    if (current is UpdateState.Downloading) current.copy(progress = percent) else current
                }
            }
        }.onSuccess { file -> _update.value = UpdateState.ReadyToInstall(file) }
            .onFailure { error -> _update.value = UpdateState.Failed(error.message ?: "Не удалось загрузить обновление") }
    }
    fun dismissUpdate() { _update.value = UpdateState.Idle }
    fun exportLabels(uri: Uri, products: List<Product>, options: LabelOptions, done: (Result<Unit>) -> Unit) =
        catalogAction(done) {
            withContext(Dispatchers.IO) { PdfLabelsExporter.export(getApplication(), uri, products, options) }
        }

    fun exportBackup(uri: Uri, includePhotos: Boolean, done: (Result<Unit>) -> Unit) =
        catalogAction(done) { BackupManager.export(getApplication(), uri, dao, includePhotos) }

    fun importBackup(uri: Uri, done: (Result<Unit>) -> Unit) = viewModelScope.launch {
        if (!saleCloseMutex.tryLock()) {
            done(Result.failure(IllegalStateException("Дождитесь сохранения продажи.")))
            return@launch
        }
        val result = try {
            catalogMutex.withLock { BackupManager.import(getApplication(), uri, dao) }
            clearDraft()
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            saleCloseMutex.unlock()
        }
        done(result)
    }
}

data class AnalyticsSnapshot(
    val summary: SalesSummary,
    val topProducts: List<TopSellingProduct>,
    val dailySales: List<DailySales>
)
