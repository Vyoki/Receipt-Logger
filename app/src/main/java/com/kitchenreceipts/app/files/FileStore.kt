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
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PageExtractor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

/** What became of cutting a file of many invoices into one PDF per invoice (see FileStore.splitPdf). */
sealed interface SplitResult {
    /** One stored PDF per range, in the order of the ranges. */
    data class Done(val parts: List<StoredFile>) : SplitResult

    /** Nothing was kept: every part written before the failure was deleted. */
    data class Failed(val reason: SplitFailure) : SplitResult
}

/** What an import copied in (see FileStore.importUri). */
data class ImportResult(
    /** The documents to read: the photo or PDF, or each purchase invoice of an e-invoice file. */
    val files: List<StoredFile>,
    /** Credit notes in an e-invoice file: never imported (money back, not a purchase), only counted. */
    val skippedCreditNotes: Int = 0,
    /** For an e-invoice file: what each invoice in it says, credit notes too (with no file), in the file's order. */
    val einvoices: List<EInvoiceEntry> = emptyList(),
    /** The SHA-256 of the file as it came in (the same file shared again has the same one). */
    val sha256: String,
)

/** One invoice of an e-invoice file, its values exact from the XML. */
data class EInvoiceEntry(
    /** Its stored XML; null for a credit note (not stored). */
    val file: StoredFile?,
    val sellerVat: String?,
    val sellerName: String?,
    /** The buyer's VAT number or tax code, as written. */
    val buyerId: String?,
    val number: String?,
    val date: java.time.LocalDate?,
    val totalCents: Long?,
    val creditNote: Boolean,
    /** Its lines as "description amount" rows, for a category suggestion. */
    val rows: List<String> = emptyList(),
)

/** Why a file could not be cut; [text] says it in the operator's words. */
enum class SplitFailure(val text: Int) {
    STORAGE(com.kitchenreceipts.app.R.string.bundle_error_storage),
    LOCKED(com.kitchenreceipts.app.R.string.bundle_error_locked),
    DAMAGED(com.kitchenreceipts.app.R.string.bundle_error_damaged),
    /** Something else went wrong (a page range the file does not have, a part Android cannot open). */
    OTHER(com.kitchenreceipts.app.R.string.bundle_error_split),
}

/** A file the app cannot import; [text] says why in the operator's language (the message stays English, for the log). */
class UnsupportedFileException(message: String, val text: Int = 0, val arg: Any? = null) : IOException(message)

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
    suspend fun importUri(uri: Uri, resolver: ContentResolver = context.contentResolver): ImportResult = withContext(Dispatchers.IO) {
        val declared = resolver.getType(uri)
        val tmp = File(documentsDir, "import_${UUID.randomUUID()}.tmp")
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open the selected file")
            val sha = input.use { copyWithHash(it, tmp) }
            if (!isPhotoOrPdf(tmp)) {
                val entries = storeEInvoices(tmp.readBytes())
                tmp.delete()
                return@withContext ImportResult(
                    files = entries.mapNotNull { it.file }, skippedCreditNotes = entries.count { it.creditNote }, einvoices = entries, sha256 = sha,
                )
            }
            val mime = detectMime(tmp, declared)
            ImportResult(listOf(finish(tmp, mime, sha)), sha256 = sha)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /**
     * The invoices in an e-invoice file, one stored XML each. Credit notes are refunds, not purchases: left out (not
     * stored), but listed, so the operator is told how many.
     */
    private fun storeEInvoices(bytes: ByteArray): List<EInvoiceEntry> {
        val xmls = try {
            EInvoice.invoices(bytes)
        } catch (_: Exception) {
            throw UnsupportedFileException("Unsupported file type. Use a JPEG or PNG photo, a PDF, or an e-invoice (.xml, .p7m, .zip).", com.kitchenreceipts.app.R.string.file_unsupported)
        }
        if (xmls.isEmpty()) throw UnsupportedFileException("No e-invoice found in this file.", com.kitchenreceipts.app.R.string.file_no_einvoice)
        val reads = xmls.mapNotNull { x -> runCatching { x to EInvoice.read(x) }.getOrNull() }
        if (reads.isEmpty()) throw UnsupportedFileException("The e-invoice could not be read (it may be damaged).", com.kitchenreceipts.app.R.string.file_einvoice_damaged)
        if (reads.none { !it.second.creditNote }) throw UnsupportedFileException("This is a credit note (money back). Credit notes are not imported as purchases.", com.kitchenreceipts.app.R.string.file_credit_note)
        val written = mutableListOf<File>()
        try {
            return reads.map { (xml, read) ->
                val stored = if (read.creditNote) null else {
                    val target = File(documentsDir, "${UUID.randomUUID()}.xml")
                    written += target
                    target.writeBytes(xml)
                    val sha = target.inputStream().use { sha256(it) }
                    StoredFile("$DOCUMENTS_DIR/${target.name}", MIME_XML, textPages(read.text).size, sha)
                }
                val p = read.parsed
                EInvoiceEntry(
                    file = stored, sellerVat = read.sellerVat, sellerName = p.sellerName?.value, buyerId = read.buyerId,
                    number = p.documentNumber?.value, date = p.documentDate?.value, totalCents = p.totalCents?.value, creditNote = read.creditNote,
                    rows = p.lineItems.mapNotNull { li -> li.lineTotalCents?.value?.let { "${li.originalDescription} ${com.kitchenreceipts.core.ItalianNumbers.formatCents(it)}" } },
                )
            }
        } catch (e: Throwable) {
            // All or nothing: a phone that fills up half-way keeps no part of the file.
            written.forEach { it.delete() }
            throw e
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

    /**
     * Cuts a stored PDF of many invoices into one stored PDF per range of pages (0-based, both ends included),
     * reading the file once. Each part is the same bytes every time it is cut from the same file (its /ID is made
     * from the file's SHA-256 and the range, not from the clock), so the same invoice cut twice is found as a
     * duplicate by its file hash. All or nothing: when any part fails, the parts already written are deleted.
     */
    suspend fun splitPdf(bundle: StoredFile, ranges: List<IntRange>): SplitResult = withContext(Dispatchers.IO) {
        val written = mutableListOf<File>()
        try {
            val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(pdfboxTempDir())
            val parts = PDDocument.load(file(bundle.relativePath), memory).use { doc ->
                ranges.map { r ->
                    ensureActive()
                    if (r.isEmpty() || r.first < 0 || r.last >= doc.numberOfPages) throw IllegalArgumentException("pages ${r.first}-${r.last} of ${doc.numberOfPages}")
                    PageExtractor(doc, r.first + 1, r.last + 1).extract().use { part ->
                        val id = MessageDigest.getInstance("MD5").digest("${bundle.sha256}:${r.first}-${r.last}".toByteArray(Charsets.UTF_8))
                        part.document.trailer.setItem(COSName.ID, COSArray().apply { add(COSString(id)); add(COSString(id)) })
                        val tmp = File(documentsDir, "import_${UUID.randomUUID()}.tmp")
                        written += tmp
                        part.save(tmp)
                        val target = File(documentsDir, "${UUID.randomUUID()}.pdf")
                        if (!tmp.renameTo(target)) throw IOException("The part could not be put in place")
                        written += target
                        // Android must be able to show and read it, page for page.
                        if (pdfPageCount(target) != r.last - r.first + 1) throw IOException("The part has the wrong number of pages")
                        StoredFile("$DOCUMENTS_DIR/${target.name}", MIME_PDF, r.last - r.first + 1, target.inputStream().use { sha256(it) })
                    }
                }
            }
            SplitResult.Done(parts)
        } catch (e: Throwable) {
            written.forEach { it.delete() }
            if (e is kotlinx.coroutines.CancellationException || e is Error) throw e
            SplitResult.Failed(splitFailure(e))
        }
    }

    private fun splitFailure(e: Throwable): SplitFailure {
        val messages = generateSequence(e) { it.cause }.take(8).mapNotNull { it.message }.joinToString(" ")
        return when {
            generateSequence(e) { it.cause }.take(8).any { it is InvalidPasswordException } -> SplitFailure.LOCKED
            e is IllegalArgumentException -> SplitFailure.OTHER
            messages.contains("ENOSPC") || messages.contains("No space left", ignoreCase = true) ||
                documentsDir.usableSpace < LOW_SPACE_BYTES -> SplitFailure.STORAGE
            e is IOException -> SplitFailure.DAMAGED
            else -> SplitFailure.OTHER
        }
    }

    /**
     * An e-invoice already stored by [importUri] (one invoice of a file of many), as a document to read: null when it
     * is gone or cannot be read any more.
     */
    suspend fun storedXml(relativePath: String): StoredFile? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = file(relativePath).takeIf { it.isFile }?.readBytes() ?: return@runCatching null
            StoredFile(relativePath, MIME_XML, textPages(EInvoice.read(bytes).text).size, bytes.inputStream().use { sha256(it) })
        }.getOrNull()
    }

    /** Where the PDF reader keeps its temporary files while a large file is open (cleared by the caller when idle). */
    fun pdfboxTempDir(): File = File(context.cacheDir, PDFBOX_TEMP_DIR).apply { mkdirs() }

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
            // Android's PDF reader says so when the file needs a password to open.
            if (e is SecurityException) throw UnsupportedFileException("The PDF is protected by a password.", com.kitchenreceipts.app.R.string.bundle_error_locked)
            throw UnsupportedFileException("The PDF could not be opened (it may be damaged or password-protected).", com.kitchenreceipts.app.R.string.file_pdf_damaged)
        }
        if (pages < 1) {
            target.delete()
            throw UnsupportedFileException("The PDF has no pages.", com.kitchenreceipts.app.R.string.file_pdf_empty)
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
                if (total > MAX_BYTES) throw UnsupportedFileException("The file is larger than ${MAX_BYTES / 1024 / 1024} MB.", com.kitchenreceipts.app.R.string.file_too_large, MAX_BYTES / 1024 / 1024)
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        if (total == 0L) throw UnsupportedFileException("The file is empty.", com.kitchenreceipts.app.R.string.file_empty)
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
        /** Below this much free space a failed write is put down to a full phone. */
        private const val LOW_SPACE_BYTES = 5L * 1024 * 1024
        const val PDFBOX_TEMP_DIR = "pdfbox"
    }
}
