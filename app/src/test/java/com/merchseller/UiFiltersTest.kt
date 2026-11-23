package com.merchseller

import com.merchseller.data.Product
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class UiFiltersTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun todayWindowUsesLocalMidnightAndExclusiveNextDay() {
        val now = calendar(2026, Calendar.NOVEMBER, 23, 14).timeInMillis
        val window = DatePreset.TODAY.window(now, utc)

        assertEquals(calendar(2026, Calendar.NOVEMBER, 23, 0).timeInMillis, window.from)
        assertEquals(calendar(2026, Calendar.NOVEMBER, 24, 0).timeInMillis, window.toExclusive)
    }

    @Test
    fun sevenDayWindowIncludesTodayAndPreviousSixDays() {
        val now = calendar(2026, Calendar.NOVEMBER, 23, 14).timeInMillis
        val window = DatePreset.LAST_7_DAYS.window(now, utc)

        assertEquals(calendar(2026, Calendar.NOVEMBER, 17, 0).timeInMillis, window.from)
        assertEquals(calendar(2026, Calendar.NOVEMBER, 24, 0).timeInMillis, window.toExclusive)
    }

    @Test
    fun monthWindowHandlesDecemberBoundary() {
        val now = calendar(2026, Calendar.DECEMBER, 18, 12).timeInMillis
        val window = DatePreset.THIS_MONTH.window(now, utc)

        assertEquals(calendar(2026, Calendar.DECEMBER, 1, 0).timeInMillis, window.from)
        assertEquals(calendar(2027, Calendar.JANUARY, 1, 0).timeInMillis, window.toExclusive)
    }

    @Test
    fun productFiltersCombineQueryCategoryAndLowStockAndSortByPrice() {
        val products = listOf(
            product(id = 1, name = "Кофе", sku = "C-1", stock = 3, price = 500, category = 7),
            product(id = 2, name = "Чай", sku = "T-2", stock = 2, price = 300, category = 7),
            product(id = 3, name = "Кофе молотый", sku = "C-3", stock = 2, price = 200, category = 8),
            product(id = 4, name = "Кофе без типа", sku = "C-4", stock = 1, price = 100, category = null)
        )

        val result = filterAndSortProducts(
            products, query = "кофе", categoryKey = "7", lowStockOnly = true,
            lowStockThreshold = 3, sort = ProductSort.PRICE_ASC
        )

        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun uncategorizedFilterAndStockSortAreStable() {
        val products = listOf(
            product(id = 1, name = "Зет", sku = "Z", stock = 2, price = 500, category = null),
            product(id = 2, name = "Альфа", sku = "A", stock = 0, price = 500, category = null),
            product(id = 3, name = "Другой тип", sku = "X", stock = 0, price = 200, category = 4)
        )

        val result = filterAndSortProducts(
            products, query = "", categoryKey = "none", lowStockOnly = false,
            lowStockThreshold = 5, sort = ProductSort.STOCK_ASC
        )

        assertEquals(listOf(2L, 1L), result.map { it.id })
    }

    private fun calendar(year: Int, month: Int, day: Int, hour: Int): Calendar = Calendar.getInstance(utc).apply {
        clear()
        set(year, month, day, hour, 0, 0)
    }

    private fun product(id: Long, name: String, sku: String, stock: Int, price: Long, category: Long?) = Product(
        id = id, name = name, sku = sku, stock = stock, priceKopecks = price,
        vatRate = 0.0, categoryId = category
    )
}
