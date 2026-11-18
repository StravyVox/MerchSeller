package com.merchseller.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test fun roundsUnitBeforeMultiplyingByQuantity() {
        val product = Product(sku = "A", name = "Товар", priceKopecks = 3, vatRate = 20.0)
        val lines = listOf(DraftLine(product, 5))
        assertEquals(4.toBigInteger(), product.totalPriceKopecks)
        assertEquals(20.toBigInteger(), lines.totalKopecks())
    }

    @Test fun roundsHalfACentAndAverageUp() {
        assertEquals(11.toBigInteger(), kopecksWithVat(10, 5.0))
        assertEquals(11.toBigInteger(), averageKopecks(21.toBigInteger(), 2))
    }

    @Test fun acceptsDecimalCommaAndRejectsSubKopeckInput() {
        assertEquals(1234L, "12,34".parsePriceKopecks())
        assertNull("12,345".parsePriceKopecks())
        assertNull("1e200".parsePriceKopecks())
    }

    @Test fun oldAmountsRoundWithoutBinaryFloatingPointDrift() {
        assertEquals(101L, legacyKopecks(1.005))
        assertEquals(7L, legacyLineKopecks(0.03, 20.0, 2))
    }
}
