package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.RoundingMode

/** One way of reading a product line's numbers that adds up: quantity x unit price = amount. */
data class LineChoice(
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val lineTotalCents: Long,
    val unit: String? = null,
    /** The amount only works out with this discount % printed on the line (not stored: the line stays for review). */
    val discountPercent: BigDecimal? = null,
    /** Where quantity and price were on the line: how many numbers come after each (for [ChoiceRule]). */
    val qtyFromEnd: Int = -1,
    val priceFromEnd: Int = -1,
    /** A number read with a scanner slip, repaired to make the line add up ("1.392 → 1,392"). */
    val repaired: String? = null,
) {
    val rule: ChoiceRule get() = ChoiceRule(qtyFromEnd, priceFromEnd)
}

/**
 * What the operator chose once for a supplier when a line could be read two ways ("the quantity is the 4th number
 * from the end, the price the 3rd"). Applied by itself the next time the same doubt comes up on that supplier's
 * documents, so the operator is asked only once for each pattern.
 */
data class ChoiceRule(val qtyFromEnd: Int, val priceFromEnd: Int) {
    fun encode() = "$qtyFromEnd:$priceFromEnd"

    companion object {
        fun decode(s: String): ChoiceRule? = s.split(':').takeIf { it.size == 2 }
            ?.let { (a, b) -> a.toIntOrNull()?.let { q -> b.toIntOrNull()?.let { p -> ChoiceRule(q, p) } } }
    }
}

/**
 * "Logic first": tries every way the numbers printed on a product line can be quantity, unit price (and an
 * optional discount %) and amount, and keeps only the readings where the arithmetic works out. Every value is
 * one printed on the line, never calculated or invented.
 *
 * One reading: the line is proven, no AI or operator needed. Several: they are offered as choices (the AI or the
 * operator picks one). None: the line stays as the reader left it, for the AI to look at.
 */
object LineSolver {

    private val NUMBER = Regex("^\\d{1,6}(?:\\.\\d{3})*(?:,\\d{1,4})?$")
    private val MONEY = Regex(",\\d{2}$")

    private data class Num(val index: Int, val raw: String, val value: BigDecimal)

    fun solve(line: String): List<LineChoice> = solveTokens(OcrCleanup.cleanLine(line).split(' ').filter { it.isNotBlank() })

    /**
     * The scanner's usual slips in a number, all in one table: letters for digits (O/0, I/l/1, S/5, B/8, Z/2, G/6),
     * a dot read for the decimal comma ("1.392", "4.02"), 7 and 1 confused, a lost decimal comma ("753" for 7,53).
     */
    private val LETTER_DIGIT = mapOf('O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0', 'I' to '1', 'l' to '1', 'i' to '1', '|' to '1', 'S' to '5', 's' to '5', 'B' to '8', 'Z' to '2', 'G' to '6')

    fun slipVariants(t: String): List<String> {
        if (t.none(Char::isDigit)) return emptyList()
        val out = mutableListOf<String>()
        if (t.length >= 2 && t.count { it.isDigit() || it in ".," } >= t.length - 2 && t.any { it in LETTER_DIGIT }) {
            out += t.map { LETTER_DIGIT[it] ?: it }.joinToString("")
        }
        if (Regex("^\\d{1,4}\\.\\d{2,3}$").matches(t)) out += t.replace('.', ',')
        if (t.all { it.isDigit() || it in ".," }) {
            for (i in t.indices) {
                if (t[i] == '7') out += t.substring(0, i) + '1' + t.substring(i + 1)
                if (t[i] == '1') out += t.substring(0, i) + '7' + t.substring(i + 1)
            }
        }
        if (Regex("^\\d{3,6}$").matches(t)) out += t.dropLast(2) + "," + t.takeLast(2)
        return out.distinct().filter { it != t }
    }

    /**
     * When no reading of the printed numbers adds up: the same with one number's scanner slip repaired (see
     * [slipVariants]). Only readings that use the repaired number are kept; each says what was repaired, and the
     * repaired value is shown for checking unless something else (the VAT summary) proves it.
     */
    fun solveWithSlips(line: String): List<LineChoice> {
        val tokens = OcrCleanup.cleanLine(line).split(' ').filter { it.isNotBlank() }
        val found = LinkedHashMap<Triple<BigDecimal, BigDecimal, Long>, LineChoice>()
        tokens.forEachIndexed { i, t ->
            for (v in slipVariants(t)) {
                val alt = tokens.toMutableList().also { it[i] = v }
                val value = ItalianNumbers.parse(v) ?: continue
                for (r in solveTokens(alt)) {
                    val uses = r.quantity.compareTo(value) == 0 || r.unitPrice.compareTo(value) == 0 || ItalianNumbers.toCents(value) == r.lineTotalCents
                    if (!uses || r.discountPercent != null) continue
                    found.putIfAbsent(Triple(r.quantity.stripTrailingZeros(), r.unitPrice.stripTrailingZeros(), r.lineTotalCents), r.copy(repaired = "$t → $v"))
                }
            }
        }
        return found.values.toList()
    }

    private fun solveTokens(tokens: List<String>): List<LineChoice> {
        val nums = tokens.mapIndexedNotNull { i, t ->
            if (!NUMBER.matches(t)) return@mapIndexedNotNull null
            // An article code at the start ("04411") or a long code is not a quantity or a price.
            if (i == 0 && !t.contains(',')) return@mapIndexedNotNull null
            if (!t.contains(',') && t.replace(".", "").length >= 5) return@mapIndexedNotNull null
            ItalianNumbers.parse(t)?.takeIf { it.signum() > 0 }?.let { Num(i, t, it) }
        }
        val amounts = nums.filter { MONEY.containsMatchIn(it.raw) }
        if (amounts.isEmpty()) return emptyList()
        val out = LinkedHashMap<Triple<BigDecimal, BigDecimal, Long>, LineChoice>()
        // The amount is the last money value on the line; if nothing fits it, the one before (a VAT amount may follow).
        for (amount in amounts.reversed().take(2)) {
            val cents = ItalianNumbers.toCents(amount.value)
            val before = nums.filter { it.index < amount.index }
            for (a in before.indices) for (b in a + 1 until before.size) {
                val q = before[a]; val p = before[b]
                val between = before.filter { it.index > p.index }
                val discount = if (ReceiptParser.matches(q.value, p.value, cents)) null
                else between.firstOrNull { d -> d.value < BigDecimal(100) && discounted(q.value, p.value, d.value, cents) }?.value
                    ?: continue
                val unit = tokens.getOrNull(q.index - 1)?.let { Units.normalizeKnown(it) }
                val qFromEnd = nums.size - 1 - nums.indexOf(q)
                val pFromEnd = nums.size - 1 - nums.indexOf(p)
                out.putIfAbsent(
                    Triple(q.value.stripTrailingZeros(), p.value.stripTrailingZeros(), cents),
                    LineChoice(q.value, p.value, cents, unit, discount, qFromEnd, pFromEnd),
                )
            }
            if (out.isNotEmpty()) break
        }
        // "2 x 3,50" and "3,50 x 2" are the same purchase read two ways: keep the one with a whole quantity first.
        return out.values.groupBy { setOf(it.quantity.stripTrailingZeros(), it.unitPrice.stripTrailingZeros()) to it.lineTotalCents }
            .values.map { same -> same.firstOrNull { isWhole(it.quantity) } ?: same.first() }
    }

    /**
     * The quantity read with one wrong digit ("4,900" for 4,000): amount / price gives a quantity that differs from the
     * one read in a single digit. Taken only then (any other difference is a real doubt), and marked as worked out.
     */
    private fun oneDigitOff(item: ParsedLineItem): LineChoice? {
        val q = item.quantity?.value ?: return null
        val p = item.unitPrice?.value ?: return null
        val t = item.lineTotalCents?.value ?: return null
        if (p.signum() <= 0) return null
        val worked = ItalianNumbers.centsToDecimal(t).divide(p, q.scale().coerceAtLeast(0).coerceAtMost(3), RoundingMode.HALF_UP)
        if (worked.signum() <= 0 || !ReceiptParser.matches(worked, p, t)) return null
        val a = q.setScale(3, RoundingMode.HALF_UP).toPlainString()
        val b = worked.setScale(3, RoundingMode.HALF_UP).toPlainString()
        if (a.length != b.length || a.zip(b).count { (x, y) -> x != y } != 1) return null
        return LineChoice(worked.setScale(q.scale().coerceAtLeast(0), RoundingMode.HALF_UP), p, t, item.unit?.value)
    }

    private fun discounted(q: BigDecimal, p: BigDecimal, d: BigDecimal, cents: Long): Boolean {
        val net = q.multiply(p).multiply(BigDecimal(100).subtract(d)).divide(BigDecimal(100))
        return kotlin.math.abs(net.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong() - cents) <= 1
    }

    private fun isWhole(v: BigDecimal) = v.stripTrailingZeros().scale() <= 0

    /**
     * Settles the doubtful lines of a document: a line whose numbers do not add up (or are missing) is solved from
     * its own printed text. A single reading replaces the doubtful one; several are kept as [ParsedLineItem.choices].
     */
    fun settle(items: List<ParsedLineItem>): List<ParsedLineItem> = items.map { item ->
        val doubtful = ParseWarning.LINE_TOTAL_MISMATCH in item.warnings || item.quantity == null || item.unitPrice == null ||
            item.quantity.confidence == Confidence.LOW || item.unitPrice.confidence == Confidence.LOW
        if (!doubtful || ReceiptParser.isSectionHeading(item.originalDescription)) return@map item
        // The AI's double-check read a number differently: that stays for the operator.
        if (listOf(item.quantity?.source, item.unitPrice?.source, item.lineTotalCents?.source).any { it?.contains(AiReader.DISAGREE) == true }) return@map item
        val source = item.lineTotalCents?.source ?: item.quantity?.source ?: return@map item
        val readings = solve(source).ifEmpty { solveWithSlips(source) }.ifEmpty { listOfNotNull(oneDigitOff(item)) }
        // The amount already read with confidence must be kept.
        val total = item.lineTotalCents
        val fitting = if (total != null && total.confidence == Confidence.HIGH) readings.filter { it.lineTotalCents == total.value } else readings
        when {
            // A discount the app does not store: the numbers are right, but the line is still shown for a look.
            fitting.size == 1 && fitting.single().discountPercent != null -> item
            fitting.size == 1 -> apply(item, fitting.single(), source)
            fitting.size > 1 -> item.copy(choices = fitting.take(4))
            else -> item
        }
    }

    private fun apply(item: ParsedLineItem, r: LineChoice, source: String): ParsedLineItem {
        // A quantity that was not printed as such (one digit repaired by amount / price): proven, but shown as worked out.
        val printed = source.contains(ItalianNumbers.formatDecimal(r.quantity, minScale = r.quantity.scale().coerceAtLeast(0), maxScale = 3)) ||
            source.split(' ').any { tok -> ItalianNumbers.parse(tok.trimEnd('c', 'C', 'f', 'F'))?.compareTo(r.quantity) == 0 }
        // The description must not keep the numbers ("... GR.500 NR 24,000c"): cut it where the quantity starts.
        var desc = item.originalDescription
        val qtyRaw = source.split(' ').firstOrNull { ItalianNumbers.parse(it.take(it.indexOfLast(Char::isDigit) + 1))?.compareTo(r.quantity) == 0 && it.contains(',') }
        if (qtyRaw != null && desc.contains(qtyRaw)) {
            desc = desc.substringBefore(qtyRaw).trim()
            val last = desc.substringAfterLast(' ')
            if (desc.contains(' ') && Units.normalizeKnown(last) != null) desc = desc.substringBeforeLast(' ').trim()
        }
        val unit = r.unit ?: item.unit?.value
        // A value repaired from a scanner slip is shown for checking (with what was printed), unless proven later.
        val fixed = r.repaired?.substringAfter("→ ")?.let { ItalianNumbers.parse(it) }
        fun conf(v: BigDecimal, ok: Boolean) = if (!ok || (fixed != null && v.compareTo(fixed) == 0)) Confidence.LOW else Confidence.HIGH
        val note = r.repaired?.let { "$source (read ${it.substringBefore(" →")}, repaired: ${it.substringAfter("→ ")})" } ?: source
        return item.copy(
            originalDescription = desc.ifBlank { item.originalDescription },
            quantity = Extracted(r.quantity, conf(r.quantity, printed || fixed?.compareTo(r.quantity) == 0), if (printed || r.repaired != null) note else "$source (amount / price)"),
            unitPrice = Extracted(r.unitPrice, conf(r.unitPrice, true), note),
            lineTotalCents = Extracted(r.lineTotalCents, conf(ItalianNumbers.centsToDecimal(r.lineTotalCents), true), note),
            unit = unit?.let { Extracted(it, Confidence.HIGH, source) },
            warnings = item.warnings - ParseWarning.LINE_TOTAL_MISMATCH,
            choices = emptyList(),
        )
    }

    /**
     * Lines of a draft that could be read several ways, settled by what the operator chose before for this
     * supplier: when exactly one reading follows a remembered [ChoiceRule], it is taken (the operator's own choice).
     */
    fun applyRules(items: List<LineItemDraft>, rules: Collection<ChoiceRule>): List<LineItemDraft> {
        if (rules.isEmpty()) return items
        return items.map { item ->
            if (item.choices.size < 2) return@map item
            val matching = item.choices.filter { it.rule in rules }
            if (matching.size == 1) item.pick(matching.single()) else item
        }
    }
}
