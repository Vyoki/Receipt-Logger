package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The courtesy printout of an electronic invoice, photographed (an invented one, in the layout many accounting
 * programs print): the two parties side by side, headings over two or three lines ("Prezzo / unitario",
 * "Numero / documento"), the amount column called "Prezzo totale", the unit after the price, and the totals in a
 * grid of labels and values.
 */
class EInvoicePrintoutTest {

    /** One OCR line, its words spread over its box (10 px per character). */
    private fun l(x: Int, y: Int, text: String): OcrLine {
        var at = x
        val words = text.split(' ').map { w -> OcrLine(w, at, y, at + w.length * 10, y + 20).also { at += (w.length + 1) * 10 } }
        return OcrLine(text, x, y, x + text.length * 10, y + 20, words = if (words.size > 1) words else emptyList())
    }

    private val page = listOf(
        l(76, 88, "Cedente/prestatore (fornitore)"), l(549, 88, "Cessionario/committente (cliente)"),
        l(76, 102, "Identificativo fiscale ai fini IVA: IT01234567897"), l(550, 101, "Identificativo fiscale ai fini IVA: IT09876543217"),
        l(76, 121, "Denominazione: MACELLERIA ROSSI DI ROSSI"), l(550, 119, "Denominazione: RISTORANTE PROVA SAS"),
        l(80, 140, "MARIO"), l(550, 155, "Indirizzo: VIA ESEMPIO 1"),
        l(77, 175, "Regime fiscale: RF01 ordinario"), l(548, 171, "Comune: ROMA Provincia: RM"),
        l(76, 189, "Indirizzo: VIA DI PROVA 12"),
        l(675, 269, "Numero"), l(795, 269, "Data"), l(899, 267, "Codice"),
        l(72, 280, "Tipologia documento"), l(601, 280, "Art. 73"),
        l(680, 292, "documento"), l(797, 292, "documento"), l(904, 292, "destinatario"),
        l(65, 312, "TD24 fattura differita di cui all'art.21 lett."), l(714, 313, "45"), l(797, 313, "31-07-2026"), l(918, 313, "ABC1234"),
        l(614, 393, "Prezzo"), l(773, 393, "Sconto"),
        l(77, 400, "Cod. articolo"), l(173, 400, "Descrizione"), l(512, 401, "Quantità"), l(714, 400, "UM"), l(841, 400, "%IVA"), l(902, 400, "Prezzo totale"),
        l(612, 410, "unitario"), l(773, 411, "o magg."),
        l(165, 434, "RIF.DDT 77 DEL 30/07/26 AGNELLO"), l(552, 437, "120,40"), l(667, 437, "12,50 KG"), l(845, 437, "10,00"), l(946, 437, "1.505,00"),
        l(169, 476, "SALSICCE"), l(561, 476, "18,00"), l(672, 476, "8,00 KG"), l(845, 476, "10,00"), l(959, 476, "144,00"),
        l(166, 513, "COSTINE"), l(574, 513, "3,10"), l(679, 513, "6,50 KG"), l(845, 516, "10,00"), l(969, 516, "20,15"),
        l(460, 571, "RIEPILOGHI IVA E TOTALI"),
        l(79, 590, "esigibilità iva / riferimenti normativi"), l(370, 592, "%IVA"), l(472, 592, "Spese accessorie"), l(629, 592, "Arr."),
        l(738, 588, "Totale imponibile"), l(888, 588, "Totale imposta"),
        l(71, 616, "I (esigibilità immediata)"), l(421, 613, "10,00"), l(808, 619, "1.669,15"), l(959, 619, "166,92"),
        l(72, 650, "Importo bollo"), l(260, 647, "Sconto/Maggiorazione"), l(629, 651, "Arr."), l(735, 650, "Totale documento"),
        l(944, 677, "1.836,07"),
    )

    @Test fun readsTheWholeInvoice() {

        val d = ReceiptParser.parsePages(listOf(page), ParseOptions(ownVatNumber = "09876543217", today = LocalDate.of(2026, 10, 9)))
        assertEquals("MACELLERIA ROSSI DI ROSSI MARIO", d.sellerName?.value)
        assertEquals("45", d.documentNumber?.value)
        assertEquals(LocalDate.of(2026, 7, 31), d.documentDate?.value)
        assertEquals(166915L, d.subtotalCents?.value)
        assertEquals(16692L, d.vatCents?.value)
        assertEquals(183607L, d.totalCents?.value)
        assertEquals(listOf(150500L, 14400L, 2015L), d.lineItems.map { it.lineTotalCents?.value })
        assertEquals(listOf("120.40", "18.00", "3.10").map(::BigDecimal), d.lineItems.map { it.quantity?.value?.setScale(2) })
        assertEquals(listOf("12.50", "8.00", "6.50").map(::BigDecimal), d.lineItems.map { it.unitPrice?.value?.setScale(2) })
        assertTrue(d.lineItems.all { it.unit?.value == "kg" })
        assertTrue(d.lineItems[0].originalDescription.contains("AGNELLO"))
        assertTrue(d.warnings.toString(), d.warnings.isEmpty())
    }

    @Test fun theCustomersBoxIsNeverTheSupplier() {
        val s = Parties.supplier(listOf(LayoutRows.layout(page)), "09876543217")!!
        assertEquals("01234567897", s.vatNumber)
        assertTrue(s.name!!.value.startsWith("MACELLERIA"))
    }
}
