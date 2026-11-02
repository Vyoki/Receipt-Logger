package com.kitchenreceipts.core

/**
 * Doubts that do not hold an invoice whose money is proven: whether a product's name goes on over the next printed
 * line, and a lot or an expiry date that was read unsure or not at all. Such an invoice is saved by the app; each doubt
 * is kept on its line as a "to tidy" mark ([ItemMark] of kind [KIND]), listed for the operator to settle when it suits
 * them. A doubtful lot or expiry is never saved as if it were sure: the line is saved without it, the reading kept in
 * the mark as a suggestion.
 */
object Tidy {

    const val KIND = "tidy"
    const val NAME = "name"
    const val LOT = "lot"
    const val EXPIRY = "expiry"

    /** The meaning of a mark for a lot printed on the other lines but not read on this one. */
    const val NONE_READ = "-"

    /**
     * Every doubt left in [d] is one to tidy: the supplier, number, date and every amount are sure (the money proven by
     * its own arithmetic, see [AutoAccept.moneyProven]), and what is unsure is only a product's name, a lot or an
     * expiry date. A lot missing on some lines while others have one counts as one to tidy too.
     */
    fun onlyTidyLeft(d: DocumentDraft): Boolean {
        val s = AutoAccept.settleProven(d)
        if (listOf(s.seller, s.date, s.number, s.currency, s.subtotal, s.vat, s.total).any { it.uncertain } || s.vatBasisUncertain) return false
        if (s.seller.isMissing || s.number.isMissing || ItalianDates.parse(s.date.text) == null) return false
        if (s.items.any { listOf(it.quantity, it.unit, it.unitPrice, it.lineTotal, it.vatRate, it.packages, it.packSize, it.discount).any { f -> f.uncertain } }) return false
        return AutoAccept.moneyProven(s)
    }

    /** A goods line without a lot on a document whose other lines have one. */
    private fun lotMissing(d: DocumentDraft, l: LineItemDraft): Boolean =
        d.lotsPrinted && !l.isCharge && l.lot.text.isBlank() && d.items.any { !it.isCharge && it.lot.text.isNotBlank() }

    /**
     * [d] as the app saves it when [onlyTidyLeft]: a doubtful lot or expiry taken off its line (kept in the mark as
     * the reading), a doubtful name kept as printed and marked, a missing lot marked. Nothing else changes.
     */
    fun prepareForSave(d: DocumentDraft): DocumentDraft = d.copy(
        items = d.items.map { l ->
            val marks = l.marks.filter { it.kind != KIND }.toMutableList()
            var lot = l.lot
            var expiry = l.expiry
            var description = l.description
            if (lot.uncertain) {
                marks += ItemMark(KIND, LOT, lot.text.trim().ifEmpty { NONE_READ })
                lot = DraftField("")
            } else if (lotMissing(d, l)) {
                marks += ItemMark(KIND, LOT, NONE_READ)
            }
            if (expiry.uncertain) {
                marks += ItemMark(KIND, EXPIRY, expiry.text.trim().ifEmpty { NONE_READ })
                expiry = DraftField("")
            }
            if (description.uncertain) {
                marks += ItemMark(KIND, NAME, description.source?.take(160)?.ifBlank { null } ?: description.text)
                description = description.copy(uncertain = false)
            }
            l.copy(lot = lot, expiry = expiry, description = description, marks = marks)
        },
    )

    /** The line's marks to tidy. */
    fun of(l: LineItemDraft): List<ItemMark> = l.marks.filter { it.kind == KIND }
    fun of(marks: List<ItemMark>): List<ItemMark> = marks.filter { it.kind == KIND }

    /** [marks] without the ones to tidy (what other screens list as the line's codes). */
    fun without(marks: List<ItemMark>): List<ItemMark> = marks.filter { it.kind != KIND }

    /** The reading kept in a lot or expiry mark; null when none was read. */
    fun suggestion(m: ItemMark): String? = m.meaning.takeIf { m.code != NAME && it != NONE_READ }

    /**
     * A saved line opened for editing: its doubts shown again as doubts (a lot or expiry read back as the unsure
     * value it was, the name highlighted), the marks taken off. Saved from the review, the line has been looked at.
     */
    fun restore(l: LineItemDraft): LineItemDraft {
        val marks = of(l)
        if (marks.isEmpty()) return l
        var out = l.copy(marks = without(l.marks))
        for (m in marks) out = when (m.code) {
            LOT -> out.copy(lot = DraftField(suggestion(m) ?: out.lot.text, uncertain = true, source = "to tidy"))
            EXPIRY -> out.copy(expiry = DraftField(suggestion(m) ?: out.expiry.text, uncertain = true, source = "to tidy"))
            else -> out.copy(description = out.description.copy(uncertain = true, source = m.meaning))
        }
        return out
    }
}
