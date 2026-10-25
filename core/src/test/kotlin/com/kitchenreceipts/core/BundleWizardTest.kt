package com.kitchenreceipts.core

import com.kitchenreceipts.core.BundleWizard.Saved
import com.kitchenreceipts.core.BundleWizard.Step
import com.kitchenreceipts.core.EInvoicePrintoutFixture.CUSTOMER_VAT
import com.kitchenreceipts.core.EInvoicePrintoutFixture.ENERGY
import com.kitchenreceipts.core.EInvoicePrintoutFixture.HOUSEHOLD
import com.kitchenreceipts.core.EInvoicePrintoutFixture.MEAT
import com.kitchenreceipts.core.EInvoicePrintoutFixture.WHOLESALER
import com.kitchenreceipts.core.InvoiceBundle.Flag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Importing a file of many invoices (invented): what is found, sorted, read, and what became of each invoice. */
class BundleWizardTest {

    private val invoices = listOf(
        MEAT, WHOLESALER, ENERGY, MEAT.copy(number = "319", date = "28-03-2031"), HOUSEHOLD,
        WHOLESALER.copy(number = "7C 000012400", type = "TD04 nota di credito"),
    )
    private val pages = EInvoicePrintoutFixture.bundle(invoices).mapIndexed { i, p -> InvoiceBundle.signals(i, p, CUSTOMER_VAT).copy(rowTexts = emptyList()) }
    private val segments = InvoiceBundle.segments(pages, CUSTOMER_VAT)
    private val base = BundlePlan(id = "ab".repeat(32), file = "x.pdf", pageCount = pages.size, status = BundleStatus.SCANNING, scanned = pages.size, signals = pages)

    private fun plan(memory: Map<String, String> = emptyMap(), saved: List<Saved> = emptyList()) = BundleWizard.plan(base, segments, memory, saved)
    private fun vatKey(vat: String) = SupplierCategories.memoryKeys(vat, null).single()
    private fun known(vararg pairs: Pair<String, String>) = pairs.associate { (vat, c) -> vatKey(vat) to c }
    private fun keyOf(p: BundlePlan, vat: String) = p.suppliers.single { it.vat == vat }.key

    /** A hand-made segment, for the cases the fixture does not print. */
    private fun seg(first: Int, vat: String?, number: String?, flags: Set<Flag> = emptySet(), total: Long? = 1000L, proven: Boolean = true, kind: InvoiceBundle.Kind = InvoiceBundle.Kind.INVOICE) =
        InvoiceBundle.Segment(
            first = first, last = first, kind = kind, start = InvoiceBundle.Start.ASSO, supplierVat = vat, supplierName = vat?.let { "FORNITORE $it" },
            customerVat = CUSTOMER_VAT, docType = "TD01", number = number, date = LocalDate.of(2031, 3, 1 + first % 28), printedTotalCents = total,
            vatGroups = emptyList(), totalProven = proven, creditNote = false, flags = flags, ended = true,
        )

    @Test fun suppliersByVatNumberWithSuggestions() {
        val p = plan()
        assertEquals(BundleStatus.SORTING, p.status)
        assertEquals(6, p.invoices.size)
        assertEquals(4, p.suppliers.size)
        val meat = p.suppliers.single { it.vat == MEAT.supplierVat }
        assertEquals("CARNI ESEMPIO S.R.L.", meat.name)
        assertEquals("food", meat.suggestion)
        // A suggestion is never a choice.
        assertNull(meat.category)
        assertFalse(meat.known)
        assertEquals("utilities", p.suppliers.single { it.vat == ENERGY.supplierVat }.suggestion)
        assertEquals(SupplierCategory.FOOD, p.invoices[0].suggestion)
    }

    @Test fun onlyStepsWithSomethingToDecideAreShownAndNumbered() {
        val p = plan()
        assertEquals(listOf(Step.FOUND, Step.SUPPLIERS, Step.READ), BundleWizard.steps(p))
        assertEquals(listOf(1, 1, 2, 3), Step.entries.map { BundleWizard.numberOf(p, it) })
        // Every supplier remembered by VAT number: no Suppliers step, and Read is step 2.
        val all = plan(memory = known(MEAT.supplierVat to "food", WHOLESALER.supplierVat to "food", ENERGY.supplierVat to "utilities", HOUSEHOLD.supplierVat to "supplies"))
        assertTrue(all.suppliers.all { it.known && it.category != null })
        assertEquals(listOf(Step.FOUND, Step.READ), BundleWizard.steps(all))
        assertNull(BundleWizard.numberOf(all, Step.SUPPLIERS))
        assertEquals(2, BundleWizard.numberOf(all, Step.READ))
        assertEquals(4, BundleWizard.knownSuppliers(all).size)
        // A supplier whose only invoice is left out is not asked about.
        val energyLeftOut = BundleWizard.setInvoiceIncluded(plan(memory = known(MEAT.supplierVat to "food", WHOLESALER.supplierVat to "food", HOUSEHOLD.supplierVat to "supplies")), 2, false)
        assertEquals(listOf(Step.FOUND, Step.READ), BundleWizard.steps(energyLeftOut))
    }

    @Test fun aNameRememberedWithoutVatNumberIsOnlyShown() {
        val nameKey = SupplierCategories.memoryKeys(null, MEAT.supplierName).single()
        val p = plan(memory = mapOf(nameKey to "food", "vat:junk" to "food"))
        val meat = p.suppliers.single { it.vat == MEAT.supplierVat }
        assertFalse(meat.known)
        assertNull(meat.category)
        assertEquals("food", meat.lastTime)
        assertTrue(Step.SUPPLIERS in BundleWizard.steps(p))
    }

    @Test fun sortingSuppliers() {
        var p = plan()
        assertEquals(4, BundleWizard.toSort(p).size)
        // "Use the suggestion for N suppliers" is one explicit tap; the others stay to be sorted. Next is always possible.
        assertEquals(4, BundleWizard.suggestable(p).size)
        p = BundleWizard.setCategory(p, keyOf(p, HOUSEHOLD.supplierVat), SupplierChoice.LATER)
        assertEquals(3, BundleWizard.suggestable(p).size)
        p = BundleWizard.useSuggestions(p)
        assertEquals(listOf("food", "food", "utilities", SupplierChoice.LATER), p.suppliers.map { it.category })
        assertTrue(BundleWizard.toSort(p).isEmpty())
        assertTrue(BundleWizard.canContinue(plan(), Step.SUPPLIERS))
        // "Leave these invoices out".
        val energy = keyOf(p, ENERGY.supplierVat)
        p = BundleWizard.setSupplierIncluded(p, energy, false)
        assertEquals(4, BundleWizard.toRead(p).size)
        assertEquals(1, BundleWizard.summary(p).leftOut[LeftOut.BY_OPERATOR])
    }

    @Test fun defaultTicks() {
        val segs = listOf(
            seg(0, null, null, kind = InvoiceBundle.Kind.UNKNOWN),
            seg(1, "01234567897", "1"),
            seg(2, "01234567897", "2", flags = setOf(Flag.OTHER_BUYER)),
            seg(3, "01234567897", "3", flags = setOf(Flag.SEVERAL_IN_ONE)),
            seg(4, "01234567897", "4", flags = setOf(Flag.START_UNSURE)),
            seg(5, "01234567897", "5").copy(creditNote = true),
        )
        val p = BundleWizard.plan(base.copy(signals = emptyList()), segs)
        assertEquals(listOf(false, true, false, false, true, false), p.invoices.map { it.include })
        assertEquals(
            listOf(LeftOut.NOT_RECOGNISED, null, LeftOut.OTHER_BUYER, LeftOut.SEVERAL_IN_ONE, null, LeftOut.CREDIT_NOTE),
            p.invoices.map { it.leftOut },
        )
        // The rows of the first step.
        val f = BundleWizard.found(p)
        assertEquals(5, f.invoices)
        assertEquals(1, f.otherBuyer)
        assertEquals(1, f.creditNotes)
        assertEquals(1, f.unknownPages)
        // The invoice with an unsure start, and the one holding several invoices, need a look.
        assertEquals(2, f.needLook)
        assertEquals(1, BundleWizard.keep(p, 4).let { BundleWizard.found(it).needLook })
        // A credit note is never imported; unrecognised pages can be read as one document.
        assertEquals(p, BundleWizard.setInvoiceIncluded(p, 5, true))
        assertTrue(BundleWizard.setInvoiceIncluded(p, 0, true).invoices[0].include)
        assertTrue(BundleWizard.setInvoiceIncluded(p, 2, true).invoices[2].let { it.include && it.leftOut == null })
        assertEquals(LeftOut.BY_OPERATOR, BundleWizard.setInvoiceIncluded(p, 1, false).invoices[1].leftOut)
    }

    @Test fun alreadySavedBySupplierVatNumberAndDateNotByNameOnly() {
        val march14 = LocalDate.of(2031, 3, 14)
        val saved = listOf(
            // The same supplier VAT number, number (written another way) and date: already saved.
            Saved(7, "IT${MEAT.supplierVat}", "another spelling", "0318", march14, "11".repeat(32), savedAt = 1234L),
            // The same name, number and date but no VAT number to compare: maybe.
            Saved(8, null, "Carni Esempio srl", "319", LocalDate.of(2031, 3, 28), null),
            // Another supplier VAT number with the same name, number and date: not this one.
            Saved(9, EInvoicePrintoutFixture.pIva("0555555000"), "CARNI ESEMPIO S.R.L.", "319", LocalDate.of(2031, 3, 28), null),
            // Another date.
            Saved(10, WHOLESALER.supplierVat, null, WHOLESALER.number, LocalDate.of(2030, 3, 19), null),
        )
        val p = plan(saved = saved)
        val first = p.invoices[0]
        assertEquals(7L, first.alreadySavedId)
        assertEquals(1234L, first.alreadySavedOn)
        assertFalse(first.include)
        assertEquals(LeftOut.ALREADY_SAVED, first.leftOut)
        val maybe = p.invoices[3]
        assertNull(maybe.alreadySavedId)
        assertEquals(8L, maybe.maybeSavedId)
        assertTrue(maybe.include)
        assertNull(p.invoices[1].alreadySavedId)
        assertEquals(1, BundleWizard.found(p).alreadySaved)
        assertEquals(1, BundleWizard.found(p).maybeSaved)
        assertFalse(p.sameFileWarning)
        // "Import anyway" stays when the check runs again.
        val again = BundleWizard.setInvoiceIncluded(p, 0, true)
        assertTrue(BundleWizard.alreadySaved(again, saved).invoices[0].include)
        // A newly saved one is left out when the check runs again.
        val later = BundleWizard.alreadySaved(plan(), saved)
        assertFalse(later.invoices[0].include)
    }

    @Test fun theWholeFileSavedBeforeIsAWarningNeverAMatch() {
        val earlier = Saved(5, MEAT.supplierVat, MEAT.supplierName, MEAT.number, LocalDate.of(2031, 3, 14), base.id.uppercase())
        val p = plan(saved = listOf(earlier))
        assertTrue(p.sameFileWarning)
        assertEquals(5L, p.sameFileDocumentId)
        assertNull(p.invoices[0].alreadySavedId)
        assertTrue(p.invoices[0].include)
    }

    @Test fun printedSumsOnlyWhenEveryTotalIsProven() {
        val groups = BundleWizard.groupBySupplier(plan()).associateBy { it.supplier.vat }
        val meat = groups.getValue(MEAT.supplierVat)
        assertEquals(2, meat.invoices)
        assertEquals(24242L * 2, meat.provenCents)
        assertTrue(meat.sumComplete)
        assertEquals(LocalDate.of(2031, 3, 14), meat.first)
        assertEquals(LocalDate.of(2031, 3, 28), meat.last)
        // The electricity bill's total is printed but not proven: "€ 0,00 + 1 not checked".
        val energy = groups.getValue(ENERGY.supplierVat)
        assertEquals(0L, energy.provenCents)
        assertEquals(1, energy.notProven)
        assertFalse(energy.sumComplete)
        // A total not found.
        val p = BundleWizard.plan(base.copy(signals = emptyList()), listOf(seg(0, "01234567897", "1"), seg(1, "01234567897", "2", total = null, proven = false)))
        val g = BundleWizard.groupBySupplier(p).single()
        assertEquals(1000L, g.provenCents)
        assertEquals(1, g.notProven)
    }

    @Test fun aCreditNoteIsKeptApart() {
        val p = plan()
        val w = BundleWizard.groupBySupplier(p).single { it.supplier.vat == WHOLESALER.supplierVat }
        assertEquals(1, w.invoices)
        assertEquals(9761L, w.provenCents)
        // Shown apart, never added to the invoices or taken from them.
        assertEquals(1, w.creditNotes)
        assertEquals(9761L, w.creditNoteCents)
        val s = BundleWizard.summary(p)
        assertEquals(5, s.invoices)
        assertEquals(1, s.creditNotes)
        assertEquals(1, s.leftOut[LeftOut.CREDIT_NOTE])
        assertEquals(24242L + 9761L + 24242L + 659L, s.provenCents)
        assertEquals(1, s.notProven)
        assertEquals(LocalDate.of(2031, 3, 14), s.first)
        assertEquals(LocalDate.of(2031, 3, 28), s.last)
        assertEquals(mapOf<String?, Int>(null to 5), s.byCategory)
    }

    @Test fun estimateAndReadingOrder() {
        // Every supplier new: their first invoices are read first, then the rest in page order.
        val p = plan()
        assertEquals(listOf(0, 1, 2, 4, 3), BundleWizard.planOrder(p))
        assertEquals(BundleWizard.Estimate(newSuppliers = 4, notGoods = 0, flagged = 0), BundleWizard.estimate(p))
        // Known suppliers: the electricity bill still needs a check.
        val k = plan(memory = known(MEAT.supplierVat to "food", WHOLESALER.supplierVat to "food", ENERGY.supplierVat to "utilities", HOUSEHOLD.supplierVat to "supplies"))
        assertEquals(listOf(0, 1, 2, 3, 4), BundleWizard.planOrder(k))
        assertEquals(1, BundleWizard.estimate(k).atLeast)
        assertEquals(1, BundleWizard.estimate(k).notGoods)
        // Only the meat supplier known: it is read after the new ones.
        val m = plan(memory = known(MEAT.supplierVat to "food"))
        assertEquals(listOf(1, 2, 4, 0, 3), BundleWizard.planOrder(m))
        // A flagged invoice counts once.
        val f = BundleWizard.plan(base.copy(signals = emptyList()), listOf(seg(0, "01234567897", "1", flags = setOf(Flag.START_UNSURE)), seg(1, "01234567897", "2", flags = setOf(Flag.NO_TOTAL))))
        assertEquals(BundleWizard.Estimate(newSuppliers = 1, notGoods = 0, flagged = 1), BundleWizard.estimate(f))
    }

    @Test fun readingOneInvoiceAfterAnother() {
        var p = BundleWizard.confirm(plan())
        assertEquals(BundleStatus.PREPARING, p.status)
        assertEquals(InvoiceOutcome.LEFT_OUT, p.invoices[5].outcome)
        val order = BundleWizard.planOrder(p)
        p = BundleWizard.queued(p, order.associateWith { "job$it" })
        assertEquals(BundleStatus.QUEUED, p.status)
        assertEquals(BundleWizard.Counts(total = 5, read = 0, savedByApp = 0, savedByYou = 0, toCheck = 0, reading = 5, failed = 0, stopped = 0, leftOut = 1), BundleWizard.counts(p))
        assertNull(BundleWizard.nextToCheck(p))
        for (i in listOf(0, 1, 2)) p = BundleWizard.record(p, "job$i", InvoiceOutcome.READY)
        p = BundleWizard.record(p, "job4", InvoiceOutcome.SAVED_BY_APP, documentId = 44)
        assertEquals(listOf(0, 1, 2), BundleWizard.chain(p))
        assertEquals(1 to 3, BundleWizard.positionInChain(p, 0))
        assertEquals(0, BundleWizard.nextToCheck(p))
        assertEquals(1, BundleWizard.nextToCheck(p, after = 0))
        // "Check later" goes to the end of the chain.
        p = BundleWizard.checkLater(p, 0)
        assertEquals(listOf(1, 2, 0), BundleWizard.chain(p))
        assertEquals(1, BundleWizard.nextToCheck(p, after = 0))
        p = BundleWizard.record(p, "job1", InvoiceOutcome.SAVED_BY_YOU, documentId = 11)
        assertEquals(2, BundleWizard.nextToCheck(p, after = 1))
        // A late event never undoes a saved invoice.
        p = BundleWizard.record(p, "job1", InvoiceOutcome.READY)
        assertEquals(InvoiceOutcome.SAVED_BY_YOU, p.invoices[1].outcome)
        assertEquals(11L, p.invoices[1].documentId)
        val c = BundleWizard.counts(p)
        assertEquals(4, c.read)
        assertEquals(2, c.saved)
        assertEquals(2, c.toCheck)
        assertEquals(1, c.reading)
        // When nothing is left to read or to check, the plan is done.
        p = BundleWizard.record(p, "job3", InvoiceOutcome.FAILED)
        p = BundleWizard.record(p, "job0", InvoiceOutcome.SAVED_BY_YOU, 10)
        assertEquals(BundleStatus.QUEUED, p.status)
        p = BundleWizard.record(p, "job2", InvoiceOutcome.SAVED_BY_YOU, 20)
        assertEquals(BundleStatus.DONE, p.status)
        assertNull(BundleWizard.nextToCheck(p))
        assertEquals(mapOf<String?, Int>(null to 4), BundleWizard.savedByCategory(p))
    }

    @Test fun stopReadingAndReadTheRest() {
        var p = BundleWizard.queued(BundleWizard.confirm(plan()), (0..4).associateWith { "job$it" })
        p = BundleWizard.record(p, "job0", InvoiceOutcome.SAVED_BY_APP, 1)
        p = BundleWizard.record(p, "job1", InvoiceOutcome.READY)
        p = BundleWizard.checkLater(BundleWizard.record(p, "job2", InvoiceOutcome.READY), 2)
        assertEquals(listOf("job3", "job4"), BundleWizard.notReadJobs(p))
        p = BundleWizard.stop(p)
        assertEquals(InvoiceOutcome.SAVED_BY_APP, p.invoices[0].outcome)
        // Read and still to check (also put off): untouched, with their jobs.
        assertEquals(InvoiceOutcome.READY, p.invoices[1].outcome)
        assertEquals("job1", p.invoices[1].jobId)
        assertEquals(InvoiceOutcome.CHECK_LATER, p.invoices[2].outcome)
        assertEquals(2, BundleWizard.counts(p).stopped)
        assertEquals(0, BundleWizard.counts(p).reading)
        assertTrue(p.invoices.drop(3).take(2).all { it.jobId == null && it.outcome == InvoiceOutcome.STOPPED })
        // Not done while some are still to check; done once they are saved.
        assertEquals(BundleStatus.QUEUED, p.status)
        p = BundleWizard.record(BundleWizard.record(p, "job1", InvoiceOutcome.SAVED_BY_YOU, 11), "job2", InvoiceOutcome.SAVED_BY_YOU, 12)
        assertEquals(BundleStatus.DONE, p.status)
        p = BundleWizard.readRest(p)
        assertEquals(2, BundleWizard.counts(p).reading)
        assertEquals(InvoiceOutcome.SAVED_BY_APP, p.invoices[0].outcome)
        // Nothing left to check when stopped: done at once.
        val q = BundleWizard.stop(BundleWizard.queued(BundleWizard.confirm(plan()), (0..4).associateWith { "job$it" }))
        assertEquals(BundleStatus.DONE, q.status)
        assertEquals(5, BundleWizard.counts(q).stopped)
    }

    @Test fun oneMessageWhenTheFileIsRead() {
        var p = BundleWizard.queued(BundleWizard.confirm(plan()), BundleWizard.planOrder(plan()).associateWith { "job$it" })
        assertFalse(BundleWizard.toAnnounce(p))
        p = BundleWizard.record(p, "job0", InvoiceOutcome.SAVED_BY_APP, 1)
        p = BundleWizard.record(p, "job1", InvoiceOutcome.READY)
        p = BundleWizard.record(p, "job2", InvoiceOutcome.FAILED)
        p = BundleWizard.record(p, "job4", InvoiceOutcome.READY)
        // One invoice is still to be read.
        assertFalse(BundleWizard.toAnnounce(p))
        p = BundleWizard.record(p, "job3", InvoiceOutcome.SAVED_BY_APP, 3)
        // All read; two still to check do not hold the message back.
        assertEquals(BundleStatus.QUEUED, p.status)
        assertTrue(BundleWizard.toAnnounce(p))
        p = BundleWizard.announce(p)
        assertFalse(BundleWizard.toAnnounce(p))
        // Not again after a restart.
        assertFalse(BundleWizard.toAnnounce(BundlePlanCodec.decode(BundlePlanCodec.encode(p))!!))
        // Stopped by the operator: no message; read the rest: one more when that is read.
        p = BundleWizard.readRest(BundleWizard.stop(p))
        assertFalse(p.announced)
        assertFalse(BundleWizard.toAnnounce(BundleWizard.stop(p)))
        // Nothing read at all (everything left out): no message.
        val none = BundleWizard.queued(BundleWizard.confirm(plan()), emptyMap()).copy(status = BundleStatus.DONE, invoices = plan().invoices.map { it.copy(outcome = InvoiceOutcome.LEFT_OUT) })
        assertFalse(BundleWizard.toAnnounce(none))
    }

    @Test fun pricesChangedAreKeptPerInvoice() {
        var p = BundleWizard.queued(BundleWizard.confirm(plan()), (0..4).associateWith { "job$it" })
        p = BundleWizard.pricesChanged(p, "job3")
        p = BundleWizard.pricesChanged(p, "nobody")
        assertEquals(listOf(3), p.invoices.indices.filter { p.invoices[it].pricesChanged })
        assertEquals(p, BundlePlanCodec.decode(BundlePlanCodec.encode(p)))
    }

    @Test fun theFirstSaveOfASupplierChecksItsOtherInvoicesAgain() {
        // MEAT prints invoices 0 and 3; every invoice was read and waits to be checked.
        var p = BundleWizard.queued(BundleWizard.confirm(plan()), (0..4).associateWith { "job$it" })
        for (i in 0..4) p = BundleWizard.record(p, "job$i", InvoiceOutcome.READY)
        p = BundleWizard.record(p, "job0", InvoiceOutcome.SAVED_BY_YOU, 10)
        assertEquals(listOf("job3"), BundleWizard.recheckAfterSave(p, "job0"))
        // An invoice put off with "Check later" is not: it is the operator's, with what they corrected on it.
        assertEquals(emptyList<String>(), BundleWizard.recheckAfterSave(BundleWizard.checkLater(p, 3), "job0"))
        // A supplier with one invoice: nothing else to check.
        p = BundleWizard.record(p, "job1", InvoiceOutcome.SAVED_BY_YOU, 11)
        assertEquals(emptyList<String>(), BundleWizard.recheckAfterSave(p, "job1"))
        // The second save of the same supplier: it was known already.
        p = BundleWizard.record(p, "job3", InvoiceOutcome.SAVED_BY_YOU, 13)
        assertEquals(emptyList<String>(), BundleWizard.recheckAfterSave(p, "job3"))
        // No VAT number: no proof to give.
        val noVat = p.copy(invoices = p.invoices.map { it.copy(supplierVat = null) })
        assertEquals(emptyList<String>(), BundleWizard.recheckAfterSave(noVat.copy(invoices = noVat.invoices.mapIndexed { i, inv -> if (i == 3) inv.copy(outcome = InvoiceOutcome.READY) else inv }), "job0"))
    }

    @Test fun theNextJobGoesInPlanOrderAndSkipsTheOnesBeingChecked() {
        var p = BundleWizard.queued(BundleWizard.confirm(plan()), (0..4).associateWith { "job$it" })
        for (i in 0..4) p = BundleWizard.record(p, "job$i", InvoiceOutcome.READY)
        // Plan order: 0, 1, 2, 4, 3.
        assertEquals("job0", BundleWizard.nextJob(p))
        assertEquals("job4", BundleWizard.nextJob(p, afterJob = "job2"))
        assertEquals("job0", BundleWizard.nextJob(p, afterJob = "job3"))
        assertEquals("job2", BundleWizard.nextJob(p, afterJob = "job0", skip = setOf("job1")))
        assertEquals("job0", BundleWizard.nextJob(p, afterJob = "nobody"))
        p = BundleWizard.checkLater(p, 1)
        assertEquals("job2", BundleWizard.nextJob(p, afterJob = "job0"))
        for (i in listOf(0, 2, 3, 4)) p = BundleWizard.record(p, "job$i", InvoiceOutcome.SAVED_BY_YOU, i.toLong())
        // Only the one put off is left; nothing once that one is skipped.
        assertEquals("job1", BundleWizard.nextJob(p, afterJob = "job4"))
        assertNull(BundleWizard.nextJob(p, afterJob = "job1"))
        assertNull(BundleWizard.nextJob(p, skip = setOf("job1")))
    }

    @Test fun joinAndSplitRoundTrip() {
        var p = plan()
        p = BundleWizard.setCategory(p, keyOf(p, HOUSEHOLD.supplierVat), "supplies")
        p = BundleWizard.setInvoiceIncluded(p, 4, false)
        val joined = BundleWizard.joinWithPrevious(p, 1)
        assertEquals(5, joined.invoices.size)
        assertEquals(0..1, joined.invoices[0].first..joined.invoices[0].last)
        assertTrue(Flag.SEVERAL_IN_ONE in joined.invoices[0].flags)
        assertFalse(joined.invoices[0].include)
        assertEquals(1, BundleWizard.found(joined).needLook)
        // The operator's choices on the other invoices and suppliers stay.
        assertFalse(joined.invoices[3].include)
        assertEquals("supplies", joined.suppliers.single { it.vat == HOUSEHOLD.supplierVat }.category)
        val split = BundleWizard.splitAt(joined, 1)
        assertEquals(p.invoices.map { it.first..it.last }, split.invoices.map { it.first..it.last })
        assertEquals(InvoiceBundle.Start.BY_HAND, split.invoices[1].start)
        assertEquals(p.invoices[1].copy(start = InvoiceBundle.Start.BY_HAND), split.invoices[1])
        assertEquals(p.invoices.drop(2), split.invoices.drop(2))
        assertEquals(p.suppliers.map { it.key }.toSet(), split.suppliers.map { it.key }.toSet())
        // Nothing to join before the first, and no split where an invoice already starts.
        assertEquals(p, BundleWizard.joinWithPrevious(p, 0))
        assertEquals(p, BundleWizard.splitAt(p, 2))
    }

    @Test fun aChoiceComesBackWhenAJoinIsUndone() {
        var p = plan()
        // The electricity supplier has one invoice only (the third).
        val energy = BundleWizard.supplierKey(p.invoices[2])
        assertEquals(1, p.invoices.count { BundleWizard.supplierKey(it) == energy })
        p = BundleWizard.setCategory(p, energy, "utilities")
        // Its invoice is joined to the one before (a mis-tap): the supplier leaves the list...
        val joined = BundleWizard.joinWithPrevious(p, 2)
        assertTrue(joined.suppliers.none { it.key == energy })
        // ...and the split that undoes it brings the choice back, not a new supplier to sort.
        val split = BundleWizard.splitAt(joined, p.invoices[2].first)
        assertEquals("utilities", split.suppliers.single { it.key == energy }.category)
        assertTrue(split.retired.isEmpty())
        assertEquals(split, BundlePlanCodec.decode(BundlePlanCodec.encode(split)))
        assertEquals(joined.retired, BundlePlanCodec.decode(BundlePlanCodec.encode(joined))!!.retired)
    }

    @Test fun atMost300InvoicesAtOnce() {
        val segs = (0 until 302).map { seg(it, "01234567897", "N$it") }
        val p = BundleWizard.plan(base.copy(signals = emptyList()), segs)
        assertEquals(BundleWizard.MAX_INVOICES, BundleWizard.toRead(p).size)
        assertEquals(listOf(LeftOut.NEXT_IMPORT, LeftOut.NEXT_IMPORT), p.invoices.takeLast(2).map { it.leftOut })
        assertEquals(p, BundleWizard.setInvoiceIncluded(p, 301, true))
    }

    @Test fun closingKeepsOnlyPagesOutcomesAndDocuments() {
        var p = BundleWizard.queued(BundleWizard.confirm(plan()), (0..4).associateWith { "job$it" })
        p = BundleWizard.record(p, "job0", InvoiceOutcome.SAVED_BY_YOU, 99)
        for (status in listOf(BundleStatus.CLOSED, BundleStatus.CANCELLED)) {
            val closed = BundleWizard.close(p, status)
            assertEquals(status, closed.status)
            assertTrue(closed.suppliers.isEmpty() && closed.signals.isEmpty())
            assertEquals(p.invoices.map { it.first..it.last }, closed.invoices.map { it.first..it.last })
            assertEquals(p.invoices.map { it.outcome }, closed.invoices.map { it.outcome })
            assertEquals(99L, closed.invoices[0].documentId)
            assertTrue(closed.invoices.all { it.supplierVat == null && it.supplierName == null && it.number == null && it.date == null && it.printedTotalCents == null })
            val text = BundlePlanCodec.encode(closed)
            for (secret in invoices.flatMap { listOf(it.supplierVat, it.supplierName, it.number) }) assertFalse(secret, text.contains(secret))
        }
    }

    /**
     * The same file imported again after its import was closed: the new plan keeps what the closed one kept (the
     * original, the e-invoices of invoices not imported), also once the new one is cancelled; a cancelled plan keeps
     * only those.
     */
    @Test fun aNewPlanForTheSameFileKeepsWhatTheEarlierOneKept() {
        val closed = BundleWizard.close(
            base.copy(file = "documents/a.pdf", invoices = listOf(PlannedInvoice(0, 0, file = "documents/left-out.xml", outcome = InvoiceOutcome.LEFT_OUT))),
        )
        assertEquals(setOf("documents/a.pdf", "documents/left-out.xml"), BundleWizard.files(closed))
        val again = BundleWizard.carryKept(base.copy(file = "documents/b.pdf"), closed)
        assertEquals(setOf("documents/a.pdf", "documents/left-out.xml", "documents/b.pdf"), BundleWizard.files(again))
        val cancelled = BundleWizard.close(again, BundleStatus.CANCELLED)
        assertEquals(setOf("documents/a.pdf", "documents/left-out.xml"), BundleWizard.files(cancelled))
        // Once more after that cancel: still kept, and only once each.
        val third = BundleWizard.carryKept(base.copy(file = "documents/a.pdf"), cancelled)
        assertEquals(listOf("documents/a.pdf", "documents/left-out.xml"), third.kept)
        assertEquals(setOf("documents/a.pdf", "documents/left-out.xml"), BundleWizard.files(third))
        assertEquals(third, BundlePlanCodec.decode(BundlePlanCodec.encode(third)))
    }
}
