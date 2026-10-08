package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The discount printed on a line, in percent: "30" (30%), "10+5" (10% then 5% on what is left, as suppliers
 * print cascaded discounts). Kept as printed, so the operator sees the same thing as on the paper; the amount
 * of the line is what was paid, and prices per kg or litre come from that amount.
 */
object LineDiscount {
    private val HUNDRED = BigDecimal(100)

    /** "30,0" → "30", "10+5%" → "10+5", "12,50" → "12,5"; null when it is not a discount (empty, 0, 100 or more). */
    fun normalize(raw: String?): String? {
        val t = raw?.trim()?.trim('%', ' ', '-')?.replace(" ", "") ?: return null
        if (t.isEmpty()) return null
        val parts = t.split('+').map { p -> ItalianNumbers.parse(p.trim('%')) ?: return null }
        if (parts.any { it.signum() < 0 || it >= HUNDRED } || parts.all { it.signum() == 0 }) return null
        return parts.filter { it.signum() > 0 }.joinToString("+") { ItalianNumbers.toEditText(it.stripTrailingZeros()) }
    }

    /** The whole discount in percent ("10+5" → 14,5). */
    fun percent(text: String?): BigDecimal? {
        val n = normalize(text) ?: return null
        var keep = BigDecimal.ONE
        for (p in n.split('+')) keep = keep.multiply(BigDecimal.ONE.subtract(ItalianNumbers.parse(p)!!.movePointLeft(2)))
        return BigDecimal.ONE.subtract(keep).movePointRight(2).stripTrailingZeros()
    }

    /** quantity x price less the discount, in cents. */
    fun net(quantity: BigDecimal, price: BigDecimal, text: String?): Long {
        val d = percent(text) ?: BigDecimal.ZERO
        val v = quantity.multiply(price).multiply(HUNDRED.subtract(d)).divide(HUNDRED, 6, RoundingMode.HALF_UP)
        return ItalianNumbers.toCents(v)
    }

    /** quantity x price, less the discount when there is one, gives the amount (to 2 cents). */
    fun matches(quantity: BigDecimal, price: BigDecimal, text: String?, cents: Long): Boolean =
        ReceiptParser.matches(quantity, price, cents) ||
            (percent(text) != null && kotlin.math.abs(net(quantity, price, text) - cents) <= 2)
}
