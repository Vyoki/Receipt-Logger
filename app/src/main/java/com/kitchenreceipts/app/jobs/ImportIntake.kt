package com.kitchenreceipts.app.jobs

import android.content.Context
import android.net.Uri
import com.kitchenreceipts.app.diagnostics.AppLog
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.ImportResult
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.app.files.UnsupportedFileException
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.InvoiceBundle
import com.kitchenreceipts.core.LeftOut
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a file comes into the app, shared to it (MainActivity) or picked on Capture ("Import PDF"): the file is
 * copied in as today, then it is decided what to do with it.
 * - A photo, a one-page PDF, or a PDF that holds one document: queued to be read, as today.
 * - A PDF of several invoices: a plan to sort them (a long one is looked through in the background first).
 * - A long PDF the app can only read as one document: the operator is asked first ("Read the first 20 pages").
 * - An e-invoice file of two or more invoices: a plan straight from their exact values.
 * The same file shared again carries on with the import already started. Credit notes are never imported: they are
 * counted, so the operator is told. Photos taken with the camera do not come through here.
 */
class ImportIntake(
    private val context: Context,
    private val fileStore: FileStore,
    private val queue: ImportQueue,
    private val bundles: BundleStore,
    private val log: AppLog,
) {
    /** What became of a file brought in. */
    sealed interface Intake {
        /** [count] documents were queued to be read; [skippedCreditNotes] credit notes in it were not imported. */
        data class Queued(val count: Int, val skippedCreditNotes: Int = 0) : Intake

        /**
         * A file of many invoices: its import opens ("bundle/<planId>"). [reopened]: the same file was started before
         * and carries on. [nextImport]: invoices beyond the most read at once, for the same file imported again later.
         * [skippedCreditNotes]: credit notes of an e-invoice file, never imported (its first step shows them too).
         */
        data class Bundle(val planId: String, val reopened: Boolean = false, val nextImport: Int = 0, val skippedCreditNotes: Int = 0) : Intake

        /** A long PDF read only as one document: nothing is queued until the operator answers (the import opens). */
        data class AskLong(val planId: String, val pages: Int) : Intake

        /** Nothing was imported: [message] says why in the operator's language ([messageRes]: its text, when known). */
        data class Failed(val message: String, val messageRes: Int = 0) : Intake
    }

    /** Copies the file at [uri] in and decides what to do with it. [source]: for the log ("shared", "file"). */
    suspend fun take(uri: Uri, source: String): Intake = withContext(Dispatchers.IO) {
        try {
            decide(fileStore.importUri(uri), source).also { r ->
                log.event("INTAKE", "source" to source, "result" to r.javaClass.simpleName, "count" to (r as? Intake.Queued)?.count)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("intake", e)
            val res = AppSettings.wrapWithLanguage(context).resources
            Intake.Failed(com.kitchenreceipts.app.ui.friendlyError(res, e), (e as? UnsupportedFileException)?.text ?: 0)
        }
    }

    private suspend fun decide(r: ImportResult, source: String): Intake {
        // The same file again: the import started before carries on, and the copy just made is not needed.
        bundles.reopenable(r.sha256)?.let { open ->
            r.files.forEach { fileStore.delete(it.relativePath) }
            log.event("INTAKE_REOPENED", "bundle" to open.id.take(8), "status" to open.status)
            return Intake.Bundle(open.id, reopened = true)
        }
        if (r.einvoices.isNotEmpty()) {
            // Its values are exact: two or more invoices make a plan at once, with no pages to look through.
            if (r.einvoices.count { !it.creditNote } >= 2) {
                return bundle(bundles.planFromEInvoices(r.sha256, r.einvoices).id).copy(skippedCreditNotes = r.skippedCreditNotes)
            }
            r.files.forEach { queue.enqueue(it, SOURCE_EINVOICE) }
            return Intake.Queued(r.files.size, r.skippedCreditNotes)
        }
        val file = r.files.single()
        if (file.mimeType != FileStore.MIME_PDF || file.pageCount < 2) return one(file, source)
        if (file.pageCount <= INLINE_PAGES) {
            // A short PDF is looked through now (about a second).
            val signals = bundles.look(file)
            if (signals != null && InvoiceBundle.usable(signals) && InvoiceBundle.isBundle(InvoiceBundle.segments(signals, ownVat()))) {
                return bundle(bundles.planFromScan(file, signals).id)
            }
            return one(file, source)
        }
        // A long one: its first pages say whether it has text to look through (a scanned file has none).
        val first = bundles.look(file, INLINE_PAGES)
        if (first == null || !InvoiceBundle.usable(first)) {
            if (file.pageCount <= InvoiceBundle.PAGE_LIMIT) return one(file, source)
            val p = bundles.notSplittable(file, first.orEmpty())
            return Intake.AskLong(p.id, file.pageCount)
        }
        // The rest is looked through in the background ("Looking through the file").
        return Intake.Bundle(bundles.startScanning(file, first).id)
    }

    private suspend fun one(file: StoredFile, source: String): Intake {
        queue.enqueue(file, source)
        return Intake.Queued(1)
    }

    private fun bundle(id: String): Intake.Bundle =
        Intake.Bundle(id, nextImport = bundles.plan(id)?.invoices?.count { it.leftOut == LeftOut.NEXT_IMPORT } ?: 0)

    private fun ownVat(): String? = bundles.ownVatNumber()

    companion object {
        /** A PDF up to this many pages is looked through at once; a longer one in the background. */
        const val INLINE_PAGES = 12
        const val SOURCE_EINVOICE = "e-invoice"
    }
}
