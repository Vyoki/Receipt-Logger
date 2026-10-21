package com.kitchenreceipts.core

import com.kitchenreceipts.core.EInvoicePrintoutFixture.CUSTOMER_VAT
import com.kitchenreceipts.core.EInvoicePrintoutFixture.ENERGY
import com.kitchenreceipts.core.EInvoicePrintoutFixture.HOUSEHOLD
import com.kitchenreceipts.core.EInvoicePrintoutFixture.MEAT
import com.kitchenreceipts.core.EInvoicePrintoutFixture.WHOLESALER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Importing a file of many invoices (invented): what is found, sorted, checked and read. */
class BundleWizardTest {

    private val invoices = listOf(
        MEAT, WHOLESALER, ENERGY, MEAT.copy(number = "319", date = "28-03-2031"), HOUSEHOLD,
        WHOLESALER.copy(number = "7C 000012400", type = "TD04 nota di credito"),
    )
    private val segments = InvoiceBundle.find(EInvoicePrintoutFixture.bundle(invoices), CUSTOMER_VAT)
    private val base = BundlePlan(id = "b1", file = "x.pdf", fileSha = "abc", pageCount = 6, status = BundleStatus.SCANNING)

    private fun plan(known: Map<String, String?> = emptyMap(), savedOn: (PlannedInvoice) -> String? = { null }, ownVat: String? = CUSTOMER_VAT) =
        BundleWizard.plan(base, segments, segments.map { SupplierCategories.suggest(it.lines) }, known, ownVat, savedOn)

    @Test fun suppliersByVatNumber() {
        val p = plan()
        assertEquals(BundleStatus.SORTING, p.status)
        assertEquals(6, p.invoices.size)
        assertEquals(4, p.suppliers.size)
        val meat = p.suppliers.single { it.vat == MEAT.supplierVat }
        assertEquals("CARNI ESEMPIO S.R.L.", meat.name)
        assertEquals("food", meat.suggestion)
        // A suggestion is never a choice.
        assertNull(meat.category)
        assertEquals("utilities", p.suppliers.single { it.vat == ENERGY.supplierVat }.suggestion)
    }

    @Test fun everyNewSupplierNeedsATap() {
        var p = plan()
        assertEquals(listOf(BundleWizard.Step.FOUND, BundleWizard.Step.SUPPLIERS, BundleWizard.Step.SUMMARY), BundleWizard.steps(p))
        assertFalse(BundleWizard.canContinue(p, BundleWizard.Step.SUPPLIERS))
        for (s in p.suppliers) p = BundleWizard.setCategory(p, s.key, s.suggestion ?: SupplierChoice.LATER)
        assertTrue(BundleWizard.canContinue(p, BundleWizard.Step.SUPPLIERS))
    }

    @Test fun knownSuppliersAreNotAskedAgain() {
        val known = invoices.associate { it.supplierVat to "food" as String? }
        val p = plan(known = known)
        assertTrue(p.suppliers.all { it.known && it.category == "food" })
        assertEquals(listOf(BundleWizard.Step.FOUND, BundleWizard.Step.SUMMARY), BundleWizard.steps(p))
    }

    @Test fun alreadySavedAndOtherBuyerStartLeftOut() {
        val p = plan(savedOn = { inv -> if (inv.number == "318") "17/03/2031" else null })
        val saved = p.invoices.single { it.number == "318" }
        assertFalse(saved.include)
        assertEquals(LeftOut.ALREADY_SAVED, saved.leftOut)
        assertEquals("17/03/2031", saved.savedOn)
        assertEquals(5, BundleWizard.toQueue(p).size)
        // "Import anyway".
        val again = BundleWizard.setInvoiceIncluded(p, p.invoices.indexOf(saved), true)
        assertEquals(6, BundleWizard.toQueue(again).size)
        // An invoice addressed to someone else.
        val other = plan(ownVat = "11111111111")
        assertTrue(other.invoices.all { !it.include && it.leftOut == LeftOut.OTHER_BUYER })
        // Without the own VAT number, the company most invoices are addressed to is the restaurant.
        val majority = plan(ownVat = null)
        assertTrue(majority.invoices.all { it.include })
    }

    @Test fun leaveOutASupplier() {
        var p = plan()
        val energy = p.suppliers.single { it.vat == ENERGY.supplierVat }
        p = BundleWizard.setSupplierIncluded(p, energy.key, false)
        assertEquals(5, BundleWizard.toQueue(p).size)
        assertTrue(BundleWizard.toSort(p).none { it.key == energy.key })
    }

    @Test fun summaryInCents() {
        var p = plan()
        for (s in p.suppliers) p = BundleWizard.setCategory(p, s.key, s.suggestion ?: SupplierChoice.LATER)
        val s = BundleWizard.summary(p)
        assertEquals(6, s.invoices)
        assertEquals(4, s.suppliers)
        assertEquals(LocalDate.of(2031, 3, 14), s.first)
        assertEquals(LocalDate.of(2031, 3, 28), s.last)
        // The invoices: 242,42 + 97,61 + 255,90 + 242,42 + 6,59. The credit note is shown on its own, never netted.
        assertEquals(24242L + 9761L + 25590L + 24242L + 659L, s.totalCents)
        assertEquals(0, s.totalsMissing)
        assertEquals(1, s.creditNotes)
        assertEquals(9761L, s.creditNoteCents)
        assertEquals(2, s.byCategory["food"])
    }

    @Test fun joinWithTheInvoiceBefore() {
        val p = plan()
        val joined = BundleWizard.joinWithPrevious(p, 1)
        assertEquals(5, joined.invoices.size)
        assertEquals(p.invoices[0].first, joined.invoices[0].first)
        assertEquals(p.invoices[1].last, joined.invoices[0].last)
        assertTrue(InvoiceBundle.Problem.SEVERAL_DOCUMENTS in joined.invoices[0].problems)
        assertTrue(BundleWizard.Step.CHECK in BundleWizard.steps(joined))
        assertEquals(p, BundleWizard.joinWithPrevious(p, 0))
    }

    @Test fun planSurvivesAsText() {
        var p = plan(savedOn = { inv -> if (inv.number == "318") "17/03/2031" else null })
        p = BundleWizard.setCategory(p, p.suppliers.first().key, "food")
        p = p.copy(queued = 3, saved = 1, discarded = 1, scannedPages = 6, createdAt = 1234L)
        val q = p.copy(suppliers = p.suppliers.map { it.copy(name = it.name + " | a\\b\nc") })
        assertEquals(q, BundlePlanCodec.decode(BundlePlanCodec.encode(q)))
        assertEquals(p, BundlePlanCodec.decode(BundlePlanCodec.encode(p) + "future=1\n"))
        assertNull(BundlePlanCodec.decode("garbage"))
    }
}
