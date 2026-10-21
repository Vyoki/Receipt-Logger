package com.kitchenreceipts.core

import com.kitchenreceipts.core.EInvoicePrintoutFixture.CUSTOMER_VAT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Invoices printed with the standard e-invoice stylesheet (invented, [EInvoicePrintoutFixture]), read from the PDF's
 * own text: the rules that read this layout for any supplier.
 */
class EInvoicePrintoutLayoutTest {

    private val options = ParseOptions(ownVatNumber = CUSTOMER_VAT, today = LocalDate.of(2031, 5, 2))

    private fun read(inv: EInvoicePrintoutFixture.Invoice) = ReceiptParser.parsePages(EInvoicePrintoutFixture.pages(inv), options)

    private fun text(inv: EInvoicePrintoutFixture.Invoice) =
        EInvoicePrintoutFixture.pages(inv).joinToString("\n") { p -> p.joinToString("\n") { it.text } }

    @Test fun headerAndTotalsUnderTheirHeadings() {
        val d = read(EInvoicePrintoutFixture.MEAT)
        // The supplier's box ends before the intermediary's block: its name is not the supplier's.
        assertEquals("CARNI ESEMPIO S.R.L.", d.sellerName?.value)
        // Number and date under labels stacked with "ART. / 73" (a short number on a label line).
        assertEquals("318", d.documentNumber?.value)
        assertEquals(Confidence.HIGH, d.documentNumber?.confidence)
        assertEquals(LocalDate.of(2031, 3, 14), d.documentDate?.value)
        // Two VAT rates: taxable and VAT are the sums of the summary rows, proven by the total under its heading.
        assertEquals(22300L, d.subtotalCents?.value)
        assertEquals(1942L, d.vatCents?.value)
        assertEquals(24242L, d.totalCents?.value)
        assertEquals(Confidence.HIGH, d.totalCents?.confidence)
    }

    @Test fun supplierVatPrintedTwiceInItsBox() {
        val inv = EInvoicePrintoutFixture.MEAT
        assertEquals(inv.supplierVat, SellerProfiles.supplierVatNumber(text(inv), CUSTOMER_VAT))
    }

    @Test fun linesReadByColumnsUnderABandOfHeadings() {
        val d = read(EInvoicePrintoutFixture.MEAT)
        assertEquals(listOf(11500L, 6000L, 4800L), d.lineItems.map { it.lineTotalCents?.value })
        assertEquals(listOf("10", "10", "4"), d.lineItems.map { it.vatRatePercent?.value?.stripTrailingZeros()?.toPlainString() })
        // The code's type printed after it is no part of the name.
        assertEquals(listOf("Pancetta tesa", "Salsiccia fresca", "Pecorino stagionato"), d.lineItems.map { it.originalDescription })
        assertTrue(d.lineItems.none { ParseWarning.LINE_TOTAL_MISMATCH in it.warnings })
    }

    @Test fun lotNotesUnderTheLineAreItsLots() {
        val d = read(EInvoicePrintoutFixture.MEAT)
        assertEquals(listOf("AB123", "CD456", "EF789"), d.lineItems.map { it.lotNumber?.value })
        assertTrue(d.lineItems.all { it.lotNumber?.confidence == Confidence.HIGH })
    }

    @Test fun otherNotesAreNeverItemsOrLots() {
        val d = read(EInvoicePrintoutFixture.WHOLESALER)
        assertEquals(listOf(1920L, 1240L, 1900L, 2100L, 120L, 1560L), d.lineItems.map { it.lineTotalCents?.value })
        assertTrue(d.lineItems.none { it.lotNumber?.value == "COPPA-INTERA" })
        // Three rates in the summary, each proven, and their sum the printed total.
        assertEquals(9761L, d.totalCents?.value)
        assertEquals(Confidence.HIGH, d.totalCents?.confidence)
        // A pack size in a name is no lot.
        assertTrue(d.lineItems.none { it.lotNumber?.value?.contains("40X40") == true })
    }

    @Test fun lotQuantityAndNumberNotesAreNoItems() {
        // The standard layout's lot note ("Lotto:", "Scadenza:", "Quantità del suddetto lotto:") and a numeric note
        // ("Rif. numero:", printed like money) under a line: notes of that line, never items or amounts.
        val meat = EInvoicePrintoutFixture.MEAT
        val first = meat.lines[0].copy(notes = emptyList(), more = listOf("Lotto: GH321", "Scadenza: 20-04-2031", "Quantità del suddetto lotto: 12,50", "Rif. numero: 12,50"))
        val d = read(meat.copy(lines = listOf(first) + meat.lines.drop(1)))
        assertEquals(listOf(11500L, 6000L, 4800L), d.lineItems.map { it.lineTotalCents?.value })
        assertEquals("GH321", d.lineItems[0].lotNumber?.value)
        assertEquals(24242L, d.totalCents?.value)
    }

    @Test fun utilityUnitsAreKeptAsPrinted() {
        val d = read(EInvoicePrintoutFixture.ENERGY)
        assertEquals(3, d.lineItems.size)
        // Units the app has no name for are kept as printed; a "pz" is never made up.
        assertEquals(listOf("kwh", "mese", "cad"), d.lineItems.map { it.unit?.value })
        assertTrue(d.lineItems.none { it.unit?.value == "pz" })
    }

    @Test fun vatNatureCodeAndNegativeLine() {
        val d = read(EInvoicePrintoutFixture.ENERGY)
        val rounding = d.lineItems.last()
        assertEquals(-30L, rounding.lineTotalCents?.value)
        assertEquals(0, BigDecimal("-0.30").compareTo(rounding.unitPrice?.value))
        // "N2.2" in the VAT column is no rate: the line has none, and nothing is made up for it.
        assertEquals(null, rounding.vatRatePercent?.value)
        // Without the nature row the summary does not add up to the total: the bill is not proven, so it goes to review.
        assertTrue(d.totalCents?.confidence != Confidence.HIGH || d.warnings.isNotEmpty())
    }

    @Test fun soleTraderAndLetterSpacedNames() {
        val d = read(EInvoicePrintoutFixture.HOUSEHOLD)
        assertEquals("BIANCHI LUCA", d.sellerName?.value)
        assertEquals("55/A", d.documentNumber?.value)
        assertEquals(listOf(300L, 240L), d.lineItems.map { it.lineTotalCents?.value })
        // Letters printed one by one are one word: never a unit "g" or "l", never a code.
        assertTrue(d.lineItems.none { it.unit?.value == "g" || it.unit?.value == "l" })
        assertTrue(d.lineItems.first().originalDescription.startsWith("SAPONE"))
    }

    @Test fun nameOverTwoLinesIsOneSupplier() {
        val d = read(EInvoicePrintoutFixture.TWO_LINE_NAME)
        assertEquals("VERDI CARNI DI VERDI ANNA & C. SNC", d.sellerName?.value)
        assertNotEquals(SupplierProof.State.CONFLICT, d.supplierProof?.state)
    }

    @Test fun linesRunningOverTwoPages() {
        val pages = EInvoicePrintoutFixture.pages(EInvoicePrintoutFixture.LONG)
        assertTrue(pages.size >= 2)
        val d = ReceiptParser.parsePages(pages, options)
        assertEquals(60, d.lineItems.size)
        assertEquals(18720L, d.totalCents?.value)
        assertEquals(18000L, d.lineItems.sumOf { it.lineTotalCents?.value ?: 0L })
    }

    @Test fun deliveryNoteReferencesAreNoLots() {
        val d = read(EInvoicePrintoutFixture.MEAT.copy(lines = EInvoicePrintoutFixture.MEAT.lines.map { it.copy(notes = emptyList()) }))
        assertTrue(d.lineItems.none { it.lotNumber?.value?.startsWith("61") == true || it.lotNumber?.value?.startsWith("74") == true })
    }

    @Test fun packSizes() {
        assertTrue(LotExtractor.packSizeLike("KG9X7"))
        assertTrue(LotExtractor.packSizeLike("ML.333"))
        assertTrue(LotExtractor.packSizeLike("ROT.MT45X3PZ"))
        assertTrue(LotExtractor.packSizeLike("BIA3V40X40X30"))
        assertTrue(!LotExtractor.packSizeLike("L.0042"))
        assertTrue(!LotExtractor.packSizeLike("AB731942"))
    }
}
