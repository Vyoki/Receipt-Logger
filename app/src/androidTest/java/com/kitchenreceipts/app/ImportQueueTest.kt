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
import com.kitchenreceipts.app.jobs.BundleStore
import com.kitchenreceipts.app.jobs.DraftPreparer
import com.kitchenreceipts.app.jobs.ImportIntake
import com.kitchenreceipts.app.jobs.ImportJob
import com.kitchenreceipts.app.jobs.ImportQueue
import com.kitchenreceipts.app.jobs.JobEvent
import com.kitchenreceipts.app.jobs.JobStatus
import com.kitchenreceipts.app.jobs.Notifier
import com.kitchenreceipts.app.jobs.ReadingNotifier
import com.kitchenreceipts.app.ocr.ImportProcessor
import com.kitchenreceipts.app.ocr.MlKitOcrEngine
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.BundlePlan
import com.kitchenreceipts.core.BundlePlanCodec
import com.kitchenreceipts.core.BundleStatus
import com.kitchenreceipts.core.BundleWizard
import com.kitchenreceipts.core.InvoiceOutcome
import com.kitchenreceipts.core.OcrLine
import com.kitchenreceipts.core.OcrLineCodec
import com.kitchenreceipts.core.PlannedInvoice
import com.kitchenreceipts.core.PriceChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Background reading: a queued document is read without any screen open, and after a restart the finished
 * reading comes back (rebuilt from what was stored, without reading again).
 */
@RunWith(AndroidJUnit4::class)
class ImportQueueTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun readsInTheBackgroundAndSurvivesARestart() = runBlocking<Unit> {
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

    @Test fun invoicesOfAFileAreQueuedInOrderAndKeepItAfterARestart() = runBlocking<Unit> {
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

    @Test fun manyReadyInvoicesRestoreQuicklyAndAreRebuiltOnlyWhenOpened() = runBlocking<Unit> {
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

    @Test fun aFlaggedInvoiceIsHeldForACheck() = runBlocking<Unit> {
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

    @Test fun theListenerHearsWhatHappensToEachJob() = runBlocking<Unit> {
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

    // ------------------------------------------------------------------ one message for a file of many, the chain, the recheck

    /** What the reading would post, counted instead (see Notifier). */
    private class CountingNotifier : Notifier {
        val finished = CopyOnWriteArrayList<ImportJob>()
        val prices = CopyOnWriteArrayList<ImportJob>()
        val bundles = CopyOnWriteArrayList<BundlePlan>()
        override fun finished(job: ImportJob) { finished += job }
        override fun priceChanges(job: ImportJob, changes: List<PriceChange>, saved: Boolean) { prices += job }
        override fun bundleScanned(planId: String) = Unit
        override fun bundleLong(planId: String, pages: Int) = Unit
        override fun bundleFinished(plan: BundlePlan) { bundles += plan }
        override fun cancelPaused() = Unit
    }

    private val plansDir get() = File(context.filesDir, "bundles-queue-test")

    /**
     * A queue that reads (e-invoices only here: no OCR, quick), with a plan store listening to it, as in the app. The
     * restaurant's own VAT number is left blank, so no invented invoice counts as addressed to another business.
     */
    private inner class Reading : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val notifier = CountingNotifier()
        val files = FileStore(context)
        val queue = ImportQueue(context, scope, ImportProcessor(PageRenderer(files)), MlKitOcrEngine(), settings, { null }, preparer, files, log, notifier)
        val store = BundleStore(context, scope, files, queue, settings, log, notifier, fileInUse = { false }, dir = plansDir)
        private val ownBefore = settings.ownVatNumber
        private val autoBefore = settings.autoSave

        init {
            settings.ownVatNumber = ""
            queue.listener = { store.onJobEvent(it) }
            queue.bundleOpen = { store.isOpen(it) }
        }

        /** A plan made from an invented zip of e-invoices ([xmls], in that order). */
        suspend fun bundle(name: String, xmls: List<String>): String {
            val r = ImportIntake(context, files, queue, store, log).take(zip(name, xmls), "test")
            assertTrue("$r", r is ImportIntake.Intake.Bundle)
            return (r as ImportIntake.Intake.Bundle).planId
        }

        fun plan(id: String) = store.plan(id)!!

        /** The job of the invoice numbered [number]. */
        fun jobOf(id: String, number: String) = plan(id).invoices.single { it.number == number }.jobId!!

        fun outcomeOf(id: String, number: String) = plan(id).invoices.single { it.number == number }.outcome

        override fun close() {
            scope.cancel()
            settings.ownVatNumber = ownBefore
            settings.autoSave = autoBefore
            plansDir.deleteRecursively()
            jobsDir.deleteRecursively()
        }
    }

    private suspend fun until(what: String, cond: () -> Boolean) {
        runCatching { withTimeout(60_000) { while (!cond()) delay(50) } }
        assertTrue("timed out waiting: $what", cond())
    }

    private fun einvoice(number: String, sellerVat: String, seller: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<p:FatturaElettronica versione="FPR12" xmlns:p="http://ivaservizi.agenziaentrate.gov.it/docs/xsd/fatture/v1.2">
  <FatturaElettronicaHeader>
    <CedentePrestatore><DatiAnagrafici><IdFiscaleIVA><IdPaese>IT</IdPaese><IdCodice>$sellerVat</IdCodice></IdFiscaleIVA><Anagrafica><Denominazione>$seller</Denominazione></Anagrafica></DatiAnagrafici></CedentePrestatore>
    <CessionarioCommittente><DatiAnagrafici><Anagrafica><Denominazione>RISTORANTE PROVA SAS</Denominazione></Anagrafica></DatiAnagrafici></CessionarioCommittente>
  </FatturaElettronicaHeader>
  <FatturaElettronicaBody>
    <DatiGenerali><DatiGeneraliDocumento><TipoDocumento>TD01</TipoDocumento><Divisa>EUR</Divisa><Data>2031-02-03</Data><Numero>$number</Numero><ImportoTotaleDocumento>9.90</ImportoTotaleDocumento></DatiGeneraliDocumento></DatiGenerali>
    <DatiBeniServizi>
      <DettaglioLinee><NumeroLinea>1</NumeroLinea><Descrizione>PATATE</Descrizione><Quantita>10.00</Quantita><UnitaMisura>KG</UnitaMisura><PrezzoUnitario>0.90</PrezzoUnitario><PrezzoTotale>9.00</PrezzoTotale><AliquotaIVA>10.00</AliquotaIVA></DettaglioLinee>
      <DatiRiepilogo><AliquotaIVA>10.00</AliquotaIVA><ImponibileImporto>9.00</ImponibileImporto><Imposta>0.90</Imposta></DatiRiepilogo>
    </DatiBeniServizi>
  </FatturaElettronicaBody>
</p:FatturaElettronica>"""

    private fun abc(number: String) = einvoice(number, "01234567897", "ABC S.r.l.")
    private fun verde(number: String) = einvoice(number, "09876543217", "VERDE FRESCO S.p.A.")

    private fun zip(name: String, xmls: List<String>): android.net.Uri {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            xmls.forEachIndexed { i, x ->
                z.putNextEntry(ZipEntry("IT01234567897_${"%05d".format(i + 1)}.xml"))
                z.write(x.toByteArray())
                z.closeEntry()
            }
        }
        return android.net.Uri.fromFile(File(context.cacheDir, name).apply { writeBytes(out.toByteArray()) })
    }

    @Test fun theInvoicesOfAFileGetOneMessageForAllOfThem() = runBlocking<Unit> {
        plansDir.deleteRecursively()
        jobsDir.deleteRecursively()
        Reading().use { r ->
            val id = r.bundle("one-message.zip", (1..5).map { abc("$it/2031") })
            assertEquals(BundleStore.Prepared.Queued(5), r.store.confirm(id))
            until("all read") { r.plan(id).invoices.none { it.outcome == InvoiceOutcome.WAITING || it.outcome == InvoiceOutcome.QUEUED } }
            until("the file's message") { r.notifier.bundles.size == 1 }
            delay(500)
            // No message for each invoice: one for the file, counted from its plan.
            assertEquals(0, r.notifier.finished.size)
            assertEquals(0, r.notifier.prices.size)
            assertEquals(1, r.notifier.bundles.size)
            assertEquals(5, BundleWizard.counts(r.notifier.bundles.single()).read)
            // Not again after a restart.
            r.store.flush()
            val again = CountingNotifier()
            BundleStore(context, r.scope, r.files, r.queue, settings, log, again, fileInUse = { false }, dir = plansDir).afterRestore(r.queue.jobs.value)
            assertTrue(again.bundles.isEmpty())
        }
    }

    @Test fun aDocumentOnItsOwnStillGetsItsOwnMessage() = runBlocking<Unit> {
        plansDir.deleteRecursively()
        jobsDir.deleteRecursively()
        Reading().use { r ->
            val f = File(context.filesDir, "${FileStore.DOCUMENTS_DIR}/single-message.xml").apply { parentFile?.mkdirs(); writeText(abc("9/2031")) }
            val stored = r.files.storedXml("${FileStore.DOCUMENTS_DIR}/${f.name}")!!
            val job = r.queue.enqueue(stored, "test")
            until("its message") { r.notifier.finished.size == 1 }
            assertEquals(job.id, r.notifier.finished.single().id)
            assertTrue(r.notifier.finished.single().status == JobStatus.READY || r.notifier.finished.single().status == JobStatus.SAVED)
            assertTrue(r.notifier.bundles.isEmpty())
            f.delete()
        }
    }

    /**
     * Once the operator saves the first invoice of a supplier, its other invoices waiting to be checked are checked
     * again with the usual rules, and saved by the app when they now check out; another supplier's are not touched.
     * The invented e-invoices are exact, so they would check out at once: the app saving by itself is off while they
     * are read, and on again when the first one is saved.
     */
    @Test fun afterTheFirstSaveOfASupplierItsOtherInvoicesAreCheckedAgain() = runBlocking<Unit> {
        plansDir.deleteRecursively()
        jobsDir.deleteRecursively()
        Reading().use { r ->
            settings.autoSave = false
            val id = r.bundle("recheck.zip", listOf(abc("1/2031"), abc("2/2031"), verde("3/2031")))
            r.store.confirm(id)
            until("all read") { r.plan(id).invoices.all { it.outcome == InvoiceOutcome.READY } }
            settings.autoSave = true
            r.queue.markSaved(r.jobOf(id, "1/2031"), 77L)
            until("checked again") { r.outcomeOf(id, "2/2031") == InvoiceOutcome.SAVED_BY_APP }
            assertEquals(JobStatus.SAVED, r.queue.job(r.jobOf(id, "2/2031"))!!.status)
            // VERDE FRESCO S.p.A. is another supplier: still to check, untouched.
            assertEquals(InvoiceOutcome.READY, r.outcomeOf(id, "3/2031"))
            assertEquals(JobStatus.READY, r.queue.job(r.jobOf(id, "3/2031"))!!.status)
            until("said once") { r.store.rechecked.value?.count == 1 }
            assertTrue(r.queue.rechecking.value.isEmpty())
            // The second save of the same supplier checks nothing again.
            assertEquals(emptyList<String>(), BundleWizard.recheckAfterSave(r.plan(id), r.jobOf(id, "2/2031")))
        }
    }

    /**
     * The check again never throws away what the operator did: an invoice of the same supplier they corrected and
     * left (the corrections kept on the phone), or put off with "Check later", stays for them to check, with its
     * corrections; another one of that supplier is still checked again and saved by the app.
     */
    @Test fun aCorrectedOrPutOffInvoiceIsNotCheckedAgain() = runBlocking<Unit> {
        plansDir.deleteRecursively()
        jobsDir.deleteRecursively()
        Reading().use { r ->
            settings.autoSave = false
            val id = r.bundle("recheck-kept.zip", listOf(abc("1/2031"), abc("2/2031"), abc("3/2031"), abc("4/2031")))
            r.store.confirm(id)
            until("all read") { r.plan(id).invoices.all { it.outcome == InvoiceOutcome.READY } }
            // 2/2031 corrected and left; 3/2031 put off.
            val draft = r.queue.draftFile(r.jobOf(id, "2/2031")).apply { parentFile?.mkdirs(); writeText("0\n{}") }
            r.store.checkLater(id, r.jobOf(id, "3/2031"))
            settings.autoSave = true
            r.queue.markSaved(r.jobOf(id, "1/2031"), 79L)
            until("checked again") { r.outcomeOf(id, "4/2031") == InvoiceOutcome.SAVED_BY_APP }
            until("check over") { r.queue.rechecking.value.isEmpty() }
            assertEquals(InvoiceOutcome.READY, r.outcomeOf(id, "2/2031"))
            assertEquals(JobStatus.READY, r.queue.job(r.jobOf(id, "2/2031"))!!.status)
            assertTrue(draft.isFile)
            assertEquals(InvoiceOutcome.CHECK_LATER, r.outcomeOf(id, "3/2031"))
            assertEquals(JobStatus.READY, r.queue.job(r.jobOf(id, "3/2031"))!!.status)
        }
    }

    @Test fun stopRemovesOnlyWhatIsNotSavedAndReadRestQueuesItAgain() = runBlocking<Unit> {
        plansDir.deleteRecursively()
        jobsDir.deleteRecursively()
        Reading().use { r ->
            settings.autoSave = false
            val id = r.bundle("stop.zip", listOf(abc("4/2031"), abc("5/2031"), verde("6/2031")))
            r.store.confirm(id)
            until("all read") { r.plan(id).invoices.all { it.outcome == InvoiceOutcome.READY } }
            r.queue.markSaved(r.jobOf(id, "4/2031"), 78L)
            // Let the check again of 5/2031 (auto-save is off: it stays to check) finish first.
            until("checked again") { r.queue.rechecking.value.isEmpty() }
            r.store.stop(id)
            assertTrue(r.queue.jobs.value.none { it.bundleId == id })
            assertEquals(InvoiceOutcome.SAVED_BY_YOU, r.outcomeOf(id, "4/2031"))
            assertEquals(InvoiceOutcome.STOPPED, r.outcomeOf(id, "5/2031"))
            assertEquals(InvoiceOutcome.STOPPED, r.outcomeOf(id, "6/2031"))
            // The e-invoices are the originals: they stay, to be read again.
            assertTrue(r.plan(id).invoices.mapNotNull { it.file }.all { r.files.file(it).isFile })
            assertEquals(BundleStore.Prepared.Queued(2), r.store.readRest(id))
            assertEquals(2, r.queue.jobs.value.count { it.bundleId == id })
            until("read again") { r.outcomeOf(id, "5/2031") == InvoiceOutcome.READY && r.outcomeOf(id, "6/2031") == InvoiceOutcome.READY }
            assertEquals(InvoiceOutcome.SAVED_BY_YOU, r.outcomeOf(id, "4/2031"))
            // One message for the first reading, one for the rest.
            until("two messages") { r.notifier.bundles.size == 2 }
            assertEquals(0, r.notifier.finished.size)
        }
    }

    @Test fun theChainGoesInPlanOrder() = runBlocking<Unit> {
        plansDir.deleteRecursively()
        jobsDir.deleteRecursively()
        Reading().use { r ->
            settings.autoSave = false
            // In the file: ABC 1, ABC 2, VERDE 3, VERDE 4. Read first: the first invoice of each new supplier.
            val id = r.bundle("chain.zip", listOf(abc("1/2031"), abc("2/2031"), verde("3/2031"), verde("4/2031")))
            r.store.confirm(id)
            val queued = r.queue.jobs.value.filter { it.bundleId == id }.sortedBy { it.createdAt }.map { it.id }
            val order = listOf("1/2031", "3/2031", "2/2031", "4/2031").map { r.jobOf(id, it) }
            assertEquals(order, queued)
            until("all read") { r.plan(id).invoices.all { it.outcome == InvoiceOutcome.READY } }
            assertEquals(order[0], r.store.next(id))
            assertEquals(order[1], r.store.next(id, afterJob = order[0]))
            assertEquals(order[2], r.store.next(id, afterJob = order[1]))
            assertEquals(order[3], r.store.next(id, afterJob = order[2]))
            assertEquals(1 to 4, r.store.position(id, order[0]))
            // After the last, round again to the first.
            assertEquals(order[0], r.store.next(id, afterJob = order[3]))
            // "Check later": to the end of the chain, after the others still to check.
            r.store.checkLater(id, order[0])
            assertEquals(order[1], r.store.next(id))
            assertEquals(order[1], r.store.next(id, afterJob = order[3]))
            assertEquals(4 to 4, r.store.position(id, order[0]))
            assertEquals(1 to 4, r.store.position(id, order[1]))
        }
    }

    /**
     * Invoices of a file saved by the app yesterday stay through a restart while the file is still being imported, so
     * its counts stay right; and a file whose last invoice was read just before the app stopped gets its message now.
     */
    @Test fun countsStayRightAfterYesterdaysSavedInvoicesPassARestart() = runBlocking<Unit> {
        plansDir.deleteRecursively()
        jobsDir.deleteRecursively()
        val file = part("restart-counts.pdf")
        val yesterday = System.currentTimeMillis() - 26 * 60 * 60 * 1000L
        writeMeta("y-0", JobStatus.SAVED, yesterday, file, mapOf("bundle" to "plan-restart", "order" to "0", "savedId" to "21"))
        writeMeta("y-1", JobStatus.SAVED, yesterday + 1, file, mapOf("bundle" to "plan-restart", "order" to "1", "savedId" to "22"))
        // Read just before the app stopped: its plan had not heard yet.
        writeMeta("y-2", JobStatus.READY, System.currentTimeMillis(), file, mapOf("bundle" to "plan-restart", "order" to "2"))
        val plan = BundlePlan(
            id = "plan-restart", file = file.relativePath, pageCount = 3, status = BundleStatus.QUEUED, createdAt = yesterday,
            invoices = listOf(
                PlannedInvoice(0, 0, supplierName = "ABC S.r.l.", jobId = "y-0", outcome = InvoiceOutcome.SAVED_BY_APP, documentId = 21),
                PlannedInvoice(1, 1, supplierName = "ABC S.r.l.", jobId = "y-1", outcome = InvoiceOutcome.SAVED_BY_APP, documentId = 22),
                PlannedInvoice(2, 2, supplierName = "ABC S.r.l.", jobId = "y-2", outcome = InvoiceOutcome.QUEUED),
            ),
        )
        File(plansDir, plan.id).apply { mkdirs() }.resolve("plan.txt").writeText(BundlePlanCodec.encode(plan))
        val notifier = CountingNotifier()
        val files = FileStore(context)
        val q = ImportQueue(
            context, CoroutineScope(Job().apply { cancel() }), ImportProcessor(PageRenderer(files)), MlKitOcrEngine(), settings, { null },
            preparer, files, log, notifier,
        )
        val storeScope = CoroutineScope(SupervisorJob())
        val store = BundleStore(context, storeScope, files, q, settings, log, notifier, fileInUse = { false }, dir = plansDir)
        q.listener = { store.onJobEvent(it) }
        q.bundleOpen = { store.isOpen(it) }
        q.restore()
        store.afterRestore(q.jobs.value)
        assertEquals(listOf("y-0", "y-1", "y-2"), q.jobs.value.map { it.id })
        val n = BundleWizard.counts(store.plan(plan.id)!!)
        assertEquals(3, n.total)
        assertEquals(3, n.read)
        assertEquals(2, n.savedByApp)
        assertEquals(1, n.toCheck)
        assertEquals(BundleStatus.QUEUED, store.plan(plan.id)!!.status)
        assertEquals(1, notifier.bundles.size)
        assertTrue(notifier.finished.isEmpty())
        store.flush()
        storeScope.cancel()
        plansDir.deleteRecursively()
        jobsDir.deleteRecursively()
        File(context.filesDir, file.relativePath).delete()
    }
}
