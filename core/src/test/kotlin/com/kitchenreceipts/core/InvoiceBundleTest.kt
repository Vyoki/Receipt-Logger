package com.kitchenreceipts.core

import com.kitchenreceipts.core.EInvoicePrintoutFixture.CUSTOMER_VAT
import com.kitchenreceipts.core.EInvoicePrintoutFixture.ENERGY
import com.kitchenreceipts.core.EInvoicePrintoutFixture.HOUSEHOLD
import com.kitchenreceipts.core.EInvoicePrintoutFixture.LONG
import com.kitchenreceipts.core.EInvoicePrintoutFixture.MEAT
import com.kitchenreceipts.core.EInvoicePrintoutFixture.TWO_LINE_NAME
import com.kitchenreceipts.core.EInvoicePrintoutFixture.WHOLESALER
import com.kitchenreceipts.core.bench.BenchTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Where each invoice starts in one PDF of many (invented invoices in the standard e-invoice printout). */
class InvoiceBundleTest {

    private val invoices = listOf(
        MEAT, WHOLESALER, ENERGY, LONG,
        MEAT.copy(number = "319", date = "28-03-2031"), // the same supplier again, the next invoice
        HOUSEHOLD, TWO_LINE_NAME,
        WHOLESALER.copy(number = "7C 000012400", type = "TD04 nota di credito"),
    )

    private fun ranges(): List<IntRange> {
        var at = 0
        return invoices.map { inv -> val n = EInvoicePrintoutFixture.pages(inv).size; (at until at + n).also { at += n } }
    }

    @Test fun findsEveryInvoice() {
        val pages = EInvoicePrintoutFixture.bundle(invoices)
        val found = InvoiceBundle.find(pages, CUSTOMER_VAT)
        assertEquals(ranges(), found.map { it.first..it.last })
        assertTrue(InvoiceBundle.isBundle(found))
        assertEquals(invoices.map { it.supplierVat }, found.map { it.supplierVat })
        assertEquals(invoices.map { it.number }, found.map { it.number })
        assertEquals(listOf(24242L, 9761L, 25590L, 18720L, 24242L, 659L, 8910L, 9761L), found.map { it.totalCents })
        assertEquals(LocalDate.of(2031, 3, 28), found[4].date)
        assertTrue(found.all { it.customerVat == CUSTOMER_VAT })
        assertTrue(found.last().creditNote)
        assertTrue(found.dropLast(1).none { it.creditNote })
        assertTrue(found.all { it.sure })
    }

    @Test fun oneInvoiceIsNoBundle() {
        for (inv in listOf(MEAT, LONG, ENERGY)) {
            val found = InvoiceBundle.find(EInvoicePrintoutFixture.pages(inv), CUSTOMER_VAT)
            assertEquals(1, found.size)
            assertFalse(InvoiceBundle.isBundle(found))
        }
    }

    @Test fun headerPrintedAgainOnEveryPageIsTheSameInvoice() {
        // A three-page invoice that reprints its parties and number at the top of each page.
        val one = EInvoicePrintoutFixture.pages(MEAT).single()
        val withoutTotals = one.filterNot { it.text.contains("TOTALE DOCUMENTO") || it.text == "242,42" }
        val found = InvoiceBundle.find(listOf(withoutTotals, withoutTotals, one), CUSTOMER_VAT)
        assertEquals(listOf(0..2), found.map { it.first..it.last })
    }

    @Test fun pagesWithoutTextAndBeforeTheFirstInvoice() {
        val blank = emptyList<OcrLine>()
        val pages = listOf(blank) + EInvoicePrintoutFixture.pages(MEAT) + EInvoicePrintoutFixture.pages(HOUSEHOLD)
        val found = InvoiceBundle.find(pages, CUSTOMER_VAT)
        assertEquals(3, found.size)
        assertFalse(found.first().opened)
        assertTrue(InvoiceBundle.Problem.NO_TEXT in found.first().problems)
        assertTrue(InvoiceBundle.isBundle(found))
    }

    @Test fun twoInvoicesOnTheSamePagesAreFlagged() {
        val a = EInvoicePrintoutFixture.pages(MEAT).single()
        val b = EInvoicePrintoutFixture.pages(HOUSEHOLD).single().map { it.copy(top = it.top + 3400, bottom = it.bottom + 3400) }
            .filterNot { it.text.startsWith("Cedente") } // the second invoice on the same sheet, below the first
        val found = InvoiceBundle.find(listOf(a + b), CUSTOMER_VAT)
        assertEquals(1, found.size)
        assertTrue(InvoiceBundle.Problem.SEVERAL_DOCUMENTS in found.single().problems)
    }

    /** No document of the benchmark is ever taken for several invoices. */
    @Test fun benchmarkDocumentsAreNeverSplit() {
        val bench = BenchTest()
        for (d in bench.realDocs() + bench.generated()) {
            val found = InvoiceBundle.find(d.pages, d.ownVat)
            assertFalse(d.name, InvoiceBundle.isBundle(found))
        }
    }

    @Test fun fastOnALongFile() {
        val pages = EInvoicePrintoutFixture.bundle(List(40) { invoices }.flatten())
        val t0 = System.nanoTime()
        val found = InvoiceBundle.find(pages, CUSTOMER_VAT)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(invoices.size * 40, found.size)
        assertTrue("took $ms ms for ${pages.size} pages", ms < 20_000)
    }
}
