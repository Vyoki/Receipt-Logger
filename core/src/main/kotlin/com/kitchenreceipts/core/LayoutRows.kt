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
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerY: Double get() = (top + bottom) / 2.0
    val centerX: Double get() = (left + right) / 2.0
}

/**
 * Rebuilds visual rows from OCR pieces.
 *
 * OCR engines return a table column by column: "BISCOTTI…" in one block, "3,45" in another.
 * On a phone photo the rows are not horizontal and not even parallel (the page is a trapezoid),
 * so rows are rebuilt the way a person follows a row with a finger: each piece is linked to the
 * piece on its left whose row, continued with that row's own slope, passes through it.
 *
 * The overall tilt is measured, not guessed: a tilted wide line has a taller bounding box than a
 * short one (height grows by width x tan(tilt)), which gives the size of the tilt; the direction
 * is the one that links the most pieces. Trying arbitrary angles is avoided on purpose: a tilt that
 * shifts the right-hand columns by exactly one row "fits" too, and pairs every description with
 * the next row's prices.
 */
object LayoutRows {

    fun toText(lines: List<OcrLine>): String = rows(lines).joinToString("\n") { row -> row.joinToString(" ") { it.text.trim() } }

    fun rows(lines: List<OcrLine>): List<List<OcrLine>> {
        val clean = lines.filter { it.text.isNotBlank() }
        if (clean.isEmpty()) return emptyList()
        val (k, textHeight) = tiltFit(clean)
        val candidates = linkedSetOf(0.0, k, -k)
        val reported = clean.filter { it.width > 3 * it.height }.map { it.angle.toDouble() }.sorted()
        if (reported.isNotEmpty()) {
            val median = tan(Math.toRadians(reported[reported.size / 2]))
            if (abs(median) in 0.001..0.3) { candidates += median; candidates += -median }
        }
        return candidates
            .map { slope -> slope to chain(clean, slope, textHeight) }
            .minWith(compareBy<Pair<Double, List<List<OcrLine>>>> { it.second.size }.thenBy { abs(it.first) })
            .second
    }

    /**
     * Box height = text height + width x |tan(tilt)| for tilted text. A least-squares line through
     * (width, height) of all pieces gives |tan(tilt)| (slope) and the text height (intercept).
     */
    private fun tiltFit(lines: List<OcrLine>): Pair<Double, Double> {
        val medianH = lines.map { it.height }.sorted()[lines.size / 2].toDouble()
        val n = lines.size.toDouble()
        val mw = lines.sumOf { it.width.toDouble() } / n
        val mh = lines.sumOf { it.height.toDouble() } / n
        var cov = 0.0
        var varW = 0.0
        for (l in lines) {
            cov += (l.width - mw) * (l.height - mh)
            varW += (l.width - mw) * (l.width - mw)
        }
        if (varW < 1.0 || lines.size < 2) return 0.0 to medianH
        val k = (cov / varW).coerceIn(0.0, 0.12)
        val textHeight = lines.map { it.height - k * it.width }.sorted()[lines.size / 2].coerceAtLeast(4.0)
        return k to textHeight
    }

    private class Row(first: OcrLine) {
        val items = mutableListOf(first)
        val last: OcrLine get() = items.last()

        /** Slope measured along the row once it spans enough width, otherwise the page estimate. */
        fun slope(default: Double): Double {
            val a = items.first()
            val b = items.last()
            val dx = b.centerX - a.centerX
            if (items.size < 2 || dx < 150) return default
            val measured = (b.centerY - a.centerY) / dx
            return measured.coerceIn(default - 0.06, default + 0.06)
        }

        fun yAt(x: Double, default: Double): Double = last.centerY + slope(default) * (x - last.centerX)
    }

    private fun chain(lines: List<OcrLine>, slope: Double, textHeight: Double): List<List<OcrLine>> {
        val tolerance = 0.6 * textHeight
        val rows = mutableListOf<Row>()
        for (line in lines.sortedWith(compareBy<OcrLine> { it.left }.thenBy { it.centerY })) {
            var best: Row? = null
            var bestDy = Double.MAX_VALUE
            for (r in rows) {
                val last = r.last
                // A piece below/above the row's last piece in the same column belongs to another row.
                val overlap = minOf(last.right, line.right) - maxOf(last.left, line.left)
                if (overlap > 0.3 * minOf(last.width, line.width)) continue
                if (line.centerX <= last.centerX) continue
                val dy = abs(r.yAt(line.centerX, slope) - line.centerY)
                if (dy <= tolerance && dy < bestDy) { best = r; bestDy = dy }
            }
            if (best != null) best.items += line else rows += Row(line)
        }
        // Top to bottom, comparing rows where they would cross the left margin.
        return rows.sortedBy { r -> r.items.first().centerY - slope * r.items.first().centerX }.map { it.items.toList() }
    }
}
