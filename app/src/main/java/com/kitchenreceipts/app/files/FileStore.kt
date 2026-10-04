package com.kitchenreceipts.app.files

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import com.kitchenreceipts.core.EInvoice
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** An original document copied into app-private storage. */
data class StoredFile(
    /** Relative to Context.filesDir, e.g. "documents/2f1c....pdf". */
    val relativePath: String,
    val mimeType: String,
    val pageCount: Int,
    val sha256: String,
)

class UnsupportedFileException(message: String) : IOException(message)

/**
 * Keeps every imported image/PDF in the app's private files directory so saved records can
 * always show their original, even offline and after the source file was deleted from the phone.
 */
class FileStore(private val context: Context) {

    private val documentsDir: File get() = File(context.filesDir, DOCUMENTS_DIR).apply { mkdirs() }
    private val capturesDir: File get() = File(context.cacheDir, "captures").apply { mkdirs() }

    fun file(relativePath: String): File = File(context.filesDir, relativePath)

    /** A fresh file + content:// Uri for the camera app to write a photo into. */
    fun newCaptureTarget(): Pair<File, Uri> {
        val f = File(capturesDir, "capture_${System.currentTimeMillis()}.jpg")
        return f to FileProvider.getUriForFile(context, authority(), f)
    }

    fun uriForSharing(relativePath: String): Uri = FileProvider.getUriForFile(context, authority(), file(relativePath))

    /**
     * Copies a picked or shared file (content:// Uri) into private storage: an image, a PDF, or e-invoices
     * (FatturaPA .xml, signed .p7m, or a .zip of several), each invoice stored as its own XML document.
     */
    suspend fun importUri(uri: Uri, resolver: ContentResolver = context.contentResolver): List<StoredFile> = withContext(Dispatchers.IO) {
        val declared = resolver.getType(uri)
        val tmp = File(documentsDir, "import_${UUID.randomUUID()}.tmp")
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open the selected file")
            val sha = input.use { copyWithHash(it, tmp) }
            if (!isPhotoOrPdf(tmp)) {
                val stored = storeEInvoices(tmp.readBytes())
                tmp.delete()
                return@withContext stored
            }
            val mime = detectMime(tmp, declared)
            listOf(finish(tmp, mime, sha))
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /** The invoices in an e-invoice file, one stored XML each. Credit notes are refunds, not purchases: left out. */
    private fun storeEInvoices(bytes: ByteArray): List<StoredFile> {
        val xmls = try {
            EInvoice.invoices(bytes)
        } catch (_: Exception) {
            throw UnsupportedFileException("Unsupported file type. Use a JPEG or PNG photo, a PDF, or an e-invoice (.xml, .p7m, .zip).")
        }
        if (xmls.isEmpty()) throw UnsupportedFileException("No e-invoice found in this file.")
        val reads = xmls.mapNotNull { x -> runCatching { x to EInvoice.read(x) }.getOrNull() }
        if (reads.isEmpty()) throw UnsupportedFileException("The e-invoice could not be read (it may be damaged).")
        val purchases = reads.filter { !it.second.creditNote }
        if (purchases.isEmpty()) throw UnsupportedFileException("This is a credit note (money back). Credit notes are not imported as purchases.")
        return purchases.map { (xml, read) ->
            val target = File(documentsDir, "${UUID.randomUUID()}.xml")
            target.writeBytes(xml)
            val sha = target.inputStream().use { sha256(it) }
            StoredFile("$DOCUMENTS_DIR/${target.name}", MIME_XML, textPages(read.text).size, sha)
        }
    }

    private fun isPhotoOrPdf(f: File): Boolean {
        val head = ByteArray(4)
        val n = f.inputStream().use { it.read(head) }
        if (n < 3) return false
        return (head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte()) ||
            (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) ||
            (n >= 4 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte())
    }

    /**
     * Stores camera photos. One photo is kept as the original JPEG; several photos (a long receipt
     * or a multi-page invoice) are combined into one PDF, one page per photo, in order.
     */
    suspend fun storePhotos(photos: List<File>, decode: (File) -> Bitmap): StoredFile = withContext(Dispatchers.IO) {
        require(photos.isNotEmpty())
        if (photos.size == 1) {
            val tmp = File(documentsDir, "import_${UUID.randomUUID()}.tmp")
            val sha = photos[0].inputStream().use { copyWithHash(it, tmp) }
            return@withContext finish(tmp, MIME_JPEG, sha)
        }
        val pdf = PdfDocument()
        val tmp = File(documentsDir, "import_${UUID.randomUUID()}.tmp")
        try {
            photos.forEachIndexed { i, photo ->
                val bmp = decode(photo)
                try {
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(bmp, 0f, 0f, null)
                    pdf.finishPage(page)
                } finally {
                    bmp.recycle()
                }
            }
            tmp.outputStream().use { pdf.writeTo(it) }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        } finally {
            pdf.close()
        }
        val sha = tmp.inputStream().use { sha256(it) }
        finish(tmp, MIME_PDF, sha)
    }

    fun delete(relativePath: String) {
        file(relativePath).delete()
    }

    fun deleteCaptures() {
        capturesDir.listFiles()?.forEach { it.delete() }
    }

    /** Removes files no saved document refers to (abandoned imports), older than one hour. */
    fun deleteOrphans(referenced: Set<String>) {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000
        documentsDir.listFiles()?.forEach { f ->
            val rel = "$DOCUMENTS_DIR/${f.name}"
            if (rel !in referenced && f.lastModified() < cutoff) f.delete()
        }
        capturesDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun finish(tmp: File, mime: String, sha: String): StoredFile {
        val ext = if (mime == MIME_PDF) "pdf" else if (mime == MIME_PNG) "png" else "jpg"
        val target = File(documentsDir, "${UUID.randomUUID()}.$ext")
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        val pages = try {
            if (mime == MIME_PDF) pdfPageCount(target) else 1
        } catch (e: Exception) {
            target.delete()
            throw UnsupportedFileException("The PDF could not be opened (it may be damaged or password-protected).")
        }
        if (pages < 1) {
            target.delete()
            throw UnsupportedFileException("The PDF has no pages.")
        }
        return StoredFile("$DOCUMENTS_DIR/${target.name}", mime, pages, sha)
    }

    private fun copyWithHash(input: InputStream, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw UnsupportedFileException("The file is larger than ${MAX_BYTES / 1024 / 1024} MB.")
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        if (total == 0L) throw UnsupportedFileException("The file is empty.")
        return digest.digest().joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }

    /** Trusts the file's first bytes over the declared type. */
    private fun detectMime(f: File, declared: String?): String {
        val head = ByteArray(8)
        val n = f.inputStream().use { it.read(head) }
        return when {
            n >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte() -> MIME_PDF
            n >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> MIME_JPEG
            n >= 4 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte() -> MIME_PNG
            else -> throw UnsupportedFileException(
                "Unsupported file type${declared?.let { " ($it)" } ?: ""}. Use a JPEG or PNG photo, or a PDF.",
            )
        }
    }

    private fun pdfPageCount(f: File): Int =
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                renderer.pageCount
            } finally {
                renderer.close()
            }
        }

    private fun authority() = "${context.packageName}.fileprovider"

    companion object {
        const val DOCUMENTS_DIR = "documents"
        const val MIME_PDF = "application/pdf"
        const val MIME_JPEG = "image/jpeg"
        const val MIME_PNG = "image/png"
        /** An e-invoice, stored as its XML; shown as the invoice laid out as text. */
        const val MIME_XML = "application/xml"
        const val TEXT_LINES_PER_PAGE = 60

        /** The e-invoice text split into pages for display (long descriptions wrapped). */
        fun textPages(text: String, width: Int = 90): List<List<String>> {
            val lines = text.lines().flatMap { l -> if (l.length <= width) listOf(l) else l.chunked(width) }
            return lines.chunked(TEXT_LINES_PER_PAGE).ifEmpty { listOf(emptyList()) }
        }
        const val MAX_BYTES = 50L * 1024 * 1024
    }
}
