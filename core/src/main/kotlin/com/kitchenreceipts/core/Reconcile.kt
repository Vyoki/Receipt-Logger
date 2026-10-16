package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The document checks itself. An invoice prints its sums more than once: the taxable amount of each VAT rate, the
 * number of packages, the page count ("1/6"). Those are equations the lines must satisfy, whatever the supplier's
 * layout. When a VAT rate does not add up, the difference says what went wrong, and the page usually holds the answer:
 *
 *  - a row the reader could not take as a line (a smudged amount, "8,21" read as "g,21"): its quantity x price gives
 *    the amount, and the cents still readable on the row must agree;
 *  - something taken as a line that is no product (a column of another sheet showing at the edge of the photo, glued
 *    to a heading): an amount with no quantity and no price;
 *  - a line whose VAT code was unreadable: the rate that makes the sums close.
 *
 * A change is made only when exactly one set of them makes every VAT rate it touches add up to the cent, and fewer
 * changes are always preferred. Otherwise nothing is changed and the operator is told which rate is short and by how
 * much. Nothing here knows any supplier.
 */
object Reconcile {

    /** What the document's own sums say about the reading, for the operator. */
    data class Report(
        /** Lines recovered from rows the reader missed: "name  amount". */
        val recovered: List<String> = emptyList(),
        /** Readings dropped because they were not products: "text  amount". */
        val dropped: List<String> = emptyList(),
        /** The same readings as (amount in cents, VAT rate): to tell, on a later reading, whether they are still left out. */
        val droppedKeys: List<Pair<Long, BigDecimal?>> = emptyList(),
        /** For each dropped reading, how many amount-only lines with its amount and rate were left right after: more on a
         * later reading means it came back. */
        val droppedAfter: List<Int> = emptyList(),
        /** Lines whose VAT rate was unreadable and is the one that makes the sums close. */
        val ratesFound: Int = 0,
        /** Every VAT rate of the summary adds up to the cent. */
        val vatProven: Boolean = false,
        /** Packages printed at the foot ("TOTALE COLLI 98") and counted on the lines; null when not printed / not on every line. */
        val packagesPrinted: Int? = null,
        val packagesCounted: Int? = null,
        /** Pages the document numbers ("1/6": 6) and those not photographed. */
        val pagesOf: Int? = null,
        val pagesMissing: List<Int> = emptyList(),
    ) {
        val changed: Boolean get() = recovered.isNotEmpty() || dropped.isNotEmpty() || ratesFound > 0
        val packagesAgree: Boolean get() = packagesPrinted != null && packagesPrinted == packagesCounted
    }

    const val RECOVERED = "(amount = quantity x price; the VAT summary adds up)"

    /** A line this check added (its amount's source carries [RECOVERED]), on this reading or an earlier one. */
    fun isRecovered(it: ParsedLineItem) = it.lineTotalCents?.source?.endsWith(RECOVERED) == true

    /** Lines with no quantity and no price whose amount and rate are [key]. */
    fun amountOnly(items: List<ParsedLineItem>, key: Pair<Long, BigDecimal?>) =
        items.count { it.quantity == null && it.unitPrice == null && key(it) == key }

    /** "PECORINO GRATTUGIATO  8,21": how a line added or left out is shown. */
    fun describe(it: ParsedLineItem) =
        "${it.originalDescription.ifBlank { "?" }}  ${it.lineTotalCents?.value?.let(ItalianNumbers::formatCents).orEmpty()}".trim()

    /**
     * The report of a reading made again ([earlier] from a previous pass, [now] from this one) over [items]: lines added
     * are those still carrying the mark; readings left out are this pass's and the earlier ones still out.
     */
    fun carry(earlier: Report?, now: Report, items: List<ParsedLineItem>): Report {
        val recovered = items.filter(::isRecovered).map(::describe)
        if (earlier == null) return now.copy(recovered = recovered)
        val still = earlier.droppedKeys.indices.filter { i -> amountOnly(items, earlier.droppedKeys[i]) <= earlier.droppedAfter.getOrElse(i) { 0 } }
        return now.copy(
            recovered = recovered,
            dropped = now.dropped + still.mapNotNull { earlier.dropped.getOrNull(it) },
            droppedKeys = now.droppedKeys + still.map { earlier.droppedKeys[it] },
            droppedAfter = now.droppedAfter + still.map { earlier.droppedAfter.getOrElse(it) { 0 } },
            ratesFound = maxOf(now.ratesFound, earlier.ratesFound),
        )
    }

    /** Above this many possible changes the search is not run: a coincidence would become too likely. */
    private const val MAX_OPTIONAL = 12
    private const val MAX_FLOATING = 3
    /** At most this many changes in one answer. */
    private const val MAX_CHANGES = 4

    fun apply(items: List<ParsedLineItem>, groups: List<VatSummary.Group>, text: String): Pair<List<ParsedLineItem>, Report> {
        val colliColumn = COLLI_HEADING.containsMatchIn(text)
        var out = items
        var recovered = emptyList<String>()
        var dropped = emptyList<String>()
        var droppedKeys = emptyList<Pair<Long, BigDecimal?>>()
        var ratesFound = 0
        if (groups.isNotEmpty() && items.none { it.lineTotalCents == null && !isAdj(it) }) {
            solve(items, groups, text, colliColumn)?.let { s ->
                out = s.items; recovered = s.recovered; dropped = s.dropped; droppedKeys = s.droppedKeys; ratesFound = s.ratesFound
            }
        }
        val printed = PackagesCheck.printedTotal(text)
        val counted = countPackages(out)
        val pages = pageMarks(text)
        return out to Report(
            recovered = recovered,
            dropped = dropped,
            droppedKeys = droppedKeys,
            droppedAfter = droppedKeys.map { k -> amountOnly(out, k) },
            ratesFound = ratesFound,
            vatProven = groups.isNotEmpty() && exact(out, groups),
            packagesPrinted = printed,
            packagesCounted = counted?.takeIf { printed != null },
            pagesOf = pages?.first,
            pagesMissing = pages?.second.orEmpty(),
        )
    }

    /** Every line has a rate of the summary, and each rate's lines add up to its taxable amount exactly. */
    fun exact(items: List<ParsedLineItem>, groups: List<VatSummary.Group>): Boolean {
        if (groups.isEmpty() || items.any { it.lineTotalCents == null || it.vatRatePercent == null }) return false
        val gaps = gaps(items, groups) ?: return false
        return gaps.all { it == 0L }
    }

    /** Printed taxable amount minus the lines, per group; null when a line's rate is not in the summary. */
    private fun gaps(items: List<ParsedLineItem>, groups: List<VatSummary.Group>): LongArray? {
        val gaps = LongArray(groups.size) { groups[it].taxableCents }
        for (it in items) {
            val rate = it.vatRatePercent?.value ?: continue
            val g = groups.indexOfFirst { gr -> gr.ratePercent.compareTo(rate) == 0 }
            if (g < 0) return null
            gaps[g] -= it.lineTotalCents?.value ?: 0
        }
        return gaps
    }

    private class Solved(
        val items: List<ParsedLineItem>, val recovered: List<String>, val dropped: List<String>,
        val droppedKeys: List<Pair<Long, BigDecimal?>>, val ratesFound: Int,
    )

    /**
     * A discount or charge ("Sconto 4%", "Spese di trasporto"): part of the sums, never a product, never left out.
     * Adjustments.mark runs after the reading, so its words decide here as well as the flag.
     */
    internal fun isAdj(it: ParsedLineItem) =
        it.adjustment || Adjustments.isAdjustment(it.originalDescription, it.lineTotalCents?.value, it.quantity?.value)

    /**
     * A reading that names no product: no words of its own, or only the table's column headings ("IMPORTO EUR IVA",
     * "(Natura e qualità) CONF. PZ/KG UNIT. € EUR IVA"). Only such a reading may be left out: "GHIACCIO ALIMENTARE
     * 9,00" is a product printed with its amount alone, whatever the sums say.
     */
    internal fun namesNoProduct(description: String): Boolean {
        val words = description.lowercase().split(rx("[^\\p{L}]+")).filter { it.length >= 2 }
        return words.all { it in HEADING_WORDS }
    }

    private val HEADING_WORDS = setOf(
        "natura", "qualità", "qualita", "conf", "pz", "kg", "unit", "unitario", "eur", "euro", "iva", "importo", "importi",
        "prezzo", "descrizione", "codice", "cod", "colli", "quantità", "quantita", "qta", "tot", "totale", "imponibile",
        "sconto", "um", "lotto", "scadenza", "beni", "tipo", "articolo", "aliquota", "valore", "netto", "lordo", "riga",
        "amount", "price", "description", "qty", "total", "vat", "rate", "code", "unit",
    )

    /** One possible change: [group] gets [delta] cents. */
    private class Option(val group: Int, val delta: Long)

    private fun solve(items: List<ParsedLineItem>, groups: List<VatSummary.Group>, text: String, colliColumn: Boolean): Solved? {
        val start = gaps(items, groups) ?: return null
        val floating = items.indices.filter { items[it].vatRatePercent == null && !isAdj(items[it]) }
        if (floating.isEmpty() && start.all { it == 0L }) return null
        val tolerance = groups.indices.map { g ->
            maxOf(1L, items.count { it.vatRatePercent?.value?.compareTo(groups[g].ratePercent) == 0 } / 4L)
        }
        // Already within the summary's rounding everywhere, and every line has its rate: nothing to explain.
        if (floating.isEmpty() && start.indices.all { kotlin.math.abs(start[it]) <= tolerance[it] }) return null
        if (floating.size > MAX_FLOATING) return null
        fun groupOf(rate: BigDecimal?) = groups.indexOfFirst { rate != null && it.ratePercent.compareTo(rate) == 0 }

        // Readings that may not be products: an amount above zero with no quantity and no price, whose words name no
        // product (only column headings, or none); never a discount or charge. Only on a document whose lines print
        // quantity x price (3 in 4 at least): on a till receipt every line is an amount alone.
        val products = items.filter { !isAdj(it) }
        val linesProve = products.count { proven(it) } * 4 >= products.size * 3
        val weak = if (!linesProve) emptyList() else items.indices.filter { i ->
            val it = items[i]
            !isAdj(it) && (it.lineTotalCents?.value ?: 0L) > 0 && it.quantity == null && it.unitPrice == null &&
                namesNoProduct(it.originalDescription) && groupOf(it.vatRatePercent?.value) >= 0 && i !in floating
        }
        // Rows of the page that hold a whole line nobody took.
        val rows = missedRows(items, text, colliColumn).filter { groupOf(it.item.vatRatePercent?.value) >= 0 }
        val optional = weak.map { i -> Option(groupOf(items[i].vatRatePercent?.value), -items[i].lineTotalCents!!.value) } +
            rows.map { r -> Option(groupOf(r.item.vatRatePercent?.value), r.item.lineTotalCents!!.value) }
        if (optional.size > MAX_OPTIONAL) return null
        val floatOptions = floating.map { i -> groups.indices.map { g -> Option(g, items[i].lineTotalCents!!.value) } }

        val printedPackages = PackagesCheck.printedTotal(text)
        data class Answer(val mask: Int, val rates: List<Int>, val changes: Int, val packagesFit: Boolean)
        val answers = mutableListOf<Answer>()
        val gaps = LongArray(groups.size)
        fun check(mask: Int, rates: List<Int>) {
            start.copyInto(gaps)
            val touched = BooleanArray(groups.size)
            for (k in optional.indices) if (mask and (1 shl k) != 0) { gaps[optional[k].group] -= optional[k].delta; touched[optional[k].group] = true }
            for ((f, g) in rates.withIndex()) { gaps[g] -= floatOptions[f][g].delta; touched[g] = true }
            // Every group a change touches closes to the cent; the others stay as close as they were.
            for (g in groups.indices) {
                if (touched[g] && gaps[g] != 0L) return
                if (!touched[g] && kotlin.math.abs(gaps[g]) > tolerance[g]) return
            }
            val changes = Integer.bitCount(mask) + rates.size
            val fit = printedPackages != null && countPackages(applyMask(items, floating, rates, groups, weak, rows, mask)) == printedPackages
            answers += Answer(mask, rates, changes, fit)
        }
        fun rates(f: Int, chosen: List<Int>, mask: Int) {
            if (f == floating.size) { check(mask, chosen); return }
            for (g in groups.indices) rates(f + 1, chosen + g, mask)
        }
        for (mask in 0 until (1 shl optional.size)) {
            if (Integer.bitCount(mask) + floating.size > MAX_CHANGES) continue
            rates(0, emptyList(), mask)
            if (answers.size > 50) return null
        }
        if (answers.isEmpty()) return null
        // Fewest changes; among those, the one that also makes the packages add up. Exactly one, or nothing.
        val fewest = answers.minOf { it.changes }
        var best = answers.filter { it.changes == fewest }
        if (best.size > 1 && best.count { it.packagesFit } == 1) best = best.filter { it.packagesFit }
        val pick = best.singleOrNull() ?: return null
        if (pick.changes == 0) return null
        val newItems = applyMask(items, floating, pick.rates, groups, weak, rows, pick.mask)
        val droppedLines = weak.filterIndexed { k, _ -> pick.mask and (1 shl k) != 0 }.map { i -> items[i] }
        val recovered = rows.filterIndexed { k, _ -> pick.mask and (1 shl (weak.size + k)) != 0 }.map { describe(it.item) }
        return Solved(newItems, recovered, droppedLines.map(::describe), droppedLines.map(::key), floating.size)
    }

    private fun applyMask(
        items: List<ParsedLineItem>, floating: List<Int>, rates: List<Int>, groups: List<VatSummary.Group>,
        weak: List<Int>, rows: List<Missed>, mask: Int,
    ): List<ParsedLineItem> {
        val drop = weak.filterIndexed { k, _ -> mask and (1 shl k) != 0 }.toSet()
        val withRates = items.mapIndexed { i, it ->
            val f = floating.indexOf(i)
            if (f >= 0 && f < rates.size) {
                val rate = groups[rates[f]].ratePercent
                it.copy(vatRatePercent = Extracted(rate, Confidence.HIGH, "VAT summary (the ${ItalianNumbers.formatDecimal(rate)}% lines add up with it)"))
            } else it
        }
        // A recovered line goes where its row was on the page: right after the line read above it (at the end when no
        // line read could be found on the page).
        val added = rows.filterIndexed { k, _ -> mask and (1 shl (weak.size + k)) != 0 }.groupBy { it.after }
        val out = mutableListOf<ParsedLineItem>()
        added[FIRST]?.forEach { out += it.item }
        withRates.forEachIndexed { i, it ->
            if (i !in drop) out += it
            added[i]?.forEach { r -> out += r.item }
        }
        added[UNPLACED]?.forEach { out += it.item }
        return out
    }


    // ------------------------------------------------------------ rows the reader missed

    /** A whole line found on a row the reading did not take, and the index of the line it comes after ([FIRST], [UNPLACED]). */
    private class Missed(val item: ParsedLineItem, val after: Int)

    private const val FIRST = -1
    private const val UNPLACED = -2

    /**
     * Rows that read as a whole line (quantity x price = amount, with a VAT code) more often than such a line was
     * taken: the excess rows are lines the reading missed. Only rows inside an item table (not totals, VAT summary,
     * addresses).
     */
    private fun missedRows(items: List<ParsedLineItem>, text: String, colliColumn: Boolean): List<Missed> {
        val lines = text.lines()
        val found = mutableListOf<Pair<Int, ParsedLineItem>>()
        for ((n, raw) in lines.withIndex()) {
            val row = raw.trim()
            if (row.length < 8 || ReceiptParser.isFooterRow(row) || ReceiptParser.isNotAnItemRow(row) || ReceiptParser.isTableHeader(row)) continue
            val it = wholeLine(row, colliColumn) ?: continue
            found += n to it
        }
        if (found.isEmpty()) return emptyList()
        // Lines already taken, by amount and rate (the reading may have tidied names; the amount is what the sums see).
        val taken = items.groupingBy { key(it) }.eachCount().toMutableMap()
        val rowOf = rowsOfItems(items, lines)
        val located = rowOf.any { it >= 0 }
        val out = mutableListOf<Missed>()
        for ((n, it) in found) {
            val k = key(it)
            val left = taken[k] ?: 0
            if (left > 0) { taken[k] = left - 1; continue }
            // After the line whose row is the nearest above this one.
            val above = rowOf.indices.filter { i -> rowOf[i] in 0 until n }.maxByOrNull { i -> rowOf[i] }
            out += Missed(it, above ?: if (located) FIRST else UNPLACED)
        }
        return out
    }

    internal fun key(it: ParsedLineItem): Pair<Long, BigDecimal?> = (it.lineTotalCents?.value ?: 0L) to it.vatRatePercent?.value?.stripTrailingZeros()

    /**
     * The text row each line read was taken from (-1: not found). A line read from the text carries its row as its
     * source; one the AI read does not, and is found by its amount printed on a row with its code or a word of its name.
     * Rows are taken in order and once each, so two identical lines get two rows.
     */
    private fun rowsOfItems(items: List<ParsedLineItem>, lines: List<String>): List<Int> {
        // Each row once: its text, its words, and an index by first word and by every amount on it.
        val rows = lines.map { it.trim() }
        val tokens = rows.map { r -> r.split(' ').filter { t -> t.isNotEmpty() } }
        val upper = rows.map { it.uppercase() }
        val byFirst = HashMap<String, MutableList<Int>>()
        val byToken = HashMap<String, MutableList<Int>>()
        for ((r, ts) in tokens.withIndex()) {
            if (rows[r].length < 8) continue
            ts.firstOrNull()?.let { byFirst.getOrPut(it) { mutableListOf() } += r }
            for (t in ts.toSet()) if (t.any(Char::isDigit) && t.contains(',')) byToken.getOrPut(t) { mutableListOf() } += r
        }
        val used = BooleanArray(rows.size)
        var from = 0
        return items.map { it ->
            val src = it.lineTotalCents?.source.orEmpty()
            val amount = it.lineTotalCents?.value?.let(ItalianNumbers::formatCents)
            val code = it.itemCode
            val words = it.originalDescription.uppercase().split(rx("[^A-Z0-9]+")).filter { w -> w.length >= 3 && w.any(Char::isLetter) }
            // The row the line was read from (its source starts with it), else a row with its amount and its code or a
            // word of its name (the AI's lines).
            val bySource = byFirst[src.substringBefore(' ')].orEmpty().filter { r -> src.startsWith(rows[r]) }
            val byAmount = amount?.let { a -> byToken[a].orEmpty().filter { r -> (code != null && code in tokens[r]) || words.any { w -> upper[r].contains(w) } } }.orEmpty()
            val candidates = (bySource + byAmount).filter { r -> !used[r] }
            val r = candidates.filter { it >= from }.minOrNull() ?: candidates.minOrNull() ?: -1
            if (r >= 0) { used[r] = true; from = r + 1 }
            r
        }
    }

    /**
     * A row that is a whole line: it reads with quantity x price = amount and a VAT code; or its amount is unreadable
     * but quantity x price gives an amount whose cents are still readable where the amount was ("16,420 g,21 04").
     */
    internal fun wholeLine(row: String, colliColumn: Boolean): ParsedLineItem? {
        ReceiptParser.parseItemLine(row, colliColumn)?.let { it ->
            return it.takeIf { proven(it) }?.let { p -> p.copy(lineTotalCents = p.lineTotalCents!!.copy(source = "${p.lineTotalCents.source} $RECOVERED")) }
        }
        val tokens = row.split(' ').filter { it.isNotBlank() }
        if (tokens.size < 5) return null
        val code = tokens.last()
        val rate = code.map { c -> RATE_LOOKALIKE[c] ?: c }.joinToString("").takeIf { RATE_CODE.matches(it) } ?: return null
        // From the right: junk where the amount was, then the price, then the quantity.
        for (p in tokens.size - 3 downTo 2) {
            val price = PRICE.find(tokens[p])?.value ?: continue
            val qty = tokens[p - 1].takeIf { QTY.matches(it) } ?: continue
            val junk = tokens.subList(p + 1, tokens.size - 1)
            if (junk.isEmpty() || junk.size > 3) return null
            val q = ItalianNumbers.parse(qty) ?: return null
            val pr = ItalianNumbers.parse(price) ?: return null
            val cents = q.multiply(pr).setScale(2, RoundingMode.HALF_UP)
            if (cents.signum() <= 0) return null
            val amount = ItalianNumbers.formatCents(cents.movePointRight(2).toLong())
            // The cents must still be readable at the end of the junk: ",21" for 8,21.
            val readable = CENTS.find(junk.last())?.groupValues?.get(1) ?: return null
            if (!amount.endsWith(",$readable")) return null
            val rebuilt = (tokens.subList(0, p + 1).dropLast(1) + price + amount + rate).joinToString(" ")
            val it = ReceiptParser.parseItemLine(rebuilt, colliColumn) ?: return null
            if (!proven(it)) return null
            val note = "$row $RECOVERED"
            return it.copy(lineTotalCents = it.lineTotalCents!!.copy(source = note))
        }
        return null
    }

    /** Quantity x price = amount, all read, and a VAT code: a line that proves itself. */
    private fun proven(it: ParsedLineItem): Boolean {
        val q = it.quantity ?: return false
        val p = it.unitPrice ?: return false
        val t = it.lineTotalCents ?: return false
        if (it.vatRatePercent == null || isAdj(it) || it.originalDescription.count(Char::isLetter) < 3) return false
        if (q.confidence != Confidence.HIGH || p.confidence != Confidence.HIGH) return false
        return ReceiptParser.matches(q.value, p.value, t.value)
    }

    private val RATE_LOOKALIKE = mapOf('O' to '0', 'o' to '0', 'D' to '0', 'I' to '1', 'l' to '1', 'Z' to '2', 'S' to '5')
    private val RATE_CODE = Regex("^(0?4|0?5|10|22)$")
    private val PRICE = Regex("^\\d{1,3}(?:\\.\\d{3})*,\\d{2,3}(?=[^\\d,]*$)")
    private val QTY = Regex("^\\d{1,3}(?:,\\d{1,3})?$")
    private val CENTS = Regex("[,.](\\d{2})\\W?$")
    private val COLLI_HEADING = Regex("(?i)\\b(colli|n\\.?\\s?colli|cartoni)\\b")

    // ------------------------------------------------------------ packages and pages

    /** Packages on the lines ("1x6" is one package of 6, "3" three); null when a product line has none or another form. */
    fun countPackages(items: List<ParsedLineItem>): Int? {
        val products = items.filter { !isAdj(it) }
        if (products.isEmpty()) return null
        var sum = 0
        for (it in products) {
            val p = it.packages?.value ?: return null
            sum += PACKAGES.matchEntire(p)?.groupValues?.get(1)?.toInt() ?: return null
        }
        return sum
    }

    private val PACKAGES = Regex("^(\\d{1,3})(?:[xX×*]\\d{1,4})?$")

    /**
     * The page count the document prints ("PAG. 1/6", "Pagina 2 di 6", "Page 3 of 6") and the numbers not found:
     * (6, [6]) when pages 1 to 5 were photographed. Null when the document does not number its pages, or the marks do
     * not agree on one count: a page is said missing only on evidence, never on a stray "1/4" (a GN tray) or "30/60"
     * (payment terms).
     */
    fun pageMarks(text: String): Pair<Int, List<Int>>? {
        val marks = mutableListOf<Pair<Int, Int>>()
        val worded = mutableSetOf<Int>()
        fun add(n: Int, total: Int) { if (total in 2..30 && n in 1..total) marks += n to total }
        fun termsAfter(line: String, end: Int) = PAYMENT_AFTER.containsMatchIn(line.substring(minOf(line.length, end + 1)))
        val lines = text.lines()
        for ((i, l) in lines.withIndex()) {
            WORDED.findAll(l).forEach { m ->
                if (!termsAfter(l, m.range.last)) {
                    add(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                    if (m.value.contains(rx("(?i)\\b(di|of|von|de)\\b"))) worded += m.groupValues[2].toInt()
                }
            }
            // A "PAG." heading of the page column (not "COND. PAG.", the payment terms), with the mark at the end of a row below.
            val head = PAG_HEADING.findAll(l).firstOrNull { m -> !PAYMENT_BEFORE.containsMatchIn(l.substring(0, m.range.first)) } ?: continue
            PAG_INLINE.find(l, head.range.first)?.let { m -> if (!termsAfter(l, m.range.last)) add(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
            for (next in lines.subList(i + 1, minOf(lines.size, i + 4))) {
                TRAILING_MARK.find(next.trim())?.let { m -> add(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
            }
        }
        if (marks.isEmpty()) return null
        // The count is the one marks agree on: a mark in words ("Pagina 3 di 6") on its own, or two different pages, or
        // the first or last page of it.
        val byTotal = marks.groupBy({ it.second }, { it.first }).mapValues { it.value.toSet() }
        val agreed = byTotal.filter { (total, ns) -> total in worded || ns.size >= 2 || 1 in ns || total in ns }
        val total = agreed.keys.singleOrNull() ?: return null
        val seen = agreed.getValue(total)
        return total to (1..total).filter { it !in seen }
    }

    private val PAYMENT_BEFORE = Regex("(?i)(\\bcond\\.?|\\bcondizioni\\s+di|\\bmod\\.?|\\bmodalit[àa]'?\\s+di|\\btermini\\s+di)\\s*$")
    private val PAYMENT_AFTER = Regex("(?i)^\\s*(gg\\b|giorni\\b|d\\.?\\s?f\\b|f\\.?\\s?m\\b)")
    private val WORDED = Regex("(?i)\\b(?:pagina|pag\\.?|page|seite)\\s*(\\d{1,2})\\s*(?:di|of|von|de|/)\\s*(\\d{1,2})\\b")
    private val PAG_HEADING = Regex("(?i)\\bpag(?:\\.|ina\\b|\\b)")
    private val PAG_INLINE = Regex("(?i)\\bpag(?:\\.|ina)?\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})\\b")
    private val TRAILING_MARK = Regex("(?<![\\d/])(\\d{1,2})\\s?/\\s?(\\d{1,2})$")
}
