package com.kitchenreceipts.core

import java.text.Normalizer

/**
 * A question about a column heading row the regular reading did not understand: [headings] as the OCR read them,
 * left to right; [boxes] the heading row and the first lines under it. See [AiReader.layoutInstruction].
 */
data class LayoutQuestion(val page: Int, val boxes: List<PageBox>, val headings: List<String>)

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
        /**
         * Names paired from another row: the following lines of the same block (consecutive, same page, same pairing).
         * The photo's tilt shifted them all the same way, so the AI confirming this line confirms them too.
         */
        val sameBlock: List<Int> = emptyList(),
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

    /** The supplier's box ("Cedente/prestatore", "Fornitore"): its company name, when the reading is not sure of it. */
    data class Supplier(override val page: Int, override val boxes: List<PageBox>, val boxText: String) : AiTarget
}

/**
 * Decides what the AI should look at. Instead of re-reading whole pages (minutes), it is shown only the parts the
 * regular reading could not prove: each line where quantity x price does not give the amount (or the amount is
 * missing), each line with an amount that was not read as a product, and the header or totals area when those are
 * missing. Each is a small picture with the column headings on top, answered in seconds.
 * Returns null only when the regular reading failed too broadly for that to help (most lines wrong or not found on
 * the page): then whole pages are read, as a last resort.
 */
object AiTargets {

    private const val MAX_ROW_TARGETS = 8

    /**
     * When the item table was not recognised (read as text) and no headings are known for this supplier, but a row
     * looks like column headings: one question to the AI about what each heading's column holds. Null otherwise.
     */
    fun layoutQuestion(pages: List<List<OcrLine>>, doc: ParsedDocument): LayoutQuestion? {
        if (doc.itemsReadBy != "text" || !doc.layout?.headings.isNullOrEmpty() || ReceiptParser.isConfident(doc)) return null
        pages.take(3).forEachIndexed { p, lines ->
            val layout = LayoutRows.layout(lines)
            layout.rows.forEachIndexed { r, row ->
                val text = row.joinToString(" ") { it.text.trim() }
                if (!ReceiptParser.isTableHeader(text) || hasMoney(text)) return@forEachIndexed
                if (TableReader.header(row, layout.slope) != null) return null // recognised: nothing to ask
                val words = row.flatMap { l -> l.words.ifEmpty { listOf(l) } }.filter { it.text.isNotBlank() }.sortedBy { it.left }
                if (words.size < 3) return@forEachIndexed
                // Heading cells: words close together belong to one heading ("PREZZO UNIT.", "DESCRIZIONE BENI").
                val h = words.map { it.height }.sorted()[words.size / 2].coerceAtLeast(8)
                val cells = mutableListOf(mutableListOf(words.first()))
                // A known heading word starts a new heading even when printed close to the previous one ("U.M. QUANTITA'").
                for (w in words.drop(1)) {
                    if (w.left - cells.last().last().right > h * 3 / 5 || TableReader.startsHeading(w.text)) cells += mutableListOf(w) else cells.last() += w
                }
                if (cells.size !in 3..12) return@forEachIndexed
                val below = layout.rows.drop(r + 1).take(3).filter { it.isNotEmpty() }
                val area = (row + below.flatten())
                val box = PageBox(area.minOf { it.left }, area.minOf { it.top } - h / 2, area.maxOf { it.right }, area.maxOf { it.bottom } + h / 2)
                return LayoutQuestion(p, listOf(box), cells.map { c -> c.joinToString(" ") { it.text.trim() } })
            }
        }
        return null
    }
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
                if (headerRows[p] == null && TableReader.header(row, layout.slope, doc.layout?.headings.orEmpty()) != null) headerRows[p] = info
                else if (headerRows[p] != null && footerRows[p] == null && ReceiptParser.isFooterRow(text)) footerRows[p] = r
            }
        }
        // Column headings the layout reader did not recognise (glued or misread words): the text still says it is a
        // heading row ("COD.ART. COLLI/DESCRIZIONE BENI U.M. QUANTITA PREZZO ... IMPORTO IVA").
        for (p in layouts.indices) {
            if (headerRows[p] != null) continue
            val pageRows = allRows.filter { it.page == p }
            val h = pageRows.firstOrNull { ReceiptParser.isTableHeader(it.text) } ?: continue
            headerRows[p] = h
            pageRows.firstOrNull { it.index > h.index && ReceiptParser.isFooterRow(it.text) }?.let { footerRows[p] = it.index }
        }
        // Rows of the item table on each page (between the column headings and the totals).
        fun inTable(r: RowInfo): Boolean {
            val h = headerRows[r.page] ?: return false
            return r.index > h.index && r.index < (footerRows[r.page] ?: Int.MAX_VALUE)
        }
        // A page without any headings: its lines are still found by their text; only the "missed line" search needs
        // the table's limits, so there it is limited to the rows between the first and last line found.
        val headless = layouts.indices.filter { headerRows[it] == null }.toSet()
        val tableRows = allRows.filter { inTable(it) || it.page in headless }
        if (tableRows.isEmpty()) return null

        // Which row each line of the document came from (best word overlap with its source text), each row used once:
        // ten identical "IMPASTO SALSICCIA" lines are ten rows, not one row and nine "missed" lines.
        val taken = mutableSetOf<RowInfo>()
        val rowWords = tableRows.associateWith { words(it.text) } // each row's words worked out once
        val itemRow = doc.lineItems.map { item ->
            val source = item.lineTotalCents?.source ?: item.quantity?.source ?: item.originalDescription
            val s = words(source)
            fun score(r: RowInfo): Double = if (s.isEmpty()) 0.0 else s.count { it in rowWords.getValue(r) }.toDouble() / s.size
            var best: RowInfo? = null
            var bestScore = 0.0
            for (r in tableRows) {
                if (r in taken) continue
                val v = score(r)
                if (best == null || v > bestScore) { best = r; bestScore = v }
            }
            best?.takeIf { bestScore >= 0.5 }?.also { taken += it }
        }
        // Too few lines found on the page: the page is not understood well enough for small questions.
        if (headless.isNotEmpty() && itemRow.count { it != null } < (doc.lineItems.size + 1) / 2) return null
        val headlessSpan = headless.associateWith { p ->
            val found = itemRow.filterNotNull().filter { it.page == p }.map { it.index }
            if (found.isEmpty()) IntRange.EMPTY else found.min()..found.max()
        }
        // Quantities worked out as amount / price are proven when every VAT group of the summary adds up: nothing to ask.
        val provenByVat = doc.vatChecks.isNotEmpty() && doc.vatChecks.all { it.ok }
        val doubtful = doc.lineItems.indices.filter { i ->
            val it = doc.lineItems[i]
            if (it.adjustment) return@filter false // a discount or charge: no goods to check
            if (it.nameDoubt) return@filter true // numbers proven or not, the name needs a look
            if (provenByVat && ReceiptParser.workedOut(it)) return@filter false
            ParseWarning.LINE_TOTAL_MISMATCH in it.warnings || it.lineTotalCents == null ||
                it.quantity?.confidence == Confidence.LOW || it.unitPrice?.confidence == Confidence.LOW ||
                ReceiptParser.isSectionHeading(it.originalDescription) || it.nameDoubt
        }
        val usedRows = itemRow.filterNotNull().toSet()
        // The numbers half of a line printed on two rows ("CECI ..." / "LT GR 1775 3 2,950 8,85 10"): its amount is
        // the amount of the item found on the row next to it. Not a missed line.
        fun continuation(r: RowInfo): Boolean {
            val amount = ReceiptParser.lastAmountCents(r.text) ?: return false
            return doc.lineItems.indices.any { i ->
                val row = itemRow[i]
                row != null && row.page == r.page && kotlin.math.abs(row.index - r.index) == 1 && doc.lineItems[i].lineTotalCents?.value == amount
            }
        }
        val missed = tableRows.filter { r ->
            r !in usedRows && (r.page !in headless || r.index in headlessSpan.getValue(r.page)) && r.text.count(Char::isLetter) >= 3 && hasMoney(r.text) &&
                !ReceiptParser.isNotAnItemRow(r.text) && LotExtractor.scan(r.text).lot == null && !continuation(r)
        }
        if (doubtful.size + missed.size > maxOf(MAX_ROW_TARGETS, doc.lineItems.size / 2)) return null

        fun strip(r: RowInfo, around: Boolean = false): List<PageBox> {
            val h = headerRows[r.page]
            val lineH = (r.box.height).coerceAtLeast(12)
            val left = minOf(h?.box?.left ?: r.box.left, r.box.left)
            val right = maxOf(h?.box?.right ?: r.box.right, r.box.right)
            // The line and the one below it (a name, a lot, or an amount printed one row lower may be there).
            val next = allRows.firstOrNull { it.page == r.page && it.index == r.index + 1 }
            val nextFits = next != null && (inTable(next) || r.page in headless) && next.text.count(Char::isLetter) < 3
            val bottom = if (nextFits || (around && next != null)) next!!.box.bottom else r.box.bottom
            // A name paired from another row: the rows above and below too, so the AI sees which name goes with the numbers.
            val prev = if (around) allRows.firstOrNull { it.page == r.page && it.index == r.index - 1 } else null
            val top = prev?.box?.top ?: r.box.top
            val line = PageBox(left, top - lineH / 2, right, bottom + lineH / 2)
            if (h == null) return listOf(line)
            val header = PageBox(left, h.box.top - lineH / 3, right, h.box.bottom + lineH / 3)
            return listOf(header, line)
        }
        fun head(page: Int) = headerRows[page]?.text ?: ""

        val targets = mutableListOf<AiTarget>()
        // Lines whose names were paired from another row, in blocks of consecutive lines on one page: one look each block.
        val block = mutableMapOf<Int, List<Int>>()
        val skip = mutableSetOf<Int>()
        val doubtNames = doubtful.filter { doc.lineItems[it].nameDoubt }.sorted()
        for (i in doubtNames) {
            if (i in skip) continue
            val rest = mutableListOf<Int>()
            var last = i
            for (j in doubtNames) if (j > last && j - last <= 2 && itemRow[j]?.page == itemRow[i]?.page) { rest += j; skip += j; last = j }
            block[i] = rest
        }
        for (i in doubtful) {
            if (i in skip) continue
            val r = itemRow[i] ?: continue
            val item = doc.lineItems[i]
            val choices = item.choices
            val head = head(r.page)
            targets += when {
                item.nameDoubt -> AiTarget.Row(r.page, strip(r, around = true), i, i, r.text, head, block[i].orEmpty())
                choices.size >= 2 -> AiTarget.Choice(r.page, strip(r), i, choices, r.text, head)
                // Only the quantity is in doubt (worked out): ask for that one number.
                ReceiptParser.workedOut(item) -> AiTarget.Number(r.page, strip(r), i, quantityHeading(head), item.quantity!!.value, r.text, head)
                else -> AiTarget.Row(r.page, strip(r), i, i, r.text, head)
            }
        }
        for (r in missed) {
            val after = itemRow.withIndex().filter { (_, row) -> row != null && (row.page < r.page || (row.page == r.page && row.index < r.index)) }
                .maxOfOrNull { it.index } ?: -1
            targets += AiTarget.Row(r.page, strip(r), null, after, r.text, head(r.page))
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
        val supplierBox = if (doc.sellerName == null || doc.sellerName.confidence == Confidence.LOW) runCatching { Parties.supplier(layouts, null) }.getOrNull() else null
        // The supplier's name is asked on its own box when the document has one (below); the header question then
        // only has the number and date to settle.
        val headerDoubt = (supplierBox == null && (doc.sellerName == null || doc.sellerName.confidence == Confidence.LOW)) ||
            doc.documentDate == null || doc.documentDate.confidence == Confidence.LOW
        // Number and date read under their own labels ("Numero documento", "Data"): the page's structure confirms them,
        // a second look adds nothing.
        val headerByLabels = doc.documentNumber?.source?.contains("under its heading") == true && doc.documentDate?.source?.contains("under its heading") == true
        if (headerDoubt || (spotCheck && !headerByLabels)) headerArea()?.let { targets += AiTarget.Header(0, listOf(it), verify = !headerDoubt) }
        // The supplier's own box, labelled as such: the AI copies the name printed in it (a sharper question than
        // "who issued this" over the whole top of the page, where the customer's box sits next to it).
        run {
            supplierBox?.let { s ->
                val h = (s.box.height / 6).coerceIn(6, 40)
                targets += AiTarget.Supplier(0, listOf(PageBox(s.box.left - h, s.box.top - h, s.box.right + h, s.box.bottom + h)), s.name?.source ?: "")
            }
        }
        val totalsDoubt = doc.totalCents == null || doc.totalCents.confidence == Confidence.LOW
        // Totals proven by the arithmetic (taxable + VAT = total, and the lines add up to them): nothing to double-check.
        val totalsProven = run {
            val sub = doc.subtotalCents?.value; val vat = doc.vatCents?.value; val tot = doc.totalCents?.value
            val sums = doc.lineItems.mapNotNull { it.lineTotalCents?.value }
            sub != null && vat != null && tot != null && kotlin.math.abs(sub + vat - tot) <= 2 && sums.size == doc.lineItems.size &&
                sums.isNotEmpty() && kotlin.math.abs(sums.sum() - sub) <= maxOf(2L, sums.size.toLong())
        }
        if (totalsDoubt || (spotCheck && !totalsProven)) totalsArea()?.let { (p, box) -> targets += AiTarget.Totals(p, listOf(box), verify = !totalsDoubt) }

        if (spotCheck) {
            val asked = targets.mapNotNull { t ->
                when (t) { is AiTarget.Row -> t.itemIndex; is AiTarget.Choice -> t.itemIndex; is AiTarget.Number -> t.itemIndex; else -> null }
            }.toSet()
            // Only lines with something not proven: a line whose printed quantity x price = amount (all read with
            // confidence) gains nothing from a second look and costs seconds.
            fun proven(it: ParsedLineItem): Boolean {
                val q = it.quantity ?: return false; val p = it.unitPrice ?: return false; val t = it.lineTotalCents ?: return false
                // A quantity worked out as amount / price is proven too when every VAT group adds up.
                val qSure = q.confidence == Confidence.HIGH || (provenByVat && ReceiptParser.workedOut(it))
                return qSure && p.confidence == Confidence.HIGH && t.confidence == Confidence.HIGH && LineDiscount.matches(q.value, p.value, it.discount?.value, t.value)
            }
            val open = doc.lineItems.indices.filter { i -> i !in asked && itemRow[i] != null && !doc.lineItems[i].adjustment && doc.lineItems[i].lineTotalCents != null }
            // Everything proven: still one look at the largest amount, the line where a misread costs most.
            val candidates = open.filter { !proven(doc.lineItems[it]) }.ifEmpty { listOfNotNull(open.maxByOrNull { doc.lineItems[it].lineTotalCents!!.value }) }
            val worked = candidates.filter { ReceiptParser.workedOut(doc.lineItems[it]) }
            val largest = (candidates - worked.toSet()).sortedByDescending { doc.lineItems[it].lineTotalCents!!.value }
            for (i in (worked + largest).take(MAX_SPOT_LINES)) {
                val r = itemRow[i]!!
                val head = head(r.page)
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
        rx("(?i)\\b(importo|totale|valore|imponibile|ammontare)\\b").find(header)?.value ?: "IMPORTO"

    /** The quantity column's heading as printed ("TOT. PZ/KG", "QUANTITA'", "QTA"), for the question. */
    private fun quantityHeading(header: String): String {
        val m = rx("(?i)\\b(tot\\.?\\s*(pz/kg)?|quantit\\S*|q\\.?t[aà]\\S*|qta\\S*|pezzi)").find(header)
        return m?.value?.trim() ?: "QUANTITA'"
    }

    private fun bounds(b: List<PageBox>) = PageBox(b.minOf { it.left }, b.minOf { it.top }, b.maxOf { it.right }, b.maxOf { it.bottom })

    private val MONEY = Regex("\\d,\\d{2}(?!\\d)")
    private fun hasMoney(s: String) = MONEY.containsMatchIn(s)

    private fun words(s: String): Set<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(rx("\\p{M}+"), "").lowercase()
            .split(rx("[^a-z0-9,]+")).filter { it.length >= 2 }.toSet()
}
