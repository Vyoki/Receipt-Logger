package com.kitchenreceipts.core

/**
 * Lots printed under the items with no heading to say so (no "Lotto", no LOTTO column): the page map finds the same
 * kind of code left over in the same place under several items. That repetition is the evidence: a code under one
 * item could be anything, the same column of codes under item after item is how a supplier prints its lots.
 *
 * Taken only for items without a lot, only from the rows under the item (never the item's first row, where its own
 * code is printed: a lot under the code column is common), never an amount or a date; and shown for a check (not
 * proven by a heading). Two or more items must agree on the place.
 */
object LotsByPosition {

    private val LOT_LIKE = rx("^[A-Z0-9][A-Z0-9\\-/.]{3,19}$")

    fun apply(doc: ParsedDocument, pages: List<List<OcrLine>>, ownVatNumber: String?): ParsedDocument {
        if (doc.lineItems.count { it.lotNumber == null && !it.adjustment } < 2) return doc
        val map = runCatching { PageMap.build(pages, doc, ownVatNumber) }.getOrNull() ?: return doc
        val ownRow = map.items.associate { b -> (b.page to b.item) to b.rows.first }
        // Candidates: leftover lot-like words under an item, not on its own first row.
        data class Cand(val item: Int, val text: String, val x: Int, val column: String?)
        val cands = map.unexplained.filter { p ->
            val t = p.word.text.uppercase()
            val item = p.item
            item != null && p.region == PageMap.Region.TABLE && doc.lineItems.getOrNull(item)?.lotNumber == null &&
                LOT_LIKE.matches(t) && t.any(Char::isDigit) && !t.contains(',') && ItalianDates.findDates(t).isEmpty() &&
                ownRow[p.word.page to item] != p.word.row && doc.lineItems[item].itemCode != p.word.text
        }.map { Cand(it.item!!, it.word.text, it.word.box.left, it.column) }
        if (cands.isEmpty()) return doc
        // The place they share: the same heading above, or the same left edge (within two characters' width).
        val groups = cands.groupBy { c -> c.column ?: "x${c.x / 40}" }
        val (place, best) = groups.maxByOrNull { (_, g) -> g.map { it.item }.distinct().size * 10_000 - g.minOf { it.x } } ?: return doc
        val items = best.groupBy { it.item }
        if (items.size < 2) return doc
        val lots = doc.lineItems.mapIndexed { i, it ->
            val c = items[i]?.singleOrNull() ?: return@mapIndexed it
            it.copy(lotNumber = Extracted(c.text, Confidence.LOW, "lot by position: the same place under ${items.size} items ($place)"))
        }
        return doc.copy(lineItems = lots, lotsPrinted = true)
    }
}
