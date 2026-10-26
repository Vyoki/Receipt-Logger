package com.kitchenreceipts.core

import java.text.Normalizer

/**
 * Finds where a value of the review screen was read on the photo, so it can be shown next to the field for a
 * quick cross-check: the row it came from ([DraftField.source]) and, inside it, the word with the value.
 * Coordinates are those of the OCR boxes (pixels of the image the OCR read).
 */
object FieldLocator {

    /**
     * [area]: the part of the page to show; [mark]: what to highlight in it (the value, or the whole row); [lineHeight]:
     * the height of one line of print there (to show it at a readable size).
     */
    data class Spot(val page: Int, val area: PageBox, val mark: PageBox, val exact: Boolean, val lineHeight: Int = 0)

    fun locate(pages: List<List<OcrLine>>, source: String?, value: String): Spot? {
        val v = value.trim()
        if (pages.isEmpty() || (source.isNullOrBlank() && v.isEmpty())) return null
        data class Row(val page: Int, val pieces: List<OcrLine>, val text: String)
        val rows = pages.flatMapIndexed { p, lines ->
            LayoutRows.rows(lines).map { r -> Row(p, r, r.joinToString(" ") { it.text.trim() }) }
        }
        if (rows.isEmpty()) return null
        // The row the value came from: most words of the source in common. A source that is not a printed row (the
        // AI's answer, "lot by position...") matches no row: then the rows that print the value itself.
        val wanted = words(source?.takeIf { it.isNotBlank() } ?: v)
        val scored = rows.map { r -> r to overlap(wanted, words(r.text)) }
        val bySource = scored.maxByOrNull { it.second }?.takeIf { it.second >= 0.4 }
        fun hasValue(r: Row) = v.isNotEmpty() && r.pieces.flatMap { piece -> piece.words.ifEmpty { listOf(piece) } }.any { same(it.text, v) || like(it.text, v) }
        // A code-like value (a document number, a lot) must be printed on the row shown: the row of the source that
        // holds it, else the best row that holds it (a slip like "34s67" for "34567" allowed), else nothing at all;
        // the wrong place on the photo is worse than none.
        val codeLike = v.length >= 4 && v.any(Char::isDigit) && v.any { !it.isDigit() && it != ',' && it != '.' } ||
            (v.length >= 6 && v.all(Char::isDigit))
        val row = when {
            bySource != null && (!codeLike || hasValue(bySource.first)) -> bySource.first
            v.isNotEmpty() -> scored.filter { hasValue(it.first) }.maxByOrNull { it.second }?.first
                ?: if (codeLike) return null else bySource?.first
            else -> bySource?.first
        } ?: return null
        val words = row.pieces.flatMap { piece -> piece.words.ifEmpty { listOf(piece) } }
        // A printed decimal ("1,000") before a bare number ("1", often the Pkgs column) when both match.
        val hit = if (v.isEmpty()) null else words.filter { same(it.text, v) }.let { hits -> hits.firstOrNull { it.text.contains(',') } ?: hits.firstOrNull() }
            ?: words.firstOrNull { contains(it.text, v) } ?: words.firstOrNull { like(it.text, v) }
        val rowBox = PageBox(row.pieces.minOf { it.left }, row.pieces.minOf { it.top }, row.pieces.maxOf { it.right }, row.pieces.maxOf { it.bottom })
        // One line of print (a tilted photo makes the row's box much taller than a line).
        val h = words.map { it.bottom - it.top }.sorted().let { it[it.size / 2] }.coerceAtLeast(10)
        val mark = hit?.let { PageBox(it.left, it.top, it.right, it.bottom) } ?: rowBox
        // A low strip (the line and a little above and below), and around the value only, so that on a phone the
        // print stays readable at the height of a small strip: about six line heights each side of the value.
        val area = if (hit != null) {
            PageBox(
                maxOf(rowBox.left - h, mark.left - 6 * h).coerceAtLeast(0), (mark.top - h - h / 2).coerceAtLeast(0),
                minOf(rowBox.right + h, mark.right + 6 * h), mark.bottom + h + h / 2,
            )
        } else {
            val mid = (rowBox.top + rowBox.bottom) / 2
            val half = minOf(rowBox.height / 2, 2 * h) + h
            PageBox((rowBox.left - h).coerceAtLeast(0), (mid - half).coerceAtLeast(0), rowBox.right + h, mid + half)
        }
        return Spot(row.page, area, mark, hit != null, h)
    }

    /** Same number (Italian format, OCR slips repaired) or same word. */
    private fun same(printed: String, value: String): Boolean {
        val a = OcrCleanup.cleanLine(printed).split(' ').firstOrNull { it.any(Char::isDigit) } ?: printed
        val x = ItalianNumbers.parse(a.trim('.', ',', '|', ';', ':'))
        val y = ItalianNumbers.parse(value)
        if (x != null && y != null) return x.compareTo(y) == 0
        return norm(printed) == norm(value)
    }

    private fun contains(printed: String, value: String): Boolean {
        val n = norm(value)
        return n.length >= 3 && norm(printed).contains(n)
    }

    /**
     * The same code through the camera's usual slips: letters read for digits (s/5, o/0, l/1, b/8...) and, in a long
     * code, one character misread ("34s67" for "34567").
     */
    internal fun like(printed: String, value: String): Boolean {
        val a = fold(printed)
        val b = fold(value)
        if (b.length < 4 || a.isEmpty()) return false
        if (a == b) return true
        return b.length >= 6 && a.length == b.length && a.indices.count { a[it] != b[it] } <= 1
    }

    private fun fold(s: String): String = norm(s).filter { it.isLetterOrDigit() || it == '/' || it == '-' }.map { c ->
        when (c) { 'o', 'd', 'q' -> '0'; 'l', 'i' -> '1'; 's' -> '5'; 'b' -> '8'; 'z' -> '2'; 'g' -> '6'; else -> c }
    }.joinToString("")

    private fun words(s: String): Set<String> = norm(s).split(' ').filter { it.length >= 2 }.toSet()

    private fun overlap(wanted: Set<String>, have: Set<String>): Double =
        if (wanted.isEmpty()) 0.0 else wanted.count { it in have }.toDouble() / wanted.size

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(rx("\\p{M}+"), "").lowercase()
            .replace(rx("[^a-z0-9,./]+"), " ").trim()
}
