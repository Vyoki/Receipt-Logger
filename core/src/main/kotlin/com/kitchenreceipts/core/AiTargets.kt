package com.kitchenreceipts.core

import java.text.Normalizer

/** A rectangle on a page photo, in the coordinates of the OCR boxes. */
data class PageBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** One small question for the AI: a picture of part of a page, and what to answer about it. */
sealed interface AiTarget {
    val page: Int
    /** Areas of the page to show, stacked top to bottom (e.g. the column headings, then the line). */
    val boxes: List<PageBox>

    /** A product line that did not add up ([itemIndex] in the document's lines), or a line with an amount the reader missed. */
    data class Row(
        override val page: Int,
        override val boxes: List<PageBox>,
        val itemIndex: Int?,
        /** For a missed line: insert after this line of the document (-1 = first). */
        val insertAfter: Int,
        val rowText: String,
        val headerText: String,
    ) : AiTarget

    /** A line whose numbers add up in several ways ([choices]): the answer is one letter. */
    data class Choice(
        override val page: Int,
        override val boxes: List<PageBox>,
        val itemIndex: Int,
        val choices: List<LineChoice>,
        val rowText: String,
        val headerText: String,
    ) : AiTarget

    /**
     * One number the OCR missed, worked out from the others ([expected] = amount / price): the AI reads just that
     * column ([column], e.g. "TOT. PZ/KG") and answers a number. Agreement proves it; a different number leaves it
     * highlighted for the operator.
     */
    data class Number(
        override val page: Int,
        override val boxes: List<PageBox>,
        val itemIndex: Int,
        val column: String,
        val expected: java.math.BigDecimal,
        val rowText: String,
        val headerText: String,
        val field: Field = Field.QUANTITY,
        /** A spot check of a value already read with confidence (see [AiTargets.plan] with spotCheck). */
        val verify: Boolean = false,
    ) : AiTarget

    enum class Field { QUANTITY, AMOUNT }

    /** Supplier, number and date (top of the first page). */
    data class Header(override val page: Int, override val boxes: List<PageBox>, val verify: Boolean = false) : AiTarget

    /** Taxable amount, VAT and total (bottom of the last page). */
    data class Totals(override val page: Int, override val boxes: List<PageBox>, val verify: Boolean = false) : AiTarget
}

/**
 * Decides what the AI should look at. Instead of re-reading whole pages (minutes), it is shown only the parts the
 * regular reading could not prove: each line where quantity x price does not give the amount (or the amount is
 * missing), each line with an amount that was not read as a product, and the header or totals area when those are
 * missing. Each is a small picture with the column headings on top, answered in seconds.
 * Returns null when the regular reading failed too broadly for that to help (no table found, most lines wrong):
 * then whole pages are read.
 */
object AiTargets {

    private const val MAX_ROW_TARGETS = 8
    private const val MAX_SPOT_LINES = 8

    /**
     * [spotCheck]: the AI also double-checks what was read with confidence, before the operator is involved: the
     * header (supplier, number, date), the totals, and up to 8 lines (quantities worked out by arithmetic first, then
     * the largest amounts). Each is a short question; a value the AI reads differently is highlighted with both.
     */
    fun plan(pages: List<List<OcrLine>>, doc: ParsedDocument, spotCheck: Boolean = false): List<AiTarget>? {
        if (pages.isEmpty() || doc.lineItems.isEmpty()) return null
        val layouts = pages.map { LayoutRows.layout(it) }
        data class RowInfo(val page: Int, val index: Int, val text: String, val box: PageBox)
        val allRows = mutableListOf<RowInfo>()
        val headerRows = mutableMapOf<Int, RowInfo>()
        val footerRows = mutableMapOf<Int, Int>()
        layouts.forEachIndexed { p, layout ->
            layout.rows.forEachIndexed { r, row ->
                val text = row.joinToString(" ") { it.text.trim() }
                val info = RowInfo(p, r, text, PageBox(row.minOf { it.left }, row.minOf { it.top }, row.maxOf { it.right }, row.maxOf { it.bottom }))
                allRows += info
                if (headerRows[p] == null && TableReader.header(row, layout.slope) != null) headerRows[p] = info
                else if (headerRows[p] != null && footerRows[p] == null && ReceiptParser.isFooterRow(text)) footerRows[p] = r
            }
        }
        // Rows of the item table on each page (between the column headings and the totals).
        fun inTable(r: RowInfo): Boolean {
            val h = headerRows[r.page] ?: return false
            return r.index > h.index && r.index < (footerRows[r.page] ?: Int.MAX_VALUE)
        }
        val tableRows = allRows.filter(::inTable)
        if (tableRows.isEmpty()) return null

        // Which row each line of the document came from (best word overlap with its source text), each row used once:
        // ten identical "IMPASTO SALSICCIA" lines are ten rows, not one row and nine "missed" lines.
        val taken = mutableSetOf<RowInfo>()
        val itemRow = doc.lineItems.map { item ->
            val source = item.lineTotalCents?.source ?: item.quantity?.source ?: item.originalDescription
            val best = tableRows.filter { it !in taken }.maxByOrNull { similarity(it.text, source) }
                ?.takeIf { similarity(it.text, source) >= 0.5 }
            best?.also { taken += it }
        }
        // Quantities worked out as amount / price are proven when every VAT group of the summary adds up: nothing to ask.
        val provenByVat = doc.vatChecks.isNotEmpty() && doc.vatChecks.all { it.ok }
        val doubtful = doc.lineItems.indices.filter { i ->
            val it = doc.lineItems[i]
            if (provenByVat && ReceiptParser.workedOut(it)) return@filter false
            ParseWarning.LINE_TOTAL_MISMATCH in it.warnings || it.lineTotalCents == null ||
                it.quantity?.confidence == Confidence.LOW || it.unitPrice?.confidence == Confidence.LOW ||
                ReceiptParser.isSectionHeading(it.originalDescription)
        }
        val usedRows = itemRow.filterNotNull().toSet()
        val missed = tableRows.filter { r ->
            r !in usedRows && r.text.count(Char::isLetter) >= 3 && hasMoney(r.text) &&
                !ReceiptParser.isNotAnItemRow(r.text) && LotExtractor.scan(r.text).lot == null
        }
        if (doubtful.size + missed.size > maxOf(MAX_ROW_TARGETS, doc.lineItems.size / 2)) return null

        fun strip(r: RowInfo): List<PageBox> {
            val h = headerRows.getValue(r.page)
            val lineH = (r.box.height).coerceAtLeast(12)
            val left = minOf(h.box.left, r.box.left)
            val right = maxOf(h.box.right, r.box.right)
            val header = PageBox(left, h.box.top - lineH / 3, right, h.box.bottom + lineH / 3)
            // The line and the one below it (a name or lot may continue there).
            val next = allRows.firstOrNull { it.page == r.page && it.index == r.index + 1 }
            val bottom = if (next != null && inTable(next) && !hasMoney(next.text)) next.box.bottom else r.box.bottom
            val line = PageBox(left, r.box.top - lineH / 2, right, bottom + lineH / 2)
            return listOf(header, line)
        }

        val targets = mutableListOf<AiTarget>()
        for (i in doubtful) {
            val r = itemRow[i] ?: continue
            val item = doc.lineItems[i]
            val choices = item.choices
            val head = headerRows.getValue(r.page).text
            targets += when {
                choices.size >= 2 -> AiTarget.Choice(r.page, strip(r), i, choices, r.text, head)
                // Only the quantity is in doubt (worked out): ask for that one number.
                ReceiptParser.workedOut(item) -> AiTarget.Number(r.page, strip(r), i, quantityHeading(head), item.quantity!!.value, r.text, head)
                else -> AiTarget.Row(r.page, strip(r), i, i, r.text, head)
            }
        }
        for (r in missed) {
            val after = itemRow.withIndex().filter { (_, row) -> row != null && (row.page < r.page || (row.page == r.page && row.index < r.index)) }
                .maxOfOrNull { it.index } ?: -1
            targets += AiTarget.Row(r.page, strip(r), null, after, r.text, headerRows.getValue(r.page).text)
        }
        val first = headerRows[0]
        fun headerArea(): PageBox? {
            val rows0 = allRows.filter { it.page == 0 }
            val bottom = first?.box?.top ?: rows0.getOrNull(rows0.size * 2 / 5)?.box?.bottom ?: return null
            val area = rows0.filter { it.box.bottom <= bottom }.map { it.box }
            return if (area.isEmpty()) null else bounds(area)
        }
        fun totalsArea(): Pair<Int, PageBox>? {
            // The page with the totals under its table (a later page may only say "TOTALE DA PAGARE" or loyalty points).
            val last = footerRows.keys.maxOrNull() ?: pages.lastIndex
            val rowsL = allRows.filter { it.page == last }
            val footer = footerRows[last]
            val from = footer ?: (rowsL.size * 3 / 5)
            val area = rowsL.filter { it.index >= from }.map { it.box }
            return if (area.isEmpty()) null else last to bounds(area)
        }
        val headerDoubt = doc.sellerName == null || doc.sellerName.confidence == Confidence.LOW || doc.documentDate == null || doc.documentDate.confidence == Confidence.LOW
        if (headerDoubt || spotCheck) headerArea()?.let { targets += AiTarget.Header(0, listOf(it), verify = !headerDoubt) }
        val totalsDoubt = doc.totalCents == null || doc.totalCents.confidence == Confidence.LOW
        if (totalsDoubt || spotCheck) totalsArea()?.let { (p, box) -> targets += AiTarget.Totals(p, listOf(box), verify = !totalsDoubt) }

        if (spotCheck) {
            val asked = targets.mapNotNull { t ->
                when (t) { is AiTarget.Row -> t.itemIndex; is AiTarget.Choice -> t.itemIndex; is AiTarget.Number -> t.itemIndex; else -> null }
            }.toSet()
            val candidates = doc.lineItems.indices.filter { i -> i !in asked && itemRow[i] != null && doc.lineItems[i].lineTotalCents != null }
            val worked = candidates.filter { ReceiptParser.workedOut(doc.lineItems[it]) }
            val largest = (candidates - worked.toSet()).sortedByDescending { doc.lineItems[it].lineTotalCents!!.value }
            for (i in (worked + largest).take(MAX_SPOT_LINES)) {
                val r = itemRow[i]!!
                val head = headerRows.getValue(r.page).text
                val item = doc.lineItems[i]
                targets += if (i in worked) {
                    AiTarget.Number(r.page, strip(r), i, quantityHeading(head), item.quantity!!.value, r.text, head, AiTarget.Field.QUANTITY, verify = true)
                } else {
                    AiTarget.Number(r.page, strip(r), i, amountHeading(head), ItalianNumbers.centsToDecimal(item.lineTotalCents!!.value), r.text, head, AiTarget.Field.AMOUNT, verify = true)
                }
            }
        }
        return targets
    }

    /** The amount column's heading as printed ("IMPORTO", "TOTALE", "VALORE"). */
    private fun amountHeading(header: String): String =
        Regex("(?i)\\b(importo|totale|valore|imponibile|ammontare)\\b").find(header)?.value ?: "IMPORTO"

    /** The quantity column's heading as printed ("TOT. PZ/KG", "QUANTITA'", "QTA"), for the question. */
    private fun quantityHeading(header: String): String {
        val m = Regex("(?i)\\b(tot\\.?\\s*(pz/kg)?|quantit\\S*|q\\.?t[aà]\\S*|qta\\S*|pezzi)").find(header)
        return m?.value?.trim() ?: "QUANTITA'"
    }

    private fun bounds(b: List<PageBox>) = PageBox(b.minOf { it.left }, b.minOf { it.top }, b.maxOf { it.right }, b.maxOf { it.bottom })

    private val MONEY = Regex("\\d,\\d{2}(?!\\d)")
    private fun hasMoney(s: String) = MONEY.containsMatchIn(s)

    private fun words(s: String): Set<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .split(Regex("[^a-z0-9,]+")).filter { it.length >= 2 }.toSet()

    /** Share of the source's words found in the row. */
    private fun similarity(row: String, source: String): Double {
        val s = words(source)
        if (s.isEmpty()) return 0.0
        val r = words(row)
        return s.count { it in r }.toDouble() / s.size
    }
}
