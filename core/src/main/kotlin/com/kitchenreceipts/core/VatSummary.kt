package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The VAT summary at the foot of an invoice ("540,62  04  21,62  ALIQUOTA 4%"): taxable amount per VAT rate.
 * A row is taken only when it proves itself: rate x taxable amount = the VAT printed next to it.
 *
 * Checked against the product lines: the lines at each rate must add up to that rate's taxable amount. A group
 * that does not add up says *where* a line was misread (e.g. "the 10% lines are 7,53 short"), which is far more
 * useful than "the total does not match".
 */
object VatSummary {

    data class Group(val ratePercent: BigDecimal, val taxableCents: Long, val vatCents: Long, val source: String)

    data class Check(val ratePercent: BigDecimal, val printedCents: Long, val linesCents: Long, val lines: Int) {
        val ok: Boolean get() = kotlin.math.abs(printedCents - linesCents) <= maxOf(1L, lines / 4L)
        val differenceCents: Long get() = printedCents - linesCents
    }

    private val MONEY = Regex("(?<![\\d,.])\\d{1,3}(?:\\.\\d{3})*,\\d{2}(?![\\d,])")
    private val RATE_TOKEN = Regex("(?<![\\d,.])(0?4|0?5|10|22)(?![\\d,])")

    fun parse(text: String): List<Group> {
        val out = mutableListOf<Group>()
        for (line in text.lines()) {
            val clean = OcrCleanup.cleanLine(line)
            val money = MONEY.findAll(clean).toList()
            if (money.size < 2) continue
            val rates = RATE_TOKEN.findAll(clean).filter { r -> money.none { m -> r.range.first in m.range } }.map { BigDecimal(it.value) }.toList()
            if (rates.isEmpty()) continue
            val amounts = money.mapNotNull { ItalianNumbers.parseCents(it.value) }
            found@ for (rate in rates.distinct()) {
                for (i in amounts.indices) for (j in amounts.indices) {
                    if (i == j || amounts[j] >= amounts[i]) continue
                    val expected = BigDecimal(amounts[i]).multiply(rate).divide(BigDecimal(100), 0, RoundingMode.HALF_UP).toLong()
                    if (kotlin.math.abs(expected - amounts[j]) <= 1 && amounts[j] > 0) {
                        out += Group(rate.stripTrailingZeros(), amounts[i], amounts[j], line)
                        break@found
                    }
                }
            }
        }
        return out.distinctBy { it.ratePercent to it.taxableCents }
    }

    /** One check per VAT rate of the summary; empty when there is no summary or some lines have no rate. */
    fun check(items: List<ParsedLineItem>, groups: List<Group>): List<Check> {
        if (groups.size < 1 || items.isEmpty()) return emptyList()
        if (items.any { it.vatRatePercent == null || it.lineTotalCents == null }) return emptyList()
        val byRate = items.groupBy { it.vatRatePercent!!.value.stripTrailingZeros() }
        // Every rate on the lines must be in the summary, or the summary was not read completely.
        if (byRate.keys.any { k -> groups.none { it.ratePercent.compareTo(k) == 0 } }) return emptyList()
        return groups.map { g ->
            val lines = byRate.entries.firstOrNull { it.key.compareTo(g.ratePercent) == 0 }?.value.orEmpty()
            Check(g.ratePercent, g.taxableCents, lines.sumOf { it.lineTotalCents!!.value }, lines.size)
        }
    }
}
