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
            when (mimeType) {
                FileStore.MIME_PDF -> renderPdfPage(f, pageIndex, targetWidth)
                FileStore.MIME_XML -> renderInvoiceText(f, pageIndex, targetWidth)
                else -> decodeImage(f, targetWidth)
            }
        }

    /** The stored file's bytes (an e-invoice is read from its XML, not from pictures). */
    fun bytes(relativePath: String): ByteArray = files.file(relativePath).readBytes()

    /** The text a digital PDF carries on a page (see PdfText); null when there is none to use. */
    fun pdfText(relativePath: String, pageIndex: Int): List<com.kitchenreceipts.core.TextLayer.Glyph>? =
        com.kitchenreceipts.app.ocr.PdfText.glyphs(files.file(relativePath), pageIndex)

    /** An e-invoice page: the invoice laid out as text on a white sheet (A4 proportions). */
    private fun renderInvoiceText(f: File, pageIndex: Int, targetWidth: Int): Bitmap {
        val text = runCatching { com.kitchenreceipts.core.EInvoice.read(f.readBytes()).text }.getOrElse { "E-invoice could not be read: ${it.message}" }
        val pages = FileStore.textPages(text)
        val lines = pages.getOrElse(pageIndex) { emptyList() }
        val w = targetWidth.coerceIn(400, 2400)
        val h = (w * 1.414f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val margin = w / 24f
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = android.graphics.Typeface.MONOSPACE
        }
        // Font size so 90 characters fit the width, and the page's lines fit the height.
        paint.textSize = 10f
        val charW = paint.measureText("M") / 10f
        paint.textSize = minOf((w - 2 * margin) / (90 * charW), (h - 2 * margin) / (FileStore.TEXT_LINES_PER_PAGE * 1.25f))
        val step = paint.textSize * 1.25f
        var y = margin + paint.textSize
        lines.forEachIndexed { i, l ->
            paint.isFakeBoldText = pageIndex == 0 && i < 2
            canvas.drawText(l, margin, y, paint)
            y += step
        }
        return bmp
    }

    /**
     * A page as it is read: a photo is flattened like a scanner does (see PageFlattener) when its sheet can be found
     * safely; a PDF page is already flat. The OCR positions refer to this image.
     */
    suspend fun renderForReading(relativePath: String, mimeType: String, pageIndex: Int, targetWidth: Int): Bitmap {
        val bmp = renderPage(relativePath, mimeType, pageIndex, targetWidth)
        if (mimeType == FileStore.MIME_PDF || mimeType == FileStore.MIME_XML) return bmp
        val flat = withContext(Dispatchers.Default) { runCatching { com.kitchenreceipts.app.ocr.PageFlattener.flatten(bmp) }.getOrNull() }
            ?: return bmp
        bmp.recycle()
        return flat
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
