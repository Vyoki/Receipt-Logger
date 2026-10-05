package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Supplier documents in other languages and formats, as found in public datasets (content invented): the words for
 * total, taxable amount, VAT, number and date in English, French, German, Dutch and Spanish; dates in their formats;
 * thousands written with a space; and the rule that nothing is sure without independent confirmation.
 */
class MultilingualTest {

    private fun read(text: String, today: LocalDate? = null) = ReceiptParser.parse(text.trimIndent(), ParseOptions(today = today))

    @Test fun datesInOtherLanguagesAndOrders() {
        fun d(s: String) = ItalianDates.findDates(s).firstOrNull()?.date
        assertEquals(LocalDate.of(2022, 9, 8), d("Invoice date: September 8, 2022"))
        assertEquals(LocalDate.of(2022, 9, 8), d("Rechnungsdatum 8. September 2022"))
        assertEquals(LocalDate.of(2022, 9, 8), d("Date 08-Sep-22"))
        assertEquals(LocalDate.of(2022, 9, 8), d("le 8 sept. 2022"))
        assertEquals(LocalDate.of(2022, 9, 8), d("2022/09/08"))
        assertEquals(LocalDate.of(2022, 8, 25), d("08/25/2022")) // month first only when nothing else fits
        assertEquals(LocalDate.of(2022, 5, 8), d("08/05/2022")) // otherwise day first, always
        assertNull(d("Time 12.31.00")) // a time, not 31 December 2000
        assertNull(d("1 SET 12 PZ")) // Italian "set" on a product line is no date
    }

    @Test fun englishInvoice() {
        val d = read(
            """
            GREEN LEAF FOODS LTD
            VAT Reg No GB123456789
            TAX INVOICE
            Invoice No: INV-2041
            Invoice Date: 03/04/2026
            Order Date: 28/03/2026
            Bill To: RISTORANTE PROVA SAS
            Olive oil 5 l 2 pcs 21.50 43.00
            Tomatoes 3 kg 2.10 6.30
            Subtotal 49.30
            VAT 20% 9.86
            Total Amount Due 59.16
            """,
        )
        assertEquals("GREEN LEAF FOODS LTD", d.sellerName?.value)
        assertEquals("INV-2041", d.documentNumber?.value)
        assertEquals(LocalDate.of(2026, 4, 3), d.documentDate?.value) // not the order date
        assertEquals(4930L, d.subtotalCents?.value)
        assertEquals(986L, d.vatCents?.value)
        assertEquals(5916L, d.totalCents?.value)
        assertEquals(Confidence.HIGH, d.totalCents?.confidence) // taxable + VAT = total
    }

    @Test fun frenchInvoiceWithSpacedThousands() {
        val d = read(
            """
            Facture FA02/2026/000123
            Date 02/03/2026
            Description Quantité Prix Taxes Sous total
            Service traiteur 64,00 Heures 190,00 TVA 20% 12 160,00 €
            Tables 35,00 Unités 1700,00 TVA 20% 59 500,00 €
            Montant HT 71 660,00 €
            Taxes 14 332,00 €
            Montant TTC 85 992,00 €
            """,
        )
        assertEquals(listOf(1216000L, 5950000L), d.lineItems.map { it.lineTotalCents?.value }) // 64 x 190 = 12 160
        assertEquals(7166000L, d.subtotalCents?.value)
        assertEquals(1433200L, d.vatCents?.value)
        assertEquals(8599200L, d.totalCents?.value)
        assertEquals(Confidence.HIGH, d.totalCents?.confidence)
    }

    @Test fun dutchSubtotalWithVatAndExclusive() {
        val d = read(
            """
            Factuurnummer: 993548
            Factuurdatum 19-04-2026
            Subtotaal € 717,97
            Exclusief BTW € 593,36
            BTW 21% € 124,61
            Totaal € 717,97
            """,
        )
        assertEquals(59336L, d.subtotalCents?.value) // the one that makes taxable + VAT = total
        assertEquals(71797L, d.totalCents?.value)
    }

    @Test fun aLabelAloneNeverMakesATotalSure() {
        // "TOTAL" with nothing that confirms it (no taxable + VAT, lines that do not add up, no payment line).
        val d = read(
            """
            CAFE ESEMPIO
            Date 12/03/2026
            ESPRESSO 2 1.20 2.40
            Total 80.91
            """,
        )
        assertEquals(8091L, d.totalCents?.value)
        assertEquals(Confidence.LOW, d.totalCents?.confidence)
        // Confirmed by the payment: cash given minus change.
        val paid = read(
            """
            CAFE ESEMPIO
            Date 12/03/2026
            ESPRESSO 2 1.20 2.40
            CROISSANT 1 1.50 1.50
            Total 3.90
            Cash 10.00
            Change 6.10
            """,
        )
        assertEquals(Confidence.HIGH, paid.totalCents?.confidence)
    }

    @Test fun aTotalWorkedOutFromTaxableAndVatIsNotSure() {
        val d = read(
            """
            Facture 2026-77
            Montant HT 100,00
            Taxes 20,00
            """,
        )
        assertEquals(12000L, d.totalCents?.value)
        assertEquals(Confidence.LOW, d.totalCents?.confidence)
    }

    @Test fun orderReferenceIsNotTheInvoiceNumber() {
        val d = read(
            """
            Facture : N°BC#-BC03984
            Facture n° FA2026-00412
            Date 05/02/2026
            """,
        )
        assertEquals("FA2026-00412", d.documentNumber?.value)
    }

    @Test fun impossibleDatesAreChecked() {
        val text = """
            ABC S.r.l.
            Fattura n. 12 del 30/04/2078
            """
        val d = read(text, today = LocalDate.of(2026, 10, 5))
        assertEquals(Confidence.LOW, d.documentDate?.confidence) // "2078": the camera misread 2018 or 2026
    }

    @Test fun foreignLegalFormsAndCustomerBlock() {
        val d = read(
            """
            ATTN: TRATTORIA PROVA LTD
            VERDE FRESCO GMBH
            Rechnungsnummer 2026-118
            Rechnungsdatum 4. März 2026
            Summe netto 100,00
            MwSt 19% 19,00
            Gesamtbetrag 119,00
            """,
        )
        assertEquals("VERDE FRESCO GMBH", d.sellerName?.value)
        assertEquals("2026-118", d.documentNumber?.value)
        assertEquals(LocalDate.of(2026, 3, 4), d.documentDate?.value)
        assertEquals(11900L, d.totalCents?.value)
        assertTrue(d.totalCents?.confidence == Confidence.HIGH)
    }
}
