package com.kitchenreceipts.app.ocr

import com.kitchenreceipts.app.files.PageRenderer
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.core.LayoutRows
import com.kitchenreceipts.core.OcrLine
import com.kitchenreceipts.core.ParsedDocument
import com.kitchenreceipts.core.ReceiptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** A stored file that has been read and parsed, waiting for the user's review. Nothing is saved yet. */
data class PendingImport(
    val file: StoredFile,
    val ocrText: String,
    val parsed: ParsedDocument,
    val engineName: String,
    val pagesRead: Int,
    /** Set when OCR failed or was skipped; the user fills the fields by hand. */
    val ocrError: String?,
    /** What the engine returned, page by page, before regrouping into rows (for troubleshooting). */
    val rawLines: List<List<OcrLine>> = emptyList(),
) {
    /** Plain-text report the user can share when a document is read badly. */
    fun debugReport(): String = buildString {
        append("Kitchen Receipts – recognised text\n")
        append("Engine: ").append(engineName).append(" · pages read: ").append(pagesRead).append('/').append(file.pageCount).append('\n')
        ocrError?.let { append("Error: ").append(it).append('\n') }
        append("\n=== Rows ===\n").append(ocrText).append('\n')
        rawLines.forEachIndexed { p, lines ->
            append("\n=== Raw lines, page ").append(p + 1).append(" (left,top,right,bottom,angle) ===\n")
            lines.forEach { l -> append("${l.left},${l.top},${l.right},${l.bottom},${"%.1f".format(java.util.Locale.ROOT, l.angle)} | ${l.text}\n") }
        }
    }
}

class ImportProcessor(private val renderer: PageRenderer) {

    suspend fun process(
        file: StoredFile,
        engine: OcrEngine,
        onProgress: (page: Int, of: Int) -> Unit = { _, _ -> },
    ): PendingImport {
        val pages = minOf(file.pageCount, MAX_OCR_PAGES)
        val texts = mutableListOf<String>()
        val raw = mutableListOf<List<OcrLine>>()
        var error: String? = null
        for (i in 0 until pages) {
            onProgress(i + 1, pages)
            try {
                val bmp = renderer.renderPage(file.relativePath, file.mimeType, i, OCR_LONG_SIDE)
                try {
                    val lines = engine.recognize(bmp)
                    raw += lines
                    texts += LayoutRows.toText(lines)
                } finally {
                    bmp.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                break
            }
        }
        val text = texts.joinToString("\n")
        val parsed = withContext(Dispatchers.Default) {
            if (text.isBlank()) ParsedDocument.EMPTY else ReceiptParser.parse(text)
        }
        return PendingImport(file, text, parsed, engine.displayName, texts.size, error, raw)
    }

    companion object {
        const val MAX_OCR_PAGES = 20
        const val OCR_LONG_SIDE = 2400
    }
}
