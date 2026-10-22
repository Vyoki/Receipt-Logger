package com.kitchenreceipts.app.jobs

import com.kitchenreceipts.app.ocr.ImportProcessor
import com.kitchenreceipts.app.ocr.PdfText
import com.kitchenreceipts.core.InvoiceBundle
import com.kitchenreceipts.core.TextLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Looks through a PDF of many invoices once, page by page, using only the PDF's own text (no pictures, no OCR): what
 * each page shows (InvoiceBundle.PageSignals), from which InvoiceBundle finds where each invoice starts and ends.
 * Each page's characters are dropped as soon as its signals are worked out, so a file of hundreds of pages needs
 * little memory. Everything stays on the phone.
 */
object BundleScanner {

    /** How often the work so far is handed out to be saved (a scan stopped by Android resumes from there). */
    const val CHECKPOINT_PAGES = 10

    /**
     * The pages looked through, in order (page i at index i). [complete]: every page of the file was looked through
     * (false when the scan was stopped).
     */
    data class Result(val signals: List<InvoiceBundle.PageSignals>, val pageCount: Int, val complete: Boolean) {
        fun segments(ownVat: String?): List<InvoiceBundle.Segment> = InvoiceBundle.segments(signals, ownVat)
    }

    /**
     * Looks through [file] from the page after [previous] (the signals of the pages already looked through, from a
     * checkpoint; empty to start at the first page). [onProgress] gets the page just looked through (from 1), the
     * number of pages and the invoices found so far; [onCheckpoint] gets all the signals so far every
     * [CHECKPOINT_PAGES] pages and once at the end. Stops when the calling coroutine is cancelled (and throws its
     * CancellationException, after the last checkpoint). Throws PdfText.Locked or PdfText.Damaged when the file
     * cannot be opened. [tempDir]: where the PDF reader may keep its temporary files.
     */
    suspend fun scan(
        file: File,
        ownVat: String?,
        previous: List<InvoiceBundle.PageSignals> = emptyList(),
        tempDir: File? = null,
        onProgress: (page: Int, of: Int, found: Int) -> Unit = { _, _, _ -> },
        onCheckpoint: (List<InvoiceBundle.PageSignals>) -> Unit = {},
    ): Result = withContext(Dispatchers.Default) {
        val ctx = coroutineContext
        val signals = ArrayList(previous)
        var count = previous.size
        var lastCheckpoint = signals.size
        PdfText.scanPages(file, from = previous.size, tempDir = tempDir, isActive = { ctx.isActive }, onOpen = { count = it }) { i, glyphs, aspect ->
            signals += signalsOf(i, glyphs, aspect, ownVat)
            if (signals.size - lastCheckpoint >= CHECKPOINT_PAGES) {
                lastCheckpoint = signals.size
                onCheckpoint(signals.toList())
            }
            onProgress(i + 1, count, found(signals, ownVat))
        }
        if (signals.size != lastCheckpoint) onCheckpoint(signals.toList())
        ensureActive()
        Result(signals, count, complete = signals.size >= count)
    }

    /**
     * One page's signals from its characters, placed on the image the reading would use (the same width as a PDF
     * page is drawn for the OCR), without the page's row texts (kept small).
     */
    fun signalsOf(index: Int, glyphs: List<TextLayer.Glyph>?, aspect: Float, ownVat: String?): InvoiceBundle.PageSignals {
        if (glyphs.isNullOrEmpty()) return InvoiceBundle.PageSignals(index, hasText = false)
        val width = ImportProcessor.OCR_LONG_SIDE
        val height = (width / aspect.coerceIn(0.1f, 10f)).toInt().coerceIn(200, width * 4)
        val lines = TextLayer.lines(PdfText.scaled(glyphs, width, height))
        return InvoiceBundle.signals(index, lines, ownVat, pageHeight = height).copy(rowTexts = emptyList())
    }

    private fun found(signals: List<InvoiceBundle.PageSignals>, ownVat: String?): Int =
        runCatching { InvoiceBundle.segments(signals, ownVat).count { it.kind == InvoiceBundle.Kind.INVOICE } }.getOrDefault(0)
}
