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

    /** Phone photos rebuild rows differently: the same note with the label/value rows apart and the total label wrapped. */
    @Test fun dateAndTotalSurviveARoughPhoto() {
        val rough = text
            .replace(
                "09876543217 09876543217 RIMESSA DIRETTA ENTRO 60 GG.D.F. B26 111945 15/09/2026 1/1",
                "09876543217 09876543217\nRIMESSA DIRETTA\nENTRO 60 GG.D.F. 15/09/2026 1/1",
            )
            .replace(
                "22 28,90 6,36 Durata: presente consegna TOTALE DOCUMENTO DI CONSEGNA VALORIZZATO CHE NON COSTITUISCE FATTURA\n231,27",
                "22 28,90 6,36\nTOTALE DOCUMENTO DI\nCONSEGNA VALORIZZATO\nCHE NON COSTITUISCE FATTURA\n231,27",
            )
        val r = ReceiptParser.parse(rough, ParseOptions(ownVatNumber = "09876543217"))
        assertEquals(LocalDate.of(2026, 9, 15), r.documentDate?.value)
        assertEquals(23127L, r.totalCents?.value)
    }

    @Test fun totalFromTheVatSummaryWhenTheTotalIsUnreadable() {
        val noTotal = text.replace("231,27\n", "").replace("TOTALE DOCUMENTO DI CONSEGNA VALORIZZATO CHE NON COSTITUISCE FATTURA", "")
        val r = ReceiptParser.parse(noTotal, ParseOptions(ownVatNumber = "09876543217"))
        assertEquals(23127L, r.totalCents?.value)
        // imponibile + IVA; confirmed because the line items add up to the taxable amount
        assertTrue(r.totalCents!!.source!!.contains("TOTALI"))
    }

    @Test fun dateOnlyAsDeliveryDateIsStillProposed() {
        val r = ReceiptParser.parse("MAGAZZINO ESEMPIO S.R.L.\nDATA CONSEGNA 16/09/2026\nPATATE KG 10,000 0,90 9,00", ParseOptions())
        assertEquals(LocalDate.of(2026, 9, 16), r.documentDate?.value)
        assertEquals(Confidence.LOW, r.documentDate?.confidence)
    }

    /**
     * A tilted photo puts the numbers of "CARTA FORNO" between its line and the section title above it,
     * and the rows are rebuilt with the title: the product is still CARTA FORNO.
     */
    @Test fun sectionTitleNeverBecomesAProduct() {
        val tilted = text.replace(
            "Merce non alimentare\n24195 CARTA FORNO 40CM X 50M C/ASTUCCIO NR 3,000 F 5,412 16,24 22",
            "Merce non alimentare NR 3,000 F 5,412 16,24 22\n24195 CARTA FORNO 40CM X 50M C/ASTUCCIO",
        )
        assertTrue(tilted != text)
        val r = ReceiptParser.parse(tilted, ParseOptions(ownVatNumber = "09876543217"))
        val carta = r.lineItems.last()
        assertTrue(carta.originalDescription, carta.originalDescription.startsWith("CARTA FORNO"))
        assertEquals("24195", carta.itemCode)
        assertEquals(1624L, carta.lineTotalCents?.value)
        assertEquals("B269-27522", carta.lotNumber?.value)
        assertTrue(r.lineItems.none { ReceiptParser.isSectionHeading(it.originalDescription) })
        assertEquals(8, r.lineItems.size)
    }

    @Test fun aiAnswerNamingASectionTitleIsRepaired() {
        val tilted = text.replace(
            "Merce non alimentare\n24195 CARTA FORNO 40CM X 50M C/ASTUCCIO NR 3,000 F 5,412 16,24 22",
            "Merce non alimentare NR 3,000 F 5,412 16,24 22\n24195 CARTA FORNO 40CM X 50M C/ASTUCCIO",
        )
        val answer = """{"seller":"VERDE FRESCO S.p.A.","seller_vat":null,"number":"B26 111945","date":"15/09/2026","subtotal":"209,76","vat":"21,51","total":"231,27",
            "items":[{"code":"24195","colli":null,"description":"Merce non alimentare","unit":"NR","quantity":"3,000","price":"5,412","discount":null,"amount":"16,24","vat_rate":"22","lot":null}]}"""
        val d = AiReader.toParsed(AiReader.decode(answer)!!, tilted)
        assertTrue(d.lineItems.single().originalDescription.startsWith("CARTA FORNO"))
        assertTrue(ReceiptParser.isSectionHeading("Merce non deperibile - Congelato"))
        assertTrue(ReceiptParser.isSectionHeading("MERCE NON ALIMENTARE"))
        assertTrue(!ReceiptParser.isSectionHeading("MERCEDES PANE"))
    }
}
