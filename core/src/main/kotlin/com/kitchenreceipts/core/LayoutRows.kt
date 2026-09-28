package com.kitchenreceipts.core

/** One line of text as returned by an OCR engine, with its bounding box in page pixels. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerY: Double get() = (top + bottom) / 2.0
}

/**
 * OCR engines often return a table column by column ("Mozzarella..." in one block and "22,25"
 * in another). The parser needs visual rows, so this regroups lines whose vertical centres are
 * close into one row, ordered left to right.
 */
object LayoutRows {

    fun toText(lines: List<OcrLine>): String = rows(lines).joinToString("\n") { row -> row.joinToString(" ") { it.text.trim() } }

    fun rows(lines: List<OcrLine>): List<List<OcrLine>> {
        val clean = lines.filter { it.text.isNotBlank() }
        if (clean.isEmpty()) return emptyList()
        val medianHeight = clean.map { it.height }.sorted()[clean.size / 2].toDouble()

        class Row(val items: MutableList<OcrLine>) {
            var centerY = items.first().centerY
            fun add(l: OcrLine) { items += l; centerY = items.map { it.centerY }.average() }
        }
        val rows = mutableListOf<Row>()
        for (line in clean.sortedBy { it.centerY }) {
            val tolerance = 0.5 * minOf(line.height.toDouble(), medianHeight)
            val row = rows.lastOrNull { kotlin.math.abs(it.centerY - line.centerY) <= tolerance &&
                it.items.none { other -> overlapsHorizontally(other, line) } }
            if (row != null) row.add(line) else rows += Row(mutableListOf(line))
        }
        return rows.sortedBy { r -> r.items.minOf { it.top } }.map { r -> r.items.sortedBy { it.left } }
    }

    private fun overlapsHorizontally(a: OcrLine, b: OcrLine): Boolean {
        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        return overlap > 0.5 * minOf(a.right - a.left, b.right - b.left)
    }
}
