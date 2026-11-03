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

    /**
     * [nature]: an e-invoice row without VAT under its nature code ("N2.2": not subject), rate 0. It counts in the
     * document's taxable amount; no line is given its rate, and it is in no check per rate (its lines print no rate).
     */
    data class Group(
        val ratePercent: BigDecimal, val taxableCents: Long, val vatCents: Long, val source: String, val nature: String? = null,
    )

    /** The summary's rows with a VAT rate: a nature row (no VAT, no rate printed on its lines) gives no line a rate. */
    fun withRate(all: List<Group>): List<Group> = all.filter { it.nature == null }

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
        // Rows under the summary's own heading, each proven by its rate (or VAT 0 under a nature code): all of them
        // count, several rows of one rate included (another exigibility or legal reference).
        val tableRows = mutableListOf<Group>()
        var inTable = false
        for (line in text.lines()) {
            val clean = DOT_DECIMAL.replace(OcrCleanup.cleanLine(line)) { m -> "${m.groupValues[1]},${m.groupValues[2]}" }
            // Inside a VAT summary table (its heading names the taxable amount and the tax), a rate may be
            // printed with decimals ("10,00"); the arithmetic below still has to prove the row.
            if (SUMMARY_HEADING.findAll(clean).map { it.value.lowercase().take(5) }.distinct().count() >= 2 && MONEY.findAll(clean).none()) { inTable = true; continue }
            if (inTable && SUMMARY_END.containsMatchIn(clean)) inTable = false
            val row = if (inTable) tableRow(clean, line) else null
            if (row != null) { tableRows += row; continue }
            // A unit price with three decimals ("10,210") makes it a product line, never a VAT summary row.
            if (UNIT_PRICE.containsMatchIn(clean)) continue
            val money = MONEY.findAll(clean).toList()
            if (money.size < 2) continue
            val decimalRates = if (!inTable) emptyList() else money.filter { DECIMAL_RATE.matches(it.value) }.map { BigDecimal(it.value.substringBefore(',')) }
            val rates = RATE_TOKEN.findAll(clean).filter { r -> money.none { m -> r.range.first in m.range } }.map { BigDecimal(it.value) }.toList() + decimalRates
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
        // The summary's own rows, one group per rate (its rows added up).
        if (tableRows.isNotEmpty()) {
            return tableRows.groupBy { it.ratePercent to it.nature }.map { (k, g) ->
                Group(k.first, g.sumOf { it.taxableCents }, g.sumOf { it.vatCents }, g.joinToString(" | ") { it.source }, k.second)
            }
        }
        // Several rows for one rate: the ones worded like a VAT summary ("ALIQUOTA 10%", "IVA", "IMPONIBILE") win;
        // a product line that happens to fit (4,90 and 0,49 at 10%) must not become a second 10% group.
        return out.distinctBy { it.ratePercent to it.taxableCents }
            .groupBy { it.ratePercent }
            .flatMap { (_, g) ->
                if (g.size == 1) g
                else g.filter { SUMMARY_WORDS.containsMatchIn(it.source) }.ifEmpty { listOf(g.maxBy { it.taxableCents }) }.take(1)
            }
    }

    /**
     * A summary row whose taxable amount is unreadable ("22 28,9 ) 6,36": a stain, a fold) gets it from the printed
     * total taxable minus the other rows, but only when that amount gives exactly the printed VAT at that rate:
     * two printed numbers must agree, nothing is guessed.
     */
    fun completeFromSubtotal(groups: List<Group>, text: String, subtotalCents: Long?): List<Group> {
        if (subtotalCents == null || groups.isEmpty()) return groups
        val rest = subtotalCents - groups.sumOf { it.taxableCents }
        if (rest <= 0) return groups
        for (line in text.lines()) {
            val clean = DOT_DECIMAL.replace(OcrCleanup.cleanLine(line)) { m -> "${m.groupValues[1]},${m.groupValues[2]}" }
            if (UNIT_PRICE.containsMatchIn(clean)) continue
            val lead = rx("^\\s*(0?4|0?5|10|22)(?![\\d,])").find(clean) ?: continue
            val rate = BigDecimal(lead.groupValues[1])
            if (groups.any { it.ratePercent.compareTo(rate) == 0 }) continue
            for (m in MONEY.findAll(clean)) {
                val vat = ItalianNumbers.parseCents(m.value) ?: continue
                val expected = BigDecimal(rest).multiply(rate).divide(BigDecimal(100), 0, RoundingMode.HALF_UP).toLong()
                if (vat > 0 && kotlin.math.abs(expected - vat) <= 1) return groups + Group(plain(rate), rest, vat, line)
            }
        }
        return groups
    }

    /**
     * A row under the summary's heading: its rate ("22,00", "10") or nature code ("N2.2": no VAT), then its amounts, the
     * last two being the taxable amount and the VAT ("0" for none). Proven by its rate (VAT = taxable x rate, to the
     * cent), or VAT 0 under a nature code; null otherwise.
     */
    private fun tableRow(clean: String, line: String): Group? {
        val nums = TABLE_NUMBER.findAll(clean).toList()
        val nature = NATURE.find(clean)
        if (nature != null) {
            if (nums.size < 2) return null
            val vat = ItalianNumbers.parseCents(nums.last().value) ?: return null
            val taxable = ItalianNumbers.parseCents(nums[nums.size - 2].value) ?: return null
            return if (vat == 0L && taxable != 0L) Group(BigDecimal.ZERO, taxable, 0L, line, nature.value) else null
        }
        if (nums.size < 3) return null
        val rate = nums.first().value.takeIf { DECIMAL_RATE.matches(it) || RATE_ONLY.matches(it) }?.let { ItalianNumbers.parse(it) } ?: return null
        val taxable = ItalianNumbers.parseCents(nums[nums.size - 2].value) ?: return null
        val vat = ItalianNumbers.parseCents(nums.last().value) ?: return null
        val expected = BigDecimal(taxable).multiply(rate).divide(BigDecimal(100), 0, RoundingMode.HALF_UP).toLong()
        return if (kotlin.math.abs(expected - vat) <= 1 && taxable != 0L) Group(plain(rate), taxable, vat, line) else null
    }

    /** Amounts in a summary row, signed; a bare "0" is no VAT. */
    private val TABLE_NUMBER = Regex("(?<![\\d,.])-?\\d{1,3}(?:\\.\\d{3})*,\\d{2}(?![\\d,])|(?<![\\d,.\\w])-?0(?![\\d,.\\w])|(?<![\\d,.\\w])(?:0?4|0?5|10|22)(?![\\d,.\\w])")
    private val RATE_ONLY = Regex("^(?:0?4|0?5|10|22)$")
    /** An e-invoice's nature code for a line without VAT (exempt, not subject, reverse charge...). */
    private val NATURE = Regex("(?<![A-Za-z0-9])N[1-7](?:\\.\\d)?(?![A-Za-z0-9.])")

    private val SUMMARY_HEADING = Regex("(?i)\\b(imponibil\\w*|imposta|aliquota|%\\s?iva)")
    private val SUMMARY_END = Regex("(?i)\\b(totale\\s+documento|modalit\\S*\\s+pagamento|importo\\s+bollo)\\b")
    private val DECIMAL_RATE = Regex("^(4|5|10|22),00$")
    private val UNIT_PRICE = Regex("(?<![\\d,.])\\d{1,3},\\d{3}(?![\\d,])")
    private val SUMMARY_WORDS = Regex("(?i)(aliquota|\\biva\\b|imponibil|imposta|esente|%)")

    /**
     * Lines without a VAT rate (the rate was printed out of place) get the one rate that makes every VAT group add up,
     * when exactly one way works. The rate is then proven by the summary; nothing is filled in otherwise.
     */
    fun fillMissingRates(items: List<ParsedLineItem>, all: List<Group>): List<ParsedLineItem> {
        val groups = withRate(all)
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

    /**
     * A nature code in a line's VAT column ("N2.2") is 0 % VAT under that nature: kept when the lines of that nature
     * add up exactly to the summary's row of the same nature, dropped otherwise (the line then has no VAT read).
     */
    fun proveNatures(items: List<ParsedLineItem>, all: List<Group>): List<ParsedLineItem> {
        val byNature = items.filter { it.vatNature != null }.groupBy { it.vatNature!! }
        if (byNature.isEmpty()) return items
        val proven = byNature.filter { (n, lines) -> all.any { g -> g.nature.equals(n, ignoreCase = true) && preciseSum(lines.map { it.preciseTotal() }) == g.taxableCents } }.keys
        return items.map { if (it.vatNature == null || it.vatNature in proven) it else it.copy(vatNature = null) }
    }

    /** One check per VAT rate of the summary; empty when there is no summary or some lines have no rate. */
    fun check(items: List<ParsedLineItem>, all: List<Group>): List<Check> {
        val groups = withRate(all)
        if (groups.size < 1 || items.isEmpty()) return emptyList()
        if (items.any { it.vatRatePercent == null || it.lineTotalCents == null }) return emptyList()
        val byRate = items.groupBy { plain(it.vatRatePercent!!.value) }
        // Every rate on the lines must be in the summary, or the summary was not read completely.
        if (byRate.keys.any { k -> groups.none { it.ratePercent.compareTo(k) == 0 } }) return emptyList()
        return groups.map { g ->
            val lines = byRate.entries.firstOrNull { it.key.compareTo(g.ratePercent) == 0 }?.value.orEmpty()
            // Added up as printed (an e-invoice's amounts with up to 8 decimals, rounded once: see preciseSum).
            Check(g.ratePercent, g.taxableCents, preciseSum(lines.map { it.preciseTotal() }) ?: 0L /* no line at this rate */, lines.size)
        }
    }
}
