package com.kitchenreceipts.app.jobs

import android.content.Context
import com.kitchenreceipts.app.diagnostics.AppLog
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.app.ocr.AiUse
import com.kitchenreceipts.app.ocr.ImportProcessor
import com.kitchenreceipts.app.ocr.ImportProgress
import com.kitchenreceipts.app.ocr.OcrEngine
import com.kitchenreceipts.app.ocr.PendingImport
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.AiScope
import com.kitchenreceipts.core.OcrLineCodec
import com.kitchenreceipts.core.ReviewReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import java.util.UUID

enum class JobStatus { QUEUED, READING, READY, SAVED, FAILED }

/** A document being read in the background, or read and waiting for the operator. */
data class ImportJob(
    val id: String,
    val createdAt: Long,
    val source: String,
    val file: StoredFile,
    val status: JobStatus,
    val progress: ImportProgress? = null,
    /** The finished reading (READY), kept in memory; rebuilt from disk after a restart. */
    val pending: PendingImport? = null,
    /** SAVED: the document the app saved by itself. */
    val savedDocumentId: Long? = null,
    val error: String? = null,
    /** For the list and the notification. */
    val sellerName: String? = null,
    val totalCents: Long? = null,
    val itemCount: Int = 0,
    /** Readings started (kept on the phone): a document that stops the app is not read again and again. */
    val attempts: Int = 0,
    /** One invoice cut from a file of many: that file's plan (its SHA-256, see BundlePlan.id); null for a single import. */
    val bundleId: String? = null,
    /** Its invoice's place in the plan (BundlePlan.invoices). */
    val order: Int? = null,
    /** The supplier category chosen for it (a SupplierCategory key, or "later"), when one was chosen. */
    val category: String? = null,
    /** Why its pages need a look (InvoiceBundle.Flag names): any flag keeps it from being saved by the app. */
    val flags: Set<String> = emptySet(),
    /** For the list once read: values still unchecked, and why it needs a look (also when its reading is not in memory). */
    val uncertainCount: Int = 0,
    val reasons: List<ReviewReason> = emptyList(),
)

/**
 * What happened to a job, for whoever keeps track of a file of many invoices (see [ImportQueue.listener]): the queue
 * forgets saved and discarded jobs, so their outcome is handed out as it happens.
 */
data class JobEvent(
    val kind: Kind,
    val jobId: String,
    val bundleId: String?,
    /** The job's [ImportJob.order]. */
    val order: Int?,
    /** SAVED_BY_APP and SAVED_BY_YOU: the saved document. RESTORED: the document the app saved by itself, if any. */
    val documentId: Long? = null,
    /** RESTORED: the job's status as loaded after a restart (a reading cut short comes back QUEUED). */
    val status: JobStatus? = null,
) {
    enum class Kind {
        /** Read: waiting for the operator. */
        READY,
        /** Read and saved by the app (every value checked out). */
        SAVED_BY_APP,
        /** Saved by the operator from the review. */
        SAVED_BY_YOU,
        /** Could not be read (or stopped the app twice). */
        FAILED,
        /** A document the app saved by itself, hidden from the list. */
        DISMISSED,
        /** Thrown away (or its reading stopped) by the operator, unsaved. */
        DISCARDED,
        /** Loaded again after the app was closed. */
        RESTORED,
        /** Its prices changed by 5% or more (a file of many posts one message for all its invoices). */
        PRICES_CHANGED,
    }
}

/**
 * Documents are read one after another in the background, so the operator can keep working (or scan the next
 * one) while a document is read — the AI reader can take minutes. The queue survives the app being closed: each
 * job's file, status and what was read are kept in the app's private storage, so a finished reading is not lost
 * and an unfinished one starts again.
 */
class ImportQueue(
    context: Context,
    private val scope: CoroutineScope,
    private val processor: ImportProcessor,
    private val engine: OcrEngine,
    private val settings: AppSettings,
    private val aiUse: () -> AiUse?,
    private val preparer: DraftPreparer,
    private val fileStore: FileStore,
    private val log: AppLog,
    private val notifier: Notifier,
    /** True when a saved document uses the file: it is then never deleted with the job. */
    private val fileInUse: suspend (String) -> Boolean = { false },
    /** Where the reading runs: threads of background priority, so the screen in use stays smooth. */
    private val worker: CoroutineDispatcher = ReadingThreads.dispatcher,
) {
    private val dir = File(context.filesDir, "jobs").apply { mkdirs() }
    private val appContext = context.applicationContext
    private val _jobs = MutableStateFlow<List<ImportJob>>(emptyList())
    val jobs: StateFlow<List<ImportJob>> = _jobs.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    // Written by the worker, read by discard() on the main thread.
    @Volatile private var current: Job? = null
    @Volatile private var currentId: String? = null
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Called when a document read in the background was saved without review (keeps its reading, see ReadingArchive);
     * the third value is the job's file of many invoices, if any.
     */
    @Volatile var onAutoSaved: (Long, PendingImport, String?) -> Unit = { _, _, _ -> }

    /**
     * Told what happens to every job (read, saved by the app or the operator, failed, hidden, thrown away, loaded
     * after a restart). Called on the thread where it happens (the reading's, the screen's): quick and thread-safe.
     */
    @Volatile var listener: (JobEvent) -> Unit = {}

    /**
     * True while the plan of a file of many invoices still needs its jobs that were saved (its import is not done):
     * those are not cleared at the next start. Unknown plans are kept.
     */
    @Volatile var bundleOpen: (String) -> Boolean = { true }

    /**
     * Readings of invoices cut from a file of many, rebuilt when opened (see [load]): the last few only, so a file of
     * hundreds of invoices does not fill the memory.
     */
    private val kept = object : LinkedHashMap<String, PendingImport>(LAZY_KEEP + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingImport>?): Boolean = size > LAZY_KEEP
    }

    /** Jobs open in a review now: the app never saves one of them by itself meanwhile (see [recheck]). */
    private val inReview = java.util.Collections.synchronizedSet(HashSet<String>())

    private val _rechecking = MutableStateFlow<Set<String>>(emptySet())

    /** Jobs being checked again right now (see [recheck]): not opened for a check meanwhile. */
    val rechecking: StateFlow<Set<String>> = _rechecking.asStateFlow()
    private val recheckLock = Mutex()

    /** True while something is still to be read (the foreground service stays up meanwhile). */
    val busy: Boolean get() = _jobs.value.any { it.status == JobStatus.QUEUED || it.status == JobStatus.READING }

    fun job(id: String): ImportJob? = _jobs.value.firstOrNull { it.id == id }

    /** Where the operator's unsaved corrections to this job's document are kept (removed with the job). */
    fun draftFile(id: String): File = File(File(dir, id), "draft.json")

    /** Files still needed by the queue (not to be cleaned up as orphans). */
    fun filePaths(): Set<String> = _jobs.value.filter { it.status != JobStatus.SAVED }.map { it.file.relativePath }.toSet()

    /** Queues one document. Its details are written to the phone off the main thread (each write waits for the disk). */
    suspend fun enqueue(file: StoredFile, source: String): ImportJob {
        val job = ImportJob(UUID.randomUUID().toString(), System.currentTimeMillis(), source, file, JobStatus.QUEUED)
        withContext(Dispatchers.IO) { writeMeta(job) }
        _jobs.update { it + job }
        log.event("JOB_QUEUED", "job" to job.id.take(8), "source" to source, "pages" to file.pageCount)
        startWorker()
        wake.trySend(Unit)
        return job
    }

    /**
     * Queues the invoices cut from one file at once, in [files] order: one write of the list, one start of the
     * reader. [categories], [flags] and [orders] go with the file at the same index (orders default to the index).
     * The order survives a restart (each job is created a millisecond after the one before).
     */
    suspend fun enqueueAll(
        files: List<StoredFile>,
        source: String,
        bundleId: String? = null,
        categories: List<String?> = emptyList(),
        flags: List<Set<String>> = emptyList(),
        orders: List<Int>? = null,
    ): List<ImportJob> {
        if (files.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val jobs = files.mapIndexed { i, f ->
            ImportJob(
                UUID.randomUUID().toString(), now + i, source, f, JobStatus.QUEUED,
                bundleId = bundleId, order = orders?.getOrNull(i) ?: i, category = categories.getOrNull(i),
                flags = flags.getOrNull(i).orEmpty(),
            )
        }
        withContext(Dispatchers.IO) { jobs.forEach { writeMeta(it) } }
        _jobs.update { it + jobs }
        log.event("JOBS_QUEUED", "jobs" to jobs.size, "source" to source, "bundle" to bundleId?.take(8), "flagged" to jobs.count { it.flags.isNotEmpty() })
        startWorker()
        wake.trySend(Unit)
        return jobs
    }

    /**
     * The finished reading of a job, for the review: the one in memory, or rebuilt from what was stored (about a
     * second; nothing is read again). An invoice cut from a file of many keeps its reading in memory only while it is
     * one of the last [LAZY_KEEP] opened. Null when the job is gone, not read yet, or its reading cannot be rebuilt
     * (it is then read again, as after a restart).
     */
    suspend fun load(id: String): PendingImport? {
        val job = job(id) ?: return null
        job.pending?.let { return it }
        synchronized(kept) { kept[id] }?.let { return it }
        if (job.status != JobStatus.READY) return null
        val pending = runCatching { loadReading(job) }.onFailure { log.error("loadReading", it) }.getOrNull()
        val now = job(id) ?: return null // discarded meanwhile
        if (pending == null) {
            if (now.status == JobStatus.READY) {
                update(now.copy(status = JobStatus.QUEUED))
                startWorker()
                wake.trySend(Unit)
            }
            return null
        }
        if (now.bundleId == null) update(now.copy(pending = pending), persist = false) else keep(id, pending)
        return pending
    }

    private fun keep(id: String, pending: PendingImport) = synchronized(kept) { kept[id] = pending }

    /**
     * Stops a reading or drops a document that was not saved; its file is deleted, unless [deleteFile] is false (the
     * e-invoice of a file of many stays, to be read again later).
     */
    fun discard(id: String, deleteFile: Boolean = true) {
        val job = job(id) ?: return
        val running = current.takeIf { currentId == id }
        remove(id)
        emit(JobEvent(JobEvent.Kind.DISCARDED, id, job.bundleId, job.order))
        // The file is deleted once the reading has really stopped (it may still be using it), and only when no saved
        // document uses it: the app may have saved it by itself just before being closed, without marking the job.
        scope.launch(Dispatchers.IO) {
            running?.cancelAndJoin()
            if (deleteFile && job.status != JobStatus.SAVED && !runCatching { fileInUse(job.file.relativePath) }.getOrDefault(true)) {
                fileStore.delete(job.file.relativePath)
            }
        }
        log.event("JOB_DISCARDED", "job" to id.take(8), "status" to job.status)
    }

    /**
     * The supplier category chosen for job [id] (a SupplierCategory key, "later", or null): "Just this invoice" on its
     * check, or the supplier's new category for an invoice of a file of many. Kept with the job, on the phone.
     */
    suspend fun setCategory(id: String, category: String?) = withContext(Dispatchers.IO) {
        var changed: ImportJob? = null
        _jobs.update { list -> list.map { if (it.id == id && it.category != category) it.copy(category = category).also { c -> changed = c } else it } }
        changed?.let(::writeMeta)
        Unit
    }

    /** The operator saved the document [documentId] from the review screen (the file now belongs to it). */
    fun markSaved(id: String, documentId: Long? = null) {
        val job = job(id)
        remove(id)
        if (job != null) emit(JobEvent(JobEvent.Kind.SAVED_BY_YOU, id, job.bundleId, job.order, documentId))
    }

    /** Hides a document the app saved by itself from the list. */
    fun dismiss(id: String) {
        val job = job(id)
        remove(id)
        if (job != null) emit(JobEvent(JobEvent.Kind.DISMISSED, id, job.bundleId, job.order, job.savedDocumentId))
    }

    fun retry(id: String) {
        val job = job(id) ?: return
        update(job.copy(status = JobStatus.QUEUED, error = null, progress = null, attempts = 0))
        startWorker()
        wake.trySend(Unit)
    }

    /**
     * Loads the queue after a restart: finished readings are rebuilt (no reading again), unfinished ones re-queued.
     * Invoices cut from a file of many keep their readings on the phone until opened (see [load]).
     */
    fun restore() {
        // Documents the app saved by itself on an earlier day leave the home screen by themselves (no "Hide" tap for
        // each); they are in Documents like any other. Those of a file still being imported stay, for its counts.
        val today = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun stillNeeded(j: ImportJob) = j.bundleId != null && runCatching { bundleOpen(j.bundleId) }.getOrDefault(true)
        val loaded = dir.listFiles()?.mapNotNull { d -> runCatching { readMeta(d) }.getOrNull() }.orEmpty()
            .filter { j -> !(j.status == JobStatus.SAVED && j.createdAt < today && !stillNeeded(j)).also { old -> if (old) File(dir, j.id).deleteRecursively() } }
            .sortedBy { it.createdAt }
            .map { if (it.status == JobStatus.READING) it.copy(status = JobStatus.QUEUED) else it }
        _jobs.value = loaded
        loaded.forEach { j -> emit(JobEvent(JobEvent.Kind.RESTORED, j.id, j.bundleId, j.order, j.savedDocumentId, j.status)) }
        // One after another: rebuilding several readings at once would slow the start and any new reading.
        val ready = loaded.filter { it.status == JobStatus.READY && it.bundleId == null }
        if (ready.isNotEmpty()) scope.launch {
            for (j in ready) {
                val pending = runCatching { loadReading(j) }.getOrNull()
                if (job(j.id) == null) continue // discarded meanwhile
                if (pending != null) update(j.copy(pending = pending)) else update(j.copy(status = JobStatus.QUEUED))
                // Read again: the worker may not be running (only finished readings were waiting at start).
                if (pending == null) { startWorker(); wake.trySend(Unit) }
            }
        }
        if (loaded.any { it.status == JobStatus.QUEUED }) {
            startWorker()
            wake.trySend(Unit)
        }
    }

    private fun startWorker() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            for (signal in wake) {
                while (true) {
                    val next = _jobs.value.firstOrNull { it.status == JobStatus.QUEUED } ?: break
                    val job = scope.launch(worker) { read(next) }
                    current = job
                    currentId = next.id
                    job.join()
                    current = null
                    currentId = null
                }
            }
        }
    }

    private suspend fun read(start: ImportJob) {
        // A reading that did not finish twice (the app was closed by Android or crashed while reading it, for example
        // short of memory with the AI) is not started again by itself: the operator sees it and decides.
        if (start.attempts >= MAX_ATTEMPTS) {
            log.event("JOB_GAVE_UP", "job" to start.id.take(8), "attempts" to start.attempts)
            val failed = start.copy(status = JobStatus.FAILED, error = STOPPED, progress = null)
            update(failed)
            emit(JobEvent(JobEvent.Kind.FAILED, failed.id, failed.bundleId, failed.order))
            if (failed.bundleId == null) notifier.finished(failed)
            return
        }
        var j = start.copy(status = JobStatus.READING, progress = ImportProgress(0, start.file.pageCount, 1), attempts = start.attempts + 1)
        update(j)
        // The second try reads without the AI (the likeliest cause of the app being stopped).
        val ai = if (j.attempts >= 2) null else aiUse()
        if (j.attempts >= 2) log.event("JOB_RETRY_WITHOUT_AI", "job" to j.id.take(8))
        val t0 = System.currentTimeMillis()
        try {
            val pending = processor.process(
                j.file, engine,
                { p -> job(j.id)?.let { update(it.copy(progress = p), persist = false) } },
                settings.parseOptions(), ai,
            )
            saveReading(j, pending)
            log.event(
                "IMPORT_DONE",
                "job" to j.id.take(8), "type" to j.file.mimeType, "pages" to j.file.pageCount, "pagesRead" to pending.pagesRead,
                "ms" to (System.currentTimeMillis() - t0), "ocrLines" to pending.rawLines.sumOf { it.size },
                "ocrError" to pending.ocrError, "reading" to pending.readingNote, "ai" to pending.aiNote,
            )
            // How long reading takes on this phone, for the estimate before a file of many invoices is read.
            if (j.file.mimeType != FileStore.MIME_XML) {
                runCatching { ReadingTime.record(settings, pending.pagesRead, System.currentTimeMillis() - t0, ai != null) }
            }
            val prepared = preparer.withJobCategory(preparer.prepare(pending, j.flags), j.category)
            val d = prepared.draft
            // Discarded while it was being read: nothing is saved.
            if (job(j.id) == null) return
            val savedId = runCatching { preparer.autoSave(pending, prepared) }.onFailure { log.error("autoSave", it) }.getOrNull()
            savedId?.let { id -> runCatching { onAutoSaved(id, pending, j.bundleId) } }
            // An invoice of a file of many waits on the phone, not in memory: it is rebuilt when opened (see load).
            val lazy = savedId == null && j.bundleId != null
            // Dropped while it was being saved: the document is in the app all the same, and its file's plan says so.
            if (job(j.id) == null && savedId != null) emit(JobEvent(JobEvent.Kind.SAVED_BY_APP, j.id, j.bundleId, j.order, savedId))
            j = (job(j.id) ?: return).copy(
                status = if (savedId != null) JobStatus.SAVED else JobStatus.READY,
                pending = if (savedId != null || lazy) null else pending,
                savedDocumentId = savedId,
                progress = null,
                sellerName = d.seller.text.ifBlank { null },
                totalCents = com.kitchenreceipts.core.ItalianNumbers.parseCents(d.total.text),
                itemCount = d.items.size,
                uncertainCount = d.uncertainCount,
                reasons = prepared.reasons,
            )
            if (lazy) keep(j.id, pending)
            update(j)
            if (savedId != null) dropReading(j.id) // saved: the reading is kept with the document (ReadingArchive)
            emit(
                if (savedId != null) JobEvent(JobEvent.Kind.SAVED_BY_APP, j.id, j.bundleId, j.order, savedId)
                else JobEvent(JobEvent.Kind.READY, j.id, j.bundleId, j.order),
            )
            // An invoice of a file of many: no message of its own (its file posts one for all, see BundleStore).
            if (j.bundleId == null) notifier.finished(j)
            val alerts = runCatching { preparer.priceAlerts(savedId, prepared) }.onFailure { log.error("priceAlerts", it) }.getOrDefault(emptyList())
            if (alerts.isNotEmpty()) {
                log.event("PRICE_ALERT", "job" to j.id.take(8), "changes" to alerts.size, "saved" to (savedId != null))
                if (j.bundleId == null) notifier.priceChanges(j, alerts, saved = savedId != null)
                else emit(JobEvent(JobEvent.Kind.PRICES_CHANGED, j.id, j.bundleId, j.order, savedId))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("backgroundImport", e)
            // In the operator's words and language (the technical cause is in the log).
            val message = runCatching {
                com.kitchenreceipts.app.ui.friendlyError(com.kitchenreceipts.app.settings.AppSettings.wrapWithLanguage(appContext).resources, e)
            }.getOrDefault(e.javaClass.simpleName)
            j = (job(j.id) ?: return).copy(status = JobStatus.FAILED, error = message, progress = null)
            update(j)
            emit(JobEvent(JobEvent.Kind.FAILED, j.id, j.bundleId, j.order))
            if (j.bundleId == null) notifier.finished(j)
        }
    }

    /** A review of job [id] opened ([open]) or closed: while open, the app does not save that job by itself. */
    fun reviewing(id: String, open: Boolean) {
        if (open) inReview.add(id) else inReview.remove(id)
    }

    /** The jobs [ids] are about to be checked again ([recheck]): from now on they are not opened for a check meanwhile. */
    fun holdForRecheck(ids: Collection<String>) = _rechecking.update { it + ids }

    /**
     * Checks the read jobs [ids] again, one after another at background priority, with today's rules and gates
     * unchanged: each reading is rebuilt from what was stored (nothing is read again, but the supplier is looked up
     * again), prepared, and saved by the app when it now checks out (for example because its supplier was just saved,
     * so its name is proven by its VAT number). The others stay to check, their reading no longer held in memory. A
     * job open in a review, gone, or no longer waiting is left alone. Returns the documents saved.
     */
    suspend fun recheck(ids: List<String>): List<Long> {
        _rechecking.update { it + ids }
        try {
            return recheckLock.withLock {
                val todo = ids.filter { job(it)?.status == JobStatus.READY }
                val saved = ArrayList<Long>()
                withContext(worker) {
                    for (id in todo) {
                        try {
                            recheckOne(id)?.let { saved += it }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.error("recheck", e)
                        } finally {
                            _rechecking.update { it - id }
                        }
                    }
                }
                if (todo.isNotEmpty()) log.event("RECHECKED", "jobs" to todo.size, "saved" to saved.size)
                saved
            }
        } finally {
            _rechecking.update { it - ids.toSet() }
        }
    }

    private suspend fun recheckOne(id: String): Long? {
        val start = job(id)?.takeIf { it.status == JobStatus.READY && id !in inReview } ?: return null
        // Rebuilt now, not the reading in memory: the supplier saved since is looked up by its VAT number again.
        val pending = loadReading(start) ?: return null
        val prepared = preparer.withJobCategory(preparer.prepare(pending, start.flags), start.category)
        // Opened, saved, thrown away or read again meanwhile: left as it is.
        val now = job(id)?.takeIf { it.status == JobStatus.READY && id !in inReview } ?: return null
        val savedId = runCatching { preparer.autoSave(pending, prepared) }.onFailure { log.error("autoSave", it) }.getOrNull()
        val d = prepared.draft
        if (savedId == null) {
            // Still to check: the list's summary as it is now; an invoice of a file of many is rebuilt again when opened.
            if (now.bundleId != null) synchronized(kept) { kept.remove(id) } else update(now.copy(pending = pending), persist = false)
            update(
                (job(id) ?: return null).copy(
                    sellerName = d.seller.text.ifBlank { null }, totalCents = com.kitchenreceipts.core.ItalianNumbers.parseCents(d.total.text),
                    itemCount = d.items.size, uncertainCount = d.uncertainCount, reasons = prepared.reasons,
                ),
            )
            return null
        }
        runCatching { onAutoSaved(savedId, pending, now.bundleId) }
        val done = (job(id) ?: now).copy(
            status = JobStatus.SAVED, pending = null, savedDocumentId = savedId, progress = null,
            sellerName = d.seller.text.ifBlank { null }, totalCents = com.kitchenreceipts.core.ItalianNumbers.parseCents(d.total.text),
            itemCount = d.items.size, uncertainCount = d.uncertainCount, reasons = prepared.reasons,
        )
        synchronized(kept) { kept.remove(id) }
        if (job(id) != null) update(done)
        dropReading(id)
        emit(JobEvent(JobEvent.Kind.SAVED_BY_APP, id, done.bundleId, done.order, savedId))
        if (done.bundleId == null) notifier.finished(done)
        return savedId
    }

    // ------------------------------------------------------------------ state + storage

    private fun update(job: ImportJob, persist: Boolean = true) {
        _jobs.update { list -> list.map { if (it.id == job.id) job else it } }
        if (persist) writeMeta(job)
    }

    /** The files of a reading no longer needed (the job's status stays, for the list). */
    private fun dropReading(id: String) {
        File(dir, id).listFiles()?.filter { it.name != "meta.properties" }?.forEach { it.delete() }
    }

    private fun remove(id: String) {
        _jobs.update { list -> list.filterNot { it.id == id } }
        synchronized(kept) { kept.remove(id) }
        File(dir, id).deleteRecursively()
    }

    private fun emit(e: JobEvent) {
        runCatching { listener(e) }.onFailure { log.error("jobListener", it) }
    }

    private fun writeMeta(job: ImportJob) {
        val d = File(dir, job.id).apply { mkdirs() }
        val p = Properties()
        p["createdAt"] = job.createdAt.toString()
        p["source"] = job.source
        p["path"] = job.file.relativePath
        p["mime"] = job.file.mimeType
        p["pages"] = job.file.pageCount.toString()
        p["sha"] = job.file.sha256
        p["status"] = job.status.name
        job.savedDocumentId?.let { p["savedId"] = it.toString() }
        job.error?.let { p["error"] = it }
        job.sellerName?.let { p["seller"] = it }
        job.totalCents?.let { p["total"] = it.toString() }
        p["items"] = job.itemCount.toString()
        p["attempts"] = job.attempts.toString()
        // A file of many invoices (older versions never wrote these: they read as none).
        job.bundleId?.let { p["bundle"] = it }
        job.order?.let { p["order"] = it.toString() }
        job.category?.let { p["category"] = it }
        if (job.flags.isNotEmpty()) p["flags"] = job.flags.sorted().joinToString(",")
        if (job.uncertainCount > 0) p["uncertain"] = job.uncertainCount.toString()
        if (job.reasons.isNotEmpty()) p["reasons"] = job.reasons.joinToString(",") { it.name }
        // Written aside, then put in place in one step: the app stopped mid-write never leaves a cut file (a job
        // whose details cannot be read is lost from the queue, and its photo with it).
        runCatching {
            val tmp = File(d, "meta.properties.tmp")
            tmp.outputStream().use { out -> p.store(out, null); out.flush(); out.fd.sync() }
            if (!tmp.renameTo(File(d, "meta.properties"))) error("rename")
        }
    }

    private fun readMeta(d: File): ImportJob? {
        val f = File(d, "meta.properties")
        if (!f.exists()) { d.deleteRecursively(); return null }
        val p = Properties().apply { f.inputStream().use { load(it) } }
        val file = StoredFile(p.getProperty("path"), p.getProperty("mime"), p.getProperty("pages").toInt(), p.getProperty("sha"))
        if (!fileStore.file(file.relativePath).exists()) { d.deleteRecursively(); return null }
        return ImportJob(
            id = d.name,
            createdAt = p.getProperty("createdAt").toLong(),
            source = p.getProperty("source", ""),
            file = file,
            status = JobStatus.valueOf(p.getProperty("status")),
            savedDocumentId = p.getProperty("savedId")?.toLongOrNull(),
            error = p.getProperty("error"),
            sellerName = p.getProperty("seller"),
            totalCents = p.getProperty("total")?.toLongOrNull(),
            itemCount = p.getProperty("items")?.toIntOrNull() ?: 0,
            attempts = p.getProperty("attempts")?.toIntOrNull() ?: 0,
            bundleId = p.getProperty("bundle")?.ifBlank { null },
            order = p.getProperty("order")?.toIntOrNull(),
            category = p.getProperty("category")?.ifBlank { null },
            flags = p.getProperty("flags").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            uncertainCount = p.getProperty("uncertain")?.toIntOrNull() ?: 0,
            // A reason this version does not know (written by a newer one) is left out.
            reasons = p.getProperty("reasons").orEmpty().split(',').mapNotNull { n -> ReviewReason.entries.firstOrNull { it.name == n.trim() } },
        )
    }

    /** What was read, so the reading can be rebuilt after a restart without reading the pages again. */
    private suspend fun saveReading(job: ImportJob, pending: PendingImport) = withContext(Dispatchers.IO) {
        val d = File(dir, job.id).apply { mkdirs() }
        File(d, "ocr.txt").writeText(OcrLineCodec.encode(pending.rawLines))
        File(d, "widths.txt").writeText(pending.ocrWidths.joinToString(","))
        File(d, "heights.txt").writeText(pending.ocrHeights.joinToString(","))
        val p = Properties()
        p["engine"] = pending.engineName
        p["readingNote"] = pending.readingNote.substringBefore(", items read by")
        pending.aiNote?.let { p["aiNote"] = it }
        pending.ocrError?.let { p["ocrError"] = it }
        p["ocrMillis"] = pending.ocrMillis.toString()
        p["aiPages"] = pending.aiRaw.size.toString()
        p["aiChecks"] = pending.aiTargeted.size.toString()
        p["aiSpot"] = pending.aiSpot.toString()
        p["aiPicks"] = pending.aiPicks.joinToString(",")
        // Which questions the AI was asked, beside the answers' keys: the replay asks the same way.
        p["aiScope"] = pending.aiScope.name
        // Pages read from the PDF's own text: the rebuilt reading uses the same rules.
        if (pending.textLayerPages.isNotEmpty()) p["textPages"] = pending.textLayerPages.sorted().joinToString(",")
        pending.aiLayout?.let { File(d, "ai-layout.txt").writeText(it) }
        File(d, "reading.properties").outputStream().use { p.store(it, null) }
        pending.aiRaw.forEachIndexed { i, raw -> File(d, "ai-$i.json").writeText(raw) }
        pending.aiTargeted.forEachIndexed { i, raw -> File(d, "ai-check-$i.json").writeText(raw) }
        pending.aiKeys.forEachIndexed { i, key -> File(d, "ai-check-$i.key").writeText(key) }
    }

    private suspend fun loadReading(job: ImportJob): PendingImport? = withContext(Dispatchers.IO) {
        // An e-invoice keeps no OCR lines: its values are read again from the XML (quick, exact, no OCR or AI).
        if (job.file.mimeType == com.kitchenreceipts.app.files.FileStore.MIME_XML) {
            return@withContext runCatching { processor.process(job.file, engine, options = settings.parseOptions()) }.getOrNull()
        }
        val d = File(dir, job.id)
        val ocr = File(d, "ocr.txt").takeIf { it.exists() } ?: return@withContext null
        val p = Properties().apply { File(d, "reading.properties").inputStream().use { load(it) } }
        val lines = OcrLineCodec.decode(ocr.readText())
        val widths = File(d, "widths.txt").takeIf { it.exists() }?.readText()?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val heights = File(d, "heights.txt").takeIf { it.exists() }?.readText()?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val aiRaw = (0 until (p.getProperty("aiPages")?.toIntOrNull() ?: 0)).map { i -> File(d, "ai-$i.json").takeIf { it.exists() }?.readText() ?: "" }
        val aiChecks = (0 until (p.getProperty("aiChecks")?.toIntOrNull() ?: 0)).map { i -> File(d, "ai-check-$i.json").takeIf { it.exists() }?.readText() ?: "" }
        val textPages = p.getProperty("textPages").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        // An older reading has no scope: it asked every question.
        val aiScope = AiScope.entries.firstOrNull { it.name == p.getProperty("aiScope") } ?: AiScope.ALL
        ImportProcessor.rebuild(
            job.file, lines, widths, p.getProperty("ocrError"), p.getProperty("engine", ""), aiRaw, p.getProperty("aiNote"), aiChecks,
            p.getProperty("readingNote", ""), settings.parseOptions().copy(textLayerPages = textPages), p.getProperty("ocrMillis")?.toLongOrNull() ?: 0,
            p.getProperty("aiSpot") == "true",
            File(d, "ai-layout.txt").takeIf { it.exists() }?.readText(),
            aiPicks = p.getProperty("aiPicks").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() },
            // Keys of every answer, or none (an older reading): a partial list would pair answers wrongly.
            aiKeys = aiChecks.indices.map { i -> File(d, "ai-check-$i.key").takeIf { it.exists() }?.readText() }
                .let { k -> if (k.all { it != null }) k.filterNotNull() else emptyList() },
            heights = heights,
            aiScope = aiScope,
        )
    }

    companion object {
        /** Readings started before giving up (the second one without the AI). */
        const val MAX_ATTEMPTS = 2

        /** Readings of invoices cut from a file of many kept in memory: the one open, the one before and the next. */
        const val LAZY_KEEP = 3

        /** [ImportJob.error] of a reading that stopped the app twice (shown in the operator's language). */
        const val STOPPED = "stopped"
    }
}
