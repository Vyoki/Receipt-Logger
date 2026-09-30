package com.kitchenreceipts.app.ocr

import com.kitchenreceipts.app.files.PageRenderer
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.app.ai.AiPageReader
import com.kitchenreceipts.core.AiReader
import com.kitchenreceipts.core.AiTarget
import com.kitchenreceipts.core.AiTargets
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
    /** The AI's answers to the small questions about doubtful lines, header or totals (see AiTargets), in order. */
    val aiTargeted: List<String> = emptyList(),
    /** Width of the image each page was read from (the OCR boxes are in its pixels). */
    val ocrWidths: List<Int> = emptyList(),
    /** The AI's questions included the double-check. */
    val aiSpot: Boolean = false,
) {
    /** Plain-text report the user can share when a document is read badly. */
    fun debugReport(): String = buildString {
        append("Kitchen Receipts – recognised text\n")
        append("Engine: ").append(engineName).append(" · pages read: ").append(pagesRead).append('/').append(file.pageCount).append('\n')
        if (readingNote.isNotEmpty()) append("Reading: ").append(readingNote).append('\n')
        ocrError?.let { append("Error: ").append(it).append('\n') }
        aiNote?.let { append("AI reader: ").append(it).append('\n') }
        parsed.aiCheck?.let { c ->
            append("AI double-check: ").append(c.checked).append(" values, ").append(c.disagreements.size).append(" different\n")
            c.disagreements.forEach { append("  ").append(it).append('\n') }
        }
        append("\n=== Rows ===\n").append(ocrText).append('\n')
        aiRaw.forEachIndexed { p, raw -> if (raw.isNotBlank()) append("\n=== AI answer, page ").append(p + 1).append(" ===\n").append(raw).append('\n') }
        aiTargeted.forEachIndexed { i, raw -> append("\n=== AI answer, check ").append(i + 1).append(" ===\n").append(raw).append('\n') }
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

/** Progress of an import: which page of how many, and which reading (1 = photo, 2 = enhanced photo, 3 = AI whole page, 4 = AI checks). */
data class ImportProgress(val page: Int, val of: Int, val pass: Int, val aiStage: Int = 0, val aiCount: Int = 0)

/**
 * How the on-phone AI reader should be used for this import (null = not at all). [examples]: lines of the same
 * supplier the operator confirmed before, shown to the AI with each line question.
 */
class AiUse(
    val reader: suspend () -> AiPageReader,
    val always: Boolean,
    val examples: (ParsedDocument, String) -> List<AiReader.RowExample> = { _, _ -> emptyList() },
)

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
        val aiTargeted = mutableListOf<String>()
        var aiSpot = false
        if (ai != null && best.error == null && best.lines.isNotEmpty()) {
            // The AI always takes part: it asks about the doubtful parts (seconds each) and double-checks the header,
            // totals and key lines of every document before the operator sees it. Whole pages only when the regular
            // reading failed too broadly for that, or when the operator asked the AI to read everything.
            val targets = if (ai.always) null else withContext(Dispatchers.Default) { AiTargets.plan(best.lines, parsed, spotCheck = true) }
            aiSpot = targets != null
            aiNote = when {
                targets == null -> runAi(file, best, ai, options, onProgress, aiRaw, parsed)
                // Nothing the AI could prove (e.g. only an uncertain document number): no minutes spent on it.
                targets.isEmpty() -> "nothing to ask"
                else -> runTargets(file, best, ai, targets, onProgress, aiTargeted, ai.examples(parsed, best.text))
            }
        }
        return rebuild(
            file, best.lines, best.widths, best.error, engine.displayName, aiRaw, aiNote, aiTargeted,
            "pass ${best.pass} of $passes" + (if (best.pass == 2) " (enhanced image)" else ""),
            options, System.currentTimeMillis() - started, aiSpot,
        )
    }

    /** Asks the AI the small questions of [targets], collecting its answers in [answers]; returns a note for the log. */
    private suspend fun runTargets(
        file: StoredFile,
        best: Reading,
        ai: AiUse,
        targets: List<AiTarget>,
        onProgress: (ImportProgress) -> Unit,
        answers: MutableList<String>,
        examples: List<AiReader.RowExample> = emptyList(),
    ): String {
        onProgress(ImportProgress(1, targets.size, 4))
        val reader = try {
            ai.reader()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "not started: ${e.message}"
        }
        val notes = mutableListOf("checks ${targets.size} (" + targets.joinToString(",") { t ->
            when (t) {
                is AiTarget.Row -> t.itemIndex?.let { "line${it + 1}" } ?: "missed"
                is AiTarget.Choice -> "choice${t.itemIndex + 1}"
                is AiTarget.Number -> (if (t.verify) "check-" else "") + (if (t.field == AiTarget.Field.AMOUNT) "amount" else "qty") + "${t.itemIndex + 1}"
                is AiTarget.Header -> if (t.verify) "check-header" else "header"
                is AiTarget.Totals -> if (t.verify) "check-totals" else "totals"
            }
        } + ") lang=${AiReader.defaultLang} examples=${examples.size} " + reader.systemInfo)
        val started = System.currentTimeMillis()
        val pageCache = mutableMapOf<Int, android.graphics.Bitmap>()
        try {
            targets.forEachIndexed { i, t ->
                onProgress(ImportProgress(i + 1, targets.size, 4))
                val bmp = pageCache.getOrPut(t.page) { renderer.renderForReading(file.relativePath, file.mimeType, t.page, OCR_LONG_SIDE) }
                val (instruction, grammar) = when (t) {
                    is AiTarget.Row -> AiReader.rowInstruction(t.headerText, t.rowText, examples = examples) to AiReader.ROW_GRAMMAR
                    is AiTarget.Choice -> AiReader.choiceInstruction(t.headerText, t.rowText, t.choices) to AiReader.CHOICE_GRAMMAR
                    is AiTarget.Number -> AiReader.numberInstruction(t.column, t.headerText, t.rowText) to AiReader.NUMBER_GRAMMAR
                    is AiTarget.Header -> AiReader.headerInstruction() to AiReader.HEADER_GRAMMAR
                    is AiTarget.Totals -> AiReader.totalsInstruction() to AiReader.TOTALS_GRAMMAR
                }
                val r = reader.readRegions(bmp, best.lines[t.page], best.widths.getOrElse(t.page) { bmp.width }, t.boxes, instruction, grammar) { stage, count ->
                    onProgress(ImportProgress(i + 1, targets.size, 4, stage, count))
                }
                if (r.error == "cancelled") throw CancellationException("AI reading cancelled")
                answers += if (r.error == null) r.raw else ""
                notes += "c${i + 1}: ${r.millis / 100 / 10.0}s ${r.stats}" + (r.error?.let { " error=$it" } ?: "")
            }
        } finally {
            pageCache.values.forEach { it.recycle() }
            reader.close()
        }
        notes += "total ${(System.currentTimeMillis() - started) / 1000}s"
        return notes.joinToString("; ")
    }

    /**
     * Runs the AI on the pages that need it (all of them when it should always help), collecting its answers in
     * [raw] ("" for pages it did not read); returns a note for the log.
     */
    private suspend fun runAi(
        file: StoredFile,
        best: Reading,
        ai: AiUse,
        options: ParseOptions,
        onProgress: (ImportProgress) -> Unit,
        raw: MutableList<String>,
        whole: ParsedDocument,
    ): String {
        val pages = best.lines.size
        val selected = if (ai.always) (0 until pages).toList() else withContext(Dispatchers.Default) {
            AiReader.pagesToRead(best.lines.map { ReceiptParser.parsePages(listOf(it), options) }, whole)
        }
        repeat(pages) { raw += "" }
        onProgress(ImportProgress(selected.first() + 1, pages, 3))
        val reader = try {
            ai.reader()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "not started: ${e.message}"
        }
        val notes = mutableListOf("pages ${selected.joinToString(",") { "${it + 1}" }} of $pages")
        try {
            for (i in selected) {
                val bmp = renderer.renderForReading(file.relativePath, file.mimeType, i, OCR_LONG_SIDE)
                val r = try {
                    reader.read(bmp, best.lines[i], best.widths.getOrElse(i) { bmp.width }, LayoutRows.toText(best.lines[i]), options) { stage, count ->
                        onProgress(ImportProgress(i + 1, pages, 3, stage, count))
                    }
                } finally {
                    bmp.recycle()
                }
                raw[i] = if (r.error == null) r.raw else ""
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
                val bmp = renderer.renderForReading(file.relativePath, file.mimeType, i, OCR_LONG_SIDE)
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
            aiTargeted: List<String>,
            readingNote: String,
            options: ParseOptions,
            ocrMillis: Long,
            /** The AI's questions included the double-check (so they are planned the same way again). */
            aiSpot: Boolean = false,
        ): PendingImport = withContext(Dispatchers.Default) {
            val pageTexts = lines.map { LayoutRows.toText(it) }
            val text = pageTexts.joinToString("\n${ReceiptParser.PAGE_BREAK}\n")
            var parsed = if (lines.all { it.isEmpty() }) ParsedDocument.EMPTY else ReceiptParser.parsePages(lines, options)
            if (aiTargeted.isNotEmpty()) {
                // The questions are worked out again from the same reading, so they pair up with the stored answers.
                val targets = AiTargets.plan(lines, parsed, spotCheck = aiSpot)
                if (targets != null && targets.size == aiTargeted.size) {
                    parsed = AiReader.applyTargets(parsed, targets.zip(aiTargeted).filter { it.second.isNotBlank() }, text, options)
                }
            }
            if (aiRaw.size == lines.size && aiRaw.any { it.isNotBlank() }) {
                val aiPages = aiRaw.mapIndexed { i, raw ->
                    raw.takeIf { it.isNotBlank() }?.let { AiReader.decode(it) }?.let { AiReader.toParsed(it, pageTexts[i], options) }
                }
                val joined = AiReader.joinPages(aiPages.filterNotNull(), text)
                if (joined != null) {
                    // Lines page by page (AI where it read the page and did better), header and totals from both readings.
                    val regularPages = lines.map { ReceiptParser.parsePages(listOf(it), options) }
                    parsed = AiReader.merge(parsed, joined.copy(lineItems = AiReader.combineItems(regularPages, aiPages)), text)
                }
            }
            PendingImport(
                file, text, parsed, engineName, lines.size, error, lines, ocrMillis,
                readingNote = "$readingNote, items read by ${parsed.itemsReadBy}",
                aiNote = aiNote,
                aiRaw = aiRaw,
                aiTargeted = aiTargeted,
                ocrWidths = widths,
                aiSpot = aiSpot,
            )
        }

        const val MAX_OCR_PAGES = 20
        const val OCR_LONG_SIDE = 2400
    }
}
