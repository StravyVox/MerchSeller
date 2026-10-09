@file:OptIn(ExperimentalMaterial3Api::class)

package com.merchseller

import android.os.Bundle
import android.app.Activity
import android.content.Intent
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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.merchseller.data.*
import com.merchseller.ui.MerchTheme
import java.text.DateFormat
import java.util.Date
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Нативная заставка обеспечивает мгновенный и брендированный первый кадр.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { MerchTheme { MerchApp() } }
    }
}

private enum class Tab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    STOCK("Склад", Icons.Default.Inventory2), PRODUCTS("Товары", Icons.Default.Category), SALE("Продажа", Icons.Default.PointOfSale), HISTORY("История", Icons.Default.ReceiptLong), ANALYTICS("Анализ", Icons.Default.Insights)
}

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
            Tab.entries.forEach { item -> NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Icon(item.icon, null) }, label = { Text(item.title) }) }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF8FAFF), Color(0xFFF0F4FF)))).padding(pad)) {
            // Переходы между разделами делают навигацию живой, но не отвлекают от работы кассира.
            AnimatedContent(tab, transitionSpec = { (fadeIn() + slideInHorizontally { it / 12 }) togetherWith (fadeOut() + slideOutHorizontally { -it / 12 }) }, label = "section") { current ->
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
            text = { Text(state.release.notes.ifBlank { "Вышло обновление MerchSeller. Скачайте и установите его, чтобы продолжить." }, maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            confirmButton = { Button(onClick = { vm.downloadUpdate(state.release) }) { Text("Скачать и установить") } },
            dismissButton = { TextButton(onClick = { dismissedUpdateTag = state.release.tag; vm.dismissUpdate() }) { Text("Позже") } }
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Default.Downloading, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Загружаю обновление") },
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
    val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
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
    val products by vm.products.collectAsState(); var columns by remember { mutableIntStateOf(3) }; var rows by remember { mutableIntStateOf(5) }; var showPhoto by remember { mutableStateOf(false) }; val context = LocalContext.current; var transferDialog by remember { mutableStateOf(false) }
    val exportDocument = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { target -> vm.exportBackup(context, target) { result -> result.onSuccess { shareBackup(context, target); Toast.makeText(context, "Резервная копия готова", Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(context, "Ошибка экспорта: ${it.message}", Toast.LENGTH_LONG).show() } } } }
    val importDocument = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri -> uri?.let { source -> vm.importBackup(context, source) { result -> Toast.makeText(context, result.fold({ "Данные импортированы" }, { "Ошибка импорта: ${it.message}" }), Toast.LENGTH_LONG).show() } } }
    Page("Склад", "Остатки, этикетки и резервные копии", action = { FilledTonalIconButton({ transferDialog = true }) { Icon(Icons.Default.ImportExport, "Передача данных") } }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCard("Позиций", products.size.toString(), Modifier.weight(1f)); MetricCard("Единиц", products.sumOf { it.stock }.toString(), Modifier.weight(1f)) }
        Spacer(Modifier.height(20.dp)); Text("PDF с QR‑кодами", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text("Компактные листы A4 сохраняются в «Загрузки»", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp)); SettingStepper("Колонки", columns, 1..5) { columns = it }; SettingStepper("Строки", rows, 1..10) { rows = it }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(showPhoto, { showPhoto = it }); Spacer(Modifier.width(10.dp)); Text("Показывать фото товаров") }
        Spacer(Modifier.height(12.dp)); Button(onClick = { val uri = PdfLabelsExporter.export(context, products, LabelOptions(columns, rows, showPhoto)); Toast.makeText(context, if (uri == null) "Нет товаров для экспорта" else "PDF сохранён в Загрузках", Toast.LENGTH_LONG).show() }, modifier = Modifier.fillMaxWidth(), enabled = products.isNotEmpty()) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text("Сформировать листы") }
        Spacer(Modifier.height(24.dp)); Text("Низкий остаток", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(products.filter { it.stock <= 5 }, key = { it.id }) { ProductRow(it, onClick = {}) } }
    }
    if (transferDialog) TransferDialog(onDismiss = { transferDialog = false }, onExport = { exportDocument.launch("MerchSeller_backup_${System.currentTimeMillis()}.json") }, onImport = { importDocument.launch("application/json") })
}

@Composable private fun SettingStepper(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); IconButton(onClick = { onChange((value - 1).coerceAtLeast(range.first)) }) { Icon(Icons.Default.Remove, "Меньше") }; Text(value.toString(), fontWeight = FontWeight.Bold, modifier = Modifier.animateContentSize()); IconButton(onClick = { onChange((value + 1).coerceAtMost(range.last)) }) { Icon(Icons.Default.Add, "Больше") } }

@Composable private fun TransferDialog(onDismiss: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary) },
    title = { Text("Передача данных") },
    text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Создайте JSON‑копию и отправьте её на другой телефон. При импорте текущая локальная база будет заменена.")
        FilledTonalButton(onExport, Modifier.fillMaxWidth()) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Экспортировать и отправить") }
        OutlinedButton(onImport, Modifier.fillMaxWidth()) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Импортировать копию") }
    } }, confirmButton = { TextButton(onDismiss) { Text("Готово") } }
)

/** Открывает привычное системное меню: файл можно передать через мессенджер, почту или облако. */
private fun shareBackup(context: android.content.Context, uri: android.net.Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    context.startActivity(Intent.createChooser(intent, "Передать резервную копию"))
}

@Composable private fun ProductScreen(vm: MerchViewModel) {
    val products by vm.products.collectAsState(); val categories by vm.categories.collectAsState(); var editor by remember { mutableStateOf<Product?>(null) }; var addCategory by remember { mutableStateOf(false) }
    Page("База товаров", "Карточки, типы и цены", action = { Row { IconButton({ addCategory = true }) { Icon(Icons.Default.CreateNewFolder, "Добавить тип") }; FilledIconButton({ editor = Product(sku = "", name = "", price = 0.0) }) { Icon(Icons.Default.Add, "Добавить товар") } } }) {
        if (products.isEmpty()) Empty("Добавьте первый товар — его можно будет сканировать на кассе.") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) { items(products, key = { it.id }) { ProductRow(it, { editor = it }) } }
    }
    editor?.let { ProductEditor(it, categories, onDismiss = { editor = null }, onSave = { vm.saveProduct(it); editor = null }, onDelete = { vm.deleteProduct(it); editor = null }) }
    if (addCategory) CategoryDialog({ vm.addCategory(it); addCategory = false }, { addCategory = false })
}

@Composable private fun ProductRow(product: Product, onClick: () -> Unit) = Card(Modifier.fillMaxWidth().animateContentSize().clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { if (product.photoUri != null) AsyncImage(product.photoUri, null, Modifier.size(50.dp).clip(RoundedCornerShape(15.dp))) else Box(Modifier.size(50.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Default.Inventory2, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(product.name, fontWeight = FontWeight.SemiBold); Text("${product.sku} · НДС ${product.vatRate}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Column(horizontalAlignment = Alignment.End) { Text(money(product.totalPrice), fontWeight = FontWeight.Bold); Text("остаток ${product.stock}", style = MaterialTheme.typography.bodySmall, color = if (product.stock <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) } } }

@Composable private fun ProductEditor(initial: Product, categories: List<Category>, onDismiss: () -> Unit, onSave: (Product) -> Unit, onDelete: () -> Unit) {
    var sku by remember { mutableStateOf(initial.sku) }; var name by remember { mutableStateOf(initial.name) }; var description by remember { mutableStateOf(initial.description) }; var price by remember { mutableStateOf(initial.price.toString()) }; var vat by remember { mutableStateOf(initial.vatRate.toString()) }; var stock by remember { mutableStateOf(initial.stock.toString()) }; var categoryId by remember { mutableStateOf(initial.categoryId) }; var photoUri by remember { mutableStateOf(initial.photoUri) }; var categoryMenu by remember { mutableStateOf(false) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { it?.let { uri -> photoUri = uri.toString() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.id == 0L) "Новый товар" else "Карточка товара") }, text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { item { if (photoUri != null) AsyncImage(photoUri, null, Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp))); OutlinedButton({ picker.launch("image/*") }) { Icon(Icons.Default.AddAPhoto, null); Spacer(Modifier.width(8.dp)); Text(if (photoUri == null) "Добавить фото" else "Заменить фото") } }; item { Field("Уникальный код", sku) { sku = it }; Field("Название", name) { name = it }; Field("Описание", description) { description = it }; ExposedDropdownMenuBox(categoryMenu, { categoryMenu = it }) { OutlinedTextField(value = categories.firstOrNull { it.id == categoryId }?.name ?: "Без типа", onValueChange = {}, readOnly = true, label = { Text("Тип товара") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth()); ExposedDropdownMenu(categoryMenu, { categoryMenu = false }) { DropdownMenuItem({ Text("Без типа") }, { categoryId = null; categoryMenu = false }); categories.forEach { c -> DropdownMenuItem({ Text(c.name) }, { categoryId = c.id; categoryMenu = false }) } } }; Field("Цена без НДС", price, KeyboardType.Decimal) { price = it }; Field("НДС, %", vat, KeyboardType.Decimal) { vat = it }; Field("Остаток", stock, KeyboardType.Number) { stock = it } } } }, confirmButton = { Button(onClick = { if (sku.isNotBlank() && name.isNotBlank()) onSave(initial.copy(sku = sku.trim(), name = name.trim(), description = description.trim(), price = price.toDoubleOrNull() ?: 0.0, vatRate = vat.toDoubleOrNull() ?: 0.0, stock = stock.toIntOrNull() ?: 0, categoryId = categoryId, photoUri = photoUri, updatedAt = System.currentTimeMillis())) }) { Text("Сохранить") } }, dismissButton = { Row { if (initial.id != 0L) TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }; TextButton(onClick = onDismiss) { Text("Отмена") } } })
}

@Composable private fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) = OutlinedTextField(value, onChange, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = keyboard), singleLine = true, modifier = Modifier.fillMaxWidth())
@Composable private fun CategoryDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) { var name by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = onDismiss, title = { Text("Новый тип товара") }, text = { Field("Название типа", name) { name = it } }, confirmButton = { Button({ onSave(name) }) { Text("Добавить") } }, dismissButton = { TextButton(onDismiss) { Text("Отмена") } }) }

@Composable private fun SaleScreen(vm: MerchViewModel) {
    val products by vm.products.collectAsState(); val draft by vm.draft.collectAsState(); var code by remember { mutableStateOf("") }; var scanner by remember { mutableStateOf(false) }; var checkout by remember { mutableStateOf(false) }; val context = LocalContext.current
    Page("Продажа", "Открытый чек · ${draft.sumOf { it.quantity }} шт.", action = { IconButton({ scanner = true }) { Icon(Icons.Default.QrCodeScanner, "Сканировать") } }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(code, { code = it }, label = { Text("Введите или отсканируйте код") }, singleLine = true, modifier = Modifier.weight(1f)); Button({ vm.addSku(code) { Toast.makeText(context, "Товар с таким кодом не найден", Toast.LENGTH_SHORT).show() }; code = "" }, enabled = code.isNotBlank()) { Text("Добавить") } }
        Spacer(Modifier.height(12.dp))
        if (draft.isEmpty()) { Empty("Чек пуст. Отсканируйте QR‑код или нажмите товар ниже."); Text("Быстрое добавление", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp)) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (draft.isNotEmpty()) { items(draft, key = { it.product.id }) { line -> DraftRow(line, { vm.changeDraft(line.product.id, -1) }, { vm.changeDraft(line.product.id, 1) }) }; item { Divider(); Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("Итого с НДС", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(money(draft.sumOf { it.product.totalPrice * it.quantity }), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) } } } else items(products.filter { it.stock > 0 }, key = { it.id }) { ProductRow(it) { vm.addToDraft(it) } }
        }
        if (draft.isNotEmpty()) Button(onClick = { checkout = true }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(8.dp)); Text("Закрыть чек") }
    }
    if (scanner) ScannerDialog(onCode = { vm.addSku(it) { Toast.makeText(context, "Товар не найден: $it", Toast.LENGTH_SHORT).show() }; scanner = false }, onDismiss = { scanner = false })
    if (checkout) CheckoutDialog(draft.sumOf { it.product.totalPrice * it.quantity }, onDismiss = { checkout = false }, onPay = { payment, note -> vm.closeSale(payment, note) { checkout = false } })
}

@Composable private fun DraftRow(line: DraftLine, minus: () -> Unit, plus: () -> Unit) = Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(line.product.name, fontWeight = FontWeight.SemiBold); Text(money(line.product.totalPrice), color = MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton(minus) { Icon(Icons.Default.RemoveCircleOutline, "Убрать") }; Text(line.quantity.toString(), fontWeight = FontWeight.Bold); IconButton(plus) { Icon(Icons.Default.AddCircleOutline, "Добавить") }; Text(money(line.product.totalPrice * line.quantity), fontWeight = FontWeight.Bold) } }

@Composable private fun CheckoutDialog(total: Double, onDismiss: () -> Unit, onPay: (PaymentType, String) -> Unit) { var payment by remember { mutableStateOf(PaymentType.CARD) }; var note by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.Payments, null) }, title = { Text("Закрытие чека") }, text = { Column { Text("К оплате ${money(total)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp)); Row { FilterChip(payment == PaymentType.CARD, { payment = PaymentType.CARD }, { Text("Карта") }, leadingIcon = { Icon(Icons.Default.CreditCard, null) }); Spacer(Modifier.width(8.dp)); FilterChip(payment == PaymentType.CASH, { payment = PaymentType.CASH }, { Text("Наличные") }, leadingIcon = { Icon(Icons.Default.Payments, null) }) }; Spacer(Modifier.height(8.dp)); Field("Комментарий к чеку", note) { note = it } } }, confirmButton = { Button({ onPay(payment, note) }) { Text("Принять оплату") } }, dismissButton = { TextButton(onDismiss) { Text("Отмена") } }) }

@Composable private fun HistoryScreen(vm: MerchViewModel) {
    val sales by vm.sales.collectAsState(); var edit by remember { mutableStateOf<Sale?>(null) }
    Page("История продаж", "${sales.size} чеков") { if (sales.isEmpty()) Empty("Закрытые чеки появятся здесь.") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) { items(sales, key = { it.id }) { sale -> Card(Modifier.fillMaxWidth().clickable { edit = sale }) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (sale.paymentType == PaymentType.CARD) Icons.Default.CreditCard else Icons.Default.Payments, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Чек №${sale.id}", fontWeight = FontWeight.SemiBold); Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(sale.createdAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(money(sale.total), fontWeight = FontWeight.Bold) } } } } }
    edit?.let { SaleEditor(it, vm, { edit = null }) }
}

@Composable private fun SaleEditor(sale: Sale, vm: MerchViewModel, dismiss: () -> Unit) { var payment by remember { mutableStateOf(sale.paymentType) }; var note by remember { mutableStateOf(sale.note) }; var lines by remember { mutableStateOf<List<SaleLine>?>(null) }; LaunchedEffect(sale.id) { lines = vm.saleLines(sale.id) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Чек №${sale.id}") }, text = { Column { Text(DateFormat.getDateTimeInstance().format(Date(sale.createdAt)), color = MaterialTheme.colorScheme.onSurfaceVariant); lines?.forEach { Text("${it.productName} × ${it.quantity}  —  ${money(it.unitPrice * (1 + it.vatRate / 100) * it.quantity)}", modifier = Modifier.padding(top = 6.dp)) }; Spacer(Modifier.height(8.dp)); Row { FilterChip(payment == PaymentType.CARD, { payment = PaymentType.CARD }, { Text("Карта") }); Spacer(Modifier.width(8.dp)); FilterChip(payment == PaymentType.CASH, { payment = PaymentType.CASH }, { Text("Наличные") }) }; Field("Комментарий", note) { note = it } } }, confirmButton = { Button({ vm.updateSale(sale.id, payment, note); dismiss() }) { Text("Сохранить") } }, dismissButton = { TextButton(dismiss) { Text("Закрыть") } }) }

@Composable private fun AnalyticsScreen(vm: MerchViewModel) { val turnover by vm.todayTurnover.collectAsState(); val count by vm.todaySales.collectAsState(); val products by vm.products.collectAsState(); val sales by vm.sales.collectAsState(); Page("Анализ", "Показатели за сегодня") { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCard("Выручка", money(turnover), Modifier.weight(1f)); MetricCard("Продаж", count.toString(), Modifier.weight(1f)) }; Spacer(Modifier.height(20.dp)); Text("Сводка", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { SummaryRow("Средний чек", if (count == 0) money(0.0) else money(turnover / count)); SummaryRow("Товаров в каталоге", products.size.toString()); SummaryRow("Чеков всего", sales.size.toString()); SummaryRow("Позиций с низким остатком", products.count { it.stock <= 5 }.toString()) } } }

}

@Composable private fun ReceiptClosed(sale: Sale, dismiss: () -> Unit) = Surface(Modifier.fillMaxSize().background(Color(0xCC0F1B32)), color = Color.Transparent) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Card(Modifier.padding(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.CheckCircle, null, Modifier.size(76.dp), tint = Color(0xFF087F23)); Spacer(Modifier.height(12.dp)); Text("Оплата принята!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Чек №${sale.id} · ${money(sale.total)}", color = MaterialTheme.colorScheme.onPrimaryContainer); Spacer(Modifier.height(18.dp)); Button(dismiss) { Text("Готово") } } } } }
@Composable private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) = Card(modifier) { Column(Modifier.padding(14.dp)) { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) } }
@Composable private fun SummaryRow(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text(value, fontWeight = FontWeight.SemiBold) }
@Composable private fun Empty(text: String) = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
private fun money(value: Double) = "${String.format("%.2f", value)} ₽"
