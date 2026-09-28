package com.kitchenreceipts.app.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Renders any page of a stored document (photo or PDF) to a Bitmap, for display and for OCR. */
class PageRenderer(private val files: FileStore) {

    // PdfRenderer allows only one open page at a time per renderer; keep PDF work serial.
    private val pdfLock = Mutex()

    suspend fun renderPage(relativePath: String, mimeType: String, pageIndex: Int, targetWidth: Int): Bitmap =
        withContext(Dispatchers.IO) {
            val f = files.file(relativePath)
            if (mimeType == FileStore.MIME_PDF) renderPdfPage(f, pageIndex, targetWidth) else decodeImage(f, targetWidth)
        }

    /** Decodes a photo respecting its EXIF orientation, downsampled so its long side is about [maxSide]. */
    fun decodeImage(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("The image cannot be decoded")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("The image cannot be decoded")
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return decoded
        }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (rotated != decoded) decoded.recycle()
        return rotated
    }

    private suspend fun renderPdfPage(f: File, pageIndex: Int, targetWidth: Int): Bitmap = pdfLock.withLock {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                if (pageIndex !in 0 until renderer.pageCount) throw IOException("Page ${pageIndex + 1} does not exist")
                val page = renderer.openPage(pageIndex)
                try {
                    val width = targetWidth.coerceIn(200, 2400)
                    val height = (page.height.toLong() * width / page.width.coerceAtLeast(1)).toInt().coerceIn(200, width * 4)
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE) // PDF pages are transparent; OCR needs dark text on white
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        }
    }
}
