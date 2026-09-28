package com.kitchenreceipts.app.ocr

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Prepares a phone photo for a second, closer reading: grey scale, and every pixel compared with the
 * brightness around it (local contrast), which removes shadows, uneven light and the grey cast of thermal
 * paper, so faint digits (a "1" or a decimal comma) stand out. Runs on the phone, in well under a second.
 */
object ImageEnhancer {

    fun enhance(src: Bitmap, maxSide: Int = 2600): Bitmap {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        if (bmp !== src) bmp.recycle()

        // Luminance and its integral image (sums fit in Int for up to ~8 megapixels).
        val lum = ByteArray(w * h)
        val integral = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            for (x in 0 until w) {
                val c = px[y * w + x]
                val l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                lum[y * w + x] = l.toByte()
                rowSum += l
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + rowSum
            }
        }
        // Window about twice the height of a text line.
        val r = maxOf(8, minOf(w, h) / 40)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - r); val y1 = minOf(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - r); val x1 = minOf(w, x + r + 1)
                val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                val mean = sum / ((x1 - x0) * (y1 - y0))
                val l = lum[y * w + x].toInt() and 0xFF
                // Ink is darker than its surroundings: stretch the difference, paper becomes white.
                val v = (255 - (mean - l) * 4).coerceIn(0, 255)
                px[y * w + x] = Color.rgb(v, v, v)
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
