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
import com.kitchenreceipts.core.OcrLineCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
)

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
    private val notifier: ReadingNotifier,
) {
    private val dir = File(context.filesDir, "jobs").apply { mkdirs() }
    private val _jobs = MutableStateFlow<List<ImportJob>>(emptyList())
    val jobs: StateFlow<List<ImportJob>> = _jobs.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var current: Job? = null
    private var currentId: String? = null
    @Volatile private var started = false

    /** True while something is still to be read (the foreground service stays up meanwhile). */
    val busy: Boolean get() = _jobs.value.any { it.status == JobStatus.QUEUED || it.status == JobStatus.READING }

    fun job(id: String): ImportJob? = _jobs.value.firstOrNull { it.id == id }

    /** Files still needed by the queue (not to be cleaned up as orphans). */
    fun filePaths(): Set<String> = _jobs.value.filter { it.status != JobStatus.SAVED }.map { it.file.relativePath }.toSet()

    fun enqueue(file: StoredFile, source: String): ImportJob {
        val job = ImportJob(UUID.randomUUID().toString(), System.currentTimeMillis(), source, file, JobStatus.QUEUED)
        writeMeta(job)
        _jobs.update { it + job }
        log.event("JOB_QUEUED", "job" to job.id.take(8), "source" to source, "pages" to file.pageCount)
        startWorker()
        wake.trySend(Unit)
        return job
    }

    /** Stops a reading or drops a document that was not saved; its file is deleted. */
    fun discard(id: String) {
        val job = job(id) ?: return
        if (currentId == id) current?.cancel()
        if (job.status != JobStatus.SAVED) fileStore.delete(job.file.relativePath)
        remove(id)
        log.event("JOB_DISCARDED", "job" to id.take(8), "status" to job.status)
    }

    /** The operator saved the document from the review screen (the file now belongs to it). */
    fun markSaved(id: String) = remove(id)

    /** Hides a document the app saved by itself from the list. */
    fun dismiss(id: String) = remove(id)

    fun retry(id: String) {
        val job = job(id) ?: return
        update(job.copy(status = JobStatus.QUEUED, error = null, progress = null))
        startWorker()
        wake.trySend(Unit)
    }

    /** Loads the queue after a restart: finished readings are rebuilt (no reading again), unfinished ones re-queued. */
    fun restore() {
        val loaded = dir.listFiles()?.mapNotNull { d -> runCatching { readMeta(d) }.getOrNull() }.orEmpty()
            .sortedBy { it.createdAt }
            .map { if (it.status == JobStatus.READING) it.copy(status = JobStatus.QUEUED) else it }
        _jobs.value = loaded
        loaded.filter { it.status == JobStatus.READY }.forEach { j ->
            scope.launch {
                val pending = runCatching { loadReading(j) }.getOrNull()
                if (pending != null) update(j.copy(pending = pending)) else update(j.copy(status = JobStatus.QUEUED))
                if (pending == null) wake.trySend(Unit)
            }
        }
        if (loaded.any { it.status == JobStatus.QUEUED }) {
            startWorker()
            wake.trySend(Unit)
        }
    }

    private fun startWorker() {
        if (started) return
        started = true
        scope.launch {
            for (signal in wake) {
                while (true) {
                    val next = _jobs.value.firstOrNull { it.status == JobStatus.QUEUED } ?: break
                    val job = scope.launch { read(next) }
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
        var j = start.copy(status = JobStatus.READING, progress = ImportProgress(0, start.file.pageCount, 1))
        update(j)
        val t0 = System.currentTimeMillis()
        try {
            val pending = processor.process(
                j.file, engine,
                { p -> job(j.id)?.let { update(it.copy(progress = p), persist = false) } },
                settings.parseOptions(), aiUse(),
            )
            saveReading(j, pending)
            log.event(
                "IMPORT_DONE",
                "job" to j.id.take(8), "type" to j.file.mimeType, "pages" to j.file.pageCount, "pagesRead" to pending.pagesRead,
                "ms" to (System.currentTimeMillis() - t0), "ocrLines" to pending.rawLines.sumOf { it.size },
                "ocrError" to pending.ocrError, "reading" to pending.readingNote, "ai" to pending.aiNote,
            )
            val prepared = preparer.prepare(pending)
            val d = prepared.draft
            val savedId = runCatching { preparer.autoSave(pending, prepared) }.onFailure { log.error("autoSave", it) }.getOrNull()
            j = (job(j.id) ?: return).copy(
                status = if (savedId != null) JobStatus.SAVED else JobStatus.READY,
                pending = if (savedId != null) null else pending,
                savedDocumentId = savedId,
                progress = null,
                sellerName = d.seller.text.ifBlank { null },
                totalCents = com.kitchenreceipts.core.ItalianNumbers.parseCents(d.total.text),
                itemCount = d.items.size,
            )
            update(j)
            notifier.finished(j)
            val alerts = runCatching { preparer.priceAlerts(savedId, prepared) }.onFailure { log.error("priceAlerts", it) }.getOrDefault(emptyList())
            if (alerts.isNotEmpty()) {
                log.event("PRICE_ALERT", "job" to j.id.take(8), "changes" to alerts.size, "saved" to (savedId != null))
                notifier.priceChanges(j, alerts, saved = savedId != null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("backgroundImport", e)
            j = (job(j.id) ?: return).copy(status = JobStatus.FAILED, error = e.message ?: e.javaClass.simpleName, progress = null)
            update(j)
            notifier.finished(j)
        }
    }

    // ------------------------------------------------------------------ state + storage

    private fun update(job: ImportJob, persist: Boolean = true) {
        _jobs.update { list -> list.map { if (it.id == job.id) job else it } }
        if (persist) writeMeta(job)
    }

    private fun remove(id: String) {
        _jobs.update { list -> list.filterNot { it.id == id } }
        File(dir, id).deleteRecursively()
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
        runCatching { File(d, "meta.properties").outputStream().use { p.store(it, null) } }
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
        )
    }

    /** What was read, so the reading can be rebuilt after a restart without reading the pages again. */
    private suspend fun saveReading(job: ImportJob, pending: PendingImport) = withContext(Dispatchers.IO) {
        val d = File(dir, job.id).apply { mkdirs() }
        File(d, "ocr.txt").writeText(OcrLineCodec.encode(pending.rawLines))
        File(d, "widths.txt").writeText(pending.ocrWidths.joinToString(","))
        val p = Properties()
        p["engine"] = pending.engineName
        p["readingNote"] = pending.readingNote.substringBefore(", items read by")
        pending.aiNote?.let { p["aiNote"] = it }
        pending.ocrError?.let { p["ocrError"] = it }
        p["ocrMillis"] = pending.ocrMillis.toString()
        p["aiPages"] = pending.aiRaw.size.toString()
        p["aiChecks"] = pending.aiTargeted.size.toString()
        File(d, "reading.properties").outputStream().use { p.store(it, null) }
        pending.aiRaw.forEachIndexed { i, raw -> File(d, "ai-$i.json").writeText(raw) }
        pending.aiTargeted.forEachIndexed { i, raw -> File(d, "ai-check-$i.json").writeText(raw) }
    }

    private suspend fun loadReading(job: ImportJob): PendingImport? = withContext(Dispatchers.IO) {
        val d = File(dir, job.id)
        val ocr = File(d, "ocr.txt").takeIf { it.exists() } ?: return@withContext null
        val p = Properties().apply { File(d, "reading.properties").inputStream().use { load(it) } }
        val lines = OcrLineCodec.decode(ocr.readText())
        val widths = File(d, "widths.txt").takeIf { it.exists() }?.readText()?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val aiRaw = (0 until (p.getProperty("aiPages")?.toIntOrNull() ?: 0)).map { i -> File(d, "ai-$i.json").takeIf { it.exists() }?.readText() ?: "" }
        val aiChecks = (0 until (p.getProperty("aiChecks")?.toIntOrNull() ?: 0)).map { i -> File(d, "ai-check-$i.json").takeIf { it.exists() }?.readText() ?: "" }
        ImportProcessor.rebuild(
            job.file, lines, widths, p.getProperty("ocrError"), p.getProperty("engine", ""), aiRaw, p.getProperty("aiNote"), aiChecks,
            p.getProperty("readingNote", ""), settings.parseOptions(), p.getProperty("ocrMillis")?.toLongOrNull() ?: 0,
        )
    }
}
