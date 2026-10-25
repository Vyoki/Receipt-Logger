package com.kitchenreceipts.core

import com.kitchenreceipts.core.EInvoicePrintoutFixture.CUSTOMER_VAT
import com.kitchenreceipts.core.EInvoicePrintoutFixture.ENERGY
import com.kitchenreceipts.core.EInvoicePrintoutFixture.MEAT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A file being imported survives the app being closed: the plan as text and back (invented data). */
class BundlePlanCodecTest {

    private val pages = EInvoicePrintoutFixture.bundle(listOf(MEAT, ENERGY)).mapIndexed { i, p -> InvoiceBundle.signals(i, p, CUSTOMER_VAT).copy(rowTexts = emptyList()) }

    private val full = BundlePlan(
        id = "0f".repeat(32), file = "bundles/x|y\\z.pdf", pageCount = 344, status = BundleStatus.QUEUED, scanned = 2, signals = pages,
        invoices = listOf(
            PlannedInvoice(
                first = 0, last = 0, kind = InvoiceBundle.Kind.INVOICE, start = InvoiceBundle.Start.SDI_BODY, file = "a.xml",
                supplierVat = "01234567897", supplierName = "ABC S.r.l. | a\\b\nc: d=e", number = "DDT 77/ZZ", date = LocalDate.of(2031, 2, 3),
                printedTotalCents = -1234L, totalProven = true, creditNote = true,
                flags = setOf(InvoiceBundle.Flag.START_UNSURE, InvoiceBundle.Flag.NO_TOTAL), suggestion = SupplierCategory.FUEL,
                alreadySavedId = 7, alreadySavedOn = 1_900_000_000_000L, maybeSavedId = 8, include = false, leftOut = LeftOut.ALREADY_SAVED,
                checked = true, jobId = "job-1", outcome = InvoiceOutcome.CHECK_LATER, documentId = 42, pricesChanged = true,
            ),
            PlannedInvoice(first = 1, last = 1, kind = InvoiceBundle.Kind.UNKNOWN, start = InvoiceBundle.Start.NONE),
        ),
        suppliers = listOf(
            SupplierChoice("01234567897", "ABC S.r.l. | a\\b\nc", "01234567897", "fuel", known = true, lastTime = "food", suggestion = "fuel", include = false),
            SupplierChoice(BundleWizard.NO_SUPPLIER, null, null),
        ),
        step = BundleWizard.Step.SUPPLIERS, createdAt = 1234L, sameFileDocumentId = 5L, announced = true,
        kept = listOf("documents/old|1.pdf", "documents/old=2.xml"),
    )

    @Test fun everyFieldRoundTrips() {
        val text = BundlePlanCodec.encode(full)
        assertEquals(full, BundlePlanCodec.decode(text))
        // One record per line: a name with a line break does not break the text.
        assertEquals(1, text.lines().count { it.startsWith("inv=") && it.contains("ABC") })
        assertTrue(text.startsWith("v=${BundlePlanCodec.VERSION}\n"))
    }

    @Test fun aPlanFromTheWizardRoundTrips() {
        val segs = InvoiceBundle.segments(pages, CUSTOMER_VAT)
        val p = BundleWizard.plan(BundlePlan("ab".repeat(32), "x.pdf", 2, BundleStatus.SCANNING, scanned = 2, signals = pages), segs)
        assertEquals(p, BundlePlanCodec.decode(BundlePlanCodec.encode(p)))
    }

    @Test fun aScanResumesFromItsCheckpoint() {
        // A long file looked through in steps of 10 pages, the app closed after each step: the saved signals plus the
        // rest give the same invoices as one pass.
        val file = EInvoicePrintoutFixture.bundle(List(4) { listOf(MEAT, ENERGY, EInvoicePrintoutFixture.LONG, EInvoicePrintoutFixture.WHOLESALER) }.flatten())
        val onePass = InvoiceBundle.find(file, CUSTOMER_VAT)
        var plan = BundlePlan("cd".repeat(32), "big.pdf", file.size, BundleStatus.SCANNING)
        while (plan.scanned < file.size) {
            val next = (plan.scanned until minOf(file.size, plan.scanned + 10)).map { InvoiceBundle.signals(it, file[it], CUSTOMER_VAT) }
            plan = BundlePlanCodec.decode(BundlePlanCodec.encode(plan.copy(scanned = plan.scanned + next.size, signals = plan.signals + next)))!!
        }
        assertEquals(onePass, InvoiceBundle.segments(plan.signals, CUSTOMER_VAT))
    }

    @Test fun toleratesWhatANewerAppWrites() {
        val text = BundlePlanCodec.encode(full)
            .replace("\ninv=f:1|", "\ninv=zz:new|f:1|")
            .replace("\nstatus=QUEUED\n", "\nstatus=SOMETHING_NEW\nfuture=1\n") + "another=line\n"
        val back = BundlePlanCodec.decode(text)!!
        assertEquals(full.invoices, back.invoices)
        // An unknown status reads as done: nothing is read by mistake, and the file is kept.
        assertEquals(BundleStatus.DONE, back.status)
        // Fields left out take their defaults.
        val minimal = BundlePlanCodec.decode("v=2\nid=abc\nfile=f.pdf\ninv=f:0|l:3\nsup=k:x\n")!!
        assertEquals(PlannedInvoice(first = 0, last = 3), minimal.invoices.single())
        assertEquals(SupplierChoice("x", null, null), minimal.suppliers.single())
        assertEquals(BundleWizard.Step.FOUND, minimal.step)
        assertEquals(0, minimal.pageCount)
        // A plan written before files of earlier imports were kept with it: none.
        assertEquals(emptyList<String>(), minimal.kept)
    }

    @Test fun garbageIsNoPlan() {
        assertNull(BundlePlanCodec.decode("garbage"))
        assertNull(BundlePlanCodec.decode(""))
        assertNull(BundlePlanCodec.decode("v=2\nfile=f.pdf\n"))
        // A broken record is skipped, the rest is read.
        assertEquals(0, BundlePlanCodec.decode("v=2\nid=a\nfile=f\ninv=f:x|l:1\n")!!.invoices.size)
    }
}
