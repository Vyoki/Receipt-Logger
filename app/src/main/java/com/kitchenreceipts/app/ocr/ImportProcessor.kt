package com.kitchenreceipts.app.ocr

import com.kitchenreceipts.app.files.PageRenderer
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.core.LayoutRows
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
)

class ImportProcessor(private val renderer: PageRenderer) {

    suspend fun process(
        file: StoredFile,
        engine: OcrEngine,
        onProgress: (page: Int, of: Int) -> Unit = { _, _ -> },
    ): PendingImport {
        val pages = minOf(file.pageCount, MAX_OCR_PAGES)
        val texts = mutableListOf<String>()
        var error: String? = null
        for (i in 0 until pages) {
            onProgress(i + 1, pages)
            try {
                val bmp = renderer.renderPage(file.relativePath, file.mimeType, i, OCR_LONG_SIDE)
                try {
                    texts += LayoutRows.toText(engine.recognize(bmp))
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
        return PendingImport(file, text, parsed, engine.displayName, texts.size, error)
    }

    companion object {
        const val MAX_OCR_PAGES = 20
        const val OCR_LONG_SIDE = 2400
    }
}
