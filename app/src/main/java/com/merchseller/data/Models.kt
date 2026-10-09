package com.merchseller.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long = 0xFF6750A4
)

@Entity(
    tableName = "products",
    foreignKeys = [ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index(value = ["sku"], unique = true), Index(value = ["categoryId"])]
)
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sku: String,
    val name: String,
    val description: String = "",
    val categoryId: Long? = null,
    val price: Double,
    val vatRate: Double = 20.0,
    val stock: Int = 0,
    val photoUri: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val totalPrice get() = price * (1 + vatRate / 100)
}

@Entity(tableName = "sales", indices = [Index(value = ["createdAt"])])
data class Sale(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val paymentType: PaymentType,
    val total: Double,
    val note: String = ""
)

@Entity(tableName = "sale_lines", foreignKeys = [ForeignKey(entity = Sale::class, parentColumns = ["id"], childColumns = ["saleId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["saleId"]), Index(value = ["productId"])])
data class SaleLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val productId: Long,
    val productName: String,
    val sku: String,
    val unitPrice: Double,
    val vatRate: Double,
    val quantity: Int
)

enum class PaymentType { CASH, CARD }

data class SaleWithLines(val sale: Sale, val lines: List<SaleLine>)
