package com.kitchenreceipts.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.kitchenreceipts.core.PageCorners

/**
 * Scanner-style flattening of a page photo, on the phone: finds the sheet's corners (core PageCorners) and turns
 * the sheet into a straight rectangle, cutting away the table around it. Returns null when the page cannot be
 * found safely; the photo is then read as it is. The same photo always gives the same result, so the positions
 * the OCR found can be shown on it again later.
 */
object PageFlattener {

    private const val PROBE_SIDE = 640

    fun flatten(src: Bitmap): Bitmap? {
        val scale = PROBE_SIDE.toDouble() / maxOf(src.width, src.height)
        if (scale >= 1.0) return null
        val sw = (src.width * scale).toInt().coerceAtLeast(1)
        val sh = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()
        val lum = IntArray(px.size) { i ->
            val c = px[i]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        val found = PageCorners.find(lum, sw, sh) ?: return null
        val q = found.scaled(1.0 / scale).grown(0.015)
        fun cx(v: Double) = v.coerceIn(0.0, src.width - 1.0).toFloat()
        fun cy(v: Double) = v.coerceIn(0.0, src.height - 1.0).toFloat()
        val from = q.points.flatMap { listOf(cx(it.x), cy(it.y)) }.toFloatArray()
        // Output as large as the sheet was in the photo, never larger than the photo itself.
        var outW = q.outWidth
        var outH = q.outHeight
        val cap = maxOf(src.width, src.height).toDouble()
        val k = minOf(1.0, cap / maxOf(outW, outH))
        outW *= k; outH *= k
        val w = outW.toInt().coerceAtLeast(100)
        val h = outH.toInt().coerceAtLeast(100)
        val to = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(from, 0, to, 0, 4)) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }
}
