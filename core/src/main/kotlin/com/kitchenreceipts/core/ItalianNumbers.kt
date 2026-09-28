package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Exact parsing and formatting of amounts as they appear on Italian documents
 * ("1.234,56", "12,5", "€ 3,90", "-2,00"). Never uses floating point.
 */
object ItalianNumbers {

    private val CURRENCY_NOISE = Regex("(?i)€|\\beur\\b|\\beuro\\b")
    private val VALID_PLAIN = Regex("^\\d+(\\.\\d+)?$")

    /**
     * Parses an amount or quantity. Returns null if [raw] is not a number.
     *
     * Rules (Italian first):
     * - both '.' and ',' present: the right-most one is the decimal separator;
     * - only ',' present: one comma is the decimal separator, several are thousands separators;
     * - only '.' present: several dots, or a single dot followed by exactly three digits
     *   ("1.250"), are thousands separators; otherwise the dot is a decimal point ("12.50").
     */
    fun parse(raw: String?): BigDecimal? {
        if (raw == null) return null
        var s = CURRENCY_NOISE.replace(raw, "")
            .replace(' ', ' ')
            .replace(" ", "")
            .replace("'", "") // Swiss-style thousands separator occasionally seen
            .trim()
        if (s.isEmpty()) return null

        var negative = false
        if (s.startsWith("-") || s.startsWith("−")) { negative = true; s = s.substring(1) }
        else if (s.startsWith("+")) s = s.substring(1)
        if (s.endsWith("-")) { negative = true; s = s.dropLast(1) } // "12,00-" credit notation
        if (s.isEmpty() || s.any { !(it.isDigit() || it == '.' || it == ',') }) return null
        if (!s.first().isDigit() && !(s.first() == ',' || s.first() == '.')) return null

        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val dots = s.count { it == '.' }
        val commas = s.count { it == ',' }

        val normalized: String = when {
            dots > 0 && commas > 0 -> if (lastComma > lastDot) {
                s.replace(".", "").replace(',', '.')
            } else {
                s.replace(",", "")
            }
            commas == 1 -> s.replace(',', '.')
            commas > 1 -> s.replace(",", "")
            dots > 1 -> s.replace(".", "")
            dots == 1 -> {
                val after = s.length - lastDot - 1
                val intPart = s.substring(0, lastDot)
                if (after == 3 && intPart.isNotEmpty() && intPart.trimStart('0').isNotEmpty()) {
                    s.replace(".", "")
                } else {
                    s
                }
            }
            else -> s
        }
        val withLeadingZero = if (normalized.startsWith(".")) "0$normalized" else normalized
        if (!VALID_PLAIN.matches(withLeadingZero)) return null
        val value = BigDecimal(withLeadingZero)
        return if (negative) value.negate() else value
    }

    /** Converts an amount to integer cents, rounding half-up only beyond the second decimal. */
    fun toCents(amount: BigDecimal): Long =
        amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()

    fun parseCents(raw: String?): Long? = parse(raw)?.let { toCents(it) }

    fun centsToDecimal(cents: Long): BigDecimal = BigDecimal.valueOf(cents, 2)

    /** 123456 -> "1.234,56" */
    fun formatCents(cents: Long): String = formatDecimal(centsToDecimal(cents), minScale = 2, maxScale = 2)

    /** 123456, "EUR" -> "1.234,56 €" */
    fun formatMoney(cents: Long, currency: String? = "EUR"): String {
        val symbol = when (currency?.uppercase()) {
            null, "", "EUR" -> "€"
            else -> currency.uppercase()
        }
        return "${formatCents(cents)} $symbol"
    }

    /**
     * Formats with Italian separators, keeping between [minScale] and [maxScale] decimals
     * and dropping superfluous trailing zeros beyond [minScale].
     */
    fun formatDecimal(value: BigDecimal, minScale: Int = 0, maxScale: Int = 4): String {
        var v = value.setScale(maxScale, RoundingMode.HALF_EVEN).stripTrailingZeros()
        if (v.scale() < minScale) v = v.setScale(minScale)
        val plain = v.abs().toPlainString()
        val intPart = plain.substringBefore('.')
        val decPart = if (plain.contains('.')) plain.substringAfter('.') else ""
        val grouped = intPart.reversed().chunked(3).joinToString(".").reversed()
        val sign = if (v.signum() < 0) "-" else ""
        return if (decPart.isEmpty()) "$sign$grouped" else "$sign$grouped,$decPart"
    }

    /** Plain editable text for input fields: "1234,5" (no thousands separator). */
    fun toEditText(value: BigDecimal?): String {
        if (value == null) return ""
        val s = value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
        return s.replace('.', ',')
    }

    fun centsToEditText(cents: Long?): String =
        if (cents == null) "" else centsToDecimal(cents).toPlainString().replace('.', ',')
}
