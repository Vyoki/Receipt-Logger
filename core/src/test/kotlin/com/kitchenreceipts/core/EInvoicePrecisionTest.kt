package com.kitchenreceipts.core

import com.kitchenreceipts.core.EInvoicePrintoutFixture.CUSTOMER_VAT
import com.kitchenreceipts.core.EInvoicePrintoutFixture.Group
import com.kitchenreceipts.core.EInvoicePrintoutFixture.Invoice
import com.kitchenreceipts.core.EInvoicePrintoutFixture.Line
import com.kitchenreceipts.core.EInvoicePrintoutFixture.pIva
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The e-invoice printout read from the PDF's own text as printed (invented invoices): amounts with up to 8 decimals
 * added up exactly, the columns where the text puts them, a row's own numbers before the words of its name, every row
 * of the VAT summary, a discount printed signed, the header read under its staggered labels.
 */
class EInvoicePrecisionTest {

    private val options = ParseOptions(ownVatNumber = CUSTOMER_VAT, today = LocalDate.of(2031, 5, 2))

    private fun read(inv: Invoice): ParsedDocument {
        val pages = EInvoicePrintoutFixture.pages(inv)
        return ReceiptParser.parsePages(pages, options.copy(textLayerPages = pages.indices.toSet()))
    }

    private fun draft(inv: Invoice) = AutoAccept.settleProven(DocumentDraft.fromParsed(read(inv)))

    private val fuel = Invoice(
        supplierVat = pIva("0456789123"), supplierName = "STAZIONE PROVA S.R.L.", number = "0004321/ZZ/2031", date = "27-03-2031",
        lines = listOf(
            Line("DIESEL PROVA", "19,41747573", "1,68852459", "LT", "22,00", "32,78688525", code = "99990001"),
            // Quantity and price printed so close that the text runs them together.
            Line("DIESEL PROVA", null, "21,857923501,50000000", "LT", "22,00", "32,78688525", code = "99990001"),
            Line("BENZINA SENZA PIOMBO", "19,41747573", "1,68852459", "LT", "22,00", "32,78688525", code = "99990002"),
            Line("BENZINA SENZA PIOMBO", "19,41747573", "1,68852459", "LT", "22,00", "32,78688525", code = "99990002"),
        ),
        // 4 x 32,78688525 = 131,147541: the taxable amount is their sum rounded once (each rounded: 131,16).
        groups = listOf(Group("22,00", "131,15", "28,85")),
        total = "160,00",
    )

    @Test fun amountsWithEightDecimalsAddUpAsPrinted() {
        val d = draft(fuel)
        assertEquals(4, d.items.size)
        assertEquals(listOf(3279L, 3279L, 3279L, 3279L), d.items.map { ItalianNumbers.parseCents(it.lineTotal.text) })
        assertEquals(BigDecimal("32.78688525"), d.items[0].exactTotal)
        // The run-together quantity and price, split where quantity x price is the amount.
        assertEquals(0, ItalianNumbers.parse(d.items[1].quantity.text)!!.compareTo(BigDecimal("21.8579235")))
        assertEquals(0, ItalianNumbers.parse(d.items[1].unitPrice.text)!!.compareTo(BigDecimal("1.5")))
        assertEquals(13115L, Money.linesSum(d.items))
        assertTrue(AutoAccept.sumMatches(d))
        assertTrue(AutoAccept.moneyProven(d))
        // The amount typed again by the operator (another value): the exact one no longer counts.
        val edited = d.items[0].copy(lineTotal = DraftField("33,00"))
        assertEquals(BigDecimal("33.00"), edited.preciseTotal())
    }

    @Test fun quantityAndPriceUnderOneHeadingAreSplitByTheAmount() {
        // The heading band in three rows as the PDF's text groups it: "PREZZO" above and "UNITARIO" below the row
        // of the other labels, so no price column is read. Quantity and price then both stand under QUANTITÀ: the
        // left one is no part of the name, and quantity x price = amount says which is which (not their decimals).
        fun w(text: String, l: Int, top: Int, r: Int): OcrLine {
            val parts = text.split(' ')
            if (parts.size == 1) return OcrLine(text, l, top, r, top + 22)
            val cw = (r - l).toDouble() / text.length
            var at = l.toDouble()
            return OcrLine(text, l, top, r, top + 22, words = parts.map { p -> OcrLine(p, at.toInt(), top, (at + p.length * cw).toInt(), top + 22).also { at += (p.length + 1) * cw } })
        }
        val rows = listOf(
            listOf(w("PREZZO", 1542, 1253, 1627), w("SCONTO", 1841, 1253, 1930)),
            listOf(w("COD. ARTICOLO", 259, 1266, 425), w("DESCRIZIONE", 888, 1266, 1030), w("QUANTITÀ", 1332, 1266, 1440), w("UM", 1733, 1266, 1766),
                w("%IVA", 2002, 1266, 2056), w("PREZZO TOTALE", 2115, 1266, 2288)),
            listOf(w("UNITARIO", 1534, 1278, 1635), w("O MAGG.", 1839, 1278, 1932)),
            listOf(w("99990001", 84, 1360, 212), w("GASOLIO PROVA", 643, 1360, 903), w("46,7345716 1,36803279", 1284, 1360, 1670), w("LT", 1734, 1360, 1764),
                w("22,00", 1985, 1360, 2072), w("63,93442623", 2164, 1360, 2366)),
        )
        val item = TableReader.read(listOf(LayoutRows.Layout(rows, 0.0)), textLayerPages = setOf(0))!!.single()
        assertEquals("GASOLIO PROVA", item.originalDescription)
        assertEquals(0, item.quantity!!.value.compareTo(BigDecimal("46.7345716")))
        assertEquals(0, item.unitPrice!!.value.compareTo(BigDecimal("1.36803279")))
        assertEquals(6393L, item.lineTotalCents!!.value)
        assertEquals(Confidence.HIGH, item.quantity!!.confidence)
    }

    @Test fun aRowsOwnNumbersBeatTheWordsOfItsName() {
        // "VETTORE" (carrier) and "POS" (card terminal) are words of rows that are no item; here they are names.
        val inv = Invoice(
            supplierVat = pIva("0567891234"), supplierName = "CASEIFICIO PROVA S.R.L.", number = "88/A", date = "03-02-2031",
            lines = listOf(
                Line("CACIOTTA MONTE VETTORE", "2,00", "5,00", "KG", "4,00", "10,00"),
                Line("CANONE POS ANNUO", "1,00", "9,90", null, "22,00", "9,90"),
            ),
            groups = listOf(Group("4,00", "10,00", "0,40"), Group("22,00", "9,90", "2,18")),
            total = "22,48",
        )
        val d = draft(inv)
        assertEquals(listOf("CACIOTTA MONTE VETTORE", "CANONE POS ANNUO"), d.items.map { it.description.text })
        assertTrue(AutoAccept.sumMatches(d))
    }

    @Test fun aDiscountPrintedSignedIsADiscount() {
        val inv = Invoice(
            supplierVat = pIva("0678912345"), supplierName = "CANTINA PROVA S.R.L.", number = "12", date = "05-02-2031",
            // 3 x 21,40 = 64,20, less 2% = 62,92; 3 x 18,90 = 56,70, less 2% = 55,57; 5 x 12,30 = 61,50, less 2% = 60,27.
            // Printed as the stylesheet does: the price, the unit and the signed discount one after the other.
            lines = listOf(
                Line("ROSSO DI PROVA DOC", "3,00", "21,40 bottiglie -2.00%", null, "22,00", "62,92", code = "PRV0001", priceAt = 1583),
                Line("ROSATO DI PROVA DOC", "3,00", "18,90 bottiglie -2.00%", null, "22,00", "55,57", code = "PRV0002", priceAt = 1583),
                Line("RISERVA DI PROVA DOCG", "5,00", "12,30 bottiglie -2.00%", null, "22,00", "60,27", code = "PRV0003", priceAt = 1583),
            ),
            groups = listOf(Group("22,00", "178,76", "39,33")),
            total = "218,09",
        )
        val d = draft(inv)
        assertEquals(listOf("2", "2", "2"), d.items.map { it.discount.text })
        assertTrue(AutoAccept.moneyProven(d))
    }

    @Test fun everySummaryRowCounts() {
        val inv = Invoice(
            supplierVat = pIva("0789123456"), supplierName = "AZIENDA AGRICOLA PROVA", number = "23", date = "31-03-2031",
            lines = listOf(
                Line("VINO DA TAVOLA ROSSO", "20,00", "1,45", "KG", "22,00", "29,00"),
                Line("DAMIGIANA VUOTA", "4,00", "0,55", "PZ", "22,00", "2,20"),
                Line("SEMOLA DI GRANO DURO", "3,00", "2,10", "PZ", "4,00", "6,30"),
            ),
            // Two rows at 22% (another legal reference), the first rounded by -0,05 under ARR.; one row at 4%.
            groups = listOf(
                Group("22,00", "21,15", "4,65", label = "Esigib. non dich. (si presume immediata)", extra = "-0,05"),
                Group("22,00", "10,00", "2,20", label = "Esigib. non dich. (si presume immediata)"),
                Group("4,00", "6,30", "0,25", label = "Esigib. non dich. (si presume immediata)"),
            ),
            total = "44,55",
        )
        val p = read(inv)
        // The summary's rows add up: taxable 37,45, VAT 7,10, total 44,55.
        assertEquals(3745L, p.subtotalCents?.value)
        assertEquals(Confidence.HIGH, p.subtotalCents?.confidence)
        assertEquals(710L, p.vatCents?.value)
        // The rounding printed in the summary (ARR.) is no line: the lines (31,20 at 22%) stay as printed, so the
        // invoice waits for a check instead of being saved with a line that is not printed.
        assertEquals(3, p.lineItems.size)
        assertTrue(!AutoAccept.sumMatches(AutoAccept.settleProven(DocumentDraft.fromParsed(p))))
    }

    @Test fun aDateShapedNumberUnderItsHeadingIsTheNumber() {
        val inv = Invoice(
            supplierVat = pIva("0891234567"), supplierName = "LEGUMI PROVA S.R.L.", number = "03/4/2031", date = "04-03-2031",
            lines = listOf(Line("LENTICCHIE SECCHE", "10,00", "2,75", "KG", "4,00", "27,50")),
            groups = listOf(Group("4,00", "27,50", "1,10")),
            total = "28,60",
        )
        val p = read(inv)
        assertEquals("03/4/2031", p.documentNumber?.value)
        assertEquals(LocalDate.of(2031, 3, 4), p.documentDate?.value)
    }

    @Test fun theDateUnderItsStaggeredLabelsBeatsADateInALine() {
        // The header's labels printed over three lines ("ART." and "DATA" a little above, "73" and "DOCUMENTO" a little
        // below): the date is read under "DATA DOCUMENTO", the number under "NUMERO DOCUMENTO". (On a full page the
        // labels can make four rows of the page; they are read as well.)
        // Word boxes as the PDF's own text gives them (the labels' glyph widths, not a fixed character width).
        fun box(text: String, l: Int, t: Int, r: Int, b: Int): OcrLine {
            val parts = text.split(' ')
            val cw = (r - l).toDouble() / text.length
            var at = l.toDouble()
            val words = parts.map { w -> OcrLine(w, at.toInt(), t, (at + w.length * cw).toInt(), b).also { at += (w.length + 1) * cw } }
            return OcrLine(text, l, t, r, b, words = if (words.size > 1) words else emptyList())
        }
        val page = listOf(
            box("ART.", 759, 786, 808, 807), box("DATA", 1583, 786, 1642, 807),
            box("TIPOLOGIA DOCUMENTO", 268, 798, 524, 820), box("NUMERO DOCUMENTO", 1054, 798, 1288, 820), box("CODICE PER RICEVERE", 1932, 798, 2174, 820),
            box("73", 771, 810, 795, 832), box("DOCUMENTO", 1544, 810, 1681, 832),
            box("TD01 fattura", 84, 857, 276, 891), box("77/B", 1126, 857, 1216, 891), box("14-03-2031", 1526, 857, 1703, 891), box("ABC1234", 1979, 857, 2128, 891),
        )
        val grid = HeaderGrid.read(listOf(LayoutRows.layout(page)))
        assertEquals(LocalDate.of(2031, 3, 14), grid.date)
        assertEquals("77/B", grid.number)
    }
}
