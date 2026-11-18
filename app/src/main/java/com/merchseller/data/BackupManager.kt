package com.merchseller.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File

internal const val MAX_BACKUP_BYTES = 8 * 1024 * 1024
internal const val MAX_BACKUP_JSON_DEPTH = 64
private const val LEGACY_VERSION = 1
private const val FORMAT_VERSION = 2
internal const val MAX_BACKUP_CATEGORIES = 1_000
internal const val MAX_BACKUP_PRODUCTS = 20_000
internal const val MAX_BACKUP_SALES = 50_000
internal const val MAX_BACKUP_LINES = 100_000

private const val MAX_CATEGORY_NAME_LENGTH = 200
private const val MAX_SKU_LENGTH = 256
private const val MAX_PRODUCT_NAME_LENGTH = 256
private const val MAX_DESCRIPTION_LENGTH = 8_192
private const val MAX_PHOTO_URI_LENGTH = 4_096
private const val MAX_SALE_NOTE_LENGTH = 4_096

/**
 * Резервная копия — незашифрованный JSON-файл для переноса базы между устройствами.
 */
object BackupManager {
    suspend fun export(context: Context, uri: Uri, dao: MerchDao, includePhotos: Boolean) = withContext(Dispatchers.IO) {
        val backup = dao.backupSnapshot()
        validateBackup(backup)
        val rows = backup.products.map { it.toJson() }
        val root = JSONObject().apply {
            put("formatVersion", FORMAT_VERSION)
            put("createdAt", backup.createdAt)
            put("categories", JSONArray(backup.categories.map { it.toJson() }))
            put("products", JSONArray(rows))
            put("sales", JSONArray(backup.sales.map { it.toJson() }))
            put("lines", JSONArray(backup.lines.map { it.toJson() }))
        }
        val tooLarge = "Копия больше 8 МиБ. Попробуйте создать копию без фотографий."
        var remaining = MAX_BACKUP_BYTES - root.toString().toByteArray(Charsets.UTF_8).size
        require(remaining >= 0) { "Данные без фотографий превышают лимит копии в 8 МиБ" }
        if (includePhotos) {
            val photos = BackupPhotos(context)
            for ((index, product) in backup.products.withIndex()) {
                if (product.photoUri == null) continue
                val payload = photos.encode(product)
                // Base64 содержит только ASCII. Запас покрывает имя поля и экранирование '/'.
                rows[index].put("photoJpeg", payload)
                remaining -= payload.length + payload.count { it == '/' } + 16
                require(remaining >= 0) { tooLarge }
            }
        }
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) { tooLarge }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: error("Не удалось открыть файл для записи")
    }

    suspend fun import(context: Context, uri: Uri, dao: MerchDao) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readBackupBytes)
            ?: error("Не удалось открыть файл резервной копии")
        val (backup, payloads) = parseBackupContents(bytes)
        val photos = BackupPhotos(context)
        val staged = mutableListOf<File>()
        var committed = false
        try {
            val products = backup.products.map { product ->
                val photo = payloads[product.id]?.let { photos.restore(it, staged) }
                    ?: photos.legacyUri(product.photoUri)
                product.copy(photoUri = photo)
            }
            // Файлы должны существовать до фиксации ссылок; при откате удаляем только новые файлы.
            withContext(NonCancellable) {
                val previous = dao.allProducts().mapNotNull { it.photoUri }.toSet()
                dao.replaceWithBackup(backup.copy(products = products))
                committed = true
                val retained = products.mapNotNull { it.photoUri }.toSet()
                (previous - retained).forEach(photos::deleteRestored)
            }
        } finally {
            if (!committed) staged.forEach { it.delete() }
        }
    }

    private fun Category.toJson() = JSONObject().apply { put("id", id); put("name", name); put("color", color) }
    private fun Product.toJson() = JSONObject().apply {
        put("id", id); put("sku", sku); put("name", name); put("description", description); put("categoryId", categoryId); put("priceKopecks", priceKopecks); put("vatRate", vatRate); put("stock", stock); put("updatedAt", updatedAt)
    }
    private fun Sale.toJson() = JSONObject().apply { put("id", id); put("createdAt", createdAt); put("paymentType", paymentType.name); put("totalKopecks", totalKopecks); put("note", note) }
    private fun SaleLine.toJson() = JSONObject().apply { put("id", id); put("saleId", saleId); put("productId", productId); put("productName", productName); put("sku", sku); put("unitPriceKopecks", unitPriceKopecks); put("lineTotalKopecks", lineTotalKopecks); put("vatRate", vatRate); put("quantity", quantity) }
}

/** Ограниченно читает поток: метаданным провайдера не доверяем, размер проверяем по фактическим байтам. */
internal fun readBackupBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream(minOf(MAX_BACKUP_BYTES, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) {
            val nextByte = input.read()
            if (nextByte < 0) break
            require(output.size() < MAX_BACKUP_BYTES) { "Файл резервной копии больше 8 МиБ" }
            output.write(nextByte)
            continue
        }
        require(output.size() <= MAX_BACKUP_BYTES - count) { "Файл резервной копии больше 8 МиБ" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** Разбирает JSON и проверяет лимиты до первого обращения к DAO. */
internal fun parseBackup(bytes: ByteArray): MerchBackup = parseBackupContents(bytes).first

internal fun parseBackupContents(bytes: ByteArray): Pair<MerchBackup, Map<Long, ByteArray>> {
    require(bytes.size <= MAX_BACKUP_BYTES) { "Файл резервной копии больше 8 МиБ" }
    validateJsonNesting(bytes)
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    val version = root.optInt("formatVersion")
    require(version == LEGACY_VERSION || version == FORMAT_VERSION) { "Неподдерживаемый формат резервной копии" }
    val legacy = version == LEGACY_VERSION
    val photos = mutableMapOf<Long, ByteArray>()
    val products = root.array("products", MAX_BACKUP_PRODUCTS) { row ->
        row.product(legacy).also { product ->
            if (!legacy && !row.isNull("photoJpeg")) {
                require(photos.put(product.id, decodePhotoPayload(row.getString("photoJpeg"))) == null) {
                    "В копии повторяется фотография товара"
                }
            }
        }
    }
    return MerchBackup(
        createdAt = root.getLong("createdAt"),
        categories = root.array("categories", MAX_BACKUP_CATEGORIES) { it.category() },
        products = products,
        sales = root.array("sales", MAX_BACKUP_SALES) { it.sale(legacy) },
        lines = root.array("lines", MAX_BACKUP_LINES) { it.line(legacy) }
    ) to photos
}

/** Ограничивает рекурсивный разбор JSON ещё до передачи данных библиотеке JSONObject. */
private fun validateJsonNesting(bytes: ByteArray) {
    var depth = 0
    var inString = false
    var escaped = false
    for (byte in bytes) {
        val character = byte.toInt() and 0xFF
        if (inString) {
            if (escaped) escaped = false
            else when (character) {
                '\\'.code -> escaped = true
                '"'.code -> inString = false
            }
            continue
        }
        when (character) {
            '"'.code -> inString = true
            '{'.code, '['.code -> {
                depth++
                require(depth <= MAX_BACKUP_JSON_DEPTH) { "Слишком глубокая вложенность JSON в резервной копии" }
            }
            '}'.code, ']'.code -> {
                depth--
                require(depth >= 0) { "Некорректная структура JSON резервной копии" }
            }
        }
    }
    require(!inString && depth == 0) { "Некорректная структура JSON резервной копии" }
}

private fun validateBackup(backup: MerchBackup) {
    require(backup.categories.size <= MAX_BACKUP_CATEGORIES) { "Слишком много типов товаров в резервной копии" }
    require(backup.products.size <= MAX_BACKUP_PRODUCTS) { "Слишком много товаров в резервной копии" }
    require(backup.sales.size <= MAX_BACKUP_SALES) { "Слишком много чеков в резервной копии" }
    require(backup.lines.size <= MAX_BACKUP_LINES) { "Слишком много строк чеков в резервной копии" }
    backup.categories.forEach { it.name.requireLength(MAX_CATEGORY_NAME_LENGTH, "name") }
    backup.products.forEach {
        it.sku.requireLength(MAX_SKU_LENGTH, "sku")
        it.name.requireLength(MAX_PRODUCT_NAME_LENGTH, "name")
        it.description.requireLength(MAX_DESCRIPTION_LENGTH, "description")
        it.photoUri?.requireLength(MAX_PHOTO_URI_LENGTH, "photoUri")
    }
    backup.sales.forEach { it.note.requireLength(MAX_SALE_NOTE_LENGTH, "note") }
    backup.lines.forEach {
        it.productName.requireLength(MAX_PRODUCT_NAME_LENGTH, "productName")
        it.sku.requireLength(MAX_SKU_LENGTH, "sku")
    }
}

private fun JSONObject.category() = Category(getLong("id"), getStringLimited("name", MAX_CATEGORY_NAME_LENGTH), getLong("color"))
private fun JSONObject.product(legacy: Boolean) = Product(
    getLong("id"),
    getStringLimited("sku", MAX_SKU_LENGTH),
    getStringLimited("name", MAX_PRODUCT_NAME_LENGTH),
    getOptionalStringLimited("description", MAX_DESCRIPTION_LENGTH),
    if (isNull("categoryId")) null else getLong("categoryId"),
    amount(legacy, "price", "priceKopecks"),
    vat(),
    getInt("stock"),
    if (legacy && !isNull("photoUri")) getStringLimited("photoUri", MAX_PHOTO_URI_LENGTH) else null,
    getLong("updatedAt")
)
private fun JSONObject.sale(legacy: Boolean) = Sale(
    getLong("id"), getLong("createdAt"), PaymentType.valueOf(getString("paymentType")),
    amount(legacy, "total", "totalKopecks"), getOptionalStringLimited("note", MAX_SALE_NOTE_LENGTH)
)

private fun JSONObject.line(legacy: Boolean): SaleLine {
    val rate = vat()
    val quantity = getInt("quantity")
    val total = if (legacy) {
        legacyLineKopecks(finiteDouble("unitPrice"), rate, quantity)
    } else integerAmount("lineTotalKopecks")
    return SaleLine(
        getLong("id"), getLong("saleId"), getLong("productId"),
        getStringLimited("productName", MAX_PRODUCT_NAME_LENGTH),
        getStringLimited("sku", MAX_SKU_LENGTH),
        amount(legacy, "unitPrice", "unitPriceKopecks"), rate, quantity, total
    )
}

private fun JSONObject.amount(legacy: Boolean, rubles: String, kopecks: String): Long =
    if (legacy) legacyKopecks(finiteDouble(rubles)) else integerAmount(kopecks)

private fun JSONObject.integerAmount(key: String): Long =
    requireNotNull(get(key).toString().toLongOrNull()) { "Поле «$key» должно содержать целое число копеек" }

private fun JSONObject.finiteDouble(key: String): Double = getDouble(key).also {
    require(it.isFinite()) { "Некорректное число в поле «$key»" }
}

private fun JSONObject.vat(): Double = finiteDouble("vatRate")

private fun JSONObject.getStringLimited(key: String, maximumLength: Int): String = getString(key).also {
    it.requireLength(maximumLength, key)
}

private fun JSONObject.getOptionalStringLimited(key: String, maximumLength: Int): String = optString(key).also {
    it.requireLength(maximumLength, key)
}

private fun String.requireLength(maximumLength: Int, field: String) {
    require(length <= maximumLength) { "Поле «$field» в резервной копии слишком длинное" }
}

private inline fun <T> JSONObject.array(key: String, maximumCount: Int, map: (JSONObject) -> T): List<T> {
        val array = getJSONArray(key)
        require(array.length() <= maximumCount) { "Слишком много элементов в разделе «$key» резервной копии" }
        return List(array.length()) { map(array.getJSONObject(it)) }
}
