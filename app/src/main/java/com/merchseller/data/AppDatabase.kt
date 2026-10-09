package com.merchseller.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

class DbConverters {
    @TypeConverter fun fromPayment(value: PaymentType) = value.name
    @TypeConverter fun toPayment(value: String) = PaymentType.valueOf(value)
}

@Dao
interface MerchDao {
    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE") fun products(): Flow<List<Product>>
    @Query("SELECT * FROM products WHERE sku = :sku LIMIT 1") suspend fun bySku(sku: String): Product?
    @Query("SELECT * FROM products WHERE id = :id LIMIT 1") suspend fun product(id: Long): Product?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveProduct(product: Product): Long
    @Query("DELETE FROM products WHERE id = :id") suspend fun deleteProduct(id: Long)
    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE") fun categories(): Flow<List<Category>>
    @Query("SELECT * FROM categories ORDER BY id") suspend fun allCategories(): List<Category>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveCategory(category: Category): Long
    @Query("SELECT * FROM sales ORDER BY createdAt DESC") fun sales(): Flow<List<Sale>>
    @Query("SELECT * FROM products ORDER BY id") suspend fun allProducts(): List<Product>
    @Query("SELECT * FROM sales ORDER BY id") suspend fun allSales(): List<Sale>
    @Query("SELECT * FROM sale_lines ORDER BY id") suspend fun allLines(): List<SaleLine>
    @Query("SELECT * FROM sale_lines WHERE saleId = :saleId") suspend fun lines(saleId: Long): List<SaleLine>
    @Insert suspend fun saveSale(sale: Sale): Long
    @Insert suspend fun saveLines(lines: List<SaleLine>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveCategories(categories: List<Category>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveProducts(products: List<Product>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSales(sales: List<Sale>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveAllLines(lines: List<SaleLine>)
    @Query("DELETE FROM sale_lines") suspend fun clearLines()
    @Query("DELETE FROM sales") suspend fun clearSales()
    @Query("DELETE FROM products") suspend fun clearProducts()
    @Query("DELETE FROM categories") suspend fun clearCategories()
    @Query("UPDATE products SET stock = stock - :quantity, updatedAt = :time WHERE id = :productId") suspend fun decreaseStock(productId: Long, quantity: Int, time: Long)
    @Query("UPDATE sales SET paymentType = :type, note = :note WHERE id = :saleId") suspend fun updateSale(saleId: Long, type: PaymentType, note: String)
    @Query("SELECT COALESCE(SUM(total), 0) FROM sales WHERE createdAt >= :from") fun turnover(from: Long): Flow<Double>
    @Query("SELECT COUNT(*) FROM sales WHERE createdAt >= :from") fun salesCount(from: Long): Flow<Int>

    @Transaction
    suspend fun closeSale(payment: PaymentType, draft: List<DraftLine>, note: String): Long {
        require(draft.isNotEmpty()) { "Чек не содержит товаров" }
        draft.forEach { require(it.quantity > 0 && it.product.stock >= it.quantity) { "Недостаточно товара: ${it.product.name}" } }
        val saleId = saveSale(Sale(paymentType = payment, total = draft.sumOf { it.product.totalPrice * it.quantity }, note = note))
        saveLines(draft.map { SaleLine(saleId = saleId, productId = it.product.id, productName = it.product.name, sku = it.product.sku, unitPrice = it.product.price, vatRate = it.product.vatRate, quantity = it.quantity) })
        draft.forEach { decreaseStock(it.product.id, it.quantity, System.currentTimeMillis()) }
        return saleId
    }

    /**
     * Импорт намеренно заменяет всю базу одной транзакцией: так ссылки в чеках
     * и идентификаторы товаров остаются согласованными даже после переноса на другой телефон.
     */
    @Transaction
    suspend fun replaceWithBackup(backup: MerchBackup) {
        clearLines(); clearSales(); clearProducts(); clearCategories()
        saveCategories(backup.categories)
        saveProducts(backup.products)
        saveSales(backup.sales)
        saveAllLines(backup.lines)
    }
}

data class DraftLine(val product: Product, val quantity: Int)

/** Полный снимок автономной базы для передачи между устройствами. */
data class MerchBackup(
    val createdAt: Long,
    val categories: List<Category>,
    val products: List<Product>,
    val sales: List<Sale>,
    val lines: List<SaleLine>
)

@Database(entities = [Category::class, Product::class, Sale::class, SaleLine::class], version = 1, exportSchema = false)
@TypeConverters(DbConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): MerchDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "merch_seller.db").build()
    }
}
