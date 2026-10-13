package com.kitchenreceipts.core

/**
 * The same sheet photographed twice (page 1 taken again instead of the last page): its lines would be counted twice
 * and the page that was never photographed would be missed without a word. A page is a copy of an earlier one when
 * most of the rows that carry numbers (item lines, totals) are the same rows, read the same way; two photos of one
 * sheet never read identically, so rows are compared on their letters and digits only, and a few misread rows do not
 * hide the copy. Different pages of one document share only the letterhead (no numbers rows of their own in common).
 */
object DuplicatePages {

    /** Index of every page that repeats an earlier page, with the earlier page it repeats. */
    fun find(pages: List<List<OcrLine>>): Map<Int, Int> {
        if (pages.size < 2) return emptyMap()
        val rows = pages.map { numberRows(it) }
        val out = linkedMapOf<Int, Int>()
        for (i in 1 until pages.size) {
            val a = rows[i]
            if (a.size < MIN_ROWS) continue
            for (j in 0 until i) {
                if (j in out) continue
                val b = rows[j]
                if (b.size < MIN_ROWS) continue
                val common = a.count { r -> b.any { it.same(r) } }
                if (common >= MIN_ROWS && common >= SHARE * minOf(a.size, b.size)) { out[i] = j; break }
            }
        }
        return out
    }

    /** The pages to read: a repeated page is left empty (page numbers stay those of the photos). */
    fun withoutRepeats(pages: List<List<OcrLine>>, repeats: Map<Int, Int>): List<List<OcrLine>> =
        if (repeats.isEmpty()) pages else pages.mapIndexed { i, p -> if (i in repeats) emptyList() else p }

    private const val MIN_ROWS = 5
    private const val SHARE = 0.5

    /**
     * Rows with an amount (item lines, totals: the letterhead repeated on every page has none), as letters and digits
     * only, so the same printed row read twice compares equal through spacing and punctuation slips.
     */
    private fun numberRows(lines: List<OcrLine>): List<Row> =
        LayoutRows.rows(lines).map { r -> r.joinToString(" ") { it.text } }
            .filter { AMOUNT.containsMatchIn(it) }
            .map { Row(it.uppercase().filter(Char::isLetterOrDigit), it.filter(Char::isDigit)) }
            .filter { it.text.length >= 12 }
            .distinctBy { it.text }

    /** A row as letters and digits, and as its digits alone (a letter misread in the name leaves the numbers equal). */
    private class Row(val text: String, val digits: String) {
        fun same(o: Row) = text == o.text || (digits.length >= 10 && digits == o.digits)
    }

    private val AMOUNT = rx("\\d+,\\d{2}\\b")
}
