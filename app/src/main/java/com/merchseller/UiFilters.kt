package com.merchseller

import com.merchseller.data.Product
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

enum class DatePreset(val label: String) {
    TODAY("Сегодня"),
    LAST_7_DAYS("7 дней"),
    THIS_MONTH("Месяц"),
    ALL_TIME("Всё время")
}

data class TimeWindow(val from: Long, val toExclusive: Long)

fun DatePreset.window(nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): TimeWindow {
    val calendar = Calendar.getInstance(timeZone).apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val endOfToday = (calendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
    when (this) {
        DatePreset.TODAY -> Unit
        DatePreset.LAST_7_DAYS -> calendar.add(Calendar.DAY_OF_YEAR, -6)
        DatePreset.THIS_MONTH -> {
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            val from = calendar.timeInMillis
            calendar.add(Calendar.MONTH, 1)
            return TimeWindow(from, calendar.timeInMillis)
        }
        DatePreset.ALL_TIME -> return TimeWindow(0L, nowMillis + 1L)
    }
    return TimeWindow(calendar.timeInMillis, endOfToday)
}

enum class ProductSort(val label: String) {
    NAME("По названию"),
    STOCK_ASC("Сначала малый остаток"),
    STOCK_DESC("Сначала большой остаток"),
    PRICE_ASC("Сначала дешёвые"),
    PRICE_DESC("Сначала дорогие")
}

fun filterAndSortProducts(
    products: List<Product>,
    query: String,
    categoryKey: String,
    lowStockOnly: Boolean,
    lowStockThreshold: Int,
    sort: ProductSort
): List<Product> {
    val normalizedQuery = query.trim()
    val categoryId = categoryKey.toLongOrNull()
    val filtered = products.asSequence().filter { product ->
        (normalizedQuery.isBlank() || product.name.contains(normalizedQuery, ignoreCase = true) || product.sku.contains(normalizedQuery, ignoreCase = true)) &&
            when (categoryKey) {
                "all" -> true
                "none" -> product.categoryId == null
                else -> categoryId != null && product.categoryId == categoryId
            } && (!lowStockOnly || product.stock <= lowStockThreshold)
    }
    val comparator = when (sort) {
        ProductSort.NAME -> compareBy<Product> { it.name.lowercase(Locale.ROOT) }
        ProductSort.STOCK_ASC -> compareBy<Product> { it.stock }.thenBy { it.name.lowercase(Locale.ROOT) }
        ProductSort.STOCK_DESC -> compareByDescending<Product> { it.stock }.thenBy { it.name.lowercase(Locale.ROOT) }
        ProductSort.PRICE_ASC -> compareBy<Product> { it.totalPriceKopecks }.thenBy { it.name.lowercase(Locale.ROOT) }
        ProductSort.PRICE_DESC -> compareByDescending<Product> { it.totalPriceKopecks }.thenBy { it.name.lowercase(Locale.ROOT) }
    }
    return filtered.sortedWith(comparator).toList()
}
