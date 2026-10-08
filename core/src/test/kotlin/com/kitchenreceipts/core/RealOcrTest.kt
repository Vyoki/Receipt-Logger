package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Messy input as produced by a phone camera + on-device OCR. */
class RealOcrTest {

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()
    private fun assertDec(expected: String, actual: BigDecimal?) =
        assertTrue("expected $expected but was $actual", actual != null && actual.compareTo(BigDecimal(expected)) == 0)

    @Test fun noisyRetailReceipt() {
        val d = ReceiptParser.parse(fixture("scontrino_ocr_reale.txt"))
        assertEquals("SUPERMERCATO LA SPIGA", d.sellerName?.value)
        assertEquals(LocalDate.of(2025, 3, 14), d.documentDate?.value) // "l4-03-2025"
        assertEquals("0123-0045", d.documentNumber?.value)
        assertEquals(967L, d.totalCents?.value)
        assertEquals(55L, d.vatCents?.value)
        assertEquals(VatBasis.INCLUSIVE, d.vatBasis?.value)

        assertEquals(listOf("PANE COMUNE", "LATTE INTERO", "POMODORI PELATI", "MOZZARELLA BUF"), d.lineItems.map { it.originalDescription })
        assertEquals(listOf(250L, 149L, 178L, 390L), d.lineItems.map { it.lineTotalCents?.value })

        val pane = d.lineItems[0] // "2 x 1,25" printed on the line below
        assertDec("2", pane.quantity?.value)
        assertDec("1.25", pane.unitPrice?.value)
        assertEquals(Confidence.HIGH, pane.quantity?.confidence)

        val latte = d.lineItems[1] // "1,49 B": VAT letter ignored
        assertEquals("l", latte.unit?.value)
        assertDec("1", latte.quantity?.value)

        val pomodori = d.lineItems[2] // description and amounts on two rows
        assertDec("2", pomodori.quantity?.value)
        assertDec("0.89", pomodori.unitPrice?.value)

        assertTrue(ParseWarning.ITEMS_SUM_MISMATCH !in d.warnings)
    }

    @Test fun invoiceWithColumnLayout() {
        val d = ReceiptParser.parse(fixture("fattura_colonne.txt"))
        assertEquals("ALIMENTARI DEL SUD S.R.L.", d.sellerName?.value)
        assertEquals(Confidence.HIGH, d.sellerName?.confidence)
        assertEquals("2025/0311", d.documentNumber?.value)
        assertEquals(LocalDate.of(2025, 3, 18), d.documentDate?.value)
        assertEquals(12300L, d.subtotalCents?.value)
        assertEquals(2130L, d.vatCents?.value)
        assertEquals(14430L, d.totalCents?.value)
        assertEquals(VatBasis.EXCLUSIVE, d.vatBasis?.value)

        assertEquals(3, d.lineItems.size)
        assertEquals(listOf(7500L, 2880L, 1920L), d.lineItems.map { it.lineTotalCents?.value })
        val olio = d.lineItems[0]
        assertDec("10", olio.quantity?.value)
        assertDec("7.50", olio.unitPrice?.value)
        assertEquals("l", olio.unit?.value)
        val pasta = d.lineItems[1] // 10% discount column: kept with the line, so the line adds up
        assertDec("20", pasta.quantity?.value)
        assertDec("1.60", pasta.unitPrice?.value)
        assertEquals("10", pasta.discount?.value)
        assertEquals(2880L, pasta.lineTotalCents?.value)
        val pomodoro = d.lineItems[2] // description split over two rows
        assertTrue(pomodoro.originalDescription.contains("Pomodoro San Marzano"))
        assertEquals("conf", pomodoro.unit?.value)
        assertDec("6", pomodoro.quantity?.value)
        assertTrue(d.lineItems.none { it.originalDescription.contains("Totale", ignoreCase = true) })
        assertNull(d.currency)
    }

    @Test fun ownBusinessIsNeverTheSeller() {
        val text = "RISTORANTE ESEMPIO S.R.L.\nVia Immaginaria 1\nMACELLERIA BIANCHI S.A.S.\nFattura n. 12\nFiletto kg 1 40,00 40,00\nTotale 40,00"
        assertEquals("RISTORANTE ESEMPIO S.R.L.", ReceiptParser.parse(text).sellerName?.value)
        val d = ReceiptParser.parse(text, ParseOptions(ownBusinessName = "Ristorante Esempio srl"))
        assertEquals("MACELLERIA BIANCHI S.A.S.", d.sellerName?.value)
    }
}
