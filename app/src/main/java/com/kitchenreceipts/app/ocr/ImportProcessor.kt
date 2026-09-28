package com.kitchenreceipts.app.ocr

import com.kitchenreceipts.app.files.PageRenderer
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.core.LayoutRows
import com.kitchenreceipts.core.OcrLine
import com.kitchenreceipts.core.ParseOptions
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
    /** Time spent rendering pages and running OCR. */
    val ocrMillis: Long = 0,
    /** Which reading was kept ("pass 2 of 2 (enhanced image), items read by columns"). */
    val readingNote: String = "",
) {
    /** Plain-text report the user can share when a document is read badly. */
    fun debugReport(): String = buildString {
        append("Kitchen Receipts – recognised text\n")
        append("Engine: ").append(engineName).append(" · pages read: ").append(pagesRead).append('/').append(file.pageCount).append('\n')
        if (readingNote.isNotEmpty()) append("Reading: ").append(readingNote).append('\n')
        ocrError?.let { append("Error: ").append(it).append('\n') }
        append("\n=== Rows ===\n").append(ocrText).append('\n')
        rawLines.forEachIndexed { p, lines ->
            append("\n=== Raw lines, page ").append(p + 1).append(" (left,top,right,bottom,angle) ===\n")
            lines.forEach { l ->
                append("${l.left},${l.top},${l.right},${l.bottom},${"%.1f".format(java.util.Locale.ROOT, l.angle)} | ${l.text}")
                // Word positions, so a column-reading problem can be reproduced exactly.
                if (l.words.size > 1) append("  [").append(l.words.joinToString(" ") { w -> "${w.left}-${w.right}:${w.text}" }).append(']')
                append('\n')
            }
        }
    }
}

class ImportProcessor(private val renderer: PageRenderer) {

    /**
     * Reads a stored document. Pass 1 reads each page as photographed. If the result does not fully check out
     * (a line where quantity x price differs from the amount, lines not adding up to the total, a missing date...),
     * pass 2 reads the pages again after [ImageEnhancer] has removed shadows and boosted faint print, and the
     * better of the two readings is kept. Both passes run on the phone.
     */
    suspend fun process(
        file: StoredFile,
        engine: OcrEngine,
        onProgress: (page: Int, of: Int, pass: Int) -> Unit = { _, _, _ -> },
        options: ParseOptions = ParseOptions(),
    ): PendingImport {
        val started = System.currentTimeMillis()
        val pages = minOf(file.pageCount, MAX_OCR_PAGES)
        val first = readAll(file, engine, pages, 1, onProgress)
        var best = first
        var passes = 1
        if (first.error == null && first.lines.isNotEmpty() && !ReceiptParser.isConfident(first.parsed(options)) && engine.worksOffline && engine !== NoOcrEngine) {
            val second = runCatching { readAll(file, engine, first.lines.size, 2, onProgress) }.getOrNull()
            passes = 2
            if (second != null && second.error == null &&
                ReceiptParser.quality(second.parsed(options)) > ReceiptParser.quality(first.parsed(options))
            ) {
                best = second
            }
        }
        val parsed = best.parsed(options)
        return PendingImport(
            file, best.text, parsed, engine.displayName, best.lines.size, best.error, best.lines,
            System.currentTimeMillis() - started,
            readingNote = "pass ${best.pass} of $passes" + (if (best.pass == 2) " (enhanced image)" else "") + ", items read by ${parsed.itemsReadBy}",
        )
    }

    private inner class Reading(val lines: List<List<OcrLine>>, val error: String?, val pass: Int) {
        private var cache: ParsedDocument? = null
        val text: String get() = lines.joinToString("\n${ReceiptParser.PAGE_BREAK}\n") { LayoutRows.toText(it) }
        suspend fun parsed(options: ParseOptions): ParsedDocument = cache ?: withContext(Dispatchers.Default) {
            if (lines.all { it.isEmpty() }) ParsedDocument.EMPTY else ReceiptParser.parsePages(lines, options)
        }.also { cache = it }
    }

    private suspend fun readAll(
        file: StoredFile,
        engine: OcrEngine,
        pages: Int,
        pass: Int,
        onProgress: (Int, Int, Int) -> Unit,
    ): Reading {
        val raw = mutableListOf<List<OcrLine>>()
        var error: String? = null
        for (i in 0 until pages) {
            onProgress(i + 1, pages, pass)
            try {
                val bmp = renderer.renderPage(file.relativePath, file.mimeType, i, OCR_LONG_SIDE)
                try {
                    if (pass == 1) {
                        raw += engine.recognize(bmp)
                    } else {
                        val enhanced = withContext(Dispatchers.Default) { ImageEnhancer.enhance(bmp) }
                        try { raw += engine.recognize(enhanced) } finally { enhanced.recycle() }
                    }
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
        return Reading(raw, error, pass)
    }

    companion object {
        const val MAX_OCR_PAGES = 20
        const val OCR_LONG_SIDE = 2400
    }
}
