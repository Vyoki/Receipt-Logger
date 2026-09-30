package com.kitchenreceipts.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Finds the four corners of a sheet of paper in a photo, so the page can be flattened like a scanner does before
 * it is read (a page photographed at an angle becomes a straight rectangle; the table beside it is cut away).
 *
 * Works on a small grey-scale copy of the photo: the paper is the largest bright area, clearly brighter than
 * what is around it; its corners are the extreme points of that area. When that is not clear (white table, page
 * filling the whole photo, odd shape) it returns null and the photo is read as it is: flattening is only done
 * when it is safe.
 */
object PageCorners {

    /** Corners in the pixels of the grey image given: top-left, top-right, bottom-right, bottom-left. */
    data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
        val points: List<Pt> get() = listOf(tl, tr, br, bl)
        fun scaled(f: Double) = Quad(tl * f, tr * f, br * f, bl * f)

        /** Pushed out from the centre by [fraction] of its size, so the edge of the print is never cut. */
        fun grown(fraction: Double): Quad {
            val cx = points.sumOf { it.x } / 4
            val cy = points.sumOf { it.y } / 4
            fun g(p: Pt) = Pt(cx + (p.x - cx) * (1 + fraction), cy + (p.y - cy) * (1 + fraction))
            return Quad(g(tl), g(tr), g(br), g(bl))
        }

        /** Width and height of the flattened page (the longer of each pair of opposite edges). */
        val outWidth: Double get() = maxOf(tl.dist(tr), bl.dist(br))
        val outHeight: Double get() = maxOf(tl.dist(bl), tr.dist(br))

        val area: Double
            get() {
                val p = points
                var s = 0.0
                for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % 4]; s += a.x * b.y - b.x * a.y }
                return abs(s) / 2
            }
    }

    data class Pt(val x: Double, val y: Double) {
        operator fun times(f: Double) = Pt(x * f, y * f)
        fun dist(o: Pt) = hypot(x - o.x, y - o.y)
    }

    /** [lum]: brightness 0..255 of each pixel, row by row, [w] x [h]. */
    fun find(lum: IntArray, w: Int, h: Int): Quad? {
        if (w < 40 || h < 40 || lum.size != w * h) return null
        val t = otsu(lum)
        // The largest bright area (4-connected).
        val label = IntArray(w * h)
        var best = -1; var bestSize = 0; var next = 1
        val queue = IntArray(w * h)
        for (start in lum.indices) {
            if (lum[start] <= t || label[start] != 0) continue
            var head = 0; var tail = 0
            queue[tail++] = start; label[start] = next
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                fun visit(j: Int) { if (lum[j] > t && label[j] == 0) { label[j] = next; queue[tail++] = j } }
                if (x > 0) visit(i - 1)
                if (x < w - 1) visit(i + 1)
                if (y > 0) visit(i - w)
                if (y < h - 1) visit(i + w)
            }
            if (tail > bestSize) { bestSize = tail; best = next }
            next++
        }
        if (best < 0) return null
        val total = (w * h).toDouble()
        if (bestSize < 0.2 * total) return null

        // Corners: the points of the area furthest towards each corner of the photo.
        var tl = Pt(0.0, 0.0); var tr = tl; var br = tl; var bl = tl
        var sTl = Double.MAX_VALUE; var sTr = -Double.MAX_VALUE; var sBr = -Double.MAX_VALUE; var sBl = -Double.MAX_VALUE
        var inside = 0L; var outside = 0L; var nIn = 0; var nOut = 0
        for (i in lum.indices) {
            if (label[i] != best) { outside += lum[i]; nOut++; continue }
            inside += lum[i]; nIn++
            val x = (i % w).toDouble(); val y = (i / w).toDouble()
            if (x + y < sTl) { sTl = x + y; tl = Pt(x, y) }
            if (x - y > sTr) { sTr = x - y; tr = Pt(x, y) }
            if (x + y > sBr) { sBr = x + y; br = Pt(x, y) }
            if (y - x > sBl) { sBl = y - x; bl = Pt(x, y) }
        }
        // The paper must stand out from what is around it.
        if (nOut < 0.03 * total) return null
        if (inside / nIn.toDouble() - outside / nOut.toDouble() < 40) return null
        val q = Quad(tl, tr, br, bl)
        if (!convex(q) || !anglesOk(q)) return null
        if (q.area < 0.2 * total || q.area > 0.97 * total) return null
        // The bright area must fill most of the shape found (a page, not a blob with arms).
        if (bestSize < 0.55 * q.area) return null
        // Already square to the photo and filling it: nothing to gain.
        val margin = 0.03 * maxOf(w, h)
        val corners = listOf(Pt(0.0, 0.0), Pt(w - 1.0, 0.0), Pt(w - 1.0, h - 1.0), Pt(0.0, h - 1.0))
        if (q.points.zip(corners).all { (a, b) -> a.dist(b) < margin }) return null
        return q
    }

    private fun otsu(lum: IntArray): Int {
        val hist = IntArray(256)
        for (v in lum) hist[v.coerceIn(0, 255)]++
        val total = lum.size.toDouble()
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0.0; var bestVar = -1.0; var threshold = 127
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > bestVar) { bestVar = between; threshold = i }
        }
        return threshold
    }

    private fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    private fun convex(q: Quad): Boolean {
        val p = q.points
        val signs = p.indices.map { i -> cross(p[i], p[(i + 1) % 4], p[(i + 2) % 4]) }
        return signs.all { it > 0 } || signs.all { it < 0 }
    }

    /** Each corner between 50 and 130 degrees: a real sheet seen at an angle, not a sliver. */
    private fun anglesOk(q: Quad): Boolean {
        val p = q.points
        return p.indices.all { i ->
            val a = p[(i + 3) % 4]; val b = p[i]; val c = p[(i + 1) % 4]
            val v1x = a.x - b.x; val v1y = a.y - b.y; val v2x = c.x - b.x; val v2y = c.y - b.y
            val n = hypot(v1x, v1y) * hypot(v2x, v2y)
            if (n == 0.0) return false
            val deg = Math.toDegrees(kotlin.math.acos(((v1x * v2x + v1y * v2y) / n).coerceIn(-1.0, 1.0)))
            deg in 50.0..130.0
        }
    }
}
