@file:OptIn(ExperimentalMaterial3Api::class)

package com.merchseller

import android.os.Bundle
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.merchseller.data.*
import com.merchseller.ui.MerchTheme
import java.text.DateFormat
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Нативная заставка обеспечивает мгновенный и брендированный первый кадр.
        installSplashScreen().setOnExitAnimationListener { splash ->
            splash.view.animate().alpha(0f).setDuration(180)
                .withEndAction { splash.remove() }.start()
        }
        super.onCreate(savedInstanceState)
        setContent { MerchTheme { MerchApp() } }
    }
}

private enum class Tab(val title: String, val icon: Int) {
    STOCK("Склад", R.drawable.lucide_warehouse),
    PRODUCTS("Товары", R.drawable.lucide_tags),
    SALE("Касса", R.drawable.lucide_shopping_basket),
    HISTORY("История", R.drawable.lucide_receipt_text),
    ANALYTICS("Аналитика", R.drawable.lucide_chart_no_axes_combined)
}

@Composable private fun Glyph(id: Int, label: String? = null) = Icon(painterResource(id), label)

@Composable private fun MerchApp(vm: MerchViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.STOCK) }
    var dismissedUpdateTag by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingInstallFile by remember { mutableStateOf<File?>(null) }
    val receipt by vm.lastClosed.collectAsState()
    val update by vm.update.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val installerPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = pendingInstallFile
        if (activity != null && apk != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls())) {
            launchApkInstaller(activity, apk)
        } else if (apk != null) {
            Toast.makeText(context, "Разрешите установку MerchSeller из этого источника, чтобы продолжить.", Toast.LENGTH_LONG).show()
        }
        pendingInstallFile = null
        vm.dismissUpdate()
    }
    // Проверяем стабильный GitHub-релиз при каждом запуске приложения.
    LaunchedEffect(Unit) { vm.checkForUpdates() }
    LaunchedEffect(update) {
        val ready = update as? UpdateState.ReadyToInstall ?: return@LaunchedEffect
        if (activity == null) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = ready.file
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            installerPermission.launch(settingsIntent)
        } else {
            launchApkInstaller(activity, ready.file)
            vm.dismissUpdate()
        }
    }
    Scaffold(containerColor = Color.Transparent, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f), tonalElevation = 10.dp) {
            Tab.entries.forEach { item -> NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Glyph(item.icon) }, label = { Text(item.title) }) }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(pad)) {
            // Переходы между разделами делают навигацию живой, но не отвлекают от работы кассира.
            AnimatedContent(
                tab,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                label = "section"
            ) { current ->
                when (current) { Tab.STOCK -> StockScreen(vm); Tab.PRODUCTS -> ProductScreen(vm); Tab.SALE -> SaleScreen(vm); Tab.HISTORY -> HistoryScreen(vm); Tab.ANALYTICS -> AnalyticsScreen(vm) }
            }
            AnimatedVisibility(receipt != null, enter = scaleIn(spring(stiffness = Spring.StiffnessLow)), exit = scaleOut()) { receipt?.let { ReceiptClosed(it) { vm.dismissReceipt() } } }
        }
    }
    when (val state = update) {
        is UpdateState.Available -> if (dismissedUpdateTag != state.release.tag) AlertDialog(
            onDismissRequest = { dismissedUpdateTag = state.release.tag; vm.dismissUpdate() },
            icon = { Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Доступна версия ${state.release.version}") },
            text = { Text(state.release.notes.ifBlank { "Вы можете установить обновление сейчас или позже." }, maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            confirmButton = { Button(onClick = { vm.downloadUpdate(state.release) }) { Text("Скачать и установить") } },
            dismissButton = { TextButton(onClick = { dismissedUpdateTag = state.release.tag; vm.dismissUpdate() }) { Text("Позже") } }
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Default.Downloading, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Скачивание обновления") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { LinearProgressIndicator(progress = state.progress, modifier = Modifier.fillMaxWidth()); Text("${(state.progress * 100).toInt()}% · ${state.release.assetName}") } },
            confirmButton = {}
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("Не удалось обновить приложение") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("Закрыть") } }
        )
        else -> Unit
    }
}

/** Передаёт проверенный APK установщику Android с одноразовым доступом только на чтение. */
private fun launchApkInstaller(activity: Activity, apk: File) {
    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    activity.startActivity(installIntent)
}

@Composable private fun Page(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(18.dp)); Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }; action?.invoke() }
        Spacer(Modifier.height(16.dp)); body()
    }
}

@Composable private fun StockScreen(vm: MerchViewModel) {
    val catalog by vm.products.collectAsState()
    val products = catalog?.getOrNull().orEmpty()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var lowOnly by rememberSaveable { mutableStateOf(false) }
    var pdf by rememberSaveable { mutableStateOf(false) }
    var transfer by rememberSaveable { mutableStateOf(false) }
    var includePhotos by rememberSaveable { mutableStateOf(true) }
    var importUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var columns by rememberSaveable { mutableIntStateOf(3) }
    var rows by rememberSaveable { mutableIntStateOf(5) }
    var photos by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Product?>(null) }

    val pdfFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            busy = true
            vm.exportLabels(uri, products, LabelOptions(columns, rows, photos)) { result ->
                busy = false
                message = result.fold(
                    { "PDF с этикетками сохранён" },
                    { "Не удалось сохранить PDF. Выберите другой файл и повторите попытку." }
                )
            }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            busy = true
            vm.exportBackup(uri, includePhotos) { result ->
                busy = false
                result.onSuccess { shareBackup(context, uri) }
                    .onFailure { message = "Не удалось создать копию. ${it.message.orEmpty()}" }
            }
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importUri = uri
    }
    val visible = products.filter { it.matches(query) && (!lowOnly || it.stock <= LOW_STOCK) }
    Page("Склад") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCard("В каталоге", products.size.toString(), Modifier.weight(1f))
                    MetricCard("Единиц на складе", products.sumOf { it.stock.toLong() }.toString(), Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ pdf = true }, enabled = products.isNotEmpty() && !busy) {
                        Glyph(R.drawable.lucide_printer); Spacer(Modifier.width(8.dp)); Text("Этикетки")
                    }
                    OutlinedButton({ transfer = true }, enabled = !busy) {
                        Glyph(R.drawable.lucide_arrow_left_right); Spacer(Modifier.width(8.dp)); Text("Перенос")
                    }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            message?.let { status -> item { Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            item { Field("Поиск по названию или коду", query) { query = it } }
            item { FilterChip(lowOnly, { lowOnly = !lowOnly }, { Text("Заканчиваются товары") }) }
            if (catalog == null || catalog?.isFailure == true) item { CatalogStatus(catalog) }
            else if (visible.isEmpty()) item {
                Empty(when {
                    products.isEmpty() -> "Добавьте товар в каталоге, чтобы увидеть его остаток."
                    query.isNotBlank() -> "Товары по этому запросу не найдены."
                    else -> "Нет товаров с остатком 5 шт. или меньше."
                })
            }
            items(visible, key = { it.id }) { ProductRow(it) { selected = it } }
        }
    }
    selected?.let { ProductEditor(it, vm) { selected = null } }
    if (pdf) {
        ModalBottomSheet(onDismissRequest = { pdf = false }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Этикетки для печати", style = MaterialTheme.typography.titleLarge)
                Text("Лист A4 · до ${columns * rows} этикеток на странице")
                SettingStepper("Столбцы", columns, 1..5) { columns = it }
                SettingStepper("Строки", rows, 1..10) { rows = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Добавить фото товаров", Modifier.weight(1f))
                    Switch(photos, { photos = it }, modifier = Modifier.semantics { contentDescription = "Добавить фото товаров" })
                }
                Button({
                    pdf = false
                    pdfFile.launch("MerchSeller-${System.currentTimeMillis()}.pdf")
                }, Modifier.fillMaxWidth(), enabled = products.isNotEmpty()) { Text("Создать PDF с этикетками") }
            }
        }
    }
    if (transfer) AlertDialog(
        onDismissRequest = { transfer = false },
        title = { Text("Перенос данных") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Копия содержит товары, типы и чеки. Файл не шифруется. Максимальный размер — 8\u00a0МиБ.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Включить фотографии", Modifier.weight(1f))
                    Switch(includePhotos, { includePhotos = it }, modifier = Modifier.semantics { contentDescription = "Включить фотографии" })
                }
            }
        },
        confirmButton = { TextButton({ transfer = false; exportFile.launch("MerchSeller-${System.currentTimeMillis()}.json") }) { Text("Создать и отправить копию") } },
        dismissButton = { TextButton({ transfer = false; importFile.launch(arrayOf("application/json")) }) { Text("Выбрать копию") } }
    )
    importUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { importUri = null },
            title = { Text("Заменить данные?") },
            text = { Text("Текущие товары, типы и история продаж будут заменены данными из копии. Открытый чек будет очищен.") },
            confirmButton = { Button({
                importUri = null
                busy = true
                vm.importBackup(uri) { result ->
                    busy = false
                    message = result.fold({ "Данные восстановлены из копии" }, { "Не удалось восстановить данные. ${it.message.orEmpty()}" })
                }
            }) { Text("Заменить данные") } },
            dismissButton = { TextButton({ importUri = null }) { Text("Отмена") } }
        )
    }
}

private const val LOW_STOCK = 5

private fun Product.matches(query: String): Boolean =
    name.contains(query.trim(), ignoreCase = true) || sku.contains(query.trim(), ignoreCase = true)

@Composable private fun SettingStepper(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        IconButton({ onChange(value - 1) }, enabled = value > range.first) {
            Icon(Icons.Default.Remove, "Уменьшить: $title")
        }
        Text(value.toString(), fontWeight = FontWeight.Bold)
        IconButton({ onChange(value + 1) }, enabled = value < range.last) {
            Icon(Icons.Default.Add, "Увеличить: $title")
        }
    }
}

/** Открывает привычное системное меню: файл можно передать через мессенджер, почту или облако. */
private fun shareBackup(context: android.content.Context, uri: android.net.Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    context.startActivity(Intent.createChooser(intent, "Передать резервную копию"))
}

@Composable private fun ProductScreen(vm: MerchViewModel) {
    val catalog by vm.products.collectAsState()
    val products = catalog?.getOrNull().orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    var editor by remember { mutableStateOf<Product?>(null) }
    var addCategory by rememberSaveable { mutableStateOf(false) }
    Page("Товары") {
        Field("Поиск по названию или коду", query) { query = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ editor = Product(sku = "", name = "", priceKopecks = 0) }) { Text("Добавить товар") }
            TextButton({ addCategory = true }) { Text("Новый тип") }
        }
        val visible = products.filter { it.matches(query) }
        if (catalog == null || catalog?.isFailure == true) {
            CatalogStatus(catalog)
        } else if (visible.isEmpty()) {
            Empty(if (products.isEmpty()) "Добавьте первый товар, чтобы начать продажи." else "Товары по этому запросу не найдены.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(visible, key = { it.id }) { ProductRow(it) { editor = it } }
            }
        }
    }
    editor?.let { ProductEditor(it, vm) { editor = null } }
    if (addCategory) CategoryDialog(vm) { addCategory = false }
}

@Composable private fun ProductRow(product: Product, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (product.photoUri == null) Glyph(R.drawable.lucide_package)
                else AsyncImage(
                    model = product.photoUri, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop, error = painterResource(R.drawable.lucide_package)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(product.name, fontWeight = FontWeight.SemiBold)
                Text(product.sku, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(money(product.totalPriceKopecks), fontWeight = FontWeight.Bold)
                Text(
                    "${product.stock}\u00a0шт.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (product.stock <= LOW_STOCK) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable private fun ProductEditor(initial: Product, vm: MerchViewModel, dismiss: () -> Unit) {
    val categories by vm.categories.collectAsState()
    val context = LocalContext.current
    var sku by rememberSaveable { mutableStateOf(initial.sku) }
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var description by rememberSaveable { mutableStateOf(initial.description) }
    var price by rememberSaveable { mutableStateOf(BigDecimal.valueOf(initial.priceKopecks, 2).toPlainString().replace('.', ',')) }
    var vat by rememberSaveable { mutableStateOf(initial.vatRate.toString().replace('.', ',')) }
    var stock by rememberSaveable { mutableStateOf(initial.stock.toString()) }
    var categoryId by rememberSaveable { mutableStateOf(initial.categoryId) }
    var photoUri by rememberSaveable { mutableStateOf(initial.photoUri) }
    var categoryMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var codeError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val priceValue = price.parsePriceKopecks()
    val vatValue = vat.decimal()?.takeIf { it in 0.0..100.0 }
    val stockValue = stock.trim().toIntOrNull()?.takeIf { it >= 0 }
    val total = if (priceValue != null && vatValue != null) kopecksWithVat(priceValue, vatValue) else null
    val skuError = codeError ?: when {
        submitted && sku.isBlank() -> "Введите код товара."
        submitted && sku.trim().length > 256 -> "Сократите код до 256 символов."
        else -> null
    }
    val nameError = when {
        submitted && name.isBlank() -> "Введите название."
        submitted && name.trim().length > 256 -> "Сократите название до 256 символов."
        else -> null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onSuccess { photoUri = uri.toString() }.onFailure {
            message = "Не удалось открыть фото. Выберите другое изображение."
        }
    }

    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp)) {
                TopAppBar(
                    title = { Text(if (initial.id == 0L) "Новый товар" else "Карточка товара") },
                    navigationIcon = { IconButton(dismiss, enabled = !busy) { Icon(Icons.Default.ArrowBack, "Назад") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Основное", style = MaterialTheme.typography.titleMedium) }
                    item { Field("Название", name, error = nameError) { name = it } }
                    item { Field("Код товара", sku, error = skuError) { sku = it; codeError = null } }
                    item { Field("Описание", description, error = if (submitted && description.trim().length > 8192) "Сократите описание до 8 192 символов." else null, singleLine = false) { description = it } }
                    item {
                        ExposedDropdownMenuBox(categoryMenu, { categoryMenu = it }) {
                            OutlinedTextField(
                                value = categories.firstOrNull { it.id == categoryId }?.name ?: "Без типа",
                                onValueChange = {}, readOnly = true, label = { Text("Тип товара") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryMenu) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(categoryMenu, { categoryMenu = false }) {
                                DropdownMenuItem({ Text("Без типа") }, { categoryId = null; categoryMenu = false })
                                categories.forEach { category ->
                                    DropdownMenuItem({ Text(category.name) }, { categoryId = category.id; categoryMenu = false })
                                }
                            }
                        }
                    }
                    item { Text("Цена и НДС", style = MaterialTheme.typography.titleMedium) }
                    item { Field("Цена без НДС", price, KeyboardType.Decimal, if (submitted && priceValue == null) "Введите цену от 0 с точностью до копейки." else null) { price = it } }
                    item { Field("Ставка НДС, %", vat, KeyboardType.Decimal, if (submitted && vatValue == null) "Введите число от 0 до 100." else null) { vat = it } }
                    if (total != null) item { SummaryRow("Цена с НДС", money(total)) }
                    item { Text("Остаток и фото", style = MaterialTheme.typography.titleMedium) }
                    item { Field("Остаток, шт.", stock, KeyboardType.Number, if (submitted && stockValue == null) "Введите целое число от 0." else null) { stock = it } }
                    item {
                        if (photoUri != null) AsyncImage(photoUri, "Фото товара", Modifier.height(120.dp).fillMaxWidth(), contentScale = ContentScale.Fit)
                        OutlinedButton({ picker.launch(arrayOf("image/*")) }) { Text(if (photoUri == null) "Добавить фото" else "Заменить фото") }
                        if (photoUri != null) TextButton({ photoUri = null }) { Text("Убрать фото") }
                    }
                    if (initial.id != 0L) item {
                        TextButton({ confirmDelete = true }, enabled = !busy) {
                            Text("Удалить товар", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
                Button({
                    submitted = true
                    if (sku.isNotBlank() && sku.trim().length <= 256 && name.isNotBlank() && name.trim().length <= 256 &&
                        description.trim().length <= 8192 && priceValue != null && vatValue != null && stockValue != null
                    ) {
                        busy = true
                        message = null
                        vm.saveProduct(initial.copy(
                            sku = sku.trim(), name = name.trim(), description = description.trim(),
                            priceKopecks = priceValue, vatRate = vatValue, stock = stockValue, categoryId = categoryId,
                            photoUri = photoUri, updatedAt = System.currentTimeMillis()
                        )) { result ->
                            busy = false
                            result.onSuccess { dismiss() }.onFailure {
                                if (it is android.database.sqlite.SQLiteConstraintException) codeError = "Этот код уже занят. Введите другой."
                                else message = "Не удалось сохранить товар. Повторите попытку."
                            }
                        }
                    }
                }, Modifier.fillMaxWidth().padding(vertical = 8.dp), enabled = !busy) {
                    Text(if (busy) "Сохраняем товар…" else "Сохранить товар")
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить товар?") },
        text = { Text("«${initial.name}» исчезнет из каталога. Закрытые чеки сохранятся.") },
        confirmButton = { TextButton({
            confirmDelete = false
            busy = true
            vm.deleteProduct(initial) { result ->
                busy = false
                result.onSuccess { dismiss() }.onFailure { message = "Не удалось удалить товар." }
            }
        }) { Text("Удалить товар", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Отмена") } }
    )
}

private fun String.decimal(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

@Composable private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    error: String? = null,
    singleLine: Boolean = true,
    onChange: (String) -> Unit
) = OutlinedTextField(
    value, onChange, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    singleLine = singleLine, isError = error != null, supportingText = error?.let { { Text(it) } },
    modifier = Modifier.fillMaxWidth()
)

@Composable private fun CategoryDialog(vm: MerchViewModel, dismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() }, title = { Text("Новый тип товара") },
        text = { Field("Название типа", name, error = error) { name = it; error = null } },
        confirmButton = { Button({
            if (name.trim().length !in 1..200) {
                error = "Введите название длиной до 200 символов."
            } else {
                busy = true
                vm.addCategory(name) { result ->
                    busy = false
                    result.onSuccess { dismiss() }.onFailure {
                        error = if (it is android.database.sqlite.SQLiteConstraintException) "Тип с таким названием уже есть." else "Не удалось добавить тип."
                    }
                }
            }
        }, enabled = !busy) { Text("Добавить тип") } },
        dismissButton = { TextButton(dismiss, enabled = !busy) { Text("Отмена") } }
    )
}

@Composable private fun SaleScreen(vm: MerchViewModel) {
    val catalog by vm.products.collectAsState()
    val products = catalog?.getOrNull().orEmpty()
    val draft by vm.draft.collectAsState()
    val isClosing by vm.isClosingSale.collectAsState()
    val context = LocalContext.current
    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
    }
    var code by rememberSaveable { mutableStateOf("") }
    var scanner by rememberSaveable { mutableStateOf(false) }
    var checkout by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val total = draft.totalKopecks()
    val addCode: (String) -> Unit = { value ->
        vm.addSku(value) { error ->
            message = error
            if (error == null) code = ""
        }
    }
    Page("Касса", if (draft.isEmpty()) "Новый чек" else "В чеке · ${draft.sumOf { it.quantity }}\u00a0шт.") {
        if (hasCamera) {
            Button({ scanner = true }, Modifier.fillMaxWidth(), enabled = !isClosing) {
                Glyph(R.drawable.lucide_scan_qr_code)
                Spacer(Modifier.width(8.dp))
                Text("Сканировать код")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(code, { code = it; message = null }, label = { Text("Код товара") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton({ addCode(code) }, enabled = code.isNotBlank() && !isClosing) { Text("Добавить") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(draft, key = { it.product.id }) { line ->
                DraftRow(line, { vm.changeDraft(line.product.id, -1) }, { vm.changeDraft(line.product.id, 1) })
            }
            val available = products.filter { it.stock > 0 && it.matches(code) }
            if (available.isNotEmpty()) item { Text("Товары в наличии", style = MaterialTheme.typography.titleMedium) }
            if (catalog == null || catalog?.isFailure == true) item { CatalogStatus(catalog) }
            else if (draft.isEmpty() && available.isEmpty()) item {
                Empty(if (products.isEmpty()) "Добавьте товары в каталоге, чтобы начать продажу." else "Нет подходящих товаров в наличии.")
            }
            items(available, key = { "product-${it.id}" }) { product ->
                ProductRow(product) {
                    message = if (vm.addToDraft(product)) null else "В чек уже добавлен весь доступный остаток."
                }
            }
        }
        if (draft.isNotEmpty()) {
            Divider()
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryRow("Итого с НДС", money(total))
                Button({ checkout = true }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !isClosing) {
                    Text("Перейти к оплате")
                }
            }
        }
    }
    if (scanner && hasCamera) ScannerDialog(onCode = { scanner = false; addCode(it) }, onDismiss = { scanner = false })
    if (checkout) CheckoutDialog(total, isClosing, onDismiss = { if (!isClosing) checkout = false }, onPay = { payment, note ->
        vm.closeSale(payment, note, completed = { checkout = false }, failed = { message = it; checkout = false })
    })
}

@Composable private fun DraftRow(line: DraftLine, minus: () -> Unit, plus: () -> Unit) {
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            SummaryRow(line.product.name, money(line.totalKopecks))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(money(line.product.totalPriceKopecks) + " за шт.", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(minus) { Icon(Icons.Default.Remove, "Уменьшить количество: ${line.product.name}") }
                AnimatedContent(
                    line.quantity,
                    transitionSpec = { fadeIn(tween(130)) togetherWith fadeOut(tween(90)) },
                    label = "quantity"
                ) { quantity -> Text(quantity.toString(), fontWeight = FontWeight.Bold) }
                IconButton(plus, enabled = line.quantity < line.product.stock) {
                    Icon(Icons.Default.Add, "Увеличить количество: ${line.product.name}")
                }
            }
        }
    }
}

@Composable private fun CheckoutDialog(total: BigInteger, isClosing: Boolean, onDismiss: () -> Unit, onPay: (PaymentType, String) -> Unit) { var payment by remember { mutableStateOf(PaymentType.CARD) }; var note by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.Payments, null) }, title = { Text("Оплата") }, text = { Column { Text("К оплате ${money(total)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp)); Row { FilterChip(payment == PaymentType.CARD, { payment = PaymentType.CARD }, { Text("Карта") }, leadingIcon = { Icon(Icons.Default.CreditCard, null) }); Spacer(Modifier.width(8.dp)); FilterChip(payment == PaymentType.CASH, { payment = PaymentType.CASH }, { Text("Наличные") }, leadingIcon = { Icon(Icons.Default.Payments, null) }) }; Spacer(Modifier.height(8.dp)); Field("Комментарий к чеку", note, error = note.length.takeIf { it > 4096 }?.let { "Сократите комментарий до 4 096 символов." }) { note = it } } }, confirmButton = { Button(onClick = { onPay(payment, note.trim()) }, enabled = !isClosing && note.trim().length <= 4096) { Text(if (isClosing) "Сохраняем продажу…" else "Подтвердить оплату") } }, dismissButton = { TextButton(onDismiss, enabled = !isClosing) { Text("Отмена") } }) }

@Composable private fun HistoryScreen(vm: MerchViewModel) {
    val sales by vm.sales.collectAsState(); var edit by remember { mutableStateOf<Sale?>(null) }
    Page("История продаж", pluralStringResource(R.plurals.receipt_count, sales.size, sales.size)) { if (sales.isEmpty()) Empty("Закрытые чеки появятся здесь.") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) { items(sales, key = { it.id }) { sale -> Card(Modifier.fillMaxWidth().clickable { edit = sale }) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (sale.paymentType == PaymentType.CARD) Icons.Default.CreditCard else Icons.Default.Payments, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Чек №\u00A0${sale.id}", fontWeight = FontWeight.SemiBold); Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(sale.createdAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(money(sale.totalKopecks), fontWeight = FontWeight.Bold) } } } } }
    edit?.let { SaleEditor(it, vm, { edit = null }) }
}

@Composable private fun SaleEditor(sale: Sale, vm: MerchViewModel, dismiss: () -> Unit) { var payment by remember { mutableStateOf(sale.paymentType) }; var note by remember { mutableStateOf(sale.note) }; var lines by remember { mutableStateOf<List<SaleLine>?>(null) }; LaunchedEffect(sale.id) { lines = vm.saleLines(sale.id) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Чек №\u00A0${sale.id}") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(DateFormat.getDateTimeInstance().format(Date(sale.createdAt)), color = MaterialTheme.colorScheme.onSurfaceVariant); lines?.forEach { Text("${it.productName} × ${it.quantity} — ${money(it.lineTotalKopecks)}", modifier = Modifier.padding(top = 6.dp)) }; Spacer(Modifier.height(8.dp)); Row { FilterChip(payment == PaymentType.CARD, { payment = PaymentType.CARD }, { Text("Карта") }); Spacer(Modifier.width(8.dp)); FilterChip(payment == PaymentType.CASH, { payment = PaymentType.CASH }, { Text("Наличные") }) }; Field("Комментарий", note, error = note.length.takeIf { it > 4096 }?.let { "Сократите комментарий до 4 096 символов." }) { note = it } } }, confirmButton = { Button({ vm.updateSale(sale.id, payment, note.trim()); dismiss() }, enabled = note.trim().length <= 4096) { Text("Сохранить") } }, dismissButton = { TextButton(dismiss) { Text("Закрыть") } }) }

@Composable private fun AnalyticsScreen(vm: MerchViewModel) { val metrics by vm.todayMetrics.collectAsState(); val (turnover, count) = metrics; val catalog by vm.products.collectAsState(); val products = catalog?.getOrNull().orEmpty(); val sales by vm.sales.collectAsState(); Page("Аналитика", "Показатели за сегодня") { Column(Modifier.verticalScroll(rememberScrollState())) { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCard("Выручка", money(turnover), Modifier.weight(1f)); MetricCard("Продажи", count.toString(), Modifier.weight(1f)) }; Spacer(Modifier.height(20.dp)); Text("Сводка", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { SummaryRow("Средний чек", money(averageKopecks(turnover, count))); SummaryRow("Товаров в каталоге", products.size.toString()); SummaryRow("Чеков всего", sales.size.toString()); SummaryRow("Позиций с низким остатком", products.count { it.stock <= LOW_STOCK }.toString()) } } } }

}

@Composable private fun ReceiptClosed(sale: Sale, dismiss: () -> Unit) = Surface(Modifier.fillMaxSize().background(Color(0xCC0F1B32)), color = Color.Transparent) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Card(Modifier.padding(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(painterResource(R.drawable.lucide_check), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(12.dp)); Text("Продажа сохранена", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Чек №\u00A0${sale.id} · ${money(sale.totalKopecks)}", color = MaterialTheme.colorScheme.onPrimaryContainer); Spacer(Modifier.height(18.dp)); Button(dismiss) { Text("Готово") } } } } }
@Composable private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) = Card(modifier) { Column(Modifier.padding(14.dp)) { Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun SummaryRow(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(label, Modifier.weight(1f)); Text(value, fontWeight = FontWeight.SemiBold) }
@Composable private fun CatalogStatus(catalog: Result<List<Product>>?) {
    if (catalog == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else Empty("Не удалось загрузить товары. Откройте приложение заново.")
}

@Composable private fun Empty(text: String) = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
private fun money(value: Long) = money(value.toBigInteger())

private fun money(value: BigInteger) =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU")).format(BigDecimal(value, 2))
