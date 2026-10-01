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
    /** "4.02" printed (or read) with a dot: two decimals after a dot and nothing more is an amount, not thousands. */
    private val DOT_DECIMAL = Regex("(?<![\\d,.])(\\d{1,3})\\.(\\d{2})(?![\\d,.])")
    private val RATE_TOKEN = Regex("(?<![\\d,.])(0?4|0?5|10|22)(?![\\d,])")

    /** 10, not 1E+1. */
    private fun plain(v: BigDecimal): BigDecimal = v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    fun parse(text: String): List<Group> {
        val out = mutableListOf<Group>()
        for (line in text.lines()) {
            val clean = DOT_DECIMAL.replace(OcrCleanup.cleanLine(line)) { m -> "${m.groupValues[1]},${m.groupValues[2]}" }
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
                        out += Group(plain(rate), amounts[i], amounts[j], line)
                        break@found
                    }
                }
            }
        }
        return out.distinctBy { it.ratePercent to it.taxableCents }
    }

    /**
     * Lines without a VAT rate (the rate was printed out of place) get the one rate that makes every VAT group add up,
     * when exactly one way works. The rate is then proven by the summary; nothing is filled in otherwise.
     */
    fun fillMissingRates(items: List<ParsedLineItem>, groups: List<Group>): List<ParsedLineItem> {
        val missing = items.indices.filter { items[it].vatRatePercent == null && items[it].lineTotalCents != null }
        if (missing.isEmpty() || missing.size > 3 || groups.isEmpty() || items.any { it.lineTotalCents == null }) return items
        val rates = groups.map { it.ratePercent }
        var solutions = 0
        var found: List<BigDecimal>? = null
        fun tryAssign(k: Int, chosen: List<BigDecimal>) {
            if (solutions > 1) return
            if (k == missing.size) {
                val filled = items.mapIndexed { i, it ->
                    val j = missing.indexOf(i)
                    if (j >= 0) it.copy(vatRatePercent = Extracted(chosen[j], Confidence.HIGH, "VAT summary")) else it
                }
                val checks = check(filled, groups)
                if (checks.isNotEmpty() && checks.all { it.ok }) { solutions++; found = chosen }
                return
            }
            for (r in rates) tryAssign(k + 1, chosen + r)
        }
        tryAssign(0, emptyList())
        val pick = found
        if (solutions != 1 || pick == null) return items
        return items.mapIndexed { i, it ->
            val j = missing.indexOf(i)
            if (j >= 0) it.copy(vatRatePercent = Extracted(pick[j], Confidence.HIGH, "VAT summary (lines of ${ItalianNumbers.formatDecimal(pick[j])}% add up)")) else it
        }
    }

    /** One check per VAT rate of the summary; empty when there is no summary or some lines have no rate. */
    fun check(items: List<ParsedLineItem>, groups: List<Group>): List<Check> {
        if (groups.size < 1 || items.isEmpty()) return emptyList()
        if (items.any { it.vatRatePercent == null || it.lineTotalCents == null }) return emptyList()
        val byRate = items.groupBy { plain(it.vatRatePercent!!.value) }
        // Every rate on the lines must be in the summary, or the summary was not read completely.
        if (byRate.keys.any { k -> groups.none { it.ratePercent.compareTo(k) == 0 } }) return emptyList()
        return groups.map { g ->
            val lines = byRate.entries.firstOrNull { it.key.compareTo(g.ratePercent) == 0 }?.value.orEmpty()
            Check(g.ratePercent, g.taxableCents, lines.sumOf { it.lineTotalCents!!.value }, lines.size)
        }
    }
}
