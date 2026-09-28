package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ReceiptParserTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("fixtures/$name")) { "missing fixture $name" }.readText()

    private fun dec(s: String) = BigDecimal(s)
    private fun assertDec(expected: String, actual: BigDecimal?) =
        assertTrue("expected $expected but was $actual", actual != null && actual.compareTo(dec(expected)) == 0)

    @Test fun invoiceCaseificio() {
        val d = ReceiptParser.parse(fixture("fattura_caseificio.txt"))
        assertEquals("CASEIFICIO VALVERDE S.R.L.", d.sellerName?.value)
        assertEquals(Confidence.HIGH, d.sellerName?.confidence)
        assertEquals("0145/2025", d.documentNumber?.value)
        assertEquals(LocalDate.of(2025, 3, 14), d.documentDate?.value)
        assertEquals("EUR", d.currency?.value)
        assertEquals(6460L, d.subtotalCents?.value)
        assertEquals(646L, d.vatCents?.value)
        assertEquals(7106L, d.totalCents?.value)
        assertEquals(VatBasis.EXCLUSIVE, d.vatBasis?.value)

        assertEquals(4, d.lineItems.size)
        val mozz = d.lineItems[0]
        assertEquals("MZ01 Mozzarella fior di latte", mozz.originalDescription)
        assertDec("2.5", mozz.quantity?.value)
        assertEquals("kg", mozz.unit?.value)
        assertDec("8.90", mozz.unitPrice?.value)
        assertEquals(2225L, mozz.lineTotalCents?.value)
        assertDec("10", mozz.vatRatePercent?.value)
        assertEquals(Confidence.HIGH, mozz.quantity?.confidence)
        assertEquals("L24-118", mozz.lotNumber?.value)
        assertEquals(LocalDate.of(2025, 3, 20), mozz.expiryDate?.value)

        assertEquals("240312", d.lineItems[1].lotNumber?.value)
        assertNull(d.lineItems[1].expiryDate)

        val parm = d.lineItems[2]
        assertDec("1.235", parm.quantity?.value)
        assertEquals(2655L, parm.lineTotalCents?.value)
        // "Lotto 12/03/2025" is a date, so the lot stays missing and a warning is raised.
        assertNull(parm.lotNumber)
        assertTrue(ParseWarning.LOT_LOOKS_LIKE_DATE in d.warnings)

        val burro = d.lineItems[3]
        assertEquals("pz", burro.unit?.value)
        assertDec("4", burro.quantity?.value)
        assertNull(burro.lotNumber)

        assertFalse(ParseWarning.ITEMS_SUM_MISMATCH in d.warnings)
        assertFalse(ParseWarning.TOTALS_INCONSISTENT in d.warnings)
    }

    @Test fun retailReceipt() {
        val d = ReceiptParser.parse(fixture("scontrino_mercato.txt"))
        assertEquals("MERCATO FRESCO", d.sellerName?.value)
        assertEquals(Confidence.LOW, d.sellerName?.confidence)
        assertEquals(LocalDate.of(2025, 3, 14), d.documentDate?.value)
        assertEquals("0042-0017", d.documentNumber?.value)
        assertEquals(1640L, d.totalCents?.value)
        assertEquals(127L, d.vatCents?.value)
        assertEquals(VatBasis.INCLUSIVE, d.vatBasis?.value)

        assertEquals(listOf("LIMONI", "PREZZEMOLO", "OLIO EXTRAVERGINE", "UOVA FRESCHE"), d.lineItems.map { it.originalDescription })
        val limoni = d.lineItems[0]
        assertDec("2", limoni.quantity?.value)
        assertDec("1.20", limoni.unitPrice?.value)
        assertEquals(240L, limoni.lineTotalCents?.value)

        val prezzemolo = d.lineItems[1]
        assertNull("quantity is not printed, so it must stay missing", prezzemolo.quantity)
        assertEquals("mazzo", prezzemolo.unit?.value)
        assertEquals(90L, prezzemolo.lineTotalCents?.value)

        val olio = d.lineItems[2]
        assertDec("1", olio.quantity?.value)
        assertEquals("l", olio.unit?.value)
        assertNull(olio.unitPrice)
        assertEquals(980L, olio.lineTotalCents?.value)

        // payment and change lines are not items
        assertTrue(d.lineItems.none { it.lineTotalCents?.value == 2000L || it.lineTotalCents?.value == 360L })
        assertTrue(d.lineItems.all { it.lotNumber == null })
    }

    @Test fun deliveryNote() {
        val d = ReceiptParser.parse(fixture("ddt_ortofrutta.txt"))
        assertEquals("Ortofrutta Collina Verde s.n.c.", d.sellerName?.value)
        assertEquals("88", d.documentNumber?.value)
        assertEquals(LocalDate.of(2025, 4, 3), d.documentDate?.value)
        assertEquals(7465L, d.subtotalCents?.value)
        assertNull(d.totalCents)
        assertNull(d.vatCents)
        assertNull("nothing says whether prices include VAT", d.vatBasis)
        assertNull("no € sign or EUR label in the text", d.currency)

        assertEquals(5, d.lineItems.size)
        assertEquals("PSM-0325", d.lineItems[0].lotNumber?.value)
        val basilico = d.lineItems[2]
        assertEquals("mazzo", basilico.unit?.value)
        assertNull("'Scadenza' must not become a lot", basilico.lotNumber)
        assertEquals(LocalDate.of(2025, 4, 10), basilico.expiryDate?.value)
        assertEquals("cs", d.lineItems[3].unit?.value)

        val insalata = d.lineItems[4]
        assertTrue(ParseWarning.LINE_TOTAL_MISMATCH in insalata.warnings)
        assertEquals(Confidence.LOW, insalata.lineTotalCents?.confidence)
        assertEquals("the printed total is kept, not recomputed", 750L, insalata.lineTotalCents?.value)
        assertTrue(ParseWarning.LINE_TOTAL_MISMATCH in d.warnings)
        // customer name must not be taken as seller
        assertFalse(d.sellerName!!.value.contains("Trattoria"))
    }

    @Test fun invoiceWithThousandsAndTwoVatRates() {
        val d = ReceiptParser.parse(fixture("fattura_macelleria.txt"))
        assertEquals("MACELLERIA F.LLI BIANCHI S.A.S.", d.sellerName?.value)
        assertEquals("FT/2025/310", d.documentNumber?.value)
        assertEquals(LocalDate.of(2025, 3, 31), d.documentDate?.value)
        assertEquals(142070L, d.subtotalCents?.value)
        assertEquals(15467L, d.vatCents?.value)
        assertEquals(Confidence.HIGH, d.vatCents?.confidence) // confirmed by subtotal + VAT = total
        assertEquals(157537L, d.totalCents?.value)
        assertEquals(VatBasis.EXCLUSIVE, d.vatBasis?.value)
        assertEquals(Confidence.LOW, d.vatBasis?.confidence)

        assertEquals(5, d.lineItems.size)
        assertEquals(listOf(42000L, 19840L, 10230L, 10500L, 59500L), d.lineItems.map { it.lineTotalCents?.value })
        assertEquals("GU-7781", d.lineItems[2].lotNumber?.value)
        assertEquals(LocalDate.of(2025, 6, 30), d.lineItems[2].expiryDate?.value)
        assertEquals("conf", d.lineItems[3].unit?.value)
        assertDec("22", d.lineItems[3].vatRatePercent?.value)
        assertTrue(d.lineItems.none { it.originalDescription.contains("Via") })
        assertTrue(d.warnings.isEmpty())
    }

    @Test fun emptyAndGarbageText() {
        assertEquals(ParsedDocument.EMPTY, ReceiptParser.parse(""))
        val d = ReceiptParser.parse("@@@\n###\n")
        assertTrue(d.lineItems.isEmpty())
        assertTrue(ParseWarning.NO_ITEMS_FOUND in d.warnings)
        assertNull(d.totalCents)
        assertNull(d.documentDate)
    }

    @Test fun singleItemLines() {
        val a = ReceiptParser.parseItemLine("Farina 00 sacco 2 18,50 37,00")!!
        assertEquals("Farina 00", a.originalDescription)
        assertEquals("sacco", a.unit?.value)
        assertEquals(3700L, a.lineTotalCents?.value)

        val b = ReceiptParser.parseItemLine("Pane casereccio 3,20")!!
        assertNull(b.quantity)
        assertNull(b.unitPrice)
        assertEquals(320L, b.lineTotalCents?.value)

        assertNull(ReceiptParser.parseItemLine("Via Roma 12"))
        assertNull(ReceiptParser.parseItemLine("12,00"))
    }
}
