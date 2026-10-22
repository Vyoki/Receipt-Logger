package com.kitchenreceipts.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.data.AppDatabase
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.diagnostics.AppLog
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.PageRenderer
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.app.jobs.DraftPreparer
import com.kitchenreceipts.app.jobs.ImportQueue
import com.kitchenreceipts.app.jobs.JobEvent
import com.kitchenreceipts.app.jobs.JobStatus
import com.kitchenreceipts.app.jobs.ReadingNotifier
import com.kitchenreceipts.app.ocr.ImportProcessor
import com.kitchenreceipts.app.ocr.MlKitOcrEngine
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.OcrLine
import com.kitchenreceipts.core.OcrLineCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Properties

/**
 * Background reading: a queued document is read without any screen open, and after a restart the finished
 * reading comes back (rebuilt from what was stored, without reading again).
 */
@RunWith(AndroidJUnit4::class)
class ImportQueueTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun readsInTheBackgroundAndSurvivesARestart() = runBlocking {
        File(context.filesDir, "jobs").deleteRecursively()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val files = FileStore(context)
        val renderer = PageRenderer(files)
        val settings = AppSettings(context)
        val log = AppLog(context, settings)
        val repo = ReceiptRepository(db, files)
        fun queue(scope: CoroutineScope) = ImportQueue(
            context, scope, ImportProcessor(renderer), MlKitOcrEngine(), settings, { null },
            DraftPreparer(repo, settings, log), files, log, ReadingNotifier(context),
        )

        // A one-page "photo" with a little text.
        val page = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)
        Canvas(page).apply {
            drawColor(Color.WHITE)
            val p = android.graphics.Paint().apply { textSize = 36f; color = Color.BLACK; isAntiAlias = true }
            drawText("FORNITORE ESEMPIO S.R.L.", 40f, 80f, p)
            drawText("PATATE KG 10,000 0,90 9,00", 40f, 250f, p)
            drawText("TOTALE 9,00", 40f, 400f, p)
        }
        val photo = File(context.cacheDir, "queue-test.jpg")
        photo.outputStream().use { page.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val stored = files.storePhotos(listOf(photo)) { BitmapFactory.decodeFile(it.absolutePath) }

        val scope1 = CoroutineScope(SupervisorJob())
        val q1 = queue(scope1)
        val job = q1.enqueue(stored, "test")
        val done = withTimeout(120_000) {
            q1.jobs.first { list -> list.any { it.id == job.id && it.status in setOf(JobStatus.READY, JobStatus.SAVED, JobStatus.FAILED) } }
        }.first { it.id == job.id }
        assertTrue("status ${done.status} ${done.error}", done.status == JobStatus.READY || done.status == JobStatus.SAVED)
        scope1.cancel()

        if (done.status == JobStatus.READY) {
            // "Restart": a new queue on the same storage.
            val scope2 = CoroutineScope(SupervisorJob())
            val q2 = queue(scope2)
            q2.restore()
            val restored = withTimeout(30_000) { q2.jobs.first { l -> l.any { it.id == job.id && it.pending != null } } }.first { it.id == job.id }
            assertEquals(JobStatus.READY, restored.status)
            assertNotNull(restored.pending)
            assertEquals(done.pending!!.ocrText, restored.pending!!.ocrText)
            q2.discard(job.id)
            assertTrue(q2.jobs.value.none { it.id == job.id })
            scope2.cancel()
        }
        db.close()
    }

    // ------------------------------------------------------------------ invoices cut from a file of many

    private val jobsDir get() = File(context.filesDir, "jobs")
    private var openDb: AppDatabase? = null
    private val db: AppDatabase get() = openDb ?: Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().also { openDb = it }
    private val settings by lazy { AppSettings(context) }
    private val log by lazy { AppLog(context, settings) }
    private val preparer by lazy { DraftPreparer(ReceiptRepository(db, FileStore(context)), settings, log) }

    @After fun closeDb() {
        openDb?.close()
        openDb = null
    }

    /** A queue whose reader never starts (its scope is cancelled): the jobs stay exactly as written. */
    private fun idleQueue(): ImportQueue {
        val files = FileStore(context)
        return ImportQueue(
            context, CoroutineScope(Job().apply { cancel() }), ImportProcessor(PageRenderer(files)), MlKitOcrEngine(), settings, { null },
            preparer, files, log, ReadingNotifier(context),
        )
    }

    /** A stand-in for a part cut from a file (never read here). */
    private fun part(name: String): StoredFile {
        val f = File(context.filesDir, "${FileStore.DOCUMENTS_DIR}/$name").apply { parentFile?.mkdirs(); writeText("%PDF-1.4 test") }
        return StoredFile("${FileStore.DOCUMENTS_DIR}/${f.name}", FileStore.MIME_PDF, 1, "sha-$name")
    }

    /** A job's details as an app version would write them; [extra] adds keys. */
    private fun writeMeta(id: String, status: JobStatus, createdAt: Long, file: StoredFile, extra: Map<String, String> = emptyMap()) {
        val d = File(jobsDir, id).apply { mkdirs() }
        val p = Properties()
        p["createdAt"] = createdAt.toString()
        p["source"] = "test"
        p["path"] = file.relativePath
        p["mime"] = file.mimeType
        p["pages"] = file.pageCount.toString()
        p["sha"] = file.sha256
        p["status"] = status.name
        p["items"] = "1"
        p["attempts"] = "1"
        extra.forEach { (k, v) -> p[k] = v }
        File(d, "meta.properties").outputStream().use { p.store(it, null) }
    }

    /** A finished reading as the queue stores it: one invented line. */
    private fun writeReading(id: String) {
        val d = File(jobsDir, id)
        File(d, "ocr.txt").writeText(OcrLineCodec.encode(listOf(listOf(
            OcrLine("ABC S.r.l.", 40, 40, 400, 80),
            OcrLine("Patate kg 10,000 0,90 9,00", 40, 300, 900, 340),
            OcrLine("Totale documento 9,90", 40, 600, 900, 640),
        ))))
        File(d, "widths.txt").writeText("1000")
        File(d, "heights.txt").writeText("1400")
        val p = Properties()
        p["engine"] = "test"
        p["readingNote"] = "pass 1 of 1"
        File(d, "reading.properties").outputStream().use { p.store(it, null) }
    }

    @Test fun invoicesOfAFileAreQueuedInOrderAndKeepItAfterARestart() = runBlocking {
        jobsDir.deleteRecursively()
        val files = (0 until 50).map { part("bundle-order-$it.pdf") }
        val q = idleQueue()
        val flags = files.indices.map { if (it == 7) setOf("START_UNSURE") else emptySet() }
        val jobs = q.enqueueAll(files, "test", bundleId = "plan-a", categories = files.map { "food" }, flags = flags)
        assertEquals(50, q.jobs.value.size)
        assertTrue(q.jobs.value.all { it.status == JobStatus.QUEUED && it.bundleId == "plan-a" })
        assertEquals((0 until 50).toList(), q.jobs.value.map { it.order })

        val again = idleQueue().apply { restore() }
        assertEquals(jobs.map { it.id }, again.jobs.value.map { it.id })
        assertEquals((0 until 50).toList(), again.jobs.value.map { it.order })
        assertTrue(again.jobs.value.all { it.category == "food" && it.status == JobStatus.QUEUED })
        assertEquals(setOf("START_UNSURE"), again.jobs.value[7].flags)
        assertTrue(again.jobs.value.filterIndexed { i, _ -> i != 7 }.all { it.flags.isEmpty() })
        jobsDir.deleteRecursively()
        files.forEach { File(context.filesDir, it.relativePath).delete() }
    }

    @Test fun anOldJobReadsAsASingleImport() {
        jobsDir.deleteRecursively()
        val file = part("old-meta.jpg").copy(mimeType = FileStore.MIME_JPEG)
        writeMeta("old-job", JobStatus.QUEUED, System.currentTimeMillis(), file)
        val q = idleQueue().apply { restore() }
        val j = q.job("old-job")!!
        assertEquals(null, j.bundleId)
        assertEquals(null, j.order)
        assertEquals(null, j.category)
        assertTrue(j.flags.isEmpty())
        assertEquals(0, j.uncertainCount)
        assertTrue(j.reasons.isEmpty())
        jobsDir.deleteRecursively()
        File(context.filesDir, file.relativePath).delete()
    }

    @Test fun aSavedInvoiceOfAFileStillBeingImportedStaysAfterAnotherDay() {
        jobsDir.deleteRecursively()
        val file = part("saved-yesterday.pdf")
        val yesterday = System.currentTimeMillis() - 26 * 60 * 60 * 1000L
        writeMeta("open-plan", JobStatus.SAVED, yesterday, file, mapOf("bundle" to "plan-open", "savedId" to "11", "order" to "3"))
        writeMeta("done-plan", JobStatus.SAVED, yesterday, file, mapOf("bundle" to "plan-done", "savedId" to "12"))
        writeMeta("single", JobStatus.SAVED, yesterday, file, mapOf("savedId" to "13"))
        val events = mutableListOf<JobEvent>()
        val q = idleQueue().apply {
            bundleOpen = { it == "plan-open" }
            listener = { events += it }
            restore()
        }
        assertEquals(listOf("open-plan"), q.jobs.value.map { it.id })
        assertTrue(File(jobsDir, "done-plan").exists().not() && File(jobsDir, "single").exists().not())
        // The plan is told what was loaded: saved by the app, with its document.
        val e = events.single()
        assertEquals(JobEvent.Kind.RESTORED, e.kind)
        assertEquals(JobStatus.SAVED, e.status)
        assertEquals(11L, e.documentId)
        assertEquals("plan-open", e.bundleId)
        assertEquals(3, e.order)
        jobsDir.deleteRecursively()
        File(context.filesDir, file.relativePath).delete()
    }

    @Test fun manyReadyInvoicesRestoreQuicklyAndAreRebuiltOnlyWhenOpened() = runBlocking {
        jobsDir.deleteRecursively()
        val file = part("ready-many.pdf")
        val now = System.currentTimeMillis()
        val ids = (0 until 150).map { "ready-$it" }
        ids.forEachIndexed { i, id ->
            writeMeta(id, JobStatus.READY, now + i, file, mapOf("bundle" to "plan-b", "order" to "$i", "seller" to "ABC S.r.l.", "total" to "990", "uncertain" to "2", "reasons" to "UNCERTAIN_VALUES,SOMETHING_NEW"))
        }
        writeReading(ids[0])
        val q = idleQueue()
        val t0 = android.os.SystemClock.elapsedRealtime()
        q.restore()
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        assertTrue("restore took $ms ms", ms < 1000)
        assertEquals(150, q.jobs.value.size)
        // The summary is there without the reading: for the list and the counts.
        val first = q.job(ids[0])!!
        assertEquals("ABC S.r.l.", first.sellerName)
        assertEquals(2, first.uncertainCount)
        assertEquals(listOf(com.kitchenreceipts.core.ReviewReason.UNCERTAIN_VALUES), first.reasons)
        kotlinx.coroutines.delay(500)
        assertTrue(q.jobs.value.all { it.pending == null })

        // Opened: rebuilt from what was stored, and still not held in the list.
        val pending = q.load(ids[0])
        assertNotNull(pending)
        assertTrue(pending!!.ocrText.contains("Patate"))
        assertEquals(null, q.job(ids[0])!!.pending)
        assertTrue(pending === q.load(ids[0])) // kept for the next look
        // No reading stored: it is read again.
        assertEquals(null, q.load(ids[1]))
        assertEquals(JobStatus.QUEUED, q.job(ids[1])!!.status)
        jobsDir.deleteRecursively()
        File(context.filesDir, file.relativePath).delete()
    }

    @Test fun aFlaggedInvoiceIsHeldForACheck() = runBlocking {
        jobsDir.deleteRecursively()
        val file = part("flagged.pdf")
        writeMeta("flagged", JobStatus.READY, System.currentTimeMillis(), file, mapOf("bundle" to "plan-c", "order" to "0", "flags" to "NO_TOTAL,START_UNSURE"))
        writeReading("flagged")
        val q = idleQueue().apply { restore() }
        val job = q.job("flagged")!!
        assertEquals(setOf("NO_TOTAL", "START_UNSURE"), job.flags)
        val pending = q.load(job.id)!!
        val held = preparer.prepare(pending, job.flags)
        assertTrue(held.reasons.toString(), com.kitchenreceipts.core.ReviewReason.BUNDLE_CHECK in held.reasons)
        assertEquals(null, preparer.autoSave(pending, held))
        val plain = preparer.prepare(pending)
        assertTrue(com.kitchenreceipts.core.ReviewReason.BUNDLE_CHECK !in plain.reasons)
        jobsDir.deleteRecursively()
        File(context.filesDir, file.relativePath).delete()
    }

    @Test fun theListenerHearsWhatHappensToEachJob() = runBlocking {
        jobsDir.deleteRecursively()
        val files = (0 until 3).map { part("events-$it.pdf") }
        val events = mutableListOf<JobEvent>()
        val q = idleQueue().apply { listener = { events += it } }
        val jobs = q.enqueueAll(files, "test", bundleId = "plan-d")
        q.markSaved(jobs[1].id, 42L)
        q.discard(jobs[2].id)
        assertEquals(listOf(JobEvent.Kind.SAVED_BY_YOU, JobEvent.Kind.DISCARDED), events.map { it.kind })
        val saved = events[0]
        assertEquals(jobs[1].id, saved.jobId)
        assertEquals(42L, saved.documentId)
        assertEquals("plan-d", saved.bundleId)
        assertEquals(1, saved.order)
        assertEquals(2, events[1].order)
        assertEquals(listOf(jobs[0].id), q.jobs.value.map { it.id })
        jobsDir.deleteRecursively()
        files.forEach { File(context.filesDir, it.relativePath).delete() }
    }
}
