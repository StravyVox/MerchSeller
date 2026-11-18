package com.merchseller.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BackupManagerTest {
    @Test
    fun parsesAValidBackupWithoutChangingItsRecords() {
        val backup = parseBackup(validBackup().toString().toByteArray(Charsets.UTF_8))

        assertEquals(1, backup.categories.size)
        assertEquals("Игрушки", backup.categories.single().name)
        assertEquals(1, backup.products.size)
        assertEquals("SKU-1", backup.products.single().sku)
        assertEquals(1, backup.sales.size)
        assertEquals(PaymentType.CARD, backup.sales.single().paymentType)
        assertEquals(1, backup.lines.size)
        assertEquals(2, backup.lines.single().quantity)
        assertEquals(1000L, backup.products.single().priceKopecks)
        assertEquals(2400L, backup.sales.single().totalKopecks)
        assertEquals(2400L, backup.lines.single().lineTotalKopecks)
    }

    @Test fun readsV2PhotoPayloadAndKeepsRecordedTotals() {
        val json = validBackup().put("formatVersion", 2)
        json.getJSONArray("products").getJSONObject(0)
            .put("priceKopecks", 3).put("photoJpeg", "AQID").put("photoUri", "file:///ignored")
        json.getJSONArray("sales").getJSONObject(0).put("totalKopecks", 7)
        json.getJSONArray("lines").getJSONObject(0).put("unitPriceKopecks", 3).put("lineTotalKopecks", 8)

        val (backup, photos) = parseBackupContents(json.toString().toByteArray(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(1, 2, 3), photos.getValue(1L))
        assertNull(backup.products.single().photoUri)
        assertEquals(7L, backup.sales.single().totalKopecks)
        assertEquals(8L, backup.lines.single().lineTotalKopecks)
    }

    @Test fun rejectsOversizedPhotoPayload() {
        val oversized = java.util.Base64.getEncoder().encodeToString(ByteArray(MAX_PHOTO_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { decodePhotoPayload(oversized) }
    }

    @Test
    fun rejectsAnOversizedInputStreamBeforeParsing() {
        val oversized = ByteArray(MAX_BACKUP_BYTES + 1)

        assertThrows(IllegalArgumentException::class.java) {
            readBackupBytes(ByteArrayInputStream(oversized))
        }
    }

    @Test
    fun rejectsExcessiveJsonNestingBeforeParsing() {
        val nestedValue = "[".repeat(MAX_BACKUP_JSON_DEPTH + 1) + "0" + "]".repeat(MAX_BACKUP_JSON_DEPTH + 1)
        val json = validBackup().toString().dropLast(1) + ",\"ignored\":$nestedValue}"

        assertThrows(IllegalArgumentException::class.java) {
            parseBackup(json.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun rejectsAProductArrayOverTheConfiguredLimit() {
        val products = JSONArray()
        repeat(MAX_BACKUP_PRODUCTS + 1) { index ->
            products.put(JSONObject()
                .put("id", index + 1)
                .put("sku", "SKU-$index")
                .put("name", "Товар")
                .put("description", "")
                .put("categoryId", JSONObject.NULL)
                .put("price", 10)
                .put("vatRate", 20)
                .put("stock", 1)
                .put("photoUri", JSONObject.NULL)
                .put("updatedAt", 1))
        }
        val json = validBackup().put("products", products).toString().toByteArray(Charsets.UTF_8)
        assertTrue("Тестовый JSON должен быть меньше лимита байтов", json.size < MAX_BACKUP_BYTES)

        assertThrows(IllegalArgumentException::class.java) { parseBackup(json) }
    }

    @Test
    fun rejectsAnOverlongProductName() {
        val product = validBackup().getJSONArray("products").getJSONObject(0)
        product.put("name", "а".repeat(257))

        assertThrows(IllegalArgumentException::class.java) {
            parseBackup(validBackup(product = product).toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun validBackup(product: JSONObject = validProduct()): JSONObject = JSONObject()
        .put("formatVersion", 1)
        .put("createdAt", 1_700_000_000_000L)
        .put("categories", JSONArray().put(JSONObject().put("id", 1).put("name", "Игрушки").put("color", 1)))
        .put("products", JSONArray().put(product))
        .put("sales", JSONArray().put(JSONObject()
            .put("id", 2).put("createdAt", 1_700_000_000_001L).put("paymentType", "CARD").put("total", 24).put("note", "Тест")))
        .put("lines", JSONArray().put(JSONObject()
            .put("id", 3).put("saleId", 2).put("productId", 1).put("productName", "Мяч")
            .put("sku", "SKU-1").put("unitPrice", 10).put("vatRate", 20).put("quantity", 2)))

    private fun validProduct(): JSONObject = JSONObject()
        .put("id", 1).put("sku", "SKU-1").put("name", "Мяч").put("description", "")
        .put("categoryId", 1).put("price", 10).put("vatRate", 20).put("stock", 4)
        .put("photoUri", JSONObject.NULL).put("updatedAt", 1_700_000_000_000L)
}
