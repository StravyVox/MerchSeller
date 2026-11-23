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
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.merchseller.data.*
import com.merchseller.ui.MerchTheme
import com.merchseller.ui.ActionPair
import com.merchseller.ui.PrimaryAction
import com.merchseller.ui.QuietAction
import com.merchseller.ui.SecondaryAction
import java.text.DateFormat
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.io.File
import kotlinx.coroutines.launch

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
    var productEditor by remember { mutableStateOf<Product?>(null) }
    var dismissedUpdateTag by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingInstallFile by remember { mutableStateOf<File?>(null) }
    val receipt by vm.lastClosed.collectAsState()
    val update by vm.update.collectAsState()
    val context = LocalContext.current
    val wideLayout = LocalConfiguration.current.screenWidthDp >= 600
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
        if (productEditor == null && !wideLayout) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f), tonalElevation = 10.dp) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Glyph(item.icon, item.title) },
                        label = { Text(item.title) }
                    )
                }
            }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxSize()) {
                if (productEditor == null && wideLayout) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        Tab.entries.forEach { item ->
                            NavigationRailItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Glyph(item.icon, item.title) },
                                label = { Text(item.title) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.background).padding(pad).consumeWindowInsets(pad)) {
                    // Переходы между разделами делают навигацию живой, но не отвлекают от работы кассира.
                    AnimatedContent(
                        tab,
                        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                        label = "section"
                    ) { current ->
                        when (current) {
                            Tab.STOCK -> StockScreen(vm, productEditor) { productEditor = it }
                            Tab.PRODUCTS -> ProductScreen(vm, productEditor) { productEditor = it }
                            Tab.SALE -> SaleScreen(vm)
                            Tab.HISTORY -> HistoryScreen(vm)
                            Tab.ANALYTICS -> AnalyticsScreen(vm)
                        }
                    }
                }
            }
            AnimatedVisibility(receipt != null, enter = scaleIn(spring(stiffness = Spring.StiffnessLow)), exit = scaleOut()) {
                receipt?.let { ReceiptClosed(it) { vm.dismissReceipt() } }
            }
        }
    }
    when (val state = update) {
        is UpdateState.Available -> if (dismissedUpdateTag != state.release.tag) AlertDialog(
            onDismissRequest = { dismissedUpdateTag = state.release.tag; vm.dismissUpdate() },
            icon = { Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Доступна версия ${state.release.version}") },
            text = { Text(state.release.notes.ifBlank { "Вы можете установить обновление сейчас или позже." }, maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            confirmButton = { PrimaryAction(onClick = { vm.downloadUpdate(state.release) }) { Text("Скачать и установить") } },
            dismissButton = { QuietAction(onClick = { dismissedUpdateTag = state.release.tag; vm.dismissUpdate() }) { Text("Позже") } }
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
            confirmButton = { QuietAction(onClick = { vm.dismissUpdate() }) { Text("Закрыть") } }
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

@Composable private fun Page(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().fillMaxHeight().align(Alignment.TopCenter).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(16.dp))
            body()
        }
    }
}

@Composable private fun StockScreen(vm: MerchViewModel, editor: Product?, onEdit: (Product?) -> Unit) {
    val catalog by vm.products.collectAsState()
    val products = catalog?.getOrNull().orEmpty()
    val categories by vm.categories.collectAsState()
    val lowStockThreshold by vm.lowStockThreshold.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var lowOnly by rememberSaveable { mutableStateOf(false) }
    var categoryKey by rememberSaveable { mutableStateOf("all") }
    var sort by rememberSaveable { mutableStateOf(ProductSort.NAME) }
    var showThresholdDialog by rememberSaveable { mutableStateOf(false) }
    var pdf by rememberSaveable { mutableStateOf(false) }
    var transfer by rememberSaveable { mutableStateOf(false) }
    var includePhotos by rememberSaveable { mutableStateOf(true) }
    var importUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var columns by rememberSaveable { mutableIntStateOf(3) }
    var rows by rememberSaveable { mutableIntStateOf(5) }
    var photos by rememberSaveable { mutableStateOf(false) }

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
    if (editor != null) {
        ProductEditor(editor, vm) { onEdit(null) }
        return
    }
    val visible = remember(products, query, categoryKey, lowOnly, lowStockThreshold, sort) {
        filterAndSortProducts(products, query, categoryKey, lowOnly, lowStockThreshold, sort)
    }
    Page("Склад") {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCard("В каталоге", products.size.toString(), Modifier.weight(1f))
                    MetricCard("Единиц на складе", products.sumOf { it.stock.toLong() }.toString(), Modifier.weight(1f))
                }
            }
            item {
                ActionPair(
                    minItemWidth = 148.dp,
                    first = { modifier ->
                        SecondaryAction({ pdf = true }, modifier, enabled = products.isNotEmpty() && !busy) {
                            Glyph(R.drawable.lucide_printer)
                            Spacer(Modifier.width(8.dp))
                            Text("Этикетки")
                        }
                    },
                    second = { modifier ->
                        SecondaryAction({ transfer = true }, modifier, enabled = !busy) {
                            Glyph(R.drawable.lucide_arrow_left_right)
                            Spacer(Modifier.width(8.dp))
                            Text("Перенос")
                        }
                    }
                )
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            message?.let { status -> item { Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            item { SearchField("Поиск по названию или коду", query) { query = it } }
            item { CategoryFilterRow(categories, categoryKey) { categoryKey = it } }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(lowOnly, { lowOnly = !lowOnly }, { Text("Остаток ≤ $lowStockThreshold") })
                    TextButton(onClick = { showThresholdDialog = true }) {
                        Icon(Icons.Default.Tune, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Порог")
                    }
                }
            }
            item { ProductSortMenu(sort) { sort = it } }
            if (catalog == null || catalog?.isFailure == true) item { CatalogStatus(catalog) { vm.retryProducts() } }
            else if (visible.isEmpty()) item {
                Empty(when {
                    products.isEmpty() -> "Добавьте товар в каталоге, чтобы увидеть его остаток."
                    query.isNotBlank() -> "Товары по этому запросу не найдены."
                    else -> "Нет товаров с остатком 5 шт. или меньше."
                })
            }
            items(visible, key = { it.id }) { ProductRow(it, lowStockThreshold) { onEdit(it) } }
        }
    }
    if (showThresholdDialog) LowStockThresholdDialog(
        value = lowStockThreshold,
        dismiss = { showThresholdDialog = false },
        save = { value ->
            vm.setLowStockThreshold(value)
            showThresholdDialog = false
        }
    )
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
                PrimaryAction({
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
        confirmButton = { PrimaryAction({ transfer = false; exportFile.launch("MerchSeller-${System.currentTimeMillis()}.json") }) { Text("Создать копию") } },
        dismissButton = { QuietAction({ transfer = false; importFile.launch(arrayOf("application/json")) }) { Text("Восстановить") } }
    )
    importUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { importUri = null },
            title = { Text("Заменить данные?") },
            text = { Text("Текущие товары, типы и история продаж будут заменены данными из копии. Открытый чек будет очищен.") },
            confirmButton = { PrimaryAction({
                importUri = null
                busy = true
                vm.importBackup(uri) { result ->
                    busy = false
                    message = result.fold({ "Данные восстановлены из копии" }, { "Не удалось восстановить данные. ${it.message.orEmpty()}" })
                }
            }, destructive = true) { Text("Заменить данные") } },
            dismissButton = { QuietAction({ importUri = null }) { Text("Отмена") } }
        )
    }
}

private const val DEFAULT_LOW_STOCK = 5

private fun Product.matches(query: String): Boolean =
    name.contains(query.trim(), ignoreCase = true) || sku.contains(query.trim(), ignoreCase = true)

@Composable private fun SearchField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        trailingIcon = if (value.isBlank()) null else ({
            IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, contentDescription = "Очистить поиск") }
        }),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable private fun CategoryFilterRow(categories: List<Category>, categoryKey: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(categoryKey == "all", { onSelect("all") }, label = { Text("Все типы") })
        FilterChip(categoryKey == "none", { onSelect("none") }, label = { Text("Без типа") })
        categories.forEach { category ->
            FilterChip(categoryKey == category.id.toString(), { onSelect(category.id.toString()) }, label = { Text(category.name) })
        }
    }
}

@Composable private fun ProductSortMenu(sort: ProductSort, onSelect: (ProductSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Sort, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(sort.label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ProductSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onSelect(option); expanded = false },
                    leadingIcon = if (sort == option) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null
                )
            }
        }
    }
}

@Composable private fun LowStockThresholdDialog(value: Int, dismiss: () -> Unit, save: (Int) -> Unit) {
    var input by rememberSaveable(value) { mutableStateOf(value.toString()) }
    val parsed = input.toIntOrNull()?.takeIf { it in 0..1_000_000 }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Порог низкого остатка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Товары с таким остатком или меньше будут отмечены как заканчивающиеся.")
                Field("Количество, шт.", input, KeyboardType.Number, if (parsed == null) "Введите целое число от 0 до 1 000 000." else null) { input = it }
            }
        },
        confirmButton = { PrimaryAction({ parsed?.let(save) }, enabled = parsed != null) { Text("Сохранить") } },
        dismissButton = { QuietAction(dismiss) { Text("Отмена") } }
    )
}

@Composable private fun SettingStepper(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        IconButton({ onChange(value - 1) }, enabled = value > range.first) {
            Icon(Icons.Default.Remove, "Уменьшить: $title")
        }
        Text(value.toString(), Modifier.widthIn(min = 32.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
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

@Composable private fun ProductScreen(vm: MerchViewModel, editor: Product?, onEdit: (Product?) -> Unit) {
    val catalog by vm.products.collectAsState()
    val products = catalog?.getOrNull().orEmpty()
    val categories by vm.categories.collectAsState()
    val lowStockThreshold by vm.lowStockThreshold.collectAsState()
    val listState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var categoryKey by rememberSaveable { mutableStateOf("all") }
    var lowOnly by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(ProductSort.NAME) }
    var showThresholdDialog by rememberSaveable { mutableStateOf(false) }
    var addCategory by rememberSaveable { mutableStateOf(false) }
    if (editor != null) {
        ProductEditor(editor, vm) { onEdit(null) }
        return
    }
    Page("Товары") {
        ActionPair(
            minItemWidth = 148.dp,
            first = { modifier ->
                PrimaryAction({ onEdit(Product(sku = "", name = "", priceKopecks = 0)) }, modifier) {
                    Text("Добавить товар")
                }
            },
            second = { modifier ->
                SecondaryAction({ addCategory = true }, modifier) { Text("Добавить тип") }
            }
        )
        Spacer(Modifier.height(12.dp))
        SearchField("Поиск по названию или коду", query) { query = it }
        Spacer(Modifier.height(8.dp))
        CategoryFilterRow(categories, categoryKey) { categoryKey = it }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(lowOnly, { lowOnly = !lowOnly }, { Text("Остаток ≤ $lowStockThreshold") })
            TextButton(onClick = { showThresholdDialog = true }) { Icon(Icons.Default.Tune, null); Spacer(Modifier.width(4.dp)); Text("Порог") }
        }
        ProductSortMenu(sort) { sort = it }
        val visible = remember(products, query, categoryKey, lowOnly, lowStockThreshold, sort) {
            filterAndSortProducts(products, query, categoryKey, lowOnly, lowStockThreshold, sort)
        }
        if (catalog == null || catalog?.isFailure == true) {
            CatalogStatus(catalog) { vm.retryProducts() }
        } else if (visible.isEmpty()) {
            Empty(if (products.isEmpty()) "Добавьте первый товар, чтобы начать продажи." else "Товары не найдены. Очистите поиск или измените фильтры.")
        } else {
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(visible, key = { it.id }) { ProductRow(it, lowStockThreshold) { onEdit(it) } }
            }
        }
    }
    if (addCategory) CategoryDialog(vm) { addCategory = false }
    if (showThresholdDialog) LowStockThresholdDialog(
        lowStockThreshold,
        dismiss = { showThresholdDialog = false },
        save = { value ->
            vm.setLowStockThreshold(value)
            showThresholdDialog = false
        }
    )
}

@Composable private fun ProductRow(product: Product, lowStockThreshold: Int = DEFAULT_LOW_STOCK, onClick: () -> Unit) {
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
                    color = if (product.stock <= lowStockThreshold) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
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
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var codeError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val priceValue = price.parsePriceKopecks()
    val vatValue = vat.decimal()?.takeIf { it in 0.0..100.0 }
    val stockValue = stock.trim().toIntOrNull()?.takeIf { it >= 0 }
    val total = if (priceValue != null && vatValue != null) kopecksWithVat(priceValue, vatValue) else null
    val isDirty = sku != initial.sku || name != initial.name || description != initial.description ||
        priceValue != initial.priceKopecks || vatValue != initial.vatRate || stockValue != initial.stock ||
        categoryId != initial.categoryId || photoUri != initial.photoUri
    val requestDismiss = {
        if (!busy) {
            if (isDirty) confirmDiscard = true else dismiss()
        }
    }
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

    BackHandler(enabled = !busy) { requestDismiss() }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp)) {
        TopAppBar(
            title = { Text(if (initial.id == 0L) "Новый товар" else "Карточка товара") },
            navigationIcon = { IconButton(requestDismiss, enabled = !busy) { Icon(Icons.Default.ArrowBack, "Назад") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryAction({ picker.launch(arrayOf("image/*")) }, Modifier.fillMaxWidth(), enabled = !busy) {
                        Text(if (photoUri == null) "Добавить фото" else "Заменить фото")
                    }
                    if (photoUri != null) QuietAction({ photoUri = null }, Modifier.fillMaxWidth(), enabled = !busy) {
                        Text("Убрать фото")
                    }
                }
            }
            if (initial.id != 0L) item {
                QuietAction({ confirmDelete = true }, Modifier.fillMaxWidth(), enabled = !busy) {
                    Text("Удалить товар", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
        PrimaryAction({
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
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить товар?") },
        text = { Text("«${initial.name}» исчезнет из каталога. Закрытые чеки сохранятся.") },
        confirmButton = { PrimaryAction({
            confirmDelete = false
            busy = true
            vm.deleteProduct(initial) { result ->
                busy = false
                result.onSuccess { dismiss() }.onFailure { message = "Не удалось удалить товар." }
            }
        }, destructive = true) { Text("Удалить товар") } },
        dismissButton = { QuietAction({ confirmDelete = false }) { Text("Отмена") } }
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Отбросить изменения?") },
        text = { Text("Введённые изменения не будут сохранены.") },
        confirmButton = { PrimaryAction({ confirmDiscard = false; dismiss() }, destructive = true) { Text("Выйти без сохранения") } },
        dismissButton = { QuietAction({ confirmDiscard = false }) { Text("Продолжить редактирование") } }
    )
}

private fun String.decimal(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

@Composable private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    error: String? = null,
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Default,
    onIme: (() -> Unit)? = null,
    onChange: (String) -> Unit
) = OutlinedTextField(
    value, onChange, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction),
    keyboardActions = KeyboardActions(onDone = { onIme?.invoke() }),
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
        confirmButton = { PrimaryAction({
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
        dismissButton = { QuietAction(dismiss, enabled = !busy) { Text("Отмена") } }
    )
}

@Composable private fun SaleScreen(vm: MerchViewModel) {
    val catalog by vm.products.collectAsState()
    val products = catalog?.getOrNull().orEmpty()
    val draft by vm.draft.collectAsState()
    val isClosing by vm.isClosingSale.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
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
            if (error == null) {
                code = ""
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            if (scanner) Toast.makeText(context, error ?: "Добавлено в чек", Toast.LENGTH_SHORT).show()
        }
    }
    Page("Касса", if (draft.isEmpty()) "Новый чек" else "В чеке · ${draft.sumOf { it.quantity }}\u00a0шт.", Modifier.imePadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            if (hasCamera) item {
                val label: @Composable RowScope.() -> Unit = {
                    Glyph(R.drawable.lucide_scan_qr_code)
                    Spacer(Modifier.width(8.dp))
                    Text("Сканировать код")
                }
                if (draft.isEmpty()) {
                    PrimaryAction({ scanner = true }, Modifier.fillMaxWidth(), enabled = !isClosing, content = label)
                } else {
                    SecondaryAction({ scanner = true }, Modifier.fillMaxWidth(), enabled = !isClosing, content = label)
                }
            }
            item {
                Field("Код товара", code, imeAction = ImeAction.Done, onIme = { if (code.isNotBlank()) addCode(code) }) { code = it; message = null }
            }
            item {
                SecondaryAction({ addCode(code) }, Modifier.fillMaxWidth(), enabled = code.isNotBlank() && !isClosing) {
                    Text("Добавить по коду")
                }
            }
            message?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            items(draft, key = { it.product.id }) { line ->
                DraftRow(line, { vm.changeDraft(line.product.id, -1) }, { vm.changeDraft(line.product.id, 1) })
            }
            val available = products.filter { it.stock > 0 && it.matches(code) }
            if (available.isNotEmpty()) item { Text("Товары в наличии", style = MaterialTheme.typography.titleMedium) }
            if (catalog == null || catalog?.isFailure == true) item { CatalogStatus(catalog) { vm.retryProducts() } }
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
                PrimaryAction({ checkout = true }, Modifier.fillMaxWidth(), enabled = !isClosing) {
                    Text("Перейти к оплате")
                }
            }
        }
    }
    if (scanner && hasCamera) ScannerDialog(onCode = { addCode(it) }, onDismiss = { scanner = false })
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
                    modifier = Modifier.widthIn(min = 32.dp),
                    contentAlignment = Alignment.Center,
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

@Composable private fun PaymentChoices(payment: PaymentType, onSelect: (PaymentType) -> Unit) {
    ActionPair(
        minItemWidth = 100.dp,
        first = { modifier ->
            FilterChip(
                selected = payment == PaymentType.CARD,
                onClick = { onSelect(PaymentType.CARD) },
                label = { Text("Карта", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = modifier.heightIn(min = 48.dp)
            )
        },
        second = { modifier ->
            FilterChip(
                selected = payment == PaymentType.CASH,
                onClick = { onSelect(PaymentType.CASH) },
                label = { Text("Наличные", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = modifier.heightIn(min = 48.dp)
            )
        }
    )
}

@Composable private fun CheckoutDialog(
    total: BigInteger,
    isClosing: Boolean,
    onDismiss: () -> Unit,
    onPay: (PaymentType, String) -> Unit
) {
    var payment by rememberSaveable { mutableStateOf(PaymentType.CARD) }
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Оплата") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("К оплате ${money(total)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                PaymentChoices(payment) { payment = it }
                Field("Комментарий к чеку", note, error = note.length.takeIf { it > 4096 }?.let {
                    "Сократите комментарий до 4 096 символов."
                }) { note = it }
            }
        },
        confirmButton = {
            PrimaryAction({ onPay(payment, note.trim()) }, enabled = !isClosing && note.trim().length <= 4096) {
                Text(if (isClosing) "Сохраняем продажу…" else "Подтвердить оплату")
            }
        },
        dismissButton = { QuietAction(onDismiss, enabled = !isClosing) { Text("Отмена") } }
    )
}

private const val HISTORY_PAGE_SIZE = 40

@Composable private fun DatePresetRow(selected: DatePreset, includeAllTime: Boolean = true, onSelect: (DatePreset) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DatePreset.entries.filter { includeAllTime || it != DatePreset.ALL_TIME }.forEach { preset ->
            FilterChip(selected == preset, { onSelect(preset) }, label = { Text(preset.label) })
        }
    }
}

@Composable private fun HistoryScreen(vm: MerchViewModel) {
    var edit by remember { mutableStateOf<Sale?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var range by rememberSaveable { mutableStateOf(DatePreset.ALL_TIME) }
    var paymentName by rememberSaveable { mutableStateOf("") }
    var sales by remember { mutableStateOf<List<Sale>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val window = remember(range) { range.window(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query, range, paymentName, reloadKey) {
        kotlinx.coroutines.delay(250)
        sales = emptyList()
        hasMore = false
        loading = true
        error = null
        try {
            val result = vm.salesPage(window.from, window.toExclusive, paymentName, query.trim(), HISTORY_PAGE_SIZE + 1, 0)
            sales = result.take(HISTORY_PAGE_SIZE)
            hasMore = result.size > HISTORY_PAGE_SIZE
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            error = "Не удалось загрузить историю. Повторите попытку."
        } finally {
            loading = false
        }
    }

    Page("История продаж", if (loading && sales.isEmpty()) "Загружаем чеки…" else pluralStringResource(R.plurals.receipt_count, sales.size, sales.size)) {
        SearchField("Поиск по номеру или комментарию", query) { query = it }
        Spacer(Modifier.height(4.dp))
        DatePresetRow(range) { range = it }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(paymentName.isEmpty(), { paymentName = "" }, label = { Text("Любая оплата") })
            FilterChip(paymentName == PaymentType.CARD.name, { paymentName = PaymentType.CARD.name }, label = { Text("Карта") })
            FilterChip(paymentName == PaymentType.CASH.name, { paymentName = PaymentType.CASH.name }, label = { Text("Наличные") })
        }
        when {
            loading && sales.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth())
            error != null && sales.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                SecondaryAction({ reloadKey++ }) { Text("Повторить") }
            }
            sales.isEmpty() -> Empty(if (query.isBlank() && paymentName.isBlank()) "За выбранный период чеков нет." else "Чеки не найдены. Измените запрос или фильтры.")
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(sales, key = { it.id }) { sale ->
                    Card(onClick = { edit = sale }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (sale.paymentType == PaymentType.CARD) Icons.Default.CreditCard else Icons.Default.Payments, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Чек №\u00A0${sale.id}", fontWeight = FontWeight.SemiBold)
                                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(sale.createdAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(money(sale.totalKopecks), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (hasMore) item {
                    if (loadingMore) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else SecondaryAction(
                        onClick = {
                            scope.launch {
                                loadingMore = true
                                error = null
                                try {
                                    val result = vm.salesPage(window.from, window.toExclusive, paymentName, query.trim(), HISTORY_PAGE_SIZE + 1, sales.size)
                                    sales = sales + result.take(HISTORY_PAGE_SIZE)
                                    hasMore = result.size > HISTORY_PAGE_SIZE
                                } catch (failure: Exception) {
                                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                                    error = "Не удалось загрузить следующие чеки. Повторите попытку."
                                } finally {
                                    loadingMore = false
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()
                    ) { Text("Загрузить ещё") }
                }
                if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    edit?.let { SaleEditor(it, vm, { edit = null }) }
}

@Composable private fun SaleEditor(sale: Sale, vm: MerchViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    var payment by remember(sale.id) { mutableStateOf(sale.paymentType) }
    var note by remember(sale.id) { mutableStateOf(sale.note) }
    var lines by remember(sale.id) { mutableStateOf<List<SaleLine>?>(null) }
    var loadFailed by remember(sale.id) { mutableStateOf(false) }
    LaunchedEffect(sale.id) {
        try { lines = vm.saleLines(sale.id) } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            loadFailed = true
        }
    }
    fun shareReceipt() {
        val body = buildString {
            appendLine("Чек № ${sale.id}")
            appendLine(DateFormat.getDateTimeInstance().format(Date(sale.createdAt)))
            lines.orEmpty().forEach { appendLine("${it.productName} × ${it.quantity} — ${money(it.lineTotalKopecks)}") }
            appendLine("Оплата: ${if (payment == PaymentType.CARD) "карта" else "наличные"}")
            appendLine("Итого: ${money(sale.totalKopecks)}")
            if (note.isNotBlank()) append("Комментарий: ${note.trim()}")
        }
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, body) }
        context.startActivity(Intent.createChooser(intent, "Поделиться чеком"))
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Чек №\u00A0${sale.id}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(DateFormat.getDateTimeInstance().format(Date(sale.createdAt)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    lines != null -> lines!!.forEach { Text("${it.productName} × ${it.quantity} — ${money(it.lineTotalKopecks)}") }
                    loadFailed -> Text("Не удалось загрузить состав чека.", color = MaterialTheme.colorScheme.error)
                    else -> LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text("Итого: ${money(sale.totalKopecks)}", fontWeight = FontWeight.Bold)
                PaymentChoices(payment) { payment = it }
                Field("Комментарий", note, error = note.length.takeIf { it > 4096 }?.let { "Сократите комментарий до 4 096 символов." }) { note = it }
                SecondaryAction(::shareReceipt, Modifier.fillMaxWidth(), enabled = lines != null) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Поделиться чеком")
                }
            }
        },
        confirmButton = { PrimaryAction({ vm.updateSale(sale.id, payment, note.trim()); dismiss() }, enabled = note.trim().length <= 4096) { Text("Сохранить") } },
        dismissButton = { QuietAction(dismiss) { Text("Закрыть") } }
    )
}

@Composable private fun AnalyticsScreen(vm: MerchViewModel) {
    var range by rememberSaveable { mutableStateOf(DatePreset.TODAY) }
    val window = remember(range) { range.window(System.currentTimeMillis()) }
    val analyticsFlow = remember(window.from, window.toExclusive) { vm.analytics(window.from, window.toExclusive) }
    val snapshot by analyticsFlow.collectAsState(initial = null)
    val catalog by vm.products.collectAsState()
    val products = catalog?.getOrNull().orEmpty()
    val lowStockThreshold by vm.lowStockThreshold.collectAsState()

    Page("Аналитика", "Показатели: ${range.label}") {
        DatePresetRow(range, includeAllTime = false) { range = it }
        Spacer(Modifier.height(8.dp))
        if (snapshot == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            val data = snapshot!!
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Выручка", money(data.summary.turnoverKopecks), Modifier.weight(1f))
                    MetricCard("Продажи", data.summary.count.toString(), Modifier.weight(1f))
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Сводка", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        SummaryRow("Средний чек", money(averageKopecks(data.summary.turnoverKopecks.toBigInteger(), data.summary.count)))
                        SummaryRow("Товаров в каталоге", products.size.toString())
                        SummaryRow("Позиций с низким остатком", products.count { it.stock <= lowStockThreshold }.toString())
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Выручка по дням", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        SalesTrendChart(data.dailySales)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Популярные товары", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (data.topProducts.isEmpty()) Text("За этот период продаж ещё нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else {
                            val maxQuantity = data.topProducts.maxOf { it.quantity }.coerceAtLeast(1L).toFloat()
                            data.topProducts.forEach { product ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(product.productName, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                    Text("${product.quantity} шт.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                LinearProgressIndicator(product.quantity / maxQuantity, Modifier.fillMaxWidth())
                                Text(money(product.revenueKopecks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SalesTrendChart(dailySales: List<DailySales>) {
    if (dailySales.isEmpty()) {
        Text("За этот период продаж ещё нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val color = MaterialTheme.colorScheme.primary
    val maxTurnover = dailySales.maxOf { it.turnoverKopecks }.coerceAtLeast(1L).toFloat()
    val chartDescription = dailySales.joinToString("; ") {
        "${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it.dayStart))}: ${money(it.turnoverKopecks)}"
    }
    Canvas(
        Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "Выручка по дням. $chartDescription" }
    ) {
        val gap = 6.dp.toPx()
        val barWidth = ((size.width - gap * (dailySales.size + 1)) / dailySales.size).coerceAtLeast(2.dp.toPx())
        dailySales.forEachIndexed { index, item ->
            val height = (size.height * (item.turnoverKopecks.toFloat() / maxTurnover)).coerceAtLeast(3.dp.toPx())
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(gap + index * (barWidth + gap), size.height - height),
                size = androidx.compose.ui.geometry.Size(barWidth, height)
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(dailySales.first().dayStart)), style = MaterialTheme.typography.bodySmall)
        if (dailySales.size > 1) Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(dailySales.last().dayStart)), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun ReceiptClosed(sale: Sale, dismiss: () -> Unit) = Surface(Modifier.fillMaxSize().background(Color(0xCC0F1B32)), color = Color.Transparent) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Card(Modifier.padding(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(painterResource(R.drawable.lucide_check), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(12.dp)); Text("Продажа сохранена", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Чек №\u00A0${sale.id} · ${money(sale.totalKopecks)}", color = MaterialTheme.colorScheme.onPrimaryContainer); Spacer(Modifier.height(18.dp)); PrimaryAction(dismiss) { Text("Готово") } } } } }
@Composable private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) = Card(modifier) { Column(Modifier.padding(14.dp)) { Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun SummaryRow(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(label, Modifier.weight(1f)); Text(value, fontWeight = FontWeight.SemiBold) }
@Composable private fun CatalogStatus(catalog: Result<List<Product>>?, onRetry: () -> Unit) {
    if (catalog == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Загружаем каталог…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Не удалось загрузить товары. Проверьте хранилище приложения и повторите попытку.", color = MaterialTheme.colorScheme.error)
            SecondaryAction(onRetry) { Text("Повторить загрузку") }
        }
    }
}

@Composable private fun Empty(text: String) = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
private fun money(value: Long) = money(value.toBigInteger())

private fun money(value: BigInteger) =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU")).format(BigDecimal(value, 2))
