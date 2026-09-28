package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Layout of a real frozen/fresh food wholesaler's delivery note (content invented). */
class DdtSurgelatiTest {
    private val text = javaClass.classLoader!!.getResource("fixtures/ddt_surgelati.txt")!!.readText()
    private val d = ReceiptParser.parse(text, ParseOptions(ownVatNumber = "09876543217"))
    private fun item(prefix: String) = d.lineItems.first { it.originalDescription.startsWith(prefix) }
    private fun assertDec(expected: String, actual: BigDecimal?) =
        assertTrue("expected $expected but was $actual", actual != null && actual.compareTo(BigDecimal(expected)) == 0)

    @Test fun header() {
        assertEquals("VERDE FRESCO S.p.A.", d.sellerName?.value)
        assertEquals("B26 111945", d.documentNumber?.value)
        assertEquals(LocalDate.of(2026, 9, 15), d.documentDate?.value)
        assertEquals("01234567897", SellerProfiles.supplierVatNumber(text, null))
    }

    @Test fun allEightLines() {
        assertEquals(listOf(9536L, 2646L, 527L, 1021L, 844L, 422L, 4356L, 1624L), d.lineItems.map { it.lineTotalCents?.value })
        val torta = item("TORTA") // "NR 40,000 C 2,384 95,36 10": C = frozen, not part of the numbers
        assertDec("40", torta.quantity?.value)
        assertEquals("pz", torta.unit?.value)
        assertDec("2.384", torta.unitPrice?.value)
        assertDec("10", torta.vatRatePercent?.value)
        assertEquals(Confidence.HIGH, torta.quantity?.confidence)
        val pelati = item("POMODORI") // "CT 2,000 CN 21,780 43,56 04"
        assertDec("2", pelati.quantity?.value)
        assertEquals("ct", pelati.unit?.value)
        assertDec("21.780", pelati.unitPrice?.value)
        assertTrue(item("CARTA FORNO").originalDescription.startsWith("CARTA FORNO"))
    }

    @Test fun lotNumbersFromTheLotColumn() {
        assertEquals("788058", item("TORTA").lotNumber?.value)
        assertEquals("B269-27519", item("PANNA").lotNumber?.value)
        assertEquals("B269-27522", item("CARTA FORNO").lotNumber?.value)
    }

    @Test fun totals() {
        assertEquals(23127L, d.totalCents?.value)
        assertEquals(20976L, d.subtotalCents?.value)
        assertEquals(2151L, d.vatCents?.value)
        assertTrue(ParseWarning.ITEMS_SUM_MISMATCH !in d.warnings)
        assertEquals(VatBasis.EXCLUSIVE, d.vatBasis?.value)
    }
}
