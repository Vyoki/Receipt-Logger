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

    /** Supplier, number and date (top of the first page). */
    data class Header(override val page: Int, override val boxes: List<PageBox>) : AiTarget

    /** Taxable amount, VAT and total (bottom of the last page). */
    data class Totals(override val page: Int, override val boxes: List<PageBox>) : AiTarget
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

    fun plan(pages: List<List<OcrLine>>, doc: ParsedDocument): List<AiTarget>? {
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

        // Which row each line of the document came from (best word overlap with its source text).
        val itemRow = doc.lineItems.map { item ->
            val source = item.lineTotalCents?.source ?: item.quantity?.source ?: item.originalDescription
            tableRows.maxByOrNull { similarity(it.text, source) }?.takeIf { similarity(it.text, source) >= 0.5 }
        }
        val doubtful = doc.lineItems.indices.filter { i ->
            val it = doc.lineItems[i]
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
            targets += AiTarget.Row(r.page, strip(r), i, i, r.text, headerRows.getValue(r.page).text)
        }
        for (r in missed) {
            val after = itemRow.withIndex().filter { (_, row) -> row != null && (row.page < r.page || (row.page == r.page && row.index < r.index)) }
                .maxOfOrNull { it.index } ?: -1
            targets += AiTarget.Row(r.page, strip(r), null, after, r.text, headerRows.getValue(r.page).text)
        }
        val first = headerRows[0]
        if (doc.sellerName == null || doc.documentDate == null || doc.documentDate.confidence == Confidence.LOW) {
            val rows0 = allRows.filter { it.page == 0 }
            val bottom = first?.box?.top ?: rows0.getOrNull(rows0.size * 2 / 5)?.box?.bottom
            if (bottom != null && rows0.isNotEmpty()) targets += AiTarget.Header(0, listOf(bounds(rows0.filter { it.box.bottom <= bottom }.map { it.box })))
        }
        if (doc.totalCents == null || doc.totalCents.confidence == Confidence.LOW) {
            val last = pages.lastIndex
            val rowsL = allRows.filter { it.page == last }
            val footer = footerRows[last]
            val from = if (footer != null) footer else (rowsL.size * 3 / 5)
            val area = rowsL.filter { it.index >= from }.map { it.box }
            if (area.isNotEmpty()) targets += AiTarget.Totals(last, listOf(bounds(area)))
        }
        return targets
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
