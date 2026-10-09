package com.merchseller

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.merchseller.data.*
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class MerchViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.create(application).dao()
    val products = dao.products().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = dao.categories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sales = dao.sales().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val todayTurnover = dao.turnover(todayStart()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)
    val todaySales = dao.salesCount(todayStart()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _draft = MutableStateFlow<List<DraftLine>>(emptyList())
    val draft: StateFlow<List<DraftLine>> = _draft.asStateFlow()
    private val _lastClosed = MutableStateFlow<Sale?>(null)
    val lastClosed: StateFlow<Sale?> = _lastClosed.asStateFlow()
    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update = _update.asStateFlow()

    fun saveProduct(product: Product) = viewModelScope.launch { dao.saveProduct(product) }
    fun deleteProduct(product: Product) = viewModelScope.launch { dao.deleteProduct(product.id) }
    fun addCategory(name: String) = viewModelScope.launch { if (name.isNotBlank()) dao.saveCategory(Category(name = name.trim())) }
    fun addSku(sku: String, onMissing: () -> Unit = {}) = viewModelScope.launch {
        val product = dao.bySku(sku.trim())
        if (product == null) onMissing() else addToDraft(product)
    }
    fun addToDraft(product: Product) {
        if (product.stock <= 0) return
        _draft.value = _draft.value.toMutableList().apply {
            val index = indexOfFirst { it.product.id == product.id }
            if (index < 0) add(DraftLine(product, 1)) else if (this[index].quantity < product.stock) this[index] = this[index].copy(quantity = this[index].quantity + 1)
        }
    }
    fun changeDraft(productId: Long, delta: Int) {
        _draft.value = _draft.value.mapNotNull {
            if (it.product.id != productId) it else (it.quantity + delta).takeIf { q -> q > 0 }?.let { q -> it.copy(quantity = q.coerceAtMost(it.product.stock)) }
        }
    }
    fun clearDraft() { _draft.value = emptyList() }
    fun closeSale(payment: PaymentType, note: String, completed: () -> Unit) = viewModelScope.launch {
        val lines = _draft.value
        if (lines.isEmpty()) return@launch
        val id = dao.closeSale(payment, lines, note)
        _lastClosed.value = Sale(id = id, paymentType = payment, total = lines.sumOf { it.product.totalPrice * it.quantity }, note = note)
        _draft.value = emptyList()
        completed()
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
    fun exportBackup(context: Context, uri: Uri, done: (Result<Unit>) -> Unit) = viewModelScope.launch {
        // Работа с файлом идёт вне интерфейса и не блокирует кассу.
        runCatching { BackupManager.export(context.contentResolver, uri, dao) }.also(done)
    }
    fun importBackup(context: Context, uri: Uri, done: (Result<Unit>) -> Unit) = viewModelScope.launch {
        runCatching { BackupManager.import(context.contentResolver, uri, dao) }.also(done)
    }
    private fun todayStart(): Long = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
}
