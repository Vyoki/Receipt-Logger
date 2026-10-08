package com.kitchenreceipts.core

import kotlin.math.max
import kotlin.math.min

/**
 * The text a digital PDF carries (made by an invoicing program, not scanned): every letter is known exactly, with
 * its position. Reading it is better than reading a picture of the page, where "26,900" can come out "26.900" and
 * a "|" can stick to a word. But the letterhead is often a picture inside the PDF, with no text: so the text layer
 * is joined with what the OCR read, keeping from the OCR only what lies where the text layer has nothing.
 *
 * Some PDFs carry text that is not what is printed (a font without a proper character map gives nonsense, a scan
 * with a poor hidden OCR layer): the text layer is used only when it agrees with what the OCR sees.
 */
object TextLayer {

    /** The confidence given to the PDF's own text: exact (below 1, which means "not said"). */
    const val EXACT = 0.99f

    /** One printed character, in the pixels of the page image (top-left origin). */
    data class Glyph(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height: Float get() = (bottom - top).coerceAtLeast(1f)
        val centerY: Float get() = (top + bottom) / 2
    }

    /**
     * Lines from the characters, cut the way an OCR engine cuts them: a row of text is split where the gap is wide
     * (between columns of a table), and into words at spaces, so the rest of the reading works the same.
     */
    fun lines(glyphs: List<Glyph>): List<OcrLine> {
        val real = glyphs.filter { it.right > it.left && it.bottom > it.top }
        if (real.isEmpty()) return emptyList()
        // Rows: characters whose middles are within half a character's height of the row's.
        val rows = mutableListOf<MutableList<Glyph>>()
        for (g in real.sortedBy { it.centerY }) {
            val row = rows.lastOrNull()?.takeIf { r ->
                val y = r.map { it.centerY }.average().toFloat()
                val h = r.map { it.height }.average().toFloat()
                kotlin.math.abs(g.centerY - y) <= 0.5f * min(h, g.height) + 0.5f
            }
            if (row != null) row += g else rows += mutableListOf(g)
        }
        val out = mutableListOf<OcrLine>()
        for (row in rows) {
            val sorted = row.sortedBy { it.left }
            val h = sorted.map { it.height }.sorted()[sorted.size / 2]
            var segment = mutableListOf<MutableList<Glyph>>() // words of the current piece of line
            var word = mutableListOf<Glyph>()
            var lastRight = Float.NaN
            fun closeWord() { if (word.any { it.text.isNotBlank() }) segment += word; word = mutableListOf() }
            fun closeSegment() { closeWord(); if (segment.isNotEmpty()) out += line(segment); segment = mutableListOf() }
            for (g in sorted) {
                if (g.text.isBlank()) { closeWord(); continue }
                val gap = if (lastRight.isNaN()) 0f else g.left - lastRight
                when {
                    gap > 1.0f * h -> closeSegment()
                    gap > 0.2f * h -> closeWord()
                }
                word += g
                lastRight = max(if (lastRight.isNaN()) g.right else lastRight, g.right)
            }
            closeSegment()
        }
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun line(words: List<List<Glyph>>): OcrLine {
        val ws = words.map { w ->
            OcrLine(
                w.joinToString("") { it.text }, w.minOf { it.left }.toInt(), w.minOf { it.top }.toInt(),
                kotlin.math.ceil(w.maxOf { it.right }.toDouble()).toInt(), kotlin.math.ceil(w.maxOf { it.bottom }.toDouble()).toInt(),
                confidence = EXACT,
            )
        }
        return OcrLine(
            ws.joinToString(" ") { it.text }, ws.minOf { it.left }, ws.minOf { it.top }, ws.maxOf { it.right }, ws.maxOf { it.bottom },
            words = if (ws.size > 1) ws else emptyList(),
            confidence = EXACT,
        )
    }

    private fun key(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    private fun overlap(a: OcrLine, b: OcrLine): Int {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        return if (w > 0 && h > 0) w * h else 0
    }

    /**
     * True when the text layer says what is printed: most words the OCR read where the text layer has text are
     * the same words. Without an OCR reading to compare (a blank page for the OCR), the text must at least look
     * like text.
     */
    fun agrees(text: List<OcrLine>, ocr: List<OcrLine>): Boolean {
        val chars = text.sumOf { l -> l.text.count { it.isLetterOrDigit() } }
        if (chars < 20) return false
        val odd = text.sumOf { l -> l.text.count { it == '�' || it.isISOControl() || Character.getType(it) == Character.PRIVATE_USE.toInt() } }
        if (odd * 20 > chars) return false
        val textWords = text.flatMap { l -> l.words.ifEmpty { listOf(l) } }
        val ocrWords = ocr.flatMap { l -> l.words.ifEmpty { listOf(l) } }.filter { key(it.text).length >= 3 }
        val compared = ocrWords.mapNotNull { o ->
            val near = textWords.filter { overlap(it, o) > 0 }
            if (near.isEmpty()) null else near.any { t -> key(t.text).let { k -> k.contains(key(o.text)) || key(o.text).contains(k) && k.length >= 3 } }
        }
        if (compared.size < 5) return compared.isEmpty() || compared.count { it } * 2 >= compared.size
        return compared.count { it } * 2 >= compared.size
    }

    /**
     * The page as read: the text layer, plus the OCR's lines that lie where the text layer has nothing (a
     * letterhead or a stamp printed as a picture). When the text layer does not agree with the OCR, the OCR alone.
     */
    fun merge(text: List<OcrLine>, ocr: List<OcrLine>): List<OcrLine> {
        if (!agrees(text, ocr)) return ocr
        val extra = ocr.filter { o ->
            val area = o.width * o.height
            text.none { t -> overlap(t, o) * 10 > area * 3 }
        }
        return (text + extra).sortedWith(compareBy({ it.top }, { it.left }))
    }
}
