package com.kitchenreceipts.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.backup.BackupManager
import com.kitchenreceipts.app.data.AppDatabase
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.diagnostics.AppLog
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.PageRenderer
import com.kitchenreceipts.app.jobs.BundleStore
import com.kitchenreceipts.app.jobs.DraftPreparer
import com.kitchenreceipts.app.jobs.ImportIntake
import com.kitchenreceipts.app.jobs.ImportIntake.Intake
import com.kitchenreceipts.app.jobs.ImportQueue
import com.kitchenreceipts.app.jobs.ReadingNotifier
import com.kitchenreceipts.app.ocr.ImportProcessor
import com.kitchenreceipts.app.ocr.MlKitOcrEngine
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.BundlePlan
import com.kitchenreceipts.core.BundlePlanCodec
import com.kitchenreceipts.core.BundleStatus
import com.kitchenreceipts.core.BundleWizard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Files of many invoices: one intake for every file, a plan kept on the phone that survives a restart, a look through
 * that carries on from its last saved page, the same file reopening its import, and the files kept while it is open.
 * Every file is invented (ABC S.r.l., RISTORANTE PROVA SAS, dates in 2031). Nothing is read by the OCR here: the
 * queue's reader never starts.
 */
@RunWith(AndroidJUnit4::class)
class BundleStoreTest {

    private val app = ApplicationProvider.getApplicationContext<KitchenReceiptsApp>().also { it.container } // initialises the PDF reader
    private val context: Context = app
    private val settings = AppSettings(context)
    private val log = AppLog(context, settings)
    private val files = FileStore(context)
    private val plansDir = File(context.filesDir, "bundles-test")
    private val jobsDir = File(context.filesDir, "jobs")
    private val db by lazy { Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ownVatBefore = ""

    private lateinit var queue: ImportQueue
    private lateinit var store: BundleStore
    private lateinit var intake: ImportIntake

    @Before fun setUp() {
        plansDir.deleteRecursively()
        jobsDir.deleteRecursively()
        ownVatBefore = settings.ownVatNumber
        settings.ownVatNumber = "09876543217"
        queue = idleQueue()
        store = newStore()
        intake = ImportIntake(context, files, queue, store, log)
    }

    @After fun tearDown() {
        scope.cancel()
        settings.ownVatNumber = ownVatBefore
        plansDir.deleteRecursively()
        db.close()
    }

    /** A queue whose reader never starts (its scope is cancelled): the jobs stay exactly as queued. */
    private fun idleQueue(): ImportQueue = ImportQueue(
        context, CoroutineScope(Job().apply { cancel() }), ImportProcessor(PageRenderer(files)), MlKitOcrEngine(), settings, { null },
        DraftPreparer(ReceiptRepository(db, files), settings, log), files, log, ReadingNotifier(context),
    )

    private fun newStore() = BundleStore(context, scope, files, queue, settings, log, ReadingNotifier(context), fileInUse = { false }, dir = plansDir)

    private fun pdf(name: String, pages: List<List<String>>, password: String? = null): Uri =
        Uri.fromFile(TestPdfs.writeLines(File(context.cacheDir, name), pages, userPassword = password))

    /** [n] invoices of one page each. */
    private fun invoices(n: Int) = (1..n).map { TestPdfs.invoicePage(it) }

    private fun documents(): Set<String> = File(context.filesDir, FileStore.DOCUMENTS_DIR).list().orEmpty().toSet()

    private suspend fun until(what: String, cond: () -> Boolean) {
        runCatching { withTimeout(60_000) { while (!cond()) delay(50) } }
        assertTrue("timed out waiting: $what", cond())
    }

    private fun einvoice(number: String, type: String = "TD01"): String = """<?xml version="1.0" encoding="UTF-8"?>
<p:FatturaElettronica versione="FPR12" xmlns:p="http://ivaservizi.agenziaentrate.gov.it/docs/xsd/fatture/v1.2">
  <FatturaElettronicaHeader>
    <CedentePrestatore><DatiAnagrafici><IdFiscaleIVA><IdPaese>IT</IdPaese><IdCodice>01234567897</IdCodice></IdFiscaleIVA><Anagrafica><Denominazione>ABC S.r.l.</Denominazione></Anagrafica></DatiAnagrafici></CedentePrestatore>
    <CessionarioCommittente><DatiAnagrafici><IdFiscaleIVA><IdPaese>IT</IdPaese><IdCodice>09876543217</IdCodice></IdFiscaleIVA><Anagrafica><Denominazione>RISTORANTE PROVA SAS</Denominazione></Anagrafica></DatiAnagrafici></CessionarioCommittente>
  </FatturaElettronicaHeader>
  <FatturaElettronicaBody>
    <DatiGenerali><DatiGeneraliDocumento><TipoDocumento>$type</TipoDocumento><Divisa>EUR</Divisa><Data>2031-02-03</Data><Numero>$number</Numero><ImportoTotaleDocumento>9.90</ImportoTotaleDocumento></DatiGeneraliDocumento></DatiGenerali>
    <DatiBeniServizi>
      <DettaglioLinee><NumeroLinea>1</NumeroLinea><Descrizione>PATATE</Descrizione><Quantita>10.00</Quantita><UnitaMisura>KG</UnitaMisura><PrezzoUnitario>0.90</PrezzoUnitario><PrezzoTotale>9.00</PrezzoTotale><AliquotaIVA>10.00</AliquotaIVA></DettaglioLinee>
      <DatiRiepilogo><AliquotaIVA>10.00</AliquotaIVA><ImponibileImporto>9.00</ImponibileImporto><Imposta>0.90</Imposta></DatiRiepilogo>
    </DatiBeniServizi>
  </FatturaElettronicaBody>
</p:FatturaElettronica>"""

    /** A zip of e-invoices: [numbers] invoices, then [creditNotes] credit notes, in that order (one credit note third). */
    private fun zip(name: String, numbers: List<String>, creditNotes: List<String>): Uri {
        val out = ByteArrayOutputStream()
        val all = numbers.map { it to "TD01" }.toMutableList()
        creditNotes.forEach { all.add(minOf(2, all.size), it to "TD04") }
        ZipOutputStream(out).use { z ->
            all.forEachIndexed { i, (n, type) ->
                z.putNextEntry(ZipEntry("IT01234567897_${"%05d".format(i + 1)}.xml"))
                z.write(einvoice(n, type).toByteArray())
                z.closeEntry()
            }
        }
        val f = File(context.cacheDir, name).apply { writeBytes(out.toByteArray()) }
        return Uri.fromFile(f)
    }

    // ------------------------------------------------------------------ PDFs

    @Test fun aPdfOfThreeInvoicesBecomesAPlanThatSurvivesANewStore() = runBlocking<Unit> {
        val r = intake.take(pdf("bundle-3.pdf", invoices(3)), "test")
        assertTrue("$r", r is Intake.Bundle)
        val id = (r as Intake.Bundle).planId
        val p = store.plan(id)!!
        assertEquals(BundleStatus.SORTING, p.status)
        assertEquals(3, p.invoices.count { it.isInvoice })
        assertEquals(3, BundleWizard.toRead(p).size)
        // Nothing is queued before the operator taps Read.
        assertTrue(queue.jobs.value.none { it.bundleId == id })
        store.flush()
        val again = newStore().plan(id)
        assertEquals(p, again)
    }

    /** The page check: joining by page twice joins once (the list changed under the second tap); a split undoes it. */
    @Test fun joinAndSplitGoByPage() = runBlocking<Unit> {
        val id = (intake.take(pdf("bundle-join.pdf", invoices(3)), "test") as Intake.Bundle).planId
        val second = store.plan(id)!!.invoices.filter { it.isInvoice }[1].first
        store.join(id, second)
        val joined = store.plan(id)!!
        assertEquals(2, joined.invoices.count { it.isInvoice })
        assertTrue(joined.invoices.none { it.first == second })
        // The same tap again: no invoice starts there any more, nothing changes.
        store.join(id, second)
        assertEquals(joined, store.plan(id))
        store.splitAt(id, second)
        val split = store.plan(id)!!
        assertEquals(3, split.invoices.count { it.isInvoice })
        assertTrue(split.invoices.any { it.first == second })
        store.flush()
        assertEquals(split, newStore().plan(id))
    }

    @Test fun aPdfOfOneInvoiceIsQueuedAsToday() = runBlocking<Unit> {
        val r = intake.take(pdf("bundle-1.pdf", listOf(TestPdfs.invoicePage(1, ends = false), TestPdfs.lastPage())), "test")
        assertEquals(Intake.Queued(1), r)
        val job = queue.jobs.value.single()
        assertNull(job.bundleId)
        assertEquals(2, job.file.pageCount)
        assertTrue(store.plans.value.isEmpty())
    }

    @Test fun aLongScannedPdfAsksFirstAndQueuesNothingUntilAnswered() = runBlocking<Unit> {
        val src = TestPdfs.writeScanned(File(context.cacheDir, "scanned-30.pdf"), 30)
        val r = intake.take(Uri.fromFile(src), "test")
        assertTrue("$r", r is Intake.AskLong)
        r as Intake.AskLong
        assertEquals(30, r.pages)
        assertEquals(BundleStatus.NOT_SPLITTABLE, store.plan(r.planId)!!.status)
        assertTrue(queue.jobs.value.isEmpty())
        // "Read the first 20 pages": the file is read as one document, as today.
        val job = store.readAsOne(r.planId)
        assertNotNull(job)
        assertEquals(1, queue.jobs.value.size)
        assertNull(queue.jobs.value.single().bundleId)
        assertEquals(BundleStatus.CLOSED, store.plan(r.planId)!!.status)
    }

    @Test fun aPasswordProtectedPdfIsRefusedAsLocked() = runBlocking<Unit> {
        val r = intake.take(pdf("locked.pdf", invoices(3), password = "prova"), "test")
        assertTrue("$r", r is Intake.Failed)
        assertEquals(R.string.bundle_error_locked, (r as Intake.Failed).messageRes)
        assertTrue(queue.jobs.value.isEmpty())
        assertTrue(store.plans.value.isEmpty())
    }

    @Test fun theSameFileAgainReopensItsPlan() = runBlocking<Unit> {
        val uri = pdf("bundle-again.pdf", invoices(3))
        val first = intake.take(uri, "test") as Intake.Bundle
        val before = documents()
        val second = intake.take(uri, "test")
        assertEquals(Intake.Bundle(first.planId, reopened = true), second)
        // The copy made the second time is not kept.
        assertEquals(before, documents())
        assertEquals(1, store.plans.value.size)
    }

    @Test fun theFileIsKeptWhileItsImportIsOpenAndGoesWithACancel() = runBlocking<Unit> {
        val r = intake.take(pdf("bundle-keep.pdf", invoices(3)), "test") as Intake.Bundle
        val p = store.plan(r.planId)!!
        assertTrue(p.file in store.filePaths())
        assertTrue(files.file(p.file).isFile)
        assertTrue(store.cancel(r.planId))
        assertEquals(BundleStatus.CANCELLED, store.plan(r.planId)!!.status)
        assertFalse(p.file in store.filePaths())
        assertFalse(files.file(p.file).exists())
    }

    @Test fun aLongPdfIsLookedThroughInTheBackground() = runBlocking<Unit> {
        val r = intake.take(pdf("bundle-15.pdf", invoices(15)), "test")
        assertTrue("$r", r is Intake.Bundle)
        val id = (r as Intake.Bundle).planId
        // The first pages were looked through at once; the rest goes on in the background.
        assertTrue(store.plan(id)!!.status == BundleStatus.SCANNING || store.plan(id)!!.status == BundleStatus.SORTING)
        until("plan sorted") { store.plan(id)?.status == BundleStatus.SORTING }
        assertEquals(15, store.plan(id)!!.invoices.count { it.isInvoice })
        assertEquals(15, store.plan(id)!!.scanned)
    }

    @Test fun aScanningPlanCarriesOnFromItsCheckpoint() = runBlocking<Unit> {
        val stored = files.importUri(pdf("bundle-resume.pdf", invoices(15))).files.single()
        // The first 10 pages, as a scan stopped by Android saved them; the first is marked, to see it is not looked at again.
        val first = store.look(stored, 10)!!
        assertEquals(10, first.size)
        val marked = listOf(first[0].copy(supplierName = "VERDE FRESCO S.p.A.")) + first.drop(1)
        val plan = BundlePlan(
            id = stored.sha256, file = stored.relativePath, pageCount = stored.pageCount, status = BundleStatus.SCANNING,
            scanned = marked.size, signals = marked, createdAt = System.currentTimeMillis(),
        )
        File(plansDir, plan.id).apply { mkdirs() }.resolve("plan.txt").writeText(BundlePlanCodec.encode(plan))
        val restarted = newStore()
        assertEquals(BundleStatus.SCANNING, restarted.plan(plan.id)!!.status)
        restarted.resumeScans()
        until("plan sorted") { restarted.plan(plan.id)?.status == BundleStatus.SORTING }
        val p = restarted.plan(plan.id)!!
        assertEquals(15, p.scanned)
        assertEquals(15, p.invoices.count { it.isInvoice })
        assertEquals("VERDE FRESCO S.p.A.", p.invoices.first().supplierName)
    }

    // ------------------------------------------------------------------ e-invoice files

    @Test fun aZipOfThreeInvoicesAndACreditNoteBecomesAPlanAndIsQueuedInPlanOrder() = runBlocking<Unit> {
        val r = intake.take(zip("einvoices-3.zip", listOf("1/2031", "2/2031", "3/2031"), listOf("4/2031")), "test")
        assertTrue("$r", r is Intake.Bundle)
        r as Intake.Bundle
        assertEquals(1, r.skippedCreditNotes)
        val p = store.plan(r.planId)!!
        assertEquals(BundleStatus.SORTING, p.status)
        assertEquals(3, BundleWizard.toRead(p).size)
        assertEquals(1, BundleWizard.found(p).creditNotes)
        assertEquals(listOf("1/2031", "2/2031", "3/2031"), BundleWizard.toRead(p).map { it.number })
        // The credit note is never imported: no file of its own.
        assertNull(p.invoices.single { it.creditNote }.file)
        // Read: each invoice queued in plan order, with its file's plan.
        val done = store.confirm(r.planId)
        assertEquals(BundleStore.Prepared.Queued(3), done)
        val jobs = queue.jobs.value.filter { it.bundleId == r.planId }
        assertEquals(BundleWizard.planOrder(p), jobs.sortedBy { it.createdAt }.map { it.order })
        assertEquals(BundleStatus.QUEUED, store.plan(r.planId)!!.status)
        assertEquals(3, store.plan(r.planId)!!.invoices.count { it.jobId != null })
    }

    @Test fun aZipOfOneInvoiceAndACreditNoteIsQueuedWithTheCreditNoteCounted() = runBlocking<Unit> {
        val r = intake.take(zip("einvoices-1.zip", listOf("5/2031"), listOf("6/2031")), "test")
        assertEquals(Intake.Queued(1, skippedCreditNotes = 1), r)
        assertEquals(FileStore.MIME_XML, queue.jobs.value.single().file.mimeType)
        assertTrue(store.plans.value.isEmpty())
    }

    // ------------------------------------------------------------------ restore and retention

    @Test fun aRestorePutsBackAWaitingBundleFile() = runBlocking<Unit> {
        val r = intake.take(pdf("bundle-restore.pdf", invoices(3)), "test") as Intake.Bundle
        val p = store.plan(r.planId)!!
        val kept = BackupManager.keptAcrossRestore(queue, store)
        assertTrue(p.file in kept)
        // A restore moves the phone's files aside and puts the backup's in place: the file is not among them.
        val previous = File(context.cacheDir, "restore-previous-test").apply { deleteRecursively(); mkdirs() }
        val original = files.file(p.file)
        val aside = File(previous, p.file).apply { parentFile?.mkdirs() }
        assertTrue(original.renameTo(aside))
        assertEquals(1, BackupManager.copyBack(previous, context.filesDir, kept))
        assertTrue(original.isFile)
        previous.deleteRecursively()
    }

    @Test fun aClosedPlanKeepsNoSupplierNameOrVatNumber() = runBlocking<Unit> {
        val r = intake.take(zip("einvoices-close.zip", listOf("7/2031", "8/2031"), emptyList()), "test") as Intake.Bundle
        val before = File(plansDir, "${r.planId}/plan.txt").readText()
        assertTrue(before.contains("ABC S.r.l.") && before.contains("01234567897"))
        store.close(r.planId)
        store.flush()
        val text = File(plansDir, "${r.planId}/plan.txt").readText()
        assertEquals(BundleStatus.CLOSED, BundlePlanCodec.decode(text)!!.status)
        assertFalse(text.contains("ABC"))
        assertFalse(text.contains("01234567897"))
        assertFalse(text.contains("7/2031"))
    }
}
