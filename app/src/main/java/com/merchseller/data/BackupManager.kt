package com.merchseller.data

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Формат резервной копии — обычный JSON. Он не привязан к облаку и подходит для
 * безопасной передачи через мессенджер, кабель или любое файловое хранилище.
 */
object BackupManager {
    private const val FORMAT_VERSION = 1

    suspend fun export(resolver: ContentResolver, uri: Uri, dao: MerchDao) {
        val root = JSONObject().apply {
            put("formatVersion", FORMAT_VERSION)
            put("createdAt", System.currentTimeMillis())
            put("categories", JSONArray(dao.allCategories().map { it.toJson() }))
            put("products", JSONArray(dao.allProducts().map { it.toJson() }))
            put("sales", JSONArray(dao.allSales().map { it.toJson() }))
            put("lines", JSONArray(dao.allLines().map { it.toJson() }))
        }
        resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(root.toString(2)) }
            ?: error("Не удалось открыть файл для записи")
    }

    suspend fun import(resolver: ContentResolver, uri: Uri, dao: MerchDao) {
        val root = resolver.openInputStream(uri)?.bufferedReader()?.use { JSONObject(it.readText()) }
            ?: error("Не удалось открыть файл резервной копии")
        require(root.optInt("formatVersion") == FORMAT_VERSION) { "Неподдерживаемый формат резервной копии" }
        val backup = MerchBackup(
            createdAt = root.getLong("createdAt"),
            categories = root.array("categories") { it.category() },
            products = root.array("products") { it.product() },
            sales = root.array("sales") { it.sale() },
            lines = root.array("lines") { it.line() }
        )
        dao.replaceWithBackup(backup)
    }

    private fun Category.toJson() = JSONObject().apply { put("id", id); put("name", name); put("color", color) }
    private fun Product.toJson() = JSONObject().apply {
        put("id", id); put("sku", sku); put("name", name); put("description", description); put("categoryId", categoryId); put("price", price); put("vatRate", vatRate); put("stock", stock); put("photoUri", photoUri); put("updatedAt", updatedAt)
    }
    private fun Sale.toJson() = JSONObject().apply { put("id", id); put("createdAt", createdAt); put("paymentType", paymentType.name); put("total", total); put("note", note) }
    private fun SaleLine.toJson() = JSONObject().apply { put("id", id); put("saleId", saleId); put("productId", productId); put("productName", productName); put("sku", sku); put("unitPrice", unitPrice); put("vatRate", vatRate); put("quantity", quantity) }
    private fun JSONObject.category() = Category(getLong("id"), getString("name"), getLong("color"))
    private fun JSONObject.product() = Product(getLong("id"), getString("sku"), getString("name"), optString("description"), if (isNull("categoryId")) null else getLong("categoryId"), getDouble("price"), getDouble("vatRate"), getInt("stock"), if (isNull("photoUri")) null else getString("photoUri"), getLong("updatedAt"))
    private fun JSONObject.sale() = Sale(getLong("id"), getLong("createdAt"), PaymentType.valueOf(getString("paymentType")), getDouble("total"), optString("note"))
    private fun JSONObject.line() = SaleLine(getLong("id"), getLong("saleId"), getLong("productId"), getString("productName"), getString("sku"), getDouble("unitPrice"), getDouble("vatRate"), getInt("quantity"))
    private inline fun <T> JSONObject.array(key: String, map: (JSONObject) -> T): List<T> {
        val array = getJSONArray(key)
        return List(array.length()) { map(array.getJSONObject(it)) }
    }
}
