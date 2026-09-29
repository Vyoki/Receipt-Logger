package com.kitchenreceipts.app.ocr

import com.kitchenreceipts.app.files.PageRenderer
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.app.ai.AiPageReader
import com.kitchenreceipts.core.AiReader
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
    /** What the on-phone AI reader did, if it ran (per page: time, size of the answer, errors). */
    val aiNote: String? = null,
    /** The AI's raw answers, page by page (for troubleshooting, and to rebuild the reading after a restart). */
    val aiRaw: List<String> = emptyList(),
    /** Width of the image each page was read from (the OCR boxes are in its pixels). */
    val ocrWidths: List<Int> = emptyList(),
) {
    /** Plain-text report the user can share when a document is read badly. */
    fun debugReport(): String = buildString {
        append("Kitchen Receipts – recognised text\n")
        append("Engine: ").append(engineName).append(" · pages read: ").append(pagesRead).append('/').append(file.pageCount).append('\n')
        if (readingNote.isNotEmpty()) append("Reading: ").append(readingNote).append('\n')
        ocrError?.let { append("Error: ").append(it).append('\n') }
        aiNote?.let { append("AI reader: ").append(it).append('\n') }
        append("\n=== Rows ===\n").append(ocrText).append('\n')
        aiRaw.forEachIndexed { p, raw -> append("\n=== AI answer, page ").append(p + 1).append(" ===\n").append(raw).append('\n') }
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

/** Progress of an import: which page of how many, and which reading (1 = photo, 2 = enhanced photo, 3 = AI). */
data class ImportProgress(val page: Int, val of: Int, val pass: Int, val aiStage: Int = 0, val aiCount: Int = 0)

/** How the on-phone AI reader should be used for this import (null = not at all). */
class AiUse(val reader: suspend () -> AiPageReader, val always: Boolean)

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
        onProgress: (ImportProgress) -> Unit = {},
        options: ParseOptions = ParseOptions(),
        ai: AiUse? = null,
    ): PendingImport {
        val started = System.currentTimeMillis()
        val pages = minOf(file.pageCount, MAX_OCR_PAGES)
        val first = readAll(file, engine, pages, 1) { p, of -> onProgress(ImportProgress(p, of, 1)) }
        var best = first
        var passes = 1
        if (first.error == null && first.lines.isNotEmpty() && !ReceiptParser.isConfident(first.parsed(options)) && engine.worksOffline && engine !== NoOcrEngine) {
            val second = try {
                readAll(file, engine, first.lines.size, 2) { p, of -> onProgress(ImportProgress(p, of, 2)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            passes = 2
            if (second != null && second.error == null &&
                ReceiptParser.quality(second.parsed(options)) > ReceiptParser.quality(first.parsed(options))
            ) {
                best = second
            }
        }
        val parsed = best.parsed(options)
        // The on-phone AI reader: only once every page has been read, and only when asked to always help or when
        // the regular readings still do not check out.
        var aiNote: String? = null
        val aiRaw = mutableListOf<String>()
        if (ai != null && best.error == null && best.lines.isNotEmpty() && (ai.always || !ReceiptParser.isConfident(parsed))) {
            aiNote = runAi(file, best, ai, options, onProgress, aiRaw)
        }
        return rebuild(
            file, best.lines, best.widths, best.error, engine.displayName, aiRaw, aiNote,
            "pass ${best.pass} of $passes" + (if (best.pass == 2) " (enhanced image)" else ""),
            options, System.currentTimeMillis() - started,
        )
    }

    /** Runs the AI on every read page, collecting its answers in [raw]; returns a note for the log. */
    private suspend fun runAi(
        file: StoredFile,
        best: Reading,
        ai: AiUse,
        options: ParseOptions,
        onProgress: (ImportProgress) -> Unit,
        raw: MutableList<String>,
    ): String {
        val pages = best.lines.size
        onProgress(ImportProgress(1, pages, 3))
        val reader = try {
            ai.reader()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "not started: ${e.message}"
        }
        val notes = mutableListOf<String>()
        try {
            for (i in 0 until pages) {
                val bmp = renderer.renderPage(file.relativePath, file.mimeType, i, OCR_LONG_SIDE)
                val r = try {
                    reader.read(bmp, best.lines[i], best.widths.getOrElse(i) { bmp.width }, LayoutRows.toText(best.lines[i]), options) { stage, count ->
                        onProgress(ImportProgress(i + 1, pages, 3, stage, count))
                    }
                } finally {
                    bmp.recycle()
                }
                raw += if (r.error == null) r.raw else ""
                notes += "p${i + 1}: ${r.millis / 1000}s ${r.stats}" + (r.error?.let { " error=$it" } ?: "") +
                    (r.parsed?.let { " items=${it.lineItems.size}" } ?: "")
                if (r.error == "cancelled") throw CancellationException("AI reading cancelled")
            }
        } finally {
            reader.close()
        }
        return notes.joinToString("; ")
    }

    private inner class Reading(val lines: List<List<OcrLine>>, val widths: List<Int>, val error: String?, val pass: Int) {
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
        onProgress: (Int, Int) -> Unit,
    ): Reading {
        val raw = mutableListOf<List<OcrLine>>()
        val widths = mutableListOf<Int>()
        var error: String? = null
        for (i in 0 until pages) {
            onProgress(i + 1, pages)
            try {
                val bmp = renderer.renderPage(file.relativePath, file.mimeType, i, OCR_LONG_SIDE)
                try {
                    if (pass == 1) {
                        raw += engine.recognize(bmp)
                        widths += bmp.width
                    } else {
                        val enhanced = withContext(Dispatchers.Default) { ImageEnhancer.enhance(bmp) }
                        try { raw += engine.recognize(enhanced); widths += enhanced.width } finally { enhanced.recycle() }
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
        return Reading(raw, widths, error, pass)
    }

    companion object {
        /**
         * The final reading from what was read: the regular reading of [lines], improved by the AI's answers when
         * there is one for every page. Also used to rebuild a background reading after the app restarts.
         */
        suspend fun rebuild(
            file: StoredFile,
            lines: List<List<OcrLine>>,
            widths: List<Int>,
            error: String?,
            engineName: String,
            aiRaw: List<String>,
            aiNote: String?,
            readingNote: String,
            options: ParseOptions,
            ocrMillis: Long,
        ): PendingImport = withContext(Dispatchers.Default) {
            val pageTexts = lines.map { LayoutRows.toText(it) }
            val text = pageTexts.joinToString("\n${ReceiptParser.PAGE_BREAK}\n")
            var parsed = if (lines.all { it.isEmpty() }) ParsedDocument.EMPTY else ReceiptParser.parsePages(lines, options)
            if (aiRaw.size == lines.size && aiRaw.isNotEmpty()) {
                val pages = aiRaw.mapIndexed { i, raw -> AiReader.decode(raw)?.let { AiReader.toParsed(it, pageTexts[i], options) } }
                if (pages.all { it != null }) {
                    AiReader.joinPages(pages.filterNotNull(), text)?.let { parsed = AiReader.merge(parsed, it, text) }
                }
            }
            PendingImport(
                file, text, parsed, engineName, lines.size, error, lines, ocrMillis,
                readingNote = "$readingNote, items read by ${parsed.itemsReadBy}",
                aiNote = aiNote,
                aiRaw = aiRaw,
                ocrWidths = widths,
            )
        }

        const val MAX_OCR_PAGES = 20
        const val OCR_LONG_SIDE = 2400
    }
}
