package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiPageTest {
    private val pb = ReceiptParser.PAGE_BREAK

    @Test fun twoPageInvoiceKeepsItemsFromBothPages() {
        val text = """
            CASEIFICIO VALVERDE S.R.L.
            P.IVA IT01234567890
            FATTURA N. 0200/2025 del 02/04/2025
            Descrizione U.M. Q.tà Prezzo Importo
            Mozzarella fior di latte kg 2,500 8,90 22,25
            Ricotta vaccina kg 1,000 6,40 6,40
            A riportare 28,65
            Pagina 1 di 2
            $pb
            CASEIFICIO VALVERDE S.R.L.
            FATTURA N. 0200/2025 del 02/04/2025
            Descrizione U.M. Q.tà Prezzo Importo
            Riporto 28,65
            Burro panna pz 4 2,35 9,40
            Imponibile 38,05
            IVA 10% 3,81
            Totale documento 41,86
            Pagina 2 di 2
        """.trimIndent()
        val d = ReceiptParser.parse(text)
        assertEquals(listOf(2225L, 640L, 940L), d.lineItems.map { it.lineTotalCents?.value })
        assertEquals(3805L, d.subtotalCents?.value)
        assertEquals(4186L, d.totalCents?.value)
        assertEquals("0200/2025", d.documentNumber?.value)
        assertTrue(ParseWarning.ITEMS_SUM_MISMATCH !in d.warnings)
    }

    @Test fun pageSubtotalIsNotTheDocumentTotal() {
        val text = "NEGOZIO ESEMPIO\nPane 2,00\nLatte 1,50\nTotale 3,50\n${pb}\nUova 3,00\nTotale 6,50"
        val d = ReceiptParser.parse(text)
        assertEquals(650L, d.totalCents?.value)
        assertEquals(listOf(200L, 150L, 300L), d.lineItems.map { it.lineTotalCents?.value })
    }

    @Test fun overlappingPhotosOfALongReceiptAreNotCountedTwice() {
        val part1 = "MERCATO FRESCO\nPANE COMUNE 2,50\nLATTE INTERO 1,49\nUOVA FRESCHE 3,30"
        val part2 = "LATTE INTERO 1,49\nUOVA FRESCHE 3,30\nMOZZARELLA 3,90\nTOTALE COMPLESSIVO 11,19"
        val d = ReceiptParser.parse("$part1\n$pb\n$part2")
        assertEquals(listOf("PANE COMUNE", "LATTE INTERO", "UOVA FRESCHE", "MOZZARELLA"), d.lineItems.map { it.originalDescription })
        assertEquals(1119L, d.totalCents?.value)
        assertTrue(ParseWarning.ITEMS_SUM_MISMATCH !in d.warnings)
    }
}
