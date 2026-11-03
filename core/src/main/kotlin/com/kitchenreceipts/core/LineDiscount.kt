package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The discount printed on a line, in percent: "30" (30%), "10+5" (10% then 5% on what is left, as suppliers
 * print cascaded discounts). Kept as printed, so the operator sees the same thing as on the paper; the amount
 * of the line is what was paid, and prices per kg or litre come from that amount.
 *
 * Or an amount off each unit, as an e-invoice's SCONTO O MAGG. column prints it without a percent sign ("-27,05"):
 * kept as "27,05€", quantity x (price - it) is the amount.
 */
object LineDiscount {
    private val HUNDRED = BigDecimal(100)

    /** "30,0" → "30", "10+5%" → "10+5", "12,50" → "12,5"; null when it is not a discount (empty, 0, 100 or more). */
    fun normalize(raw: String?): String? {
        val t = raw?.trim()?.trim('%', ' ', '-')?.replace(" ", "") ?: return null
        if (t.isEmpty()) return null
        if (t.endsWith("€")) {
            val a = ItalianNumbers.parse(t.trimEnd('€')) ?: return null
            return if (a.signum() > 0) ItalianNumbers.toEditText(a.stripTrailingZeros()) + "€" else null
        }
        val parts = t.split('+').map { p -> ItalianNumbers.parse(p.trim('%')) ?: return null }
        // 100: free goods ("omaggio"), the amount is 0,00.
        if (parts.any { it.signum() < 0 || it > HUNDRED } || parts.all { it.signum() == 0 }) return null
        return parts.filter { it.signum() > 0 }.joinToString("+") { ItalianNumbers.toEditText(it.stripTrailingZeros()) }
    }

    /** Free goods ("omaggio"): a quantity discounted 100% ([percent]); its amount is 0,00 whatever its price. */
    fun isFree(quantity: BigDecimal?, percent: BigDecimal?): Boolean = quantity != null && percent?.compareTo(HUNDRED) == 0

    /** As shown next to a price: "10" → "-10%", "10+5" → "-10%+5%"; null when it is not a discount. */
    fun label(text: String?): String? = normalize(text)?.let { n ->
        if (n.endsWith("€")) "-" + n.trimEnd('€') + " €" else n.split('+').joinToString("+", prefix = "-") { "$it%" }
    }

    /** An amount off each unit ("27,05€"), or null for a percent discount. */
    fun perUnit(text: String?): BigDecimal? = normalize(text)?.takeIf { it.endsWith("€") }?.let { ItalianNumbers.parse(it.trimEnd('€')) }

    /** The whole discount in percent ("10+5" → 14,5). */
    fun percent(text: String?): BigDecimal? {
        val n = normalize(text)?.takeIf { !it.endsWith("€") } ?: return null
        var keep = BigDecimal.ONE
        for (p in n.split('+')) keep = keep.multiply(BigDecimal.ONE.subtract(ItalianNumbers.parse(p)!!.movePointLeft(2)))
        return BigDecimal.ONE.subtract(keep).movePointRight(2).stripTrailingZeros()
    }

    /** quantity x price less the discount, in cents. */
    fun net(quantity: BigDecimal, price: BigDecimal, text: String?): Long {
        perUnit(text)?.let { a -> return ItalianNumbers.toCents(quantity.multiply(price.subtract(a))) }
        val d = percent(text) ?: BigDecimal.ZERO
        val v = quantity.multiply(price).multiply(HUNDRED.subtract(d)).divide(HUNDRED, 6, RoundingMode.HALF_UP)
        return ItalianNumbers.toCents(v)
    }

    /**
     * quantity x price, less the discount when there is one, gives the amount (the discount's rounding: 2 cents).
     * With a discount on the line, only the discounted amount proves it: "2 × 5,00 = 10,00 sconto 30" does not add
     * up (the discount or a number is wrong), whatever 2 × 5,00 alone gives.
     */
    fun matches(quantity: BigDecimal, price: BigDecimal, text: String?, cents: Long): Boolean {
        perUnit(text)?.let { a -> return ReceiptParser.matches(quantity, price.subtract(a), cents) }
        val d = percent(text)
        if (d == null || d.signum() == 0) return ReceiptParser.matches(quantity, price, cents)
        return kotlin.math.abs(net(quantity, price, text) - cents) <= 2
    }
}
