package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The document checks itself (content invented; the failures are those of a real cash & carry invoice):
 * - one row's amount smudged ("8,21" read "g,21"), another with a speck after the amount ("8,21."): both lines kept;
 * - another sheet's amount column showing at the edge of the photo, glued to a column heading: not a line;
 * - the Pkgs column read into the name ("112" for 1x2, "Ixi" for 1x1): Pkgs, not name;
 * - a VAT code with a letter for a digit ("D4");
 * - the VAT summary, the packages total and the page count prove the result.
 */
class ReconcileTest {

    private val page1 = """
        ABC S.r.l.
        VIA ESEMPIO, 1 00100 ROMA (RM)
        P.IVA 01234567897
        TIPO DOCUMENTO N.RO DOCUMENTO DATA DOCUMENTO CONDIZIONI DI PAGAMENTO PAG.
        COPIA FATTURA 12A/34567 14/10/2026 RIMESSA DIRETTA 1/2
        COLLI DESCRIZIONE BENI TIPO TOT. PREZZO IMPORTO COD
        CODICE
        N.xPz ( Natura e qualità ) CONF. PZ/KG UNIT. € EUR IVA
        1000011 1x10 LATTE INTERO LT.1 BT LT 1 10 1,100 11,00 04
        1000022 1x2 BISCOTTI FROLLINI GR.250 AS GR 250 2 1,300 2,60 10
        O 1000033 1 PECORINO GRATTUGIATO KG 0,50 16,420d Sxf g,21 04
        O 1000033 1 PECORINO GRATTUGIATO KG 0,50 16,420 8,21. 04
        1000044 112 ERBA CIPOLLINA G30 GR 30 2 0,950 1,90 04
        1000055 Ixi DETERSIVO PIATTI ML.750 FL ML 750 1 4,800 4,80 22
        SEGUE >>>
    """.trimIndent()

    private val page2 = """
        ABC S.r.l.
        TIPO DOCUMENTO N.RO DOCUMENTO DATA DOCUMENTO CONDIZIONI DI PAGAMENTO PAG.
        COPIA FATTURA 12A/34567 14/10/2026 RIMESSA DIRETTA 2/2
        DESCRIZIONE BENI TIPO TOT. PREZZO IMPORTO COD
        EUR IVA CODICE N.XPZ
        (Natura e qualità ) CONF. PZ/KG UNIT. € EUR IVA
        11,00 04
        1000066 1x6 ACQUA MINERALE LT1 PZ 1 6 0,300 1,80 22
        2,60 10
        1000077 1x1 CAFFE MACINATO GR 250 1 3,890 3,89 10
        1000088 1x1 LATTE PARZ. SCREMATO ML 500 1 0,850 0,85 D4
        IMPONIBILI IVA IMPORTO IVA DESCRIZIONE COD IVA
        30,17 04 1,21 ALIQUOTA 4%
        6,49 10 0,65 ALIQUOTA 10%
        6,60 22 1,45 ALIQUOTA 22%
        TOTALE IMPONIBILI 43,26
        TOTALE IVA 3,31
        TOTALE DOCUMENTO 46,57
        TOTALE COLLI 9
    """.trimIndent()

    private fun read(vararg pages: String) = ReceiptParser.parse(pages.joinToString("\n${ReceiptParser.PAGE_BREAK}\n"))

    @Test fun everyLineIsFoundAndTheTotalsProveIt() {
        val d = read(page1, page2)
        val names = d.lineItems.map { it.originalDescription }
        assertEquals(names.toString(), 9, d.lineItems.size)
        assertEquals(2, names.count { it.startsWith("PECORINO") })
        assertTrue(d.lineItems.all { (it.lineTotalCents?.value ?: 0) > 0 && it.quantity != null })
        val r = d.reconciled!!
        assertTrue(r.vatProven)
        assertEquals(1, r.recovered.size)
        assertTrue(r.recovered.single().contains("8,21"))
        assertEquals(1, r.dropped.size)
        assertTrue(r.dropped.single().endsWith("11,00"))
        assertTrue(d.vatChecks.all { it.ok && it.differenceCents == 0L })
        assertFalse(ParseWarning.VAT_GROUP_MISMATCH in d.warnings)
    }

    @Test fun theRecoveredLineIsWhereItWasPrinted() {
        val names = read(page1, page2).lineItems.map { it.originalDescription }
        assertTrue(names[1].startsWith("BISCOTTI"))
        assertTrue(names[2].startsWith("PECORINO") && names[3].startsWith("PECORINO"))
    }

    @Test fun packagesAreNotLinesAndAddUp() {
        val d = read(page1, page2)
        val herbs = d.lineItems.single { it.originalDescription.startsWith("ERBA") }
        assertEquals("1x2", herbs.packages?.value)
        val soap = d.lineItems.single { it.originalDescription.startsWith("DETERSIVO") }
        assertEquals("1x1", soap.packages?.value)
        val r = d.reconciled!!
        assertEquals(9, r.packagesPrinted)
        assertEquals(9, r.packagesCounted)
        assertTrue(r.packagesAgree)
    }

    @Test fun aVatCodeWithALetterForADigit() {
        val milk = read(page1, page2).lineItems.single { it.originalDescription.startsWith("LATTE PARZ") }
        assertEquals(0, BigDecimal(4).compareTo(milk.vatRatePercent?.value))
    }

    @Test fun allPagesPhotographed() {
        val r = read(page1, page2).reconciled!!
        assertEquals(2, r.pagesOf)
        assertTrue(r.pagesMissing.isEmpty())
    }

    @Test fun aMissingPageIsSaidWhenTheSumsCannotProveNothingIsMissing() {
        val d = read(page2)
        assertEquals(listOf(1), d.reconciled!!.pagesMissing)
        assertFalse(d.reconciled!!.vatProven)
        assertTrue(ParseWarning.PAGES_MISSING in d.warnings)
    }

    @Test fun twoEqualAnswersChangeNothing() {
        // Either "extra" line could be the one that is no product: nothing is decided, the gap stays to be checked.
        fun line(name: String, cents: Long, rate: Int, qty: Boolean) = ParsedLineItem(
            name, if (qty) Extracted(BigDecimal.ONE, Confidence.HIGH, name) else null, null,
            if (qty) Extracted(BigDecimal(cents).movePointLeft(2), Confidence.HIGH, name) else null,
            Extracted(cents, Confidence.HIGH, name), Extracted(BigDecimal(rate), Confidence.HIGH, name), null, null,
        )
        val items = listOf(
            line("PANE", 1000, 4, true), line("LATTE", 300, 4, true), line("UOVA", 200, 4, true), line("BURRO", 400, 4, true),
            line("FARINA", 100, 4, true), line("RISO", 250, 4, true), line("IMPORTO", 500, 4, false), line("EUR IVA", 500, 4, false),
        )
        val groups = listOf(VatSummary.Group(BigDecimal(4), 2750, 110, "27,50 04 1,10"))
        val (out, report) = Reconcile.apply(items, groups, "")
        assertEquals(items, out)
        assertFalse(report.changed)
        assertFalse(report.vatProven)
    }

    @Test fun aTillReceiptLineIsNeverDropped() {
        // Every line an amount alone: one equal to the difference is a product all the same.
        fun line(name: String, cents: Long) = ParsedLineItem(
            name, null, null, null, Extracted(cents, Confidence.HIGH, name), Extracted(BigDecimal(10), Confidence.HIGH, name), null, null,
        )
        val items = listOf(line("PANE", 250), line("CAFFE", 600), line("ACQUA", 100))
        val groups = listOf(VatSummary.Group(BigDecimal(10), 350, 35, "3,50 10 0,35"))
        val (out, report) = Reconcile.apply(items, groups, "")
        assertEquals(items, out)
        assertFalse(report.changed)
    }

    @Test fun oneLineThatIsNoProductIsLeftOut() {
        fun line(name: String, cents: Long, qty: Boolean) = ParsedLineItem(
            name, if (qty) Extracted(BigDecimal.ONE, Confidence.HIGH, name) else null, null,
            if (qty) Extracted(BigDecimal(cents).movePointLeft(2), Confidence.HIGH, name) else null,
            Extracted(cents, Confidence.HIGH, name), Extracted(BigDecimal(4), Confidence.HIGH, name), null, null,
        )
        val items = listOf(line("PANE", 1000, true), line("LATTE", 300, true), line("UOVA", 200, true), line("IMPORTO EUR IVA", 1040, false))
        val groups = listOf(VatSummary.Group(BigDecimal(4), 1500, 60, "15,00 04 0,60"))
        val (out, report) = Reconcile.apply(items, groups, "")
        assertEquals(3, out.size)
        assertEquals(listOf("IMPORTO EUR IVA  10,40"), report.dropped)
        assertTrue(report.vatProven)
    }

    @Test fun anUnreadableRateIsTheOneThatClosesTheSums() {
        fun line(name: String, cents: Long, rate: Int?) = ParsedLineItem(
            name, Extracted(BigDecimal.ONE, Confidence.HIGH, name), null, Extracted(BigDecimal(cents).movePointLeft(2), Confidence.HIGH, name),
            Extracted(cents, Confidence.HIGH, name), rate?.let { Extracted(BigDecimal(it), Confidence.HIGH, name) }, null, null,
        )
        val items = listOf(line("PANE", 1000, 4), line("VINO", 2000, 22), line("LATTE", 80, null))
        val groups = listOf(
            VatSummary.Group(BigDecimal(4), 1080, 43, "10,80 04 0,43"),
            VatSummary.Group(BigDecimal(22), 2000, 440, "20,00 22 4,40"),
        )
        val (out, report) = Reconcile.apply(items, groups, "")
        assertEquals(0, BigDecimal(4).compareTo(out[2].vatRatePercent?.value))
        assertEquals(1, report.ratesFound)
        assertTrue(report.vatProven)
    }

    @Test fun aRowWithAnUnreadableAmountAndNoReadableCentsIsNotALine() {
        assertNull(Reconcile.wholeLine("1000033 1 PECORINO GRATTUGIATO KG 0,50 16,420 xx 04", colliColumn = true))
        // The cents readable but not those of quantity x price: not this line.
        assertNull(Reconcile.wholeLine("1000033 1 PECORINO GRATTUGIATO KG 0,50 16,420 g,20 04", colliColumn = true))
        assertNotNull(Reconcile.wholeLine("1000033 1 PECORINO GRATTUGIATO KG 0,50 16,420 g,21 04", colliColumn = true))
    }

    @Test fun pageMarks() {
        val text = "TIPO DOCUMENTO PAG.\nFATTURA 1 14/10/2026 1/3\n\u000C\nTIPO DOCUMENTO PAG.\nFATTURA 1 14/10/2026 3/3"
        assertEquals(3 to listOf(2), Reconcile.pageMarks(text))
        assertEquals(4 to listOf(1, 3), Reconcile.pageMarks("Pagina 2 di 4\nPagina 4 di 4"))
        assertNull(Reconcile.pageMarks("CONT. GN1/4 H150 1 6,10"))
    }

    @Test fun aPackagesColumnHeadingIsNoTotal() {
        val text = "COD.ART. N.COLLI DESCRIZIONE ARTICOLO U.M. Q.TA PR.UNITARIO VALORE IVA\nKG 7,418 2,61 19,36 04\n100011 1 LIMONI"
        assertNull(PackagesCheck.printedTotal(text))
        assertEquals(12, PackagesCheck.printedTotal("TOTALE COLLI 12"))
    }

    // ------------------------------------------------------------ found by review (invented data)

    private fun proven(name: String, cents: Long, rate: Int = 22) = ParsedLineItem(
        name, Extracted(BigDecimal.ONE, Confidence.HIGH, name), null, Extracted(BigDecimal(cents).movePointLeft(2), Confidence.HIGH, name),
        Extracted(cents, Confidence.HIGH, name), Extracted(BigDecimal(rate), Confidence.HIGH, name), null, null,
    )
    private fun amountOnly(name: String, cents: Long, rate: Int = 22) = ParsedLineItem(
        name, null, null, null, Extracted(cents, Confidence.HIGH, name), Extracted(BigDecimal(rate), Confidence.HIGH, name), null, null,
    )

    @Test fun aDiscountOrChargeIsNeverLeftOut() {
        val lines = listOf(proven("ACQUA", 1000), proven("BIRRA", 2000), proven("VINO", 3000), proven("COLA", 1200))
        val groups = listOf(VatSummary.Group(BigDecimal(22), 7200 - 900, 1386, "63,00 22 13,86"))
        for (adj in listOf(amountOnly("SCONTO PROMOZIONALE", -900), amountOnly("CONTRIBUTO TRASPORTO", 900))) {
            val g = if (adj.lineTotalCents!!.value > 0) listOf(VatSummary.Group(BigDecimal(22), 7200, 1584, "72,00 22 15,84")) else groups
            val (out, report) = Reconcile.apply(lines + adj, g, "")
            assertEquals(5, out.size)
            assertTrue(report.dropped.isEmpty())
        }
    }

    @Test fun aProductPrintedWithItsAmountAloneIsNeverLeftOut() {
        val items = listOf(proven("ACQUA", 1000), proven("BIRRA", 2000), proven("VINO", 3000), proven("COLA", 900), proven("COLA", 900), amountOnly("GHIACCIO ALIMENTARE", 900))
        val groups = listOf(VatSummary.Group(BigDecimal(22), 7800, 1716, "78,00 22 17,16"))
        val (out, report) = Reconcile.apply(items, groups, "")
        assertEquals(items, out)
        assertFalse(report.changed)
    }

    @Test fun whatTheCheckerChangedGoesToReview() {
        val d = read(page1, page2)
        assertTrue(ParseWarning.CHECKED_BY_TOTALS in d.warnings)
    }

    @Test fun paymentTermsAndTraysAreNoPageNumbers() {
        assertNull(Reconcile.pageMarks("COND. PAG.\nRIBA 30/60\nVASSOIO GN 1/4"))
        assertNull(Reconcile.pageMarks("DATA DOC. NUMERO PAG.\n14/10/2026 12/26"))
        assertNull(Reconcile.pageMarks("Pag. 15/30 gg d.f."))
        assertNull(Reconcile.pageMarks("P. 1/2 KG"))
        // A stray mark and a real one disagree: nothing is said.
        assertNull(Reconcile.pageMarks("TIPO DOCUMENTO PAG.\n01/10\nFATTURA 1 1/2"))
        assertEquals(2 to listOf(1), Reconcile.pageMarks("TIPO DOCUMENTO PAG.\nFATTURA 1 14/10/2026 2/2"))
    }

    @Test fun aLineRecoveredForTheAiGoesWhereItWasPrinted() {
        // The AI's lines carry no OCR row as their source: they are found on the page by amount and name.
        fun ai(name: String, q: String, p: String, cents: Long) = ParsedLineItem(
            name, Extracted(BigDecimal(q), Confidence.HIGH, "AI: $name"), null, Extracted(BigDecimal(p), Confidence.HIGH, "AI: $name"),
            Extracted(cents, Confidence.HIGH, "AI: $name"), Extracted(BigDecimal(4), Confidence.HIGH, "AI: $name"), null, null,
        )
        val text = """
            1000011 LATTE INTERO LT 1 10 1,100 11,00 04
            1000022 BURRO KG 1 2 3,000 6,00 04
            1000033 PECORINO GRATTUGIATO KG 0,50 16,420 8,21 04
            1000044 FARINA 00 KG 1 5 0,800 4,00 04
        """.trimIndent()
        val items = listOf(ai("Latte intero", "10", "1.1", 1100), ai("Burro", "2", "3", 600), ai("Farina 00", "5", "0.8", 400))
        val groups = listOf(VatSummary.Group(BigDecimal(4), 2921, 117, "29,21 04 1,17"))
        val (out, report) = Reconcile.apply(items, groups, text)
        assertEquals(1, report.recovered.size)
        assertEquals(listOf("Latte intero", "Burro", "PECORINO GRATTUGIATO", "Farina 00"), out.map { it.originalDescription })
    }

    @Test fun aNoteEndingWithAnAmountIsNoLine() {
        assertNull(ReceiptParser.parseItemLine("Consegna gratuita per ordini superiori a 150,00.", colliColumn = false))
        assertNull(ReceiptParser.parseItemLine("ACQUA MINERALE NATURALE PET 1,50.", colliColumn = false))
        assertEquals(821L, ReceiptParser.parseItemLine("PECORINO GRATTUGIATO KG 0,50 16,420 8,21.", colliColumn = false)?.lineTotalCents?.value)
        assertEquals(821L, ReceiptParser.parseItemLine("PECORINO GRATTUGIATO KG 0,50 16,420 8,21. 10%", colliColumn = false)?.lineTotalCents?.value)
        assertEquals(821L, ReceiptParser.parseItemLine("PECORINO GRATTUGIATO KG 0,50 16,420 8,21. B", colliColumn = false)?.lineTotalCents?.value)
    }

    @Test fun aPackagesTotalNextToTheQuantityTotal() {
        assertEquals(12, PackagesCheck.printedTotal("TOTALE COLLI 12 TOTALE QUANTITA' 48"))
        assertEquals(12, PackagesCheck.printedTotal("N. COLLI 12 Q.TA TOTALE 48"))
        assertEquals(12, PackagesCheck.printedTotal("TOTALE COLLI TOTALE QUANTITA'\n12 48"))
        // Column headings, whatever digits they carry ("SC.1"), are no total; nor is the line below them.
        assertNull(PackagesCheck.printedTotal("COD.ART. N.COLLI DESCRIZIONE BENI U.M. QUANTITA' PREZZO UNIT. SC.1 SC.2 IMPORTO % IVA\n19966 7 PANE NR 10 1,50 15,00 04"))
        assertNull(PackagesCheck.printedTotal("CODICE N.COLLI DESCR. U.M. Q.TA PREZZO IMPORTO\n123 2 LATTE LT 1 1,00 1,00 04"))
    }

    @Test fun aNumberStartingANameStaysInTheNameWithoutTableEvidence() {
        val it = ReceiptParser.parseItemLine("1000099 111 MINUTI DI SOLE 1 3,00 3,00 22", colliColumn = true)!!
        assertEquals("111 MINUTI DI SOLE", it.originalDescription)
        assertNull(it.packages)
        // In a table that prints its Pkgs as A x B, "112" is 1x2 when 1 x 2 is the quantity, shown for a check.
        val herbs = read(page1, page2).lineItems.single { it.originalDescription.startsWith("ERBA") }
        assertEquals(Confidence.LOW, herbs.packages?.confidence)
    }

    @Test fun afterAnEditTheProofGoesButTheListStays() {
        val d = DocumentDraft.fromParsed(read(page1, page2))
        assertTrue(d.checks!!.vatProven)
        val edited = d.copy(items = d.items.mapIndexed { i, it -> if (i == 0) it.copy(lineTotal = it.lineTotal.copy(text = "12,00")) else it })
            .checksStillFor(d)
        assertFalse(edited.checks!!.vatProven)
        assertNull(edited.checks!!.packagesPrinted)
        assertEquals(1, edited.checks!!.recovered.size)
        assertEquals(1, edited.checks!!.dropped.size)
    }

    @Test fun pkgsMisreadInAColumnReading() {
        // Read by columns: the Pkgs column value as read, the row text as the source.
        fun line(code: String, pkgs: String?, name: String, qty: Int, row: String) = ParsedLineItem(
            name, Extracted(BigDecimal(qty), Confidence.HIGH, row), null, Extracted(BigDecimal.ONE, Confidence.HIGH, row),
            Extracted(qty * 100L, Confidence.HIGH, row), Extracted(BigDecimal(4), Confidence.HIGH, row), null, null,
            itemCode = code, packages = pkgs?.let { Extracted(it, Confidence.HIGH, row) },
        )
        val items = listOf(
            line("1000011", "1x6", "ACQUA", 6, "1000011 1x6 ACQUA 6 1,00 6,00 04"),
            line("1000022", "1x24", "BIRRA", 24, "1000022 1x24 BIRRA 24 1,00 24,00 04"),
            line("1000033", "1x2", "BURRO", 2, "1000033 1x2 BURRO 2 1,00 2,00 04"),
            line("1000044", "112", "KIWI GREEN", 2, "1000044 112 KIWI GREEN 2 1,00 2,00 04"),
            line("1000055", null, "BISCOTTI FROLLINI", 14, "1000055 x14 BISCOTTI FROLLINI 14 1,00 14,00 04"),
            line("1000066", null, "ix2 ESTRATTO VANIGLIA", 2, "1000066 ix2 ESTRATTO VANIGLIA 2 1,00 2,00 04"),
        )
        val out = ReceiptParser.colliFromNames(items, "CODICE COLLI DESCRIZIONE")
        assertEquals("1x2", out[3].packages?.value); assertEquals(Confidence.LOW, out[3].packages?.confidence)
        assertEquals("1x14", out[4].packages?.value); assertEquals(Confidence.LOW, out[4].packages?.confidence)
        assertEquals("1x2", out[5].packages?.value); assertEquals("ESTRATTO VANIGLIA", out[5].originalDescription)
        assertEquals(1 + 1 + 1 + 1 + 1 + 1, Reconcile.countPackages(out))
        // Without A x B Pkgs in the table nothing is worked out.
        assertEquals(items.drop(3), ReceiptParser.colliFromNames(items.drop(3), "CODICE COLLI DESCRIZIONE").let { o -> o.take(2) + items.drop(5) })
    }

    @Test fun aRecoveredLineStaysForReviewOnEveryLaterReading() {
        // The AI loop finishes the reading again after each answer: what the check added must still be said.
        val d = read(page1, page2)
        val again = ReceiptParser.finish(d, listOf(page1, page2).joinToString("\n${ReceiptParser.PAGE_BREAK}\n"))
        assertEquals(d.reconciled!!.recovered, again.reconciled!!.recovered)
        assertEquals(d.reconciled!!.dropped, again.reconciled!!.dropped)
        assertTrue(ParseWarning.CHECKED_BY_TOTALS in again.warnings)
    }

    @Test fun aRealLineWithTheSameAmountDoesNotUndoADrop() {
        val earlier = Reconcile.Report(dropped = listOf("IMPORTO EUR IVA  5,00"), droppedKeys = listOf(500L to BigDecimal(4)), droppedAfter = listOf(1))
        val items = listOf(proven("PANE", 1000, 4), amountOnly("GHIACCIO ALIMENTARE", 500, 4))
        val carried = Reconcile.carry(earlier, Reconcile.Report(), items)
        assertEquals(1, carried.dropped.size)
        // The left-out reading came back as a line: no longer said to be left out.
        val back = Reconcile.carry(earlier, Reconcile.Report(), items + amountOnly("IMPORTO EUR IVA", 500, 4))
        assertTrue(back.dropped.isEmpty())
    }

    @Test fun aPageNumberInWordsCountsOnItsOwn() {
        assertEquals(6 to listOf(1, 2, 4, 5, 6), Reconcile.pageMarks("Pagina 3 di 6"))
    }
}
