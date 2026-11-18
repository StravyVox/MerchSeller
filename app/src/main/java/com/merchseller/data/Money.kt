package com.merchseller.data

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

internal fun String.parsePriceKopecks(): Long? {
    val amount = trim().replace(',', '.')
    if (amount.length > 20 || !amount.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) return null
    return runCatching { amount.toBigDecimal().movePointRight(2).longValueExact() }.getOrNull()
}

internal fun BigDecimal.toKopecks(): Long =
    movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()

internal fun legacyKopecks(rubles: Double): Long =
    BigDecimal.valueOf(rubles).toKopecks()

internal fun legacyLineKopecks(unitPrice: Double, vatRate: Double, quantity: Int): Long =
    BigDecimal.valueOf(unitPrice)
        .multiply(BigDecimal.ONE.add(BigDecimal.valueOf(vatRate).movePointLeft(2)))
        .multiply(BigDecimal.valueOf(quantity.toLong()))
        .toKopecks()

internal fun kopecksWithVat(priceKopecks: Long, vatRate: Double): BigInteger {
    // Округляем цену одной единицы с НДС, затем умножаем на количество.
    val factor = BigDecimal.ONE.add(BigDecimal.valueOf(vatRate).movePointLeft(2))
    return BigDecimal.valueOf(priceKopecks).multiply(factor).setScale(0, RoundingMode.HALF_UP).toBigIntegerExact()
}

internal fun Iterable<DraftLine>.totalKopecks(): BigInteger =
    fold(BigInteger.ZERO) { sum, line -> sum.add(line.totalKopecks) }

internal fun BigInteger.toReceiptKopecks(): Long {
    require(bitLength() <= Long.SIZE_BITS - 1) {
        "Сумма чека слишком велика. Уменьшите количество товаров."
    }
    return toLong()
}

internal fun averageKopecks(total: BigInteger, count: Int): BigInteger =
    if (count == 0) BigInteger.ZERO
    else total.toBigDecimal().divide(BigDecimal.valueOf(count.toLong()), 0, RoundingMode.HALF_UP).toBigIntegerExact()
