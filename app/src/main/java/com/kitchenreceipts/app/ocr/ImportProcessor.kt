package com.kitchenreceipts.app.ocr

import com.kitchenreceipts.app.files.PageRenderer
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.app.ai.AiPageReader
import com.kitchenreceipts.core.AiReader
import com.kitchenreceipts.core.AiTarget
import com.kitchenreceipts.core.AiTargets
import com.kitchenreceipts.core.LayoutQuestion
import com.kitchenreceipts.core.LayoutRows
import com.kitchenreceipts.core.OcrLine
import com.kitchenreceipts.core.ParseOptions
import com.kitchenreceipts.core.ParsedDocument
import com.kitchenreceipts.core.ReceiptParser
import com.kitchenreceipts.core.SupplierLayout
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
    /** The AI's answer about column headings the app did not know (encoded SupplierLayout), if it was used. */
    val aiLayout: String? = null,
) {
    /** Plain-text report the user can share when a document is read badly. */
    fun debugReport(): String = buildString {
        append("Kitchen Receipts – recognised text\n")
        append("App: ").append(appVersion).append('\n')
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
                val conf = if (l.confidence < 1f) ",${"%.2f".format(java.util.Locale.ROOT, l.confidence)}" else ""
                append("${l.left},${l.top},${l.right},${l.bottom},${"%.1f".format(java.util.Locale.ROOT, l.angle)}$conf | ${l.text}")
                // Word positions (and how sure the OCR was, when unsure), so a reading problem can be reproduced exactly.
                if (l.words.size > 1 || l.words.any { it.confidence < 0.8f }) append("  [").append(l.words.joinToString(" ") { w ->
                    "${w.left}-${w.right}:${w.text}" + if (w.confidence < 0.8f) "@${"%.2f".format(java.util.Locale.ROOT, w.confidence)}" else ""
                }).append(']')
                append('\n')
            }
        }
    }
}

/** "0.1.0+abc1234": the build (CI commit) that read a document, set when the app starts. */
@Volatile var appVersion: String = "?"

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
     * The page image being worked on, kept for the next step that needs the same page (OCR pass 2, the AI) instead
     * of decoding and flattening the photo again. One page at a time: asking for another frees the previous one.
     */
    private inner class PageImages(private val file: StoredFile) {
        private var page = -1
        private var bmp: android.graphics.Bitmap? = null

        suspend fun get(i: Int): android.graphics.Bitmap {
            bmp?.let { if (page == i && !it.isRecycled) return it }
            release()
            return renderer.renderForReading(file.relativePath, file.mimeType, i, OCR_LONG_SIDE).also { bmp = it; page = i }
        }

        /** The PDF's own text of each page (see PdfText), read once for every pass; null = none to use. */
        private val text = HashMap<Int, List<com.kitchenreceipts.core.TextLayer.Glyph>?>()

        suspend fun textOf(i: Int): List<com.kitchenreceipts.core.TextLayer.Glyph>? {
            if (file.mimeType != com.kitchenreceipts.app.files.FileStore.MIME_PDF) return null
            return text.getOrPut(i) { withContext(Dispatchers.IO) { renderer.pdfText(file.relativePath, i) } }
        }

        fun release() {
            bmp?.recycle()
            bmp = null
            page = -1
        }
    }

    /** The AI model, loaded once for the whole document (loading takes seconds and a lot of memory). */
    private class SharedReader(private val ai: AiUse) {
        private var reader: AiPageReader? = null
        private var failure: Exception? = null

        /** The loaded model, or the reason it could not be loaded. */
        suspend fun get(): Result<AiPageReader> {
            reader?.let { return Result.success(it) }
            failure?.let { return Result.failure(it) }
            return try {
                Result.success(ai.reader().also { reader = it })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                Result.failure(e)
            }
        }

        fun close() {
            reader?.close()
            reader = null
        }
    }

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
        if (file.mimeType == com.kitchenreceipts.app.files.FileStore.MIME_XML) {
            // An e-invoice: every value is in the XML. No OCR, no AI, nothing guessed.
            onProgress(ImportProgress(1, 1, 1))
            val read = withContext(Dispatchers.Default) { com.kitchenreceipts.core.EInvoice.read(renderer.bytes(file.relativePath), options.ownVatNumber) }
            return PendingImport(
                file = file,
                ocrText = read.text,
                parsed = read.parsed,
                engineName = "E-invoice (XML)",
                pagesRead = file.pageCount,
                ocrError = null,
                ocrMillis = System.currentTimeMillis() - started,
                readingNote = "read from the e-invoice XML" + (read.documentType?.let { " ($it)" } ?: ""),
            )
        }
        val pages = minOf(file.pageCount, MAX_OCR_PAGES)
        val images = PageImages(file)
        val shared = ai?.let { SharedReader(it) }
        try {
            return processPages(file, engine, onProgress, options, ai, pages, images, shared, started)
        } finally {
            images.release()
            shared?.close()
        }
    }

    private suspend fun processPages(
        file: StoredFile,
        engine: OcrEngine,
        onProgress: (ImportProgress) -> Unit,
        options: ParseOptions,
        ai: AiUse?,
        pages: Int,
        images: PageImages,
        shared: SharedReader?,
        started: Long,
    ): PendingImport {
        val first = readAll(images, engine, pages, 1) { p, of -> onProgress(ImportProgress(p, of, 1)) }
        var best = first
        var passes = 1
        // Pages read from the PDF's own text gain nothing from a sharper image.
        if (first.error == null && first.lines.isNotEmpty() && first.textPages < first.lines.size && !ReceiptParser.isConfident(first.parsed(options)) && engine.worksOffline && engine !== NoOcrEngine) {
            val second = try {
                readAll(images, engine, first.lines.size, 2) { p, of -> onProgress(ImportProgress(p, of, 2)) }
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
        var parsed = best.parsed(options)
        var aiLayout: String? = null
        var layoutNote: String? = null
        // The on-phone AI reader: only once every page has been read, and only when asked to always help or when
        // the regular readings still do not check out.
        var aiNote: String? = null
        val aiRaw = mutableListOf<String>()
        val aiTargeted = mutableListOf<String>()
        var aiSpot = false
        var targets: List<AiTarget>? = null
        if (ai != null && shared != null && best.error == null && best.lines.isNotEmpty()) {
            // The AI always takes part: it asks about the doubtful parts (seconds each) and double-checks the header,
            // totals and key lines of every document before the operator sees it. Whole pages only when the regular
            // reading failed too broadly for that, or when the operator asked the AI to read everything.
            // Column headings the app does not know, of a supplier not seen before: one short question first, so the
            // rest of the reading (and the AI's other questions) use the answer. Kept only if it reads the document better.
            if (!ai.always) {
                val q = withContext(Dispatchers.Default) { AiTargets.layoutQuestion(best.lines, parsed) }
                if (q != null) {
                    val (raw, note) = askLayout(images, best, shared, q, onProgress)
                    val answer = raw?.let { AiReader.decodeLayout(it, q) }
                    val better = answer?.let { a -> withContext(Dispatchers.Default) { withAiLayout(best.lines, options, parsed, a) } }
                    if (better != null && answer != null) {
                        parsed = better
                        aiLayout = answer.encode()
                    }
                    layoutNote = "headings: $note" + (if (better != null) " (used)" else if (answer != null) " (not better)" else " (no answer)")
                }
            }
            val planned = if (ai.always) null else withContext(Dispatchers.Default) { AiTargets.plan(best.lines, parsed, spotCheck = true) }
            targets = planned
            aiSpot = planned != null
            aiNote = when {
                planned == null -> runAi(images, best, ai, shared, options, onProgress, aiRaw, parsed)
                // Nothing the AI could prove (e.g. only an uncertain document number): no minutes spent on it.
                planned.isEmpty() -> "nothing to ask"
                else -> runTargets(images, best, shared, planned, onProgress, aiTargeted, ai.examples(parsed, best.text))
            }
            layoutNote?.let { aiNote = "$it; $aiNote" }
        }
        return rebuild(
            file, best.lines, best.widths, best.error, if (best.textPages > 0) "PDF text + ${engine.displayName}" else engine.displayName,
            aiRaw, aiNote, aiTargeted,
            "pass ${best.pass} of $passes" + (if (best.pass == 2) " (enhanced image)" else "") +
                (if (best.textPages > 0) ", text of the PDF on ${best.textPages} of ${best.lines.size} page(s)" else ""),
            options, System.currentTimeMillis() - started, aiSpot, aiLayout,
            // Worked out above from the same reading: not again.
            preParsed = parsed, preTargets = targets,
        )
    }

    /** Asks the AI what each column heading of [q] holds; returns its answer (null on failure) and a note for the log. */
    private suspend fun askLayout(
        images: PageImages,
        best: Reading,
        shared: SharedReader,
        q: LayoutQuestion,
        onProgress: (ImportProgress) -> Unit,
    ): Pair<String?, String> {
        onProgress(ImportProgress(1, 1, 4))
        val reader = shared.get().getOrElse { return null to "not started: ${it.message}" }
        val bmp = images.get(q.page)
        val r = reader.readRegions(
            bmp, best.lines[q.page], best.widths.getOrElse(q.page) { bmp.width }, q.boxes,
            AiReader.layoutInstruction(q.headings), AiReader.layoutGrammar(q.headings.size),
        ) { stage, count -> onProgress(ImportProgress(1, 1, 4, stage, count)) }
        if (r.error == "cancelled") throw CancellationException("AI reading cancelled")
        return (if (r.error == null) r.raw else null) to "${q.headings.size} headings, ${r.millis / 100 / 10.0}s answer='${r.raw.take(40)}'" + (r.error?.let { " error=$it" } ?: "")
    }

    /** Asks the AI the small questions of [targets], collecting its answers in [answers]; returns a note for the log. */
    private suspend fun runTargets(
        images: PageImages,
        best: Reading,
        shared: SharedReader,
        targets: List<AiTarget>,
        onProgress: (ImportProgress) -> Unit,
        answers: MutableList<String>,
        examples: List<AiReader.RowExample> = emptyList(),
    ): String {
        onProgress(ImportProgress(1, targets.size, 4))
        val reader = shared.get().getOrElse { return "not started: ${it.message}" }
        val notes = mutableListOf("checks ${targets.size} (" + targets.joinToString(",") { t ->
            when (t) {
                is AiTarget.Row -> t.itemIndex?.let { "line${it + 1}" } ?: "missed"
                is AiTarget.Choice -> "choice${t.itemIndex + 1}"
                is AiTarget.Number -> (if (t.verify) "check-" else "") + (if (t.field == AiTarget.Field.AMOUNT) "amount" else "qty") + "${t.itemIndex + 1}"
                is AiTarget.Header -> if (t.verify) "check-header" else "header"
                is AiTarget.Totals -> if (t.verify) "check-totals" else "totals"
                is AiTarget.Supplier -> "supplier"
            }
        } + ") lang=${AiReader.defaultLang} examples=${examples.size} " + reader.systemInfo)
        val started = System.currentTimeMillis()
        // Each question is independent (a fresh context every time), so they are asked page by page: only one page
        // image is in memory, however long the document. Answers are kept in the planned order.
        val got = arrayOfNulls<String>(targets.size)
        val byNote = arrayOfNulls<String>(targets.size)
        var done = 0
        run {
            for ((i, t) in targets.withIndex().sortedBy { it.value.page }) {
                done++
                onProgress(ImportProgress(done, targets.size, 4))
                val bmp = images.get(t.page)
                val (instruction, grammar) = when (t) {
                    is AiTarget.Row -> AiReader.rowInstruction(t.headerText, t.rowText, examples = examples) to AiReader.ROW_GRAMMAR
                    is AiTarget.Choice -> AiReader.choiceInstruction(t.headerText, t.rowText, t.choices) to AiReader.CHOICE_GRAMMAR
                    is AiTarget.Number -> AiReader.numberInstruction(t.column, t.headerText, t.rowText) to AiReader.NUMBER_GRAMMAR
                    is AiTarget.Header -> AiReader.headerInstruction() to AiReader.HEADER_GRAMMAR
                    is AiTarget.Totals -> AiReader.totalsInstruction() to AiReader.TOTALS_GRAMMAR
                    is AiTarget.Supplier -> AiReader.supplierInstruction(t.boxText) to AiReader.SUPPLIER_GRAMMAR
                }
                val step = done
                val r = reader.readRegions(bmp, best.lines[t.page], best.widths.getOrElse(t.page) { bmp.width }, t.boxes, instruction, grammar) { stage, count ->
                    onProgress(ImportProgress(step, targets.size, 4, stage, count))
                }
                if (r.error == "cancelled") throw CancellationException("AI reading cancelled")
                got[i] = if (r.error == null) r.raw else ""
                byNote[i] = "c${i + 1}: ${r.millis / 100 / 10.0}s ${r.stats}" + (r.error?.let { " error=$it" } ?: "")
            }
        }
        got.forEach { answers += it ?: "" }
        byNote.forEach { n -> n?.let { notes += it } }
        notes += "total ${(System.currentTimeMillis() - started) / 1000}s"
        return notes.joinToString("; ")
    }

    /**
     * Runs the AI on the pages that need it (all of them when it should always help), collecting its answers in
     * [raw] ("" for pages it did not read); returns a note for the log.
     */
    private suspend fun runAi(
        images: PageImages,
        best: Reading,
        ai: AiUse,
        shared: SharedReader,
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
        val reader = shared.get().getOrElse { return "not started: ${it.message}" }
        val notes = mutableListOf("pages ${selected.joinToString(",") { "${it + 1}" }} of $pages")
        for (i in selected) {
            val bmp = images.get(i)
            val r = reader.read(bmp, best.lines[i], best.widths.getOrElse(i) { bmp.width }, LayoutRows.toText(best.lines[i]), options) { stage, count ->
                onProgress(ImportProgress(i + 1, pages, 3, stage, count))
            }
            raw[i] = if (r.error == null) r.raw else ""
            notes += "p${i + 1}: ${r.millis / 1000}s ${r.stats}" + (r.error?.let { " error=$it" } ?: "") +
                (r.parsed?.let { " items=${it.lineItems.size}" } ?: "")
            if (r.error == "cancelled") throw CancellationException("AI reading cancelled")
        }
        return notes.joinToString("; ")
    }

    private inner class Reading(
        val lines: List<List<OcrLine>>,
        val widths: List<Int>,
        val error: String?,
        val pass: Int,
        /** Pages read from the PDF's own text (with the OCR only where it has none). */
        val textPages: Int = 0,
    ) {
        private var cache: ParsedDocument? = null
        val text: String get() = lines.joinToString("\n${ReceiptParser.PAGE_BREAK}\n") { LayoutRows.toText(it) }
        suspend fun parsed(options: ParseOptions): ParsedDocument = cache ?: withContext(Dispatchers.Default) {
            if (lines.all { it.isEmpty() }) ParsedDocument.EMPTY else ReceiptParser.parsePages(lines, options)
        }.also { cache = it }
    }

    private suspend fun readAll(
        images: PageImages,
        engine: OcrEngine,
        pages: Int,
        pass: Int,
        onProgress: (Int, Int) -> Unit,
    ): Reading {
        val raw = mutableListOf<List<OcrLine>>()
        val widths = mutableListOf<Int>()
        var error: String? = null
        var textPages = 0
        // A digital PDF's own text where it has some: exact, where the OCR can slip. The OCR still reads the page
        // for what is printed as a picture (often the letterhead), and to check the text is what is printed.
        suspend fun withText(i: Int, seen: List<OcrLine>, w: Int, h: Int): List<OcrLine> {
            val glyphs = runCatching { images.textOf(i) }.getOrNull()
            if (glyphs.isNullOrEmpty()) return seen
            return withContext(Dispatchers.Default) {
                val text = com.kitchenreceipts.core.TextLayer.lines(PdfText.scaled(glyphs, w, h))
                val merged = com.kitchenreceipts.core.TextLayer.merge(text, seen)
                if (merged !== seen) textPages++
                merged
            }
        }
        for (i in 0 until pages) {
            onProgress(i + 1, pages)
            try {
                // The page image stays with [images] (freed when the next page is read or the document is done).
                val bmp = images.get(i)
                if (pass == 1) {
                    raw += withText(i, engine.recognize(bmp), bmp.width, bmp.height)
                    widths += bmp.width
                } else {
                    val enhanced = withContext(Dispatchers.Default) { ImageEnhancer.enhance(bmp) }
                    try {
                        raw += withText(i, engine.recognize(enhanced), enhanced.width, enhanced.height)
                        widths += enhanced.width
                    } finally { enhanced.recycle() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                break
            }
        }
        return Reading(raw, widths, error, pass, textPages)
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
            /** The AI's answer about the column headings, when it was used (see [withAiLayout]). */
            aiLayout: String? = null,
            /** The reading (with the AI's headings) and the questions, when just worked out from the same lines. */
            preParsed: ParsedDocument? = null,
            preTargets: List<AiTarget>? = null,
        ): PendingImport = withContext(Dispatchers.Default) {
            val pageTexts = lines.map { LayoutRows.toText(it) }
            val text = pageTexts.joinToString("\n${ReceiptParser.PAGE_BREAK}\n")
            var parsed = preParsed ?: run {
                var p = if (lines.all { it.isEmpty() }) ParsedDocument.EMPTY else ReceiptParser.parsePages(lines, options)
                SupplierLayout.decode(aiLayout)?.let { a -> withAiLayout(lines, options, p, a)?.let { p = it } }
                p
            }
            if (aiTargeted.isNotEmpty()) {
                // The questions are worked out again from the same reading, so they pair up with the stored answers.
                val targets = preTargets ?: AiTargets.plan(lines, parsed, spotCheck = aiSpot)
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
                readingNote = "$readingNote, items read by ${parsed.itemsReadBy}" + (if (parsed.handwritten) ", handwritten or unclear" else "") +
                    (parsed.layout?.takeIf { it.documents > 0 }?.let { ", supplier layout learned from ${it.documents} document(s)" } ?: "") +
                    (if (aiLayout != null) ", column headings from the AI" else ""),
                aiNote = aiNote,
                aiRaw = aiRaw,
                aiTargeted = aiTargeted,
                ocrWidths = widths,
                aiSpot = aiSpot,
                aiLayout = aiLayout,
            )
        }

        /**
         * The reading with the AI's answer about the column headings added to what is known of the supplier, or null
         * when it does not read the document better than [base].
         */
        fun withAiLayout(lines: List<List<OcrLine>>, options: ParseOptions, base: ParsedDocument, answer: SupplierLayout): ParsedDocument? {
            val layout = (base.layout ?: SupplierLayout()).let { it.copy(headings = it.headings + answer.headings) }
            val p = ReceiptParser.parsePages(lines, options.copy(layout = layout, layoutLookup = null))
            return if (ReceiptParser.quality(p) > ReceiptParser.quality(base)) p else null
        }

        const val MAX_OCR_PAGES = 20
        const val OCR_LONG_SIDE = 2400
    }
}
