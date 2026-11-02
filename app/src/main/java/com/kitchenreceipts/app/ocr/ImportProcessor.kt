package com.kitchenreceipts.app.ocr

import com.kitchenreceipts.app.files.PageRenderer
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.app.ai.AiPageReader
import com.kitchenreceipts.core.AiLoop
import com.kitchenreceipts.core.AutoAccept
import com.kitchenreceipts.core.DocumentDraft
import com.kitchenreceipts.core.AiReader
import com.kitchenreceipts.core.AiScope
import com.kitchenreceipts.core.AiTarget
import com.kitchenreceipts.core.AiTargets
import com.kitchenreceipts.core.LayoutQuestion
import com.kitchenreceipts.core.LayoutRows
import com.kitchenreceipts.core.OcrLine
import com.kitchenreceipts.core.ParseOptions
import com.kitchenreceipts.core.ParsedDocument
import com.kitchenreceipts.core.ReceiptParser
import com.kitchenreceipts.core.SupplierLayout
import kotlinx.coroutines.CoroutineDispatcher
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
    /** Height of that image (with the width, its shape: to tell later whether a page is drawn the same way). */
    val ocrHeights: List<Int> = emptyList(),
    /** The AI's questions included the double-check. */
    val aiSpot: Boolean = false,
    /** The AI's answer about column headings the app did not know (encoded SupplierLayout), if it was used. */
    val aiLayout: String? = null,
    /** Which open question was asked each time (0 = the app's first; the trained model may choose another): see AiLoop. */
    val aiPicks: List<Int> = emptyList(),
    /** What each of [aiTargeted] answered (see AiLoop.keys), for the replay after a restart or an update. */
    val aiKeys: List<String> = emptyList(),
    /** Pages (from 0) whose words came from the PDF's own text layer (see ParseOptions.textLayerPages). */
    val textLayerPages: Set<Int> = emptySet(),
    /** Which questions the AI was asked (see AiScope): the answers are replayed with the same scope. */
    val aiScope: AiScope = AiScope.ALL,
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
data class ImportProgress(
    val page: Int,
    val of: Int,
    val pass: Int,
    val aiStage: Int = 0,
    val aiCount: Int = 0,
    /** What the AI is being asked about (pass 4): "supplier", "missed", "line:3", "totals", "header", "check". */
    val aiTopic: String? = null,
)

/** What the AI is being asked about, in words for the operator. */
fun aiTopicText(res: android.content.res.Resources, topic: String?): String? = when {
    topic == null -> null
    topic == "supplier" -> res.getString(com.kitchenreceipts.app.R.string.ai_topic_supplier)
    topic == "missed" -> res.getString(com.kitchenreceipts.app.R.string.ai_topic_missed)
    topic.startsWith("line:") -> res.getString(com.kitchenreceipts.app.R.string.ai_topic_line, topic.substringAfter(':').toIntOrNull() ?: 0)
    topic == "totals" -> res.getString(com.kitchenreceipts.app.R.string.ai_topic_totals)
    topic == "header" -> res.getString(com.kitchenreceipts.app.R.string.ai_topic_header)
    else -> res.getString(com.kitchenreceipts.app.R.string.ai_topic_check)
}

/**
 * How the on-phone AI reader should be used for this import (null = not at all). [examples]: lines of the same
 * supplier the operator confirmed before, shown to the AI with each line question.
 */
class AiUse(
    val reader: suspend () -> AiPageReader,
    val always: Boolean,
    val examples: (ParsedDocument, String) -> List<AiReader.RowExample> = { _, _ -> emptyList() },
    /** How the model is asked: the long instructions, or the short ones the trained model knows (faster). */
    val style: com.kitchenreceipts.core.AiTasks.Style = com.kitchenreceipts.core.AiTasks.Style.FULL,
    /** The model chooses which open question comes next (the trained model); otherwise the app's order. */
    val agent: Boolean = false,
)

/**
 * [cpu]: where the reading's own computing runs (parsing, page text, image clean-up). The background reader gives it
 * threads of lower priority, so the screen the operator is using stays smooth while documents are read.
 */
class ImportProcessor(private val renderer: PageRenderer, private val cpu: CoroutineDispatcher = Dispatchers.Default) {

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

        /** The PDF, opened once for all its pages (null: not a PDF, or it cannot be opened). */
        private var pdf: PdfText.Doc? = null
        private var pdfOpened = false
        private fun pdf(): PdfText.Doc? {
            if (!pdfOpened) {
                pdfOpened = true
                if (file.mimeType == com.kitchenreceipts.app.files.FileStore.MIME_PDF) pdf = runCatching { renderer.openPdf(file.relativePath) }.getOrNull()
            }
            return pdf
        }

        suspend fun textOf(i: Int): List<com.kitchenreceipts.core.TextLayer.Glyph>? {
            if (file.mimeType != com.kitchenreceipts.app.files.FileStore.MIME_PDF) return null
            return text.getOrPut(i) { withContext(Dispatchers.IO) { runCatching { pdf()?.glyphs(i) }.getOrNull() } }
        }

        /**
         * Page [i] read from the PDF's own text alone, at the size the camera reading would use: a page that draws no
         * picture, whose text reads like a document (see TextLayer.readsAlone). Nothing is printed outside that text,
         * so the page is neither drawn nor read by the camera reading. Null otherwise.
         */
        suspend fun textAlone(i: Int): Triple<List<OcrLine>, Int, Int>? {
            val glyphs = textOf(i)?.takeIf { it.isNotEmpty() } ?: return null
            val doc = pdf() ?: return null
            val (pictures, aspect) = withContext(Dispatchers.IO) { runCatching { doc.hasPictures(i) to doc.aspect(i) }.getOrDefault(true to 1f) }
            if (pictures) return null
            val w = OCR_LONG_SIDE
            val h = (w / aspect).toInt().coerceIn(200, w * 4)
            val lines = withContext(cpu) { com.kitchenreceipts.core.TextLayer.lines(PdfText.scaled(glyphs, w, h)) }
            return if (com.kitchenreceipts.core.TextLayer.readsAlone(lines)) Triple(lines, w, h) else null
        }

        fun release() {
            bmp?.recycle()
            bmp = null
            page = -1
        }

        /** Frees the page image and closes the PDF. */
        fun close() {
            release()
            runCatching { pdf?.close() }
            pdf = null
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
            val read = withContext(cpu) { com.kitchenreceipts.core.EInvoice.read(renderer.bytes(file.relativePath), options.ownVatNumber) }
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
        // Pages of the PDF's own text cost a moment each; the camera reading stops after MAX_OCR_PAGES pages (readAll).
        val pages = minOf(file.pageCount, com.kitchenreceipts.core.InvoiceBundle.TEXT_PAGE_LIMIT)
        val images = PageImages(file)
        val shared = ai?.let { SharedReader(it) }
        try {
            return processPages(file, engine, onProgress, options, ai, pages, images, shared, started)
        } finally {
            images.close()
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
        val pagesMs = System.currentTimeMillis() - started
        var enhancedMs = 0L
        var best = first
        var passes = 1
        // Pages read from the PDF's own text gain nothing from a sharper image.
        if (first.error == null && first.lines.isNotEmpty() && first.textPages < first.lines.size && !ReceiptParser.isConfident(first.parsed(options)) && engine.worksOffline && engine !== NoOcrEngine) {
            val t2 = System.currentTimeMillis()
            val second = try {
                readAll(images, engine, first.lines.size, 2) { p, of -> onProgress(ImportProgress(p, of, 2)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            enhancedMs = System.currentTimeMillis() - t2
            passes = 2
            if (second != null && second.error == null &&
                ReceiptParser.quality(second.parsed(options)) > ReceiptParser.quality(first.parsed(options))
            ) {
                best = second
            }
        }
        // The rules for exact text apply to the pages read from the PDF's own text, in every reading below.
        val opts = options.copy(textLayerPages = best.textSet)
        var parsed = best.parsed(options)
        var aiLayout: String? = null
        var layoutNote: String? = null
        // The on-phone AI reader: only once every page has been read, and only when asked to always help or when
        // the regular readings still do not check out.
        var aiNote: String? = null
        val aiRaw = mutableListOf<String>()
        val aiTargeted = mutableListOf<String>()
        val aiPicks = mutableListOf<Int>()
        val aiKeys = mutableListOf<String>()
        var aiSpot = false
        // Every question the plan has (exact text, where a shorter check was once an option, is no longer asked about).
        val scope = AiScope.ALL
        // The PDF's own text on every page: exact, so the AI could only read again what is already known, at minutes per
        // invoice. Whatever does not add up goes to the operator, who sees the page itself.
        val exactText = best.error == null && best.lines.isNotEmpty() && best.textPages == best.lines.size
        // A photo whose reading proves itself: every amount by its own arithmetic, the supplier by places on the page
        // that agree (or its VAT number), and a date and a number read with confidence. The AI would only confirm it.
        val provenPhoto = !exactText && best.error == null && best.lines.isNotEmpty() &&
            parsed.supplierProof?.state == com.kitchenreceipts.core.SupplierProof.State.PROVEN &&
            parsed.documentDate?.confidence == com.kitchenreceipts.core.Confidence.HIGH &&
            parsed.documentNumber?.confidence == com.kitchenreceipts.core.Confidence.HIGH &&
            withContext(cpu) { runCatching { AutoAccept.moneyProven(AutoAccept.settleProven(DocumentDraft.fromParsed(parsed))) }.getOrDefault(false) }
        val proven = ai != null && !ai.always && (exactText || provenPhoto)
        if (proven) aiNote = if (exactText) AI_NOT_NEEDED else AI_NOT_NEEDED_PHOTO
        val aiStarted = System.currentTimeMillis()
        if (ai != null && !proven && shared != null && best.error == null && best.lines.isNotEmpty()) {
            // The AI always takes part: it asks about the doubtful parts (seconds each) and double-checks the header,
            // totals and key lines of every document before the operator sees it. Whole pages only when the regular
            // reading failed too broadly for that, or when the operator asked the AI to read everything.
            // Column headings the app does not know, of a supplier not seen before: one short question first, so the
            // rest of the reading (and the AI's other questions) use the answer. Kept only if it reads the document better.
            if (!ai.always) {
                val q = withContext(cpu) { AiTargets.layoutQuestion(best.lines, parsed) }
                if (q != null) {
                    val (raw, note) = askLayout(images, best, shared, q, onProgress, ai.style)
                    val answer = raw?.let { AiReader.decodeLayout(it, q) }
                    val better = answer?.let { a -> withContext(cpu) { withAiLayout(best.lines, opts, parsed, a) } }
                    if (better != null && answer != null) {
                        parsed = better
                        aiLayout = answer.encode()
                    }
                    layoutNote = "headings: $note" + (if (better != null) " (used)" else if (answer != null) " (not better)" else " (no answer)")
                }
            }
            val planned = if (ai.always) null else withContext(cpu) { AiTargets.plan(best.lines, parsed, spotCheck = true, scope = scope) }
            // aiSpot marks the question loop (the answers are replayed through it after a restart).
            aiSpot = planned != null
            aiNote = when {
                // On exact text, never whole pages: nothing to ask is nothing to ask.
                planned == null -> runAi(images, best, ai, shared, opts, onProgress, aiRaw, parsed)
                // Nothing the AI could prove (e.g. only an uncertain document number): no minutes spent on it.
                planned.isEmpty() -> "nothing to ask"
                else -> runLoop(
                    images, best, shared, parsed, opts, onProgress, aiTargeted, aiPicks, aiKeys, ai, scope, ai.examples(parsed, best.text),
                    limitMs = AI_TIME_LIMIT_MS,
                ).second
            }
            layoutNote?.let { aiNote = "$it; $aiNote" }
        }
        val aiMs = System.currentTimeMillis() - aiStarted
        // Where the time went, for the reading report (seconds per step on this phone).
        fun sec(ms: Long) = String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0)
        val times = "time: pages ${sec(pagesMs)}" + (if (passes == 2) ", enhanced pages ${sec(enhancedMs)}" else "") +
            ", AI ${sec(aiMs)}, all ${sec(System.currentTimeMillis() - started)}"
        return rebuild(
            file, best.lines, best.widths, best.error, if (best.textPages > 0) "PDF text + ${engine.displayName}" else engine.displayName,
            aiRaw, aiNote, aiTargeted,
            "pass ${best.pass} of $passes" + (if (best.pass == 2) " (enhanced image)" else "") +
                (if (best.textPages > 0) ", text of the PDF on ${best.textPages} of ${best.lines.size} page(s)" else "") + "; $times",
            opts, System.currentTimeMillis() - started, aiSpot, aiLayout,
            // Worked out above from the same reading: not again (the AI's answers are replayed on it).
            preParsed = parsed, aiPicks = aiPicks, aiKeys = aiKeys, heights = best.heights, aiScope = scope, cpu = cpu,
        )
    }

    /** Asks the AI what each column heading of [q] holds; returns its answer (null on failure) and a note for the log. */
    private suspend fun askLayout(
        images: PageImages,
        best: Reading,
        shared: SharedReader,
        q: LayoutQuestion,
        onProgress: (ImportProgress) -> Unit,
        style: com.kitchenreceipts.core.AiTasks.Style = com.kitchenreceipts.core.AiTasks.Style.FULL,
    ): Pair<String?, String> {
        onProgress(ImportProgress(1, 1, 4))
        val reader = shared.get().getOrElse { return null to "not started: ${it.message}" }
        val bmp = images.get(q.page)
        val (instruction, grammar) = if (style == com.kitchenreceipts.core.AiTasks.Style.COMPACT) com.kitchenreceipts.core.AiTasks.layout(q.headings)
            else AiReader.layoutInstruction(q.headings) to AiReader.layoutGrammar(q.headings.size)
        val r = reader.readRegions(bmp, best.lines[q.page], best.widths.getOrElse(q.page) { bmp.width }, q.boxes, instruction, grammar) { stage, count ->
            onProgress(ImportProgress(1, 1, 4, stage, count))
        }
        if (r.error == "cancelled") throw CancellationException("AI reading cancelled")
        return (if (r.error == null) r.raw else null) to "${q.headings.size} headings, ${r.millis / 100 / 10.0}s answer='${r.raw.take(40)}'" + (r.error?.let { " error=$it" } ?: "")
    }

    private fun label(t: AiTarget): String = when (t) {
        is AiTarget.Row -> t.itemIndex?.let { "line${it + 1}" } ?: "missed"
        is AiTarget.Choice -> "choice${t.itemIndex + 1}"
        is AiTarget.Number -> (if (t.verify) "check-" else "") + (if (t.field == AiTarget.Field.AMOUNT) "amount" else "qty") + "${t.itemIndex + 1}"
        is AiTarget.Header -> if (t.verify) "check-header" else "header"
        is AiTarget.Totals -> if (t.verify) "check-totals" else "totals"
        is AiTarget.Supplier -> "supplier"
    }

    /**
     * Asks the AI one question at a time (see core AiLoop): each chosen from the document as the previous answer left
     * it, until nothing is left to prove, [AiLoop.MAX_QUESTIONS], or [limitMs]. The answers, in order, go to
     * [answers]; returns the document as the answers left it and a note for the log.
     */
    private suspend fun runLoop(
        images: PageImages,
        best: Reading,
        shared: SharedReader,
        start: ParsedDocument,
        options: ParseOptions,
        onProgress: (ImportProgress) -> Unit,
        answers: MutableList<String>,
        picks: MutableList<Int>,
        keys: MutableList<String>,
        ai: AiUse,
        scope: AiScope,
        examples: List<AiReader.RowExample> = emptyList(),
        limitMs: Long = AI_TIME_LIMIT_MS,
    ): Pair<ParsedDocument, String> {
        val loop = AiLoop(best.lines, best.text, options, start, scope = scope)
        val reader = shared.get().getOrElse { return start to "not started: ${it.message}" }
        // The agent's choice has no picture to look at: the smallest blank image keeps it fast.
        val blank = android.graphics.Bitmap.createBitmap(28, 28, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val started = System.currentTimeMillis()
        val notes = mutableListOf(
            "loop ${ai.style.name.lowercase()}${if (ai.agent) " agent" else ""}${if (scope != AiScope.ALL) " scope=${scope.name}" else ""} " +
                "lang=${AiReader.defaultLang} examples=${examples.size} " + reader.systemInfo,
        )
        var stopped = "nothing left to prove"
        while (true) {
            if (System.currentTimeMillis() - started > limitMs) { stopped = "time limit"; break }
            val open = withContext(cpu) { loop.candidates() }
            if (open.isEmpty()) {
                if (loop.answers.size >= loop.maxQuestions) stopped = "question limit"
                break
            }
            // The trained model chooses what to look at next from a short summary (no picture: under a second);
            // its choice is only an order, the arithmetic still decides every value.
            var pick = 0
            if (ai.agent && loop.worthDeciding(open)) {
                val (instruction, grammar) = com.kitchenreceipts.core.AiTasks.decide(loop.doc, open)
                val d = reader.readRegions(blank, emptyList(), blank.width, listOf(com.kitchenreceipts.core.PageBox(0, 0, blank.width, blank.height)), instruction, grammar) { _, _ -> }
                if (d.error == "cancelled") throw CancellationException("AI reading cancelled")
                pick = (if (d.error == null) com.kitchenreceipts.core.AiTasks.decodeDecide(d.raw, open) else null) ?: 0
                notes += "decide: ${"ABCDEF".getOrNull(pick)} of ${minOf(open.size, com.kitchenreceipts.core.AiTasks.MAX_OPTIONS)} ${d.millis / 100 / 10.0}s"
            }
            val t = open[pick]
            val step = loop.answers.size + 1
            val topic = when (t) {
                is AiTarget.Supplier -> "supplier"
                is AiTarget.Row -> t.itemIndex?.let { "line:${it + 1}" } ?: "missed"
                is AiTarget.Choice -> "line:${t.itemIndex + 1}"
                is AiTarget.Number -> if (t.verify) "check" else "line:${t.itemIndex + 1}"
                is AiTarget.Totals -> if (t.verify) "check" else "totals"
                is AiTarget.Header -> if (t.verify) "check" else "header"
            }
            onProgress(ImportProgress(step, loop.maxQuestions, 4, aiTopic = topic))
            val bmp = images.get(t.page)
            val (instruction, grammar) = com.kitchenreceipts.core.AiTasks.question(t, ai.style, examples = examples, previous = loop.previous(t))
            val r = reader.readRegions(bmp, best.lines[t.page], best.widths.getOrElse(t.page) { bmp.width }, t.boxes, instruction, grammar) { stage, count ->
                onProgress(ImportProgress(step, loop.maxQuestions, 4, stage, count, topic))
            }
            if (r.error == "cancelled") throw CancellationException("AI reading cancelled")
            val raw = if (r.error == null) r.raw else ""
            withContext(cpu) { loop.answer(t, raw, pick) }
            notes += "q$step ${label(t)}: ${r.millis / 100 / 10.0}s ${r.stats}" + (r.error?.let { " error=$it" } ?: "")
        }
        answers += loop.answers
        picks += loop.picks
        keys += loop.keys
        blank.recycle()
        notes += "${loop.answers.size} questions, $stopped, total ${(System.currentTimeMillis() - started) / 1000}s"
        return loop.doc to notes.joinToString("; ")
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
        val selected = if (ai.always) (0 until pages).toList() else withContext(cpu) {
            AiReader.pagesToRead(best.lines.mapIndexed { i, page -> ReceiptParser.parsePages(listOf(page), pageOptions(options, i)) }, whole)
        }
        repeat(pages) { raw += "" }
        onProgress(ImportProgress(selected.first() + 1, pages, 3))
        val reader = shared.get().getOrElse { return "not started: ${it.message}" }
        val notes = mutableListOf("pages ${selected.joinToString(",") { "${it + 1}" }} of $pages")
        val started = System.currentTimeMillis()
        for (i in selected) {
            // A whole page takes minutes: past the limit the remaining pages keep the regular reading (the phone
            // does not stay hot for the best part of an hour on a long document).
            if (System.currentTimeMillis() - started > AI_PAGES_LIMIT_MS) { notes += "time limit before p${i + 1}"; break }
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
        val heights: List<Int>,
        val error: String?,
        val pass: Int,
        /** Pages read from the PDF's own text (with the OCR only where it has none). */
        val textSet: Set<Int> = emptySet(),
    ) {
        val textPages: Int get() = textSet.size
        private var cache: ParsedDocument? = null
        val text: String get() = lines.joinToString("\n${ReceiptParser.PAGE_BREAK}\n") { LayoutRows.toText(it) }
        suspend fun parsed(options: ParseOptions): ParsedDocument = cache ?: withContext(cpu) {
            if (lines.all { it.isEmpty() }) ParsedDocument.EMPTY else ReceiptParser.parsePages(lines, options.copy(textLayerPages = textSet))
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
        val heights = mutableListOf<Int>()
        var error: String? = null
        val textSet = mutableSetOf<Int>()
        // A digital PDF's own text where it has some: exact, where the OCR can slip. The OCR still reads the page
        // for what is printed as a picture (often the letterhead), and to check the text is what is printed.
        suspend fun withText(i: Int, seen: List<OcrLine>, w: Int, h: Int): List<OcrLine> {
            val glyphs = runCatching { images.textOf(i) }.getOrNull()
            if (glyphs.isNullOrEmpty()) return seen
            return withContext(cpu) {
                val text = com.kitchenreceipts.core.TextLayer.lines(PdfText.scaled(glyphs, w, h))
                val merged = com.kitchenreceipts.core.TextLayer.merge(text, seen)
                if (merged !== seen) textSet += i
                merged
            }
        }
        var drawn = 0
        for (i in 0 until pages) {
            onProgress(i + 1, pages)
            try {
                // A digital page that draws no picture: its own text is all that is printed (no drawing, no camera
                // reading; seconds saved on every page).
                if (pass == 1) {
                    val alone = runCatching { images.textAlone(i) }.getOrNull()
                    if (alone != null) {
                        raw += alone.first
                        widths += alone.second
                        heights += alone.third
                        textSet += i
                        continue
                    }
                }
                // Pages that need the camera reading take seconds each: the first MAX_OCR_PAGES only (said in the report).
                if (++drawn > MAX_OCR_PAGES) break
                // The page image stays with [images] (freed when the next page is read or the document is done).
                val bmp = images.get(i)
                if (pass == 1) {
                    raw += withText(i, engine.recognize(bmp), bmp.width, bmp.height)
                    widths += bmp.width
                    heights += bmp.height
                } else {
                    val enhanced = withContext(cpu) { ImageEnhancer.enhance(bmp) }
                    try {
                        raw += withText(i, engine.recognize(enhanced), enhanced.width, enhanced.height)
                        widths += enhanced.width
                        heights += enhanced.height
                    } finally { enhanced.recycle() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                break
            }
        }
        return Reading(raw, widths, heights, error, pass, textSet)
    }

    companion object {
        /**
         * The final reading from what was read: the regular reading of [lines], improved by the AI's answers when
         * there is one for every page. Also used to rebuild a background reading after the app restarts.
         * [options] carries the pages read from the PDF's own text (ParseOptions.textLayerPages), as when it was read.
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
            /** The AI's answers come from the question loop (see AiLoop): they are replayed through it. */
            aiSpot: Boolean = false,
            /** The AI's answer about the column headings, when it was used (see [withAiLayout]). */
            aiLayout: String? = null,
            /** The reading (with the AI's headings), when just worked out from the same lines. */
            preParsed: ParsedDocument? = null,
            /** Which open question was asked each time (see AiLoop.replay). */
            aiPicks: List<Int> = emptyList(),
            aiKeys: List<String> = emptyList(),
            heights: List<Int> = emptyList(),
            /** The scope the AI's questions were asked with (see AiScope). */
            aiScope: AiScope = AiScope.ALL,
            cpu: CoroutineDispatcher = Dispatchers.Default,
        ): PendingImport = withContext(cpu) {
            val pageTexts = lines.map { LayoutRows.toText(it) }
            val text = pageTexts.joinToString("\n${ReceiptParser.PAGE_BREAK}\n")
            var parsed = preParsed ?: run {
                var p = if (lines.all { it.isEmpty() }) ParsedDocument.EMPTY else ReceiptParser.parsePages(lines, options)
                SupplierLayout.decode(aiLayout)?.let { a -> withAiLayout(lines, options, p, a)?.let { p = it } }
                p
            }
            if (aiTargeted.isNotEmpty()) {
                // The questions are chosen again the same way from the same reading, so they pair up with the stored answers.
                parsed = AiLoop.replay(lines, text, options, parsed, aiTargeted, aiPicks, aiKeys, scope = aiScope)
            }
            if (aiRaw.size == lines.size && aiRaw.any { it.isNotBlank() }) {
                val aiPages = aiRaw.mapIndexed { i, raw ->
                    raw.takeIf { it.isNotBlank() }?.let { AiReader.decode(it) }?.let { AiReader.toParsed(it, pageTexts[i], options) }
                }
                val joined = AiReader.joinPages(aiPages.filterNotNull(), text)
                if (joined != null) {
                    // Lines page by page (AI where it read the page and did better), header and totals from both readings.
                    val regularPages = lines.mapIndexed { i, page -> ReceiptParser.parsePages(listOf(page), pageOptions(options, i)) }
                    parsed = AiReader.merge(parsed, joined.copy(lineItems = AiReader.combineItems(regularPages, aiPages)), text)
                }
            }
            // A long scan read only in part: said, and never saved without the operator seeing it.
            if (file.mimeType == com.kitchenreceipts.app.files.FileStore.MIME_PDF && error == null && lines.size in 1 until file.pageCount) {
                parsed = parsed.copy(warnings = parsed.warnings + com.kitchenreceipts.core.ParseWarning.PAGES_NOT_READ)
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
                ocrHeights = heights,
                aiSpot = aiSpot,
                aiLayout = aiLayout,
                aiPicks = aiPicks,
                aiKeys = aiKeys,
                textLayerPages = options.textLayerPages,
                aiScope = aiScope,
            )
        }

        /** [options] for page [i] read on its own (its text-layer mark moved to index 0). */
        private fun pageOptions(options: ParseOptions, i: Int): ParseOptions =
            if (options.textLayerPages.isEmpty()) options else options.copy(textLayerPages = if (i in options.textLayerPages) setOf(0) else emptySet())

        /**
         * The reading with the AI's answer about the column headings added to what is known of the supplier, or null
         * when it does not read the document better than [base].
         */
        fun withAiLayout(lines: List<List<OcrLine>>, options: ParseOptions, base: ParsedDocument, answer: SupplierLayout): ParsedDocument? {
            val layout = (base.layout ?: SupplierLayout()).let { it.copy(headings = it.headings + answer.headings) }
            val p = ReceiptParser.parsePages(lines, options.copy(layout = layout, layoutLookup = null))
            return if (ReceiptParser.quality(p) > ReceiptParser.quality(base)) p else null
        }

        /** Pages read by the camera reading in one document (pages of the PDF's own text are not counted). */
        const val MAX_OCR_PAGES = com.kitchenreceipts.core.InvoiceBundle.PAGE_LIMIT
        /** The AI's questions stop after this long on one document, on any phone (a slow one asks fewer). */
        const val AI_TIME_LIMIT_MS = 150_000L

        /** The notes of a reading the AI was spared: the PDF's own text, or a photo whose reading proves itself. */
        const val AI_NOT_NEEDED = "not needed: the PDF's own text"
        const val AI_NOT_NEEDED_PHOTO = "not needed: the amounts, the supplier, the date and the number prove themselves"
        /** Whole pages read by the AI, all pages together (a page takes 2 to 5 minutes on a phone). */
        const val AI_PAGES_LIMIT_MS = 10 * 60_000L
        const val OCR_LONG_SIDE = 2400
    }
}
