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
        assertEquals(13115L, preciseSum(d.items.map { it.preciseTotal() }))
        assertTrue(AutoAccept.sumMatches(d))
        assertTrue(AutoAccept.moneyProven(d))
        // The amount typed again by the operator (another value): the exact one no longer counts.
        val edited = d.items[0].copy(lineTotal = DraftField("33,00"))
        assertEquals(BigDecimal("33.00"), edited.preciseTotal())
    }

    /** A piece of a PDF's own text at its place on the page (words spread over its width). */
    private fun w(text: String, l: Int, top: Int, r: Int): OcrLine {
        val parts = text.split(' ')
        if (parts.size == 1) return OcrLine(text, l, top, r, top + 22)
        val cw = (r - l).toDouble() / text.length
        var at = l.toDouble()
        return OcrLine(text, l, top, r, top + 22, words = parts.map { p -> OcrLine(p, at.toInt(), top, (at + p.length * cw).toInt(), top + 22).also { at += (p.length + 1) * cw } })
    }

    @Test fun quantityAndPriceUnderOneHeadingAreSplitByTheAmount() {
        // The heading band in three rows as the PDF's text groups it: "PREZZO" above and "UNITARIO" below the row
        // of the other labels, so no price column is read. Quantity and price then both stand under QUANTITÀ: the
        // left one is no part of the name, and quantity x price = amount says which is which (not their decimals).
        val rows = listOf(
            listOf(w("PREZZO", 1542, 1253, 1627), w("SCONTO", 1841, 1253, 1930)),
            listOf(w("COD. ARTICOLO", 259, 1266, 425), w("DESCRIZIONE", 888, 1266, 1030), w("QUANTITÀ", 1332, 1266, 1440), w("UM", 1733, 1266, 1766),
                w("%IVA", 2002, 1266, 2056), w("PREZZO TOTALE", 2115, 1266, 2288)),
            listOf(w("UNITARIO", 1534, 1278, 1635), w("O MAGG.", 1839, 1278, 1932)),
            listOf(w("99990001", 84, 1360, 212), w("GASOLIO PROVA", 643, 1360, 903), w("38,4172615 1,52468193", 1284, 1360, 1670), w("LT", 1734, 1360, 1764),
                w("22,00", 1985, 1360, 2072), w("58,57410441", 2164, 1360, 2366)),
        )
        val item = TableReader.read(listOf(LayoutRows.Layout(rows, 0.0)), textLayerPages = setOf(0))!!.single()
        assertEquals("GASOLIO PROVA", item.originalDescription)
        assertEquals(0, item.quantity!!.value.compareTo(BigDecimal("38.4172615")))
        assertEquals(0, item.unitPrice!!.value.compareTo(BigDecimal("1.52468193")))
        assertEquals(5857L, item.lineTotalCents!!.value)
        assertEquals(Confidence.HIGH, item.quantity!!.confidence)
    }

    @Test fun aRoundingRowIsPartOfTheTotal() {
        // "Arrotondamento 1,00 -0,063 -0,06" among the lines: its amount counts, its quantity and price only repeat it.
        val inv = Invoice(
            supplierVat = pIva("0789123456"), supplierName = "BUONI PROVA S.R.L.", number = "417", date = "29-12-2030",
            lines = listOf(
                Line("BUONI PROVA", "200,00", "6,148", "NR", "22,00", "1.229,60", code = "BUONI"),
                Line("Arrotondamento", "1,00", "-0,063", "NR", "22,00", "-0,06", code = "ARR"),
            ),
            groups = listOf(Group("22,00", "1.229,54", "270,50")),
            total = "1.500,04",
        )
        val d = draft(inv)
        assertEquals(listOf(122960L, -6L), d.items.map { ItalianNumbers.parseCents(it.lineTotal.text) })
        assertTrue(d.items[1].isCharge)
        assertTrue(AutoAccept.moneyProven(d))
    }

    @Test fun aNegativePriceKeepsItsMinus() {
        // "Coupon 1,00 -20,00 -20,00": quantity x price is the negative amount only with the minus.
        val inv = Invoice(
            supplierVat = pIva("0891234567"), supplierName = "ARREDI PROVA S.P.A.", number = "310", date = "12-05-2031",
            lines = listOf(
                Line("TAVOLO PROVA", "2,00", "50,00", "PZ", "22,00", "100,00"),
                Line("Coupon PROVA", "1,00", "-20,00", null, "22,00", "-20,00"),
            ),
            groups = listOf(Group("22,00", "80,00", "17,60")),
            total = "97,60",
        )
        val d = draft(inv)
        assertEquals(0, ItalianNumbers.parse(d.items[1].unitPrice.text)!!.compareTo(BigDecimal("-20.00")))
        assertTrue(AutoAccept.moneyProven(d))
    }

    @Test fun discountsPrintedOneUnderTheOtherApplyInTurn() {
        // "-10.00%" on the item's row, "-10.00%" and "-4.00%" under it in the same column, a note between them:
        // 3 x 7,40 = 22,20 x 0,90 x 0,90 x 0,96 = 17,26. Together they are one discount, applied in turn.
        val rows = listOf(
            listOf(w("CODICE", 84, 990, 200), w("DESCRIZIONE", 643, 990, 900), w("QUANTITÀ", 1284, 990, 1400), w("PREZZO", 1550, 990, 1670),
                w("SCONTO", 1813, 990, 1943), w("IVA", 2000, 990, 2072), w("IMPORTO", 2250, 990, 2366)),
            listOf(w("10001", 84, 1050, 200), w("MANDORLE PROVA KG 1", 643, 1050, 1000), w("3,00", 1330, 1050, 1400), w("7,40", 1602, 1050, 1670),
                w("-10.00%", 1813, 1050, 1943), w("10,00", 1985, 1050, 2072), w("17,26", 2279, 1050, 2366)),
            listOf(w("Tipo dato: LOTTI", 643, 1092, 900), w("-10.00%", 1813, 1092, 1943)),
            listOf(w("scadenze)", 643, 1126, 777)),
            listOf(w("Lotto: 99001", 643, 1161, 804), w("-4.00%", 1833, 1161, 1943)),
            listOf(w("10002", 84, 1212, 200), w("NOCI PROVA KG 1", 643, 1212, 950), w("1,00", 1330, 1212, 1400), w("6,60", 1602, 1212, 1670),
                w("-5% -5%", 1813, 1212, 1943), w("4,00", 2004, 1212, 2072), w("5,96", 2298, 1212, 2366)),
        )
        val items = TableReader.read(listOf(LayoutRows.Layout(rows, 0.0)), textLayerPages = setOf(0))!!
        assertEquals(listOf("10+10+4", "5+5"), items.map { it.discount?.value })
        assertEquals(listOf(1726L, 596L), items.map { it.lineTotalCents?.value })
        assertTrue(items.all { it.quantity?.confidence == Confidence.HIGH && ParseWarning.LINE_TOTAL_MISMATCH !in it.warnings })
    }

    @Test fun aPricedRowWithItsVatIsALineAndAPaymentRowIsNot() {
        // "Vendita" says no item, but a row with its price, its amount and a nature code in the VAT column adds into the
        // VAT summary: it is a line. A payment row ("MP05 Bonifico ... Data 1.016,67") is the amount due, never a line.
        val inv = Invoice(
            supplierVat = pIva("0912345678"), supplierName = "TELEFONIA PROVA S.P.A.", number = "7X0001", date = "11-03-2031",
            lines = listOf(
                Line("Contributo attivazione PROVA", null, "2,00", null, "22,00", "2,00", code = null),
                Line("Vendita rateale apparati PROVA", null, "41,30", null, "N1", "41,30", code = null),
            ),
            groups = listOf(Group("N1", "41,30", "0"), Group("22,00", "2,00", "0,44")),
            total = "43,74",
        )
        assertEquals(listOf(200L, 4130L), read(inv).lineItems.map { it.lineTotalCents?.value })
        val text = "PANE PROVA 2 1,50 3,00\nMP05 Bonifico Beneficiario PROVA Data 3,00\nTOTALE DOCUMENTO 3,00"
        assertEquals(listOf(300L), ReceiptParser.parse(text).lineItems.map { it.lineTotalCents?.value })
    }

    @Test fun highPrecisionAmountsAreRoundedOncePerRate() {
        // 1,00400 at 22 % and 1,00400 at 10 %: each rate's taxable is 1,00 (rounded once per rate), the document's 2,00.
        // Added up across the rates before rounding they would give 2,01: never the printed taxable.
        val inv = Invoice(
            supplierVat = pIva("0912345671"), supplierName = "DISCOUNT PROVA S.P.A.", number = "L9/101", date = "12-04-2031",
            lines = listOf(
                Line("DETERSIVO PROVA", "1,00", "1,00400", "PZ", "22,00", "1,00400"),
                Line("PANE PROVA", "1,00", "1,00400", "PZ", "10,00", "1,00400"),
            ),
            groups = listOf(Group("22,00", "1,00", "0,22"), Group("10,00", "1,00", "0,10")),
            total = "2,32",
        )
        val d = draft(inv)
        assertEquals(200L, d.linesTotal())
        assertTrue(AutoAccept.sumMatches(d))
        assertTrue(AutoAccept.moneyProven(d))
    }

    @Test fun theTotalPrintedAtTheTopOfTheNextPageIsRead() {
        // "IMPORTO BOLLO ... TOTALE DOCUMENTO" ends the page; its value is the first amount of the next page, under the
        // summary's heading printed again there.
        val page1 = listOf(
            listOf(w("ESIGIBILITÀ IVA", 240, 3049, 674), w("%IVA", 890, 3049, 944), w("TOTALE IMPONIBILE", 1493, 3049, 1702), w("TOTALE IMPOSTA", 2032, 3049, 2216)),
            listOf(w("I (esigibilità immediata)", 84, 3087, 707), w("10,00", 873, 3087, 960), w("80,00", 1733, 3087, 1840), w("8,00", 2279, 3087, 2366)),
            listOf(w("IMPORTO BOLLO", 238, 3262, 413), w("SCONTO/MAGGIORAZIONE", 779, 3262, 1054), w("ARR.", 1264, 3262, 1315), w("TOTALE DOCUMENTO", 1748, 3262, 1973)),
        )
        val page2 = listOf(
            listOf(w("ESIGIBILITÀ IVA", 240, 177, 674), w("%IVA", 890, 177, 944), w("TOTALE IMPONIBILE", 1493, 177, 1702), w("TOTALE IMPOSTA", 2032, 177, 2216)),
            listOf(w("88,00", 2260, 214, 2366)),
            listOf(w("MODALITÀ PAGAMENTO", 318, 335, 568), w("IMPORTO", 1958, 335, 2055)),
        )
        val grid = HeaderGrid.read(listOf(LayoutRows.Layout(page1, 0.0), LayoutRows.Layout(page2, 0.0)))
        assertEquals(8800L, grid.total)
        // On one page alone nothing is taken for the total.
        assertEquals(null, HeaderGrid.read(listOf(LayoutRows.Layout(page1, 0.0))).total)
    }

    @Test fun aUtilityBillsLinesAreProvenByTheirVatRates() {
        // A meter reading with no price printed (only its amount), a price glued to its unit ("0,0410KWH"), and two
        // N2.2 lines adding up to the N2.2 row: each rate's lines are its taxable amount, so every amount is proven.
        val inv = Invoice(
            supplierVat = pIva("0345678912"), supplierName = "ENERGIA PROVA SRL", number = "000920310000099999", date = "18-03-2031",
            lines = listOf(
                Line("POD/PDR: IT001E00000009", "1.200,000", "0,1000", "KWH", "22,00", "120,00", code = "11223399", codeType = null),
                Line("POD/PDR: IT001E00000009", "30,000", null, "KWH", "22,00", "7,50", code = "11223399", codeType = null),
                Line("POD/PDR: IT001E00000009", "100,000", "0,0410KWH", null, "22,00", "4,10", code = "11223399", codeType = null),
                Line("POD/PDR: IT001E00000009", "1,00", "-0,50", "CAD", "N2.2", "-0,50", code = "11223399", codeType = null),
                Line("POD/PDR: IT001E00000009", "1,00", "0,40", "CAD", "N2.2", "0,40", code = "11223399", codeType = null),
            ),
            groups = listOf(Group("22,00", "131,60", "28,95"), Group("N2.2", "-0,10", "0")),
            total = "160,45",
            payment = "MP09 RID",
        )
        val p = read(inv)
        assertEquals(true, p.reconciled?.vatProven)
        assertEquals(0, p.lineItems[2].unitPrice?.value?.compareTo(BigDecimal("0.0410")))
        val d = draft(inv)
        assertEquals(emptyList<ReviewReason>(), AutoAccept.reasons(d).toList())
        // The quantity of the line with no price stays as printed.
        assertEquals(0, ItalianNumbers.parse(d.items[1].quantity.text)!!.compareTo(BigDecimal("30")))
    }

    @Test fun theTextReadingTakesNoSummaryRowAndNoQuantityAloneAsALine() {
        // Read by text: a VAT summary row and a note printing only "1,00 Pz" are no lines.
        val text = """
            FORNITORE PROVA S.R.L.
            TARTUFO PROVA 2,00 40,00 Kg 5,00 80,00
            NOTE-Reclami entro 8 giorni 1,00 Pz
            I (esigibilità immediata) 5,00 80,00 4,00
            TOTALE DOCUMENTO 84,00
        """.trimIndent()
        val d = ReceiptParser.parse(text)
        assertEquals("text", d.itemsReadBy)
        assertEquals(listOf(8000L), d.lineItems.map { it.lineTotalCents?.value })
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
        // The rounding printed in the summary (ARR.) is no line, but part of its row's taxable amount: the lines at 22%
        // (31,20) with it (-0,05) are the two 22% rows (31,15). No line is made up for it, and the invoice is proven.
        assertEquals(3, p.lineItems.size)
        assertEquals(-5L, p.summaryExtraCents)
        assertTrue(p.vatChecks.isNotEmpty() && p.vatChecks.all { it.ok })
        val d = AutoAccept.settleProven(DocumentDraft.fromParsed(p))
        assertTrue(AutoAccept.sumMatches(d))
        assertTrue(AutoAccept.moneyProven(d))
    }

    @Test fun theCassaPrevidenzialeCountsInItsRatesTaxable() {
        // DATI CASSA PREVIDENZIALE: base 300,00, 4 % = 12,00 at 22 %. The lines are 300,00; the 22 % row prints 312,00.
        val text = """
            RIEPILOGHI IVA E TOTALI
            ESIGIBILITÀ IVA / RIFERIMENTI NORMATIVI %IVA SPESE ACCESSORIE ARR. TOTALE IMPONIBILE TOTALE IMPOSTA
            I (esigibilità immediata) 22,00 312,00 68,64
            DATI CASSA PREVIDENZIALE IMPONIBILE %CONTR. RITENUTA %IVA IMPORTO
            TC22 (Cassa di prova) 300,00 4,00 22,00 12,00
        """.trimIndent()
        val g = VatSummary.parse(text).single()
        assertEquals(31200L, g.taxableCents)
        assertEquals(1200L, g.extraCents)
        // A contribution whose base x percentage is not its amount is not taken.
        assertEquals(0L, VatSummary.parse(text.replace("4,00 22,00 12,00", "4,00 22,00 13,00")).single().extraCents)
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
