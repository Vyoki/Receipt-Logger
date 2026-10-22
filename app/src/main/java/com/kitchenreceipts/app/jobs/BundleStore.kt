package com.kitchenreceipts.app.jobs

import android.content.Context
import com.kitchenreceipts.app.diagnostics.AppLog
import com.kitchenreceipts.app.files.EInvoiceEntry
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.SplitFailure
import com.kitchenreceipts.app.files.SplitResult
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.app.ocr.PdfText
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.BundlePlan
import com.kitchenreceipts.core.BundlePlanCodec
import com.kitchenreceipts.core.BundleStatus
import com.kitchenreceipts.core.BundleWizard
import com.kitchenreceipts.core.InvoiceBundle
import com.kitchenreceipts.core.InvoiceOutcome
import com.kitchenreceipts.core.PlannedInvoice
import com.kitchenreceipts.core.SupplierCategories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The imports of files of many invoices, one plan each (core BundlePlan): what was found in the file, the operator's
 * choices, and what became of each invoice. A plan is kept on the phone (filesDir/bundles/<id>/plan.txt, not in
 * backups), so an import carries on after the app was closed. Its id is the file's SHA-256: the same file shared again
 * reopens it.
 *
 * Looking through a long PDF happens here, in the background, while [ReadingService] keeps the app in the foreground
 * and shows the progress; it saves its progress every few pages and goes on from there after a restart. Once the
 * operator taps Read, the file is cut into one PDF per invoice and each is queued in [ImportQueue]; what happens to
 * each job comes back through [onJobEvent] and is recorded in the plan, because the queue forgets saved jobs.
 *
 * Nothing here changes how a document is read, checked or saved. Everything stays on the phone.
 */
class BundleStore(
    context: Context,
    private val scope: CoroutineScope,
    private val fileStore: FileStore,
    private val queue: ImportQueue,
    private val settings: AppSettings,
    private val log: AppLog,
    private val notifier: ReadingNotifier,
    /** The documents saved in the app: an invoice of the file already among them starts left out. */
    private val saved: suspend () -> List<BundleWizard.Saved> = { emptyList() },
    /** True when a saved document uses the file: it is then never deleted with a cancelled import. */
    private val fileInUse: suspend (String) -> Boolean = { true },
    /** The supplier categories remembered on this phone, by memory key (SupplierCategories.memoryKeys). */
    private val memory: () -> Map<String, String> = { emptyMap() },
    /** Where the plans are kept (a test uses its own folder). */
    private val dir: File = File(context.filesDir, DIR),
) {
    private val lock = Any()
    private val byId = LinkedHashMap<String, BundlePlan>()
    private val _plans = MutableStateFlow<List<BundlePlan>>(emptyList())

    /** Every plan on the phone, oldest first. */
    val plans: StateFlow<List<BundlePlan>> = _plans.asStateFlow()

    /** Where a scan is: the page just looked through (from 1), of how many, and the invoices found so far. */
    data class ScanProgress(val page: Int, val of: Int, val found: Int)

    private val _scans = MutableStateFlow<Map<String, ScanProgress>>(emptyMap())

    /** The scans running now, by plan. */
    val scans: StateFlow<Map<String, ScanProgress>> = _scans.asStateFlow()

    private val _preparing = MutableStateFlow<Set<String>>(emptySet())

    /** The plans whose file is being cut and queued now (a few seconds; "Preparing the invoices…"). */
    val preparing: StateFlow<Set<String>> = _preparing.asStateFlow()

    private val _missing = MutableStateFlow<Set<String>>(emptySet())

    /** Plans closed in this session because their file is no longer on the phone (the screen says so). */
    val missing: StateFlow<Set<String>> = _missing.asStateFlow()

    private val scanJobs = HashMap<String, Job>() // under scanJobs
    /** Job events that came while a plan's jobs were being queued, before their ids were in the plan. */
    private val early = HashMap<String, MutableList<JobEvent>>() // under lock
    /** Plans changed in memory and not yet written. */
    private val dirty = HashSet<String>() // under lock
    private val writeLock = Mutex()
    private val writes = HashSet<Job>() // under writes
    /** Scans, splits and quick looks running (the PDF reader's temporary files are in use meanwhile). */
    private val working = java.util.concurrent.atomic.AtomicInteger()

    init {
        dir.mkdirs()
        load()
    }

    fun plan(id: String): BundlePlan? = synchronized(lock) { byId[id] }

    /**
     * True while the plan still needs its jobs that were saved (for ImportQueue.bundleOpen): it is not done, closed or
     * cancelled. A plan this store does not know is kept, to be safe.
     */
    fun isOpen(id: String): Boolean = plan(id)?.let { it.status !in ENDED } ?: true

    /** Something is being looked through or cut: the reading service stays up, and a restore waits. */
    val busy: Boolean get() = _scans.value.isNotEmpty() || _preparing.value.isNotEmpty() || synchronized(scanJobs) { scanJobs.isNotEmpty() }

    /**
     * Files the plans still need, not to be cleaned up as orphans and to be put back after a restore: each file of many
     * invoices, and the e-invoice files of a file of many, for every plan but a cancelled one. The original is kept
     * after the import is closed, until the owner decides how long (question 3).
     */
    fun filePaths(): Set<String> = synchronized(lock) {
        byId.values.filter { it.status != BundleStatus.CANCELLED }.flatMap { p ->
            listOfNotNull(p.file.ifBlank { null }) + p.invoices.mapNotNull { it.file }
        }.toSet()
    }

    /** A plan that was read as one document (it holds no invoice of its own). */
    fun isOneDocument(p: BundlePlan): Boolean = p.status == BundleStatus.CLOSED && p.invoices.isEmpty() && p.file.isNotBlank()

    /**
     * The plan the same file (by its SHA-256) can carry on with: one not closed or cancelled. A done plan that left
     * invoices for the next import (more than [BundleWizard.MAX_INVOICES]) is closed instead, so the file starts again
     * with the rest.
     */
    suspend fun reopenable(sha: String): BundlePlan? {
        val p = plan(sha) ?: return null
        if (p.status == BundleStatus.CLOSED || p.status == BundleStatus.CANCELLED) return null
        if (p.file.isNotBlank() && !fileStore.file(p.file).exists()) {
            closeMissing(p.id)
            return null
        }
        if (p.status == BundleStatus.DONE && p.invoices.any { it.leftOut == com.kitchenreceipts.core.LeftOut.NEXT_IMPORT }) {
            update(p.id) { BundleWizard.close(it) }
            return null
        }
        return p
    }

    // ------------------------------------------------------------------ new plans

    /** A PDF whose first pages were looked through ([signals]) and that holds more pages: the rest is looked through in the background. */
    suspend fun startScanning(file: StoredFile, signals: List<InvoiceBundle.PageSignals>): BundlePlan {
        val p = BundlePlan(
            id = file.sha256, file = file.relativePath, pageCount = file.pageCount, status = BundleStatus.SCANNING,
            scanned = signals.size, signals = signals, createdAt = System.currentTimeMillis(),
        )
        put(p)
        log.event("BUNDLE_SCAN_START", "bundle" to p.id.take(8), "pages" to p.pageCount, "from" to p.scanned)
        startScan(p.id)
        return p
    }

    /** A PDF looked through in full and found to hold two or more invoices: ready for the operator to sort. */
    suspend fun planFromScan(file: StoredFile, signals: List<InvoiceBundle.PageSignals>): BundlePlan {
        val base = BundlePlan(
            id = file.sha256, file = file.relativePath, pageCount = file.pageCount, status = BundleStatus.SCANNING,
            scanned = signals.size, signals = signals, createdAt = System.currentTimeMillis(),
        )
        val p = planned(base)
        put(p)
        logPlanned(p)
        return p
    }

    /** A long PDF that can only be read as one document: the operator is asked first ("Read the first 20 pages"). */
    suspend fun notSplittable(file: StoredFile, signals: List<InvoiceBundle.PageSignals> = emptyList()): BundlePlan {
        val p = BundlePlan(
            id = file.sha256, file = file.relativePath, pageCount = file.pageCount, status = BundleStatus.NOT_SPLITTABLE,
            scanned = signals.size, signals = signals, createdAt = System.currentTimeMillis(),
        )
        put(p)
        log.event("BUNDLE_NOT_SPLITTABLE", "bundle" to p.id.take(8), "pages" to p.pageCount)
        return p
    }

    /**
     * An e-invoice file of many invoices ([sha]: the file's): the plan is made straight from the exact values of each
     * invoice, with no pages to look at. Credit notes are in the plan, left out (never imported), so they are counted.
     */
    suspend fun planFromEInvoices(sha: String, entries: List<EInvoiceEntry>): BundlePlan {
        val own = ownVat()
        val invoices = entries.mapIndexed { i, e ->
            val buyer = e.buyerId?.uppercase()?.filterNot(Char::isWhitespace)?.removePrefix("IT")?.takeIf { it.length == 11 && it.all(Char::isDigit) }
            PlannedInvoice(
                first = i, last = i, start = InvoiceBundle.Start.NONE, file = e.file?.relativePath,
                supplierVat = e.sellerVat?.let { v -> v.removePrefix("IT").takeIf { it.length == 11 && it.all(Char::isDigit) } ?: v },
                supplierName = e.sellerName, number = e.number, date = e.date,
                printedTotalCents = e.totalCents, totalProven = e.totalCents != null, creditNote = e.creditNote,
                // Addressed to another business: left out unless the operator ticks it, as in a PDF.
                flags = if (own != null && buyer != null && buyer != own) setOf(InvoiceBundle.Flag.OTHER_BUYER) else emptySet(),
                suggestion = if (e.creditNote) null else SupplierCategories.suggestFromLines(e.rows),
            )
        }
        val base = BundlePlan(id = sha, file = "", pageCount = entries.size, status = BundleStatus.SORTING, createdAt = System.currentTimeMillis())
        val p = BundleWizard.prepare(base, invoices, memory(), savedOrEmpty())
        put(p)
        logPlanned(p)
        return p
    }

    private suspend fun planned(base: BundlePlan): BundlePlan =
        BundleWizard.plan(base, InvoiceBundle.segments(base.signals, ownVat()), memory(), savedOrEmpty())

    private fun logPlanned(p: BundlePlan) = log.event(
        "BUNDLE_PLANNED", "bundle" to p.id.take(8), "pages" to p.pageCount, "invoices" to p.invoices.count { it.isInvoice },
        "toRead" to BundleWizard.toRead(p).size, "flagged" to p.invoices.count { it.flags.isNotEmpty() },
        "nextImport" to p.invoices.count { it.leftOut == com.kitchenreceipts.core.LeftOut.NEXT_IMPORT },
    )

    private suspend fun savedOrEmpty(): List<BundleWizard.Saved> =
        runCatching { saved() }.onFailure { log.error("bundleSaved", it) }.getOrDefault(emptyList())

    private fun ownVat(): String? = settings.ownVatNumber.filter(Char::isDigit).takeIf { it.length == 11 }

    /** The restaurant's own VAT number, as plans compare it (an invoice addressed to another business is left out). */
    fun ownVatNumber(): String? = ownVat()

    // ------------------------------------------------------------------ looking through a PDF

    /**
     * The signals of the first [pages] pages of [file] (all of them when null), looked through now. Null when the PDF
     * cannot be looked through (the reading with Android's own PDF reader may still work).
     */
    suspend fun look(file: StoredFile, pages: Int? = null): List<InvoiceBundle.PageSignals>? = try {
        working { lookNow(file, pages) }
    } finally {
        cleanTempIfIdle()
    }

    private suspend fun lookNow(file: StoredFile, pages: Int?): List<InvoiceBundle.PageSignals>? =
        withContext(Dispatchers.Default) {
            val own = ownVat()
            val out = ArrayList<InvoiceBundle.PageSignals>()
            try {
                PdfText.scanPages(
                    fileStore.file(file.relativePath), tempDir = fileStore.pdfboxTempDir(),
                    isActive = { pages == null || out.size < pages },
                ) { i, glyphs, aspect -> out += BundleScanner.signalsOf(i, glyphs, aspect, own) }
                out
            } catch (e: PdfText.Problem) {
                log.event("BUNDLE_LOOK_FAILED", "why" to e.javaClass.simpleName)
                null
            }
        }

    /** Starts (or carries on) looking through the plan's file in the background, unless it is running already. */
    fun startScan(id: String) {
        if (plan(id)?.status != BundleStatus.SCANNING) return
        synchronized(scanJobs) {
            if (scanJobs[id]?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    scan(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("bundleScan", e)
                }
            }
            scanJobs[id] = job
            job.invokeOnCompletion {
                synchronized(scanJobs) { if (scanJobs[id] === job) scanJobs.remove(id) }
                _scans.update { it - id }
                cleanTempIfIdle()
            }
            job.start()
        }
    }

    /** Every scan that was stopped (the app closed, Android paused it) carries on from its last saved page. */
    fun resumeScans() {
        plans.value.filter { it.status == BundleStatus.SCANNING }.forEach { startScan(it.id) }
    }

    /**
     * Looks through the rest of the plan's file, from the last saved page, saving the progress every
     * [BundleScanner.CHECKPOINT_PAGES] pages. Then: two or more invoices make the plan ready to sort; otherwise the file
     * is read as one document (up to 20 pages) or the operator is asked (longer).
     */
    suspend fun scan(id: String): BundlePlan? {
        val start = plan(id)?.takeIf { it.status == BundleStatus.SCANNING } ?: return null
        val file = fileStore.file(start.file)
        if (!file.exists()) return closeMissing(id)
        _scans.update { it + (id to ScanProgress(start.scanned, start.pageCount, 0)) }
        val result = working {
            try {
                BundleScanner.scan(
                    file, ownVat(), previous = start.signals, tempDir = fileStore.pdfboxTempDir(),
                    onProgress = { page, of, found -> _scans.update { it + (id to ScanProgress(page, of, found)) } },
                    onCheckpoint = { signals -> change(id) { p -> if (p.status == BundleStatus.SCANNING) p.copy(scanned = signals.size, signals = signals) else p } },
                )
            } catch (e: PdfText.Problem) {
                log.event("BUNDLE_SCAN_FAILED", "bundle" to id.take(8), "why" to e.javaClass.simpleName)
                null
            }
        }
        val now = plan(id)?.takeIf { it.status == BundleStatus.SCANNING } ?: return plan(id) // cancelled meanwhile
        val signals = result?.signals ?: now.signals
        val pages = result?.pageCount ?: now.pageCount
        val base = now.copy(scanned = signals.size, signals = signals, pageCount = pages)
        val segments = InvoiceBundle.segments(signals, ownVat())
        log.event("BUNDLE_SCANNED", "bundle" to id.take(8), "pages" to pages, "invoices" to segments.count { it.kind == InvoiceBundle.Kind.INVOICE })
        return when {
            result != null && InvoiceBundle.usable(signals) && InvoiceBundle.isBundle(segments) -> {
                val p = planned(base)
                val now2 = update(id) { if (it.status == BundleStatus.SCANNING) p else it }
                if (now2?.status == BundleStatus.SORTING) {
                    logPlanned(p)
                    notifier.bundleScanned(id)
                }
                now2
            }
            // One document, or a file the app cannot look through: read as today when it fits.
            pages <= InvoiceBundle.PAGE_LIMIT -> {
                update(id) { if (it.status == BundleStatus.SCANNING) base else it }
                readAsOne(id, SOURCE_ONE)
                plan(id)
            }
            else -> {
                val p = update(id) { if (it.status == BundleStatus.SCANNING) base.copy(status = BundleStatus.NOT_SPLITTABLE) else it }
                if (p?.status == BundleStatus.NOT_SPLITTABLE) notifier.bundleLong(id, pages)
                p
            }
        }
    }

    private suspend fun <T> working(block: suspend () -> T): T {
        working.incrementAndGet()
        try {
            return block()
        } finally {
            working.decrementAndGet()
        }
    }

    /** Clears the PDF reader's temporary files when nothing is looking through or cutting a file. */
    fun cleanTempIfIdle() {
        if (working.get() > 0 || busy) return
        runCatching { fileStore.pdfboxTempDir().listFiles()?.forEach { it.deleteRecursively() } }
    }

    // ------------------------------------------------------------------ the operator's choices

    /** Saves a change the wizard made (every choice is saved at once, so leaving and coming back resumes). */
    suspend fun update(id: String, f: (BundlePlan) -> BundlePlan): BundlePlan? {
        val p = change(id, f) ?: return null
        persist(id)
        return p
    }

    /** What [confirm] and [readRest] came to. */
    sealed interface Prepared {
        /** [count] invoices were queued to be read. */
        data class Queued(val count: Int) : Prepared

        /** Nothing was queued; [reason] says why in the operator's words. */
        data class Failed(val reason: SplitFailure) : Prepared
    }

    /**
     * The operator taps Read: the invoices to read are cut out of the file (or, from an e-invoice file, taken as they
     * are) and queued in plan order (the first invoice of each new supplier first). All or nothing: when the file
     * cannot be cut, nothing is queued and the plan is back at the Read step.
     */
    suspend fun confirm(id: String): Prepared {
        val p = plan(id) ?: return Prepared.Failed(SplitFailure.OTHER)
        if (p.status != BundleStatus.SORTING) return Prepared.Queued(0)
        update(id) { BundleWizard.confirm(it) }
        log.event("BUNDLE_CONFIRMED", "bundle" to id.take(8), "toRead" to BundleWizard.toRead(p).size)
        return queueWaiting(id) { it.copy(status = BundleStatus.SORTING, step = BundleWizard.Step.READ) }
    }

    /** "Read the N not read yet": the invoices stopped before are cut from the kept file again and queued. */
    suspend fun readRest(id: String): Prepared {
        val p = plan(id) ?: return Prepared.Failed(SplitFailure.OTHER)
        if (p.invoices.none { it.outcome == InvoiceOutcome.STOPPED }) return Prepared.Queued(0)
        if (p.file.isNotBlank() && !fileStore.file(p.file).exists()) return Prepared.Failed(SplitFailure.DAMAGED)
        val before = p.status
        update(id) { BundleWizard.readRest(it) }
        log.event("BUNDLE_READ_REST", "bundle" to id.take(8), "invoices" to p.invoices.count { it.outcome == InvoiceOutcome.STOPPED })
        return queueWaiting(id) { q ->
            q.copy(status = before, invoices = q.invoices.map { if (it.outcome == InvoiceOutcome.WAITING) it.copy(outcome = InvoiceOutcome.STOPPED) else it })
        }
    }

    /** Cuts and queues the plan's waiting invoices; [undo] puts the plan back when that fails. */
    private suspend fun queueWaiting(id: String, undo: (BundlePlan) -> BundlePlan): Prepared {
        _preparing.update { it + id }
        try {
            val p = plan(id) ?: return Prepared.Failed(SplitFailure.OTHER)
            val order = BundleWizard.planOrder(p).filter { p.invoices[it].outcome == InvoiceOutcome.WAITING }
            if (order.isEmpty()) {
                update(id) { settle(it.copy(status = BundleStatus.QUEUED)) }
                return Prepared.Queued(0)
            }
            val files: List<StoredFile> = if (p.file.isBlank()) {
                // An e-invoice file: each invoice is its own stored XML already.
                val xmls = order.map { i -> p.invoices[i].file?.let { fileStore.storedXml(it) } }
                if (xmls.any { it == null }) {
                    update(id, undo)
                    return Prepared.Failed(SplitFailure.DAMAGED)
                }
                xmls.filterNotNull()
            } else {
                val bundle = StoredFile(p.file, FileStore.MIME_PDF, p.pageCount, p.id)
                when (val r = working { fileStore.splitPdf(bundle, order.map { p.invoices[it].first..p.invoices[it].last }) }) {
                    is SplitResult.Done -> r.parts
                    is SplitResult.Failed -> {
                        log.event("BUNDLE_SPLIT_FAILED", "bundle" to id.take(8), "why" to r.reason)
                        update(id, undo)
                        return Prepared.Failed(r.reason)
                    }
                }
            }
            // Cancelled while it was being cut: nothing is queued, and the parts just cut go.
            if (plan(id)?.status != BundleStatus.PREPARING) {
                if (p.file.isNotBlank()) withContext(Dispatchers.IO) { files.forEach { fileStore.delete(it.relativePath) } }
                return Prepared.Queued(0)
            }
            val jobs = queue.enqueueAll(
                files, SOURCE, bundleId = id,
                categories = order.map { p.supplierOf(p.invoices[it])?.category },
                // Any doubt about its pages keeps the invoice from being saved by the app (BUNDLE_CHECK).
                flags = order.map { i -> p.invoices[i].flags.map { it.name }.toSet() },
                orders = order,
            )
            synchronized(lock) {
                var q = BundleWizard.queued(byId[id] ?: return Prepared.Queued(jobs.size), order.zip(jobs).associate { (i, j) -> i to j.id })
                early.remove(id)?.forEach { q = apply(q, it) }
                set(settle(q))
            }
            persist(id)
            log.event("BUNDLE_QUEUED", "bundle" to id.take(8), "jobs" to jobs.size)
            return Prepared.Queued(jobs.size)
        } finally {
            _preparing.update { it - id }
            cleanTempIfIdle()
        }
    }

    /** Read as one document (the operator's answer "Read the first 20 pages", or a file that holds one invoice). */
    suspend fun readAsOne(id: String, source: String = SOURCE_ONE): ImportJob? {
        val p = plan(id) ?: return null
        if (p.status != BundleStatus.NOT_SPLITTABLE && p.status != BundleStatus.SCANNING) return null
        synchronized(scanJobs) { scanJobs[id] }?.takeIf { it != kotlin.coroutines.coroutineContext[Job] }?.cancelAndJoin()
        val job = queue.enqueue(StoredFile(p.file, FileStore.MIME_PDF, p.pageCount, p.id), source)
        // Nothing more to plan: the document is read and checked as any other.
        update(id) { BundleWizard.close(it.copy(invoices = emptyList())) }
        log.event("BUNDLE_READ_AS_ONE", "bundle" to id.take(8), "pages" to p.pageCount, "job" to job.id.take(8))
        return job
    }

    /**
     * "Stop reading this file": the invoices not saved yet are not read now (their jobs and readings go); the saved
     * ones are untouched. They can be read later ("Read the N not read yet").
     */
    suspend fun stop(id: String) {
        val p = plan(id) ?: return
        val jobs = BundleWizard.unsavedJobs(p)
        update(id) { BundleWizard.stop(it) }
        // A part cut from a PDF goes (it is cut again from the kept file); an e-invoice is the original: it stays.
        jobs.forEach { queue.discard(it, deleteFile = p.file.isNotBlank()) }
        log.event("BUNDLE_STOPPED", "bundle" to id.take(8), "jobs" to jobs.size)
    }

    /**
     * Cancels an import before anything from it was saved (stopping a scan, "Don't import", "Cancel the import"):
     * like discarding a queued import, its jobs and its file go, unless a saved document uses the file. When something
     * was saved already, it only stops (nothing saved changes, and the file stays). False then.
     */
    suspend fun cancel(id: String): Boolean {
        val p = plan(id) ?: return false
        if (p.invoices.any { it.outcome == InvoiceOutcome.SAVED_BY_APP || it.outcome == InvoiceOutcome.SAVED_BY_YOU }) {
            stop(id)
            return false
        }
        synchronized(scanJobs) { scanJobs[id] }?.cancelAndJoin()
        val jobs = BundleWizard.unsavedJobs(p)
        val done = update(id) { BundleWizard.close(BundleWizard.stop(it), BundleStatus.CANCELLED) } ?: return false
        jobs.forEach { queue.discard(it) }
        withContext(Dispatchers.IO) {
            val queued = queue.filePaths()
            (listOfNotNull(p.file.ifBlank { null }) + p.invoices.mapNotNull { it.file })
                .filter { it !in queued && it !in filePaths() && !runCatching { fileInUse(it) }.getOrDefault(true) }
                .forEach { fileStore.delete(it) }
        }
        log.event("BUNDLE_CANCELLED", "bundle" to id.take(8), "status" to p.status, "jobs" to jobs.size)
        return done.status == BundleStatus.CANCELLED
    }

    /**
     * "Close" after the last invoice: the plan keeps only pages, outcomes and document ids (no names, VAT numbers or
     * numbers). Anything not saved yet is stopped first. The original file stays on the phone.
     */
    suspend fun close(id: String) {
        val p = plan(id) ?: return
        if (BundleWizard.unsavedJobs(p).isNotEmpty()) stop(id)
        update(id) { BundleWizard.close(it) }
        log.event("BUNDLE_CLOSED", "bundle" to id.take(8))
    }

    /** Closes a plan whose file is gone (it cannot be looked through or cut any more); the screen says so. */
    private suspend fun closeMissing(id: String): BundlePlan? {
        log.event("BUNDLE_FILE_MISSING", "bundle" to id.take(8))
        _missing.update { it + id }
        return update(id) { BundleWizard.close(it) }
    }

    // ------------------------------------------------------------------ the reading service

    /**
     * Android stopped the background service (its time for the day is up): scans stop at their last saved page, and
     * the plans being read show as paused. True when anything was going on.
     */
    fun pause(): Boolean {
        val scanning = synchronized(scanJobs) { scanJobs.values.toList() }
        scanning.forEach { it.cancel() }
        val reading = plans.value.filter { it.status == BundleStatus.QUEUED }
        reading.forEach { p -> change(p.id) { if (it.status == BundleStatus.QUEUED) it.copy(status = BundleStatus.PAUSED) else it } }
        if (scanning.isNotEmpty() || reading.isNotEmpty()) log.event("BUNDLE_PAUSED", "scans" to scanning.size, "plans" to reading.size)
        return scanning.isNotEmpty() || reading.isNotEmpty()
    }

    /** The app is in front again: paused plans carry on, and so do stopped scans. */
    fun resume() {
        notifier.cancelPaused()
        plans.value.filter { it.status == BundleStatus.PAUSED }.forEach { p ->
            change(p.id) { if (it.status == BundleStatus.PAUSED) settle(it.copy(status = BundleStatus.QUEUED)) else it }
        }
        resumeScans()
    }

    // ------------------------------------------------------------------ what happens to each job

    /** A job of a plan moved on (see ImportQueue.listener). Called on any thread; quick. */
    fun onJobEvent(e: JobEvent) {
        val id = e.bundleId ?: return
        val launch = synchronized(lock) {
            val p = byId[id] ?: return
            val n = if (p.invoices.none { it.jobId == e.jobId }) {
                when {
                    // Its id is not in the plan yet: the jobs are being queued right now.
                    p.status == BundleStatus.PREPARING -> {
                        early.getOrPut(id) { mutableListOf() } += e
                        return
                    }
                    // Saved by the app just as its reading was stopped: it stays saved.
                    (e.kind == JobEvent.Kind.SAVED_BY_APP || e.kind == JobEvent.Kind.SAVED_BY_YOU) && e.order != null &&
                        p.invoices.getOrNull(e.order)?.let { it.jobId == null && it.outcome == InvoiceOutcome.STOPPED } == true -> {
                        val outcome = if (e.kind == JobEvent.Kind.SAVED_BY_APP) InvoiceOutcome.SAVED_BY_APP else InvoiceOutcome.SAVED_BY_YOU
                        p.copy(invoices = p.invoices.mapIndexed { i, inv -> if (i == e.order) inv.copy(outcome = outcome, documentId = e.documentId) else inv })
                    }
                    else -> return
                }
            } else {
                apply(p, e)
            }
            if (n == p) return
            set(n)
            dirty.add(id)
        }
        if (launch) writeSoon(id)
    }

    /** One event recorded in the plan. */
    private fun apply(p: BundlePlan, e: JobEvent): BundlePlan {
        val current = p.invoices.firstOrNull { it.jobId == e.jobId } ?: return p
        fun record(o: InvoiceOutcome) = BundleWizard.record(p, e.jobId, o, e.documentId)
        return when (e.kind) {
            JobEvent.Kind.READY -> if (current.outcome == InvoiceOutcome.CHECK_LATER) p else record(InvoiceOutcome.READY)
            JobEvent.Kind.SAVED_BY_APP -> record(InvoiceOutcome.SAVED_BY_APP)
            JobEvent.Kind.SAVED_BY_YOU -> record(InvoiceOutcome.SAVED_BY_YOU)
            JobEvent.Kind.FAILED -> record(InvoiceOutcome.FAILED)
            // A document the app saved, hidden from the list: still saved.
            JobEvent.Kind.DISMISSED -> p
            // Thrown away by the operator, unsaved: left out of this import.
            JobEvent.Kind.DISCARDED -> record(InvoiceOutcome.LEFT_OUT)
            JobEvent.Kind.RESTORED -> when (e.status) {
                JobStatus.SAVED -> record(InvoiceOutcome.SAVED_BY_APP)
                JobStatus.READY -> if (current.outcome == InvoiceOutcome.CHECK_LATER) p else record(InvoiceOutcome.READY)
                JobStatus.QUEUED, JobStatus.READING -> record(InvoiceOutcome.QUEUED)
                JobStatus.FAILED -> record(InvoiceOutcome.FAILED)
                null -> p
            }
        }
    }

    /**
     * After the queue was loaded at start: each plan being read is set right from its jobs as they are now. A plan cut
     * when the app was closed gets the jobs that were queued (or goes back to the Read step when none was), and an
     * invoice whose job is gone (its file was missing) counts as not read yet.
     */
    fun afterRestore(jobs: List<ImportJob>) {
        val mine = jobs.filter { it.bundleId != null }.groupBy { it.bundleId!! }
        val reading = setOf(BundleStatus.PREPARING, BundleStatus.QUEUED, BundleStatus.PAUSED)
        for (p0 in plans.value.filter { it.status in reading }) {
            val own = mine[p0.id].orEmpty()
            change(p0.id) { start ->
                synchronized(lock) { early.remove(start.id) }
                var p = start
                if (p.status == BundleStatus.PREPARING) {
                    val known = p.invoices.mapNotNull { it.jobId }.toSet()
                    val fresh = own.filter { j -> j.id !in known && j.order != null && p.invoices.getOrNull(j.order)?.jobId == null }
                    p = when {
                        fresh.isNotEmpty() -> BundleWizard.queued(p, fresh.associate { it.order!! to it.id })
                        known.isEmpty() -> p.copy(status = BundleStatus.SORTING, step = BundleWizard.Step.READ)
                        else -> p.copy(status = BundleStatus.QUEUED)
                    }
                    // Invoices of a cut that did not finish: read again on request.
                    if (p.status == BundleStatus.QUEUED) {
                        p = p.copy(invoices = p.invoices.map { if (it.outcome == InvoiceOutcome.WAITING && it.jobId == null) it.copy(outcome = InvoiceOutcome.STOPPED) else it })
                    }
                }
                if (p.status == BundleStatus.SORTING) return@change p
                for (j in own) p = apply(p, JobEvent(JobEvent.Kind.RESTORED, j.id, j.bundleId, j.order, j.savedDocumentId, j.status))
                val ids = own.map { it.id }.toSet()
                p = p.copy(invoices = p.invoices.map {
                    if (it.jobId != null && it.jobId !in ids && it.outcome in OPEN) it.copy(outcome = InvoiceOutcome.STOPPED, jobId = null) else it
                })
                settle(p)
            }
        }
    }

    /** A plan being read with nothing left to read or check is done. */
    private fun settle(p: BundlePlan): BundlePlan {
        val reading = p.status == BundleStatus.QUEUED || p.status == BundleStatus.PAUSED
        return if (reading && p.invoices.none { it.outcome in OPEN }) p.copy(status = BundleStatus.DONE) else p
    }

    // ------------------------------------------------------------------ storage

    private fun set(p: BundlePlan) { // under lock
        byId[p.id] = p
        _plans.value = byId.values.sortedBy { it.createdAt }
    }

    /** Changes a plan in memory at once, and on the phone soon after (never on the calling thread). */
    private fun change(id: String, f: (BundlePlan) -> BundlePlan): BundlePlan? {
        val (p, launch) = synchronized(lock) {
            val old = byId[id] ?: return null
            val new = f(old)
            if (new == old) return old
            set(new)
            new to dirty.add(id)
        }
        if (launch) writeSoon(id)
        return p
    }

    /** A new plan (or a new one for the same file), written before anything else happens to it. */
    private suspend fun put(p: BundlePlan) {
        synchronized(lock) {
            early.remove(p.id)
            set(p)
        }
        persist(p.id)
    }

    private fun writeSoon(id: String) {
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { writeLock.withLock { write(id) } }
        synchronized(writes) { writes += job }
        job.invokeOnCompletion { synchronized(writes) { writes -= job } }
        job.start()
    }

    private suspend fun persist(id: String) = withContext(Dispatchers.IO) { writeLock.withLock { write(id) } }

    /** Waits until every change so far is on the phone. */
    suspend fun flush() {
        while (true) {
            val next = synchronized(writes) { writes.firstOrNull() } ?: break
            next.join()
        }
    }

    /** Written aside, then put in place in one step: the app stopped mid-write never leaves a cut plan. */
    private fun write(id: String) {
        val p = synchronized(lock) {
            dirty.remove(id)
            byId[id]
        } ?: return
        runCatching {
            val d = File(dir, id).apply { mkdirs() }
            val tmp = File(d, "$PLAN.tmp")
            tmp.outputStream().use { out -> out.write(BundlePlanCodec.encode(p).toByteArray(Charsets.UTF_8)); out.flush(); out.fd.sync() }
            if (!tmp.renameTo(File(d, PLAN))) error("rename")
        }.onFailure { log.error("bundleWrite", it) }
    }

    /** Every plan on the phone. A plan that still needs its file to go on, and lost it, is closed. */
    private fun load() {
        val loaded = dir.listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
            runCatching { File(d, PLAN).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.let(BundlePlanCodec::decode) }
                .onFailure { log.error("bundleLoad", it) }.getOrNull()?.takeIf { it.id == d.name }
        }.orEmpty()
        val needFile = setOf(BundleStatus.SCANNING, BundleStatus.NOT_SPLITTABLE, BundleStatus.SORTING, BundleStatus.PREPARING)
        val gone = mutableListOf<String>()
        synchronized(lock) {
            for (p in loaded) {
                val lost = p.status in needFile && p.file.isNotBlank() && !fileStore.file(p.file).exists()
                if (lost) gone += p.id
                byId[p.id] = if (lost) BundleWizard.close(p) else p
            }
            _plans.value = byId.values.sortedBy { it.createdAt }
            dirty.addAll(gone)
        }
        if (gone.isNotEmpty()) {
            _missing.update { it + gone }
            log.event("BUNDLE_FILE_MISSING", "plans" to gone.size)
            gone.forEach(::writeSoon)
        }
    }

    companion object {
        const val DIR = "bundles"
        private const val PLAN = "plan.txt"
        /** ImportJob.source of an invoice cut from a file of many. */
        const val SOURCE = "bundle"
        /** ImportJob.source of a long file read as one document. */
        const val SOURCE_ONE = "file"
        private val ENDED = setOf(BundleStatus.DONE, BundleStatus.CLOSED, BundleStatus.CANCELLED)
        private val OPEN = setOf(InvoiceOutcome.WAITING, InvoiceOutcome.QUEUED, InvoiceOutcome.READY, InvoiceOutcome.CHECK_LATER)
    }
}
