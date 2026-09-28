package com.kitchenreceipts.core

import kotlin.math.abs
import kotlin.math.tan

/** One line of text as returned by an OCR engine, with its bounding box in page pixels. */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** Rotation reported by the OCR engine in degrees, if any (sign convention may vary). */
    val angle: Float = 0f,
) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerY: Double get() = (top + bottom) / 2.0
    val centerX: Double get() = (left + right) / 2.0
}

/**
 * OCR engines often return a table column by column ("Mozzarella..." in one block and "22,25"
 * in another). The parser needs visual rows, so this regroups lines whose vertical centres are
 * close into one row, ordered left to right.
 *
 * Phone photos are rarely perfectly straight: a 2° tilt moves the right edge of a 1000 px wide
 * receipt by 35 px, more than a text line. So the grouping is tried for several small skew angles
 * (and the engine's own reported angle) and the one that produces the most compact rows wins.
 */
object LayoutRows {

    fun toText(lines: List<OcrLine>): String = rows(lines).joinToString("\n") { row -> row.joinToString(" ") { it.text.trim() } }

    fun rows(lines: List<OcrLine>): List<List<OcrLine>> {
        val clean = lines.filter { it.text.isNotBlank() }
        if (clean.isEmpty()) return emptyList()

        val candidates = mutableSetOf(0.0)
        var a = -5.0
        while (a <= 5.0) { candidates += a; a += 0.5 }
        val reported = clean.filter { (it.right - it.left) > 3 * it.height }.map { it.angle.toDouble() }.sorted()
        if (reported.isNotEmpty()) {
            val median = reported[reported.size / 2]
            if (abs(median) in 0.1..15.0) { candidates += median; candidates += -median }
        }
        // Fewest rows = best alignment; on a tie prefer the smallest correction.
        return candidates
            .map { angle -> angle to group(clean, angle) }
            .minWith(compareBy<Pair<Double, List<List<OcrLine>>>> { it.second.size }.thenBy { abs(it.first) })
            .second
    }

    private fun group(lines: List<OcrLine>, angleDeg: Double): List<List<OcrLine>> {
        val slope = tan(Math.toRadians(angleDeg))
        val medianHeight = lines.map { it.height }.sorted()[lines.size / 2].toDouble()
        fun y(l: OcrLine) = l.centerY - l.centerX * slope

        class Row(first: OcrLine) {
            val items = mutableListOf(first)
            var y = y(first)
            fun add(l: OcrLine) { items += l; y = items.map { y(it) }.average() }
        }
        val rows = mutableListOf<Row>()
        for (line in lines.sortedBy { y(it) }) {
            val tolerance = 0.6 * minOf(line.height.toDouble(), medianHeight)
            val row = rows.lastOrNull { r ->
                abs(r.y - y(line)) <= tolerance && r.items.none { other -> overlapsHorizontally(other, line) }
            }
            if (row != null) row.add(line) else rows += Row(line)
        }
        return rows.sortedBy { it.y }.map { r -> r.items.sortedBy { it.left } }
    }

    private fun overlapsHorizontally(a: OcrLine, b: OcrLine): Boolean {
        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        return overlap > 0.5 * minOf(a.right - a.left, b.right - b.left)
    }
}
