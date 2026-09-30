package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Layout of a real cash & carry invoice photographed page by page (content invented): column headings printed on
 * three lines ("COLLI ... TIPO TOT. PREZZO IMPORTO COD" / "CODICE" / "N.xPz ... CONF. PZ/KG UNIT. EUR IVA"),
 * quantities the OCR missed, "Lx4" for 1x4, "111,4104" (amount and VAT code glued), "753" (comma lost),
 * a group's boilerplate line that ends in "S.p.A.", the name cut to "ABC S.r" on page 1, the number misread on
 * page 1, and the total to pay alone on a later page.
 */
class CashCarryLayoutTest {

    /** One OCR line from words placed at x positions (about 11 px per character). */
    private fun row(y: Int, vararg cells: Pair<Int, String>): OcrLine {
        val words = cells.map { (x, t) -> OcrLine(t, x, y, x + 11 * t.length, y + 18) }
        return OcrLine(words.joinToString(" ") { it.text }, words.minOf { it.left }, y, words.maxOf { it.right }, y + 18, 0f, words)
    }

    private fun letterhead(page: Int, name: String, number: String): List<OcrLine> = listOf(
        row(20, 146 to "ABC"),
        row(60, 113 to name),
        row(95, 132 to "Società", 220 to "soggetta", 320 to "all'attività", 460 to "di", 490 to "direzione", 600 to "e",
            620 to "coordinamento", 780 to "di", 810 to "Gruppo", 890 to "Esempio", 990 to "S.p.A."),
        row(130, 130 to "Sede", 190 to "legale", 270 to "00100", 340 to "ROMA", 400 to "(RM)", 460 to "-", 480 to "VIA", 530 to "ESEMPIO,", 630 to "1"),
        row(165, 131 to "Reg.", 185 to "Imp.", 240 to "RM,", 290 to "C.F.", 345 to "-", 365 to "P.IVA", 440 to "01234567897", 580 to "-", 600 to "REA", 650 to "n.", 680 to "123456/RM"),
        row(200, 140 to "EMITTENTE:", 270 to "C+C", 320 to "Esempio", 420 to "-", 440 to "Via", 490 to "delle", 560 to "Prove", 630 to "40", 670 to "Roma"),
        row(240, 110 to "DESTINAZIONE", 260 to "MERCE", 720 to "SPETTABILE"),
        row(270, 720 to "RISTORANTE", 850 to "PROVA", 930 to "SAS", 980 to "DI", 1010 to "ROSSI", 1080 to "MARIO", 1150 to "&"),
        row(300, 720 to "CODICE", 800 to "975000", 1000 to "PARTITA", 1090 to "IVA", 1170 to "09876543217"),
        row(340, 120 to "TIPO", 175 to "DOCUMENTO", 420 to "N.RO", 475 to "DOCUMENTO", 645 to "DATA", 700 to "DOCUMENTO", 915 to "CONDIZIONI", 1040 to "DI", 1070 to "PAGAMENTO", 1320 to "PAG."),
        row(370, 120 to "COPIA", 190 to "FATTURA", 470 to number, 670 to "30/09/2026", 960 to "RIMESSA", 1060 to "DIRETTA", 1330 to "$page/3"),
    )

    private val page1 = letterhead(1, "ABC S.r", "3SB/12345") + listOf(
        row(420, 215 to "COLLI", 450 to "DESCRIZIONE", 590 to "BENI", 920 to "TIPO", 1010 to "TOT.", 1105 to "PREZZO", 1240 to "IMPORTO", 1350 to "COD"),
        row(445, 100 to "CODICE"),
        row(470, 215 to "N.xPz", 445 to "(", 455 to "Natura", 530 to "e", 545 to "qualità", 630 to ")", 905 to "CONF.", 995 to "PZ/KG", 1105 to "UNIT.", 1175 to "€", 1290 to "EUR", 1355 to "IVA"),
        row(510, 100 to "1000011", 225 to "1x10", 285 to "LATTE", 350 to "INTERO", 430 to "LT.1", 880 to "BT", 920 to "LT", 955 to "1", 1060 to "10", 1130 to "1,050", 1290 to "10,50", 1380 to "04"),
        row(540, 100 to "1000022", 240 to "1x1", 285 to "UOVA", 340 to "MEDIE", 880 to "VA", 920 to "KG", 955 to "11.3", 1130 to "40,900", 1290 to "40,90", 1380 to "10"),
        row(570, 100 to "1000033", 255 to "1", 285 to "COPPA", 350 to "SUINO", 420 to "S/0ssO", 885 to "CS", 925 to "KG", 1040 to "2,88", 1130 to "7,820", 1290 to "22,52", 1380 to "10"),
        row(600, 100 to "1000033", 255 to "1", 285 to "COPPA", 350 to "SUINO", 420 to "S/OSSO", 885 to "CS", 925 to "KG", 1040 to "3,24", 1130 to "7,820", 1290 to "25,34", 1380 to "10"),
        row(630, 100 to "1000044", 225 to "Lx4", 285 to "LIMONI", 360 to "RETE", 880 to "NC", 920 to "GR", 955 to "750", 1060 to "4", 1130 to "1,390", 1300 to "5,56", 1380 to "04"),
        row(660, 100 to "1000055", 255 to "1", 285 to "PECORINO", 390 to "STAGIONATO", 880 to "NC", 920 to "KG", 1040 to "5,87", 1120 to "18,980", 1280 to "111,4104"),
        row(690, 100 to "1000066", 255 to "1", 285 to "INSALATA", 390 to "GENTILINA", 880 to "NC", 920 to "KG", 1040 to "2,52", 1130 to "2,990", 1300 to "753", 1380 to "04"),
        row(720, 100 to "1000077", 225 to "1x2", 285 to "PASTA", 350 to "PENNE", 880 to "CL", 920 to "GR", 955 to "250", 1130 to "1,490", 1300 to "2,98", 1380 to "04"),
        row(760, 100 to "TIPO", 150 to "RIGA", 200 to ":", 215 to "O", 230 to "-", 245 to "OFFERTE"),
        row(800, 240 to "IMPONIBILI", 500 to "IVA", 660 to "IMPORTO", 760 to "IVA", 880 to "DESCRIZIONE", 1010 to "COD", 1060 to "IVA"),
        row(830, 320 to "137,98", 510 to "04", 760 to "5,52", 880 to "ALIQUOTA", 990 to "4%"),
        row(860, 330 to "88,76", 510 to "10", 760 to "8,88", 880 to "ALIQUOTA", 990 to "10%"),
        row(900, 185 to "TOTALE", 260 to "IMPONIBILI", 730 to "226,74"),
        row(930, 185 to "TOTALE", 260 to "IVA", 740 to "14,40"),
        row(960, 185 to "TOTALE", 260 to "DOCUMENTO", 730 to "241,14"),
        row(1000, 1230 to "SEGUE", 1300 to ">>>"),
    )
    private val page2 = letterhead(2, "ABC S.r.l.", "38B/12345") + listOf(
        row(420, 190 to "TOTALE", 270 to "DA", 300 to "PAGARE", 680 to "241,14"),
        row(450, 190 to "TOTALE", 270 to "PAGATO", 680 to "241,14", 820 to "TOTALE", 900 to "COLLI", 1060 to "12"),
    )
    private val page3 = letterhead(3, "ABC S.r.l.", "38B/12345") + listOf(
        row(420, 260 to "SALDO", 330 to "PUNTI", 400 to "INIZIALE:", 610 to "2423"),
    )

    private val d = ReceiptParser.parsePages(listOf(page1, page2, page3), ParseOptions(ownVatNumber = "09876543217"))
    private fun item(prefix: String) = d.lineItems.first { it.originalDescription.startsWith(prefix) }
    private fun eq(e: String, a: BigDecimal?) = assertTrue("expected $e but was $a", a != null && a.compareTo(BigDecimal(e)) == 0)

    @Test fun header() {
        assertEquals("ABC S.r.l.", d.sellerName?.value) // not the group's "... S.p.A." line, not the cut "ABC S.r"
        assertEquals("38B/12345", d.documentNumber?.value) // two pages agree against a blurred page 1
        assertEquals(Confidence.HIGH, d.documentNumber?.confidence)
        assertEquals(LocalDate.of(2026, 9, 30), d.documentDate?.value)
        assertEquals(24114L, d.totalCents?.value)
        assertEquals(22674L, d.subtotalCents?.value) // from the VAT summary on page 1, the total to pay on page 2
        assertEquals(1440L, d.vatCents?.value)
    }

    @Test fun everyLineWithCodeNameAndUnit() {
        assertEquals(8, d.lineItems.size)
        assertEquals(listOf("1000011", "1000022", "1000033", "1000033", "1000044", "1000055", "1000066", "1000077"), d.lineItems.map { it.itemCode })
        assertEquals(listOf("LATTE", "UOVA", "COPPA", "COPPA", "LIMONI", "PECORINO", "INSALATA", "PASTA"), d.lineItems.map { it.originalDescription.substringBefore(' ') })
        // "TIPO / CONF." + "TOT. PZ/KG": bottles and packs are pieces, "CS KG" / "NC KG" are kilos.
        assertEquals(listOf("pz", "pz", "kg", "kg", "pz", "kg", "kg", "pz"), d.lineItems.map { it.unit?.value })
        assertEquals("1x10", item("LATTE").packages?.value)
        assertEquals("1x4", item("LIMONI").packages?.value) // "Lx4"
        assertTrue(item("LATTE").originalDescription.contains("LT.1"))
        assertFalse(item("LATTE").originalDescription.endsWith("LT 1")) // the size is not repeated
    }

    @Test fun packSizeKeptApartFromTheCount() {
        // "BT LT 1 · 10": ten 1-litre bottles; "NC GR 750 · 4": four 750 g packs. The count and price stay as printed.
        assertEquals("1 l", item("LATTE").packSize?.value)
        assertEquals("750 g", item("LIMONI").packSize?.value)
        assertEquals("250 g", item("PASTA").packSize?.value)
        assertNull(item("COPPA").packSize) // weighed: "CS KG 2,88" is 2,88 kg
        val draft = DocumentDraft.fromParsed(d).items.first { it.description.text.startsWith("LIMONI") }
        val (amount, unit) = draft.packTotal()!!
        eq("3", amount) // 4 x 750 g
        assertEquals("kg", unit)
    }

    @Test fun averagesPerKiloFromPackSize() {
        // 4 packs of 750 g for 5,56 and 2 packs of 750 g for 2,90: 8,46 / 4,5 kg = 1,88 per kg.
        val records = listOf(
            PurchaseRecord(1, 1, null, "ABC", BigDecimal(4), "pz", null, 556, VatBasis.EXCLUSIVE, null),
            PurchaseRecord(2, 2, null, "ABC", BigDecimal(2), "pz", null, 290, VatBasis.EXCLUSIVE, null),
        )
        val avg = CostCalculator.summarize(records, listOf(UnitConversion("pz", "g", BigDecimal(750)))).averages.single()
        assertEquals("kg", avg.unit)
        eq("1.88", avg.averageUnitCost)
    }

    @Test fun ocrSlipsRepairedWhenTheArithmeticProvesThem() {
        val pecorino = item("PECORINO") // "111,4104"
        assertEquals(11141L, pecorino.lineTotalCents?.value)
        eq("4", pecorino.vatRatePercent?.value)
        assertEquals(753L, item("INSALATA").lineTotalCents?.value) // "753"
        // Quantities the OCR missed: amount / price, proven by every VAT group adding up.
        eq("1", item("UOVA").quantity?.value)
        eq("2", item("PASTA").quantity?.value)
        assertTrue(d.lineItems.none { ParseWarning.LINE_TOTAL_MISMATCH in it.warnings })
    }

    @Test fun vatGroupsAndNames() {
        assertEquals(2, d.vatChecks.size)
        assertTrue(d.vatChecks.all { it.ok })
        assertTrue(ReceiptParser.isConfident(d))
        assertEquals(22674L, d.lineItems.sumOf { it.lineTotalCents!!.value })
        // Same article code, same name: "S/0ssO" is the same COPPA as "S/OSSO".
        assertEquals(1, d.lineItems.filter { it.itemCode == "1000033" }.map { it.originalDescription }.distinct().size)
        assertEquals("COPPA SUINO S/OSSO", item("COPPA").originalDescription)
        // No lots on this document: an empty lot is normal.
        assertFalse(d.lotsPrinted)
        assertNull(item("LATTE").lotNumber)
        // Nothing left for the AI: every line and VAT group adds up.
        assertTrue(AiTargets.plan(listOf(page1, page2, page3), d).orEmpty().none { it is AiTarget.Row || it is AiTarget.Number })
    }

    @Test fun aiDoubleChecksEvenWhenEverythingAddsUp() {
        val pages = listOf(page1, page2, page3)
        val t = AiTargets.plan(pages, d, spotCheck = true)!!
        assertTrue(t.any { it is AiTarget.Header && it.verify })
        assertTrue(t.any { it is AiTarget.Totals && it.verify })
        val numbers = t.filterIsInstance<AiTarget.Number>()
        assertTrue(numbers.size in 3..8)
        // Quantities worked out by arithmetic are checked first, then the largest amounts.
        assertEquals(AiTarget.Field.QUANTITY, numbers[0].field)
        assertTrue(numbers.any { it.field == AiTarget.Field.AMOUNT && it.expected.compareTo(BigDecimal("111.41")) == 0 })
        // Same targets when the reading is rebuilt after a restart (the answers are paired with them in order).
        assertEquals(t, AiTargets.plan(pages, d, spotCheck = true))

        // The AI agrees with everything except one amount: that value is highlighted with both readings.
        val text = pages.joinToString("\n") { p -> p.joinToString("\n") { it.text } }
        val answers = t.map { target ->
            target to when (target) {
                is AiTarget.Header -> "{\"seller\":\"ABC S.r.l.\",\"seller_vat\":\"01234567897\",\"number\":\"38B/12345\",\"date\":\"30/09/2026\"}"
                is AiTarget.Totals -> "{\"subtotal\":\"226,74\",\"vat\":\"14,40\",\"total\":\"241,14\"}"
                is AiTarget.Number -> if (target.field == AiTarget.Field.AMOUNT && target.expected.compareTo(BigDecimal("111.41")) == 0) "111,47"
                    else ItalianNumbers.formatDecimal(target.expected, maxScale = 3)
                else -> ""
            }
        }
        val checked = AiReader.applyTargets(d, answers, text, ParseOptions(ownVatNumber = "09876543217"))
        val check = checked.aiCheck!!
        assertEquals(1, check.disagreements.size)
        assertTrue(check.disagreements.single().contains("111,47"))
        assertEquals(Confidence.LOW, checked.lineItems.first { it.originalDescription.startsWith("PECORINO") }.lineTotalCents?.confidence)
        assertEquals(Confidence.HIGH, checked.sellerName?.confidence)
        assertEquals(Confidence.HIGH, checked.totalCents?.confidence)
        eq("1", checked.lineItems.first { it.originalDescription.startsWith("UOVA") }.quantity?.value)
        assertEquals(Confidence.HIGH, checked.lineItems.first { it.originalDescription.startsWith("UOVA") }.quantity?.confidence)
        // The arithmetic does not clear what the AI disputed.
        val draft = AutoAccept.settleProven(DocumentDraft.fromParsed(checked))
        assertTrue(draft.items.first { it.description.text.startsWith("PECORINO") }.lineTotal.uncertain)
    }

    @Test fun vatGroupThatDoesNotAddUpIsNamed() {
        val wrong = d.copy(lineItems = d.lineItems.map { if (it.originalDescription.startsWith("COPPA")) it.copy(lineTotalCents = Extracted(100L, Confidence.HIGH, "")) else it })
        val checks = VatSummary.check(wrong.lineItems, VatSummary.parse(page1.joinToString("\n") { it.text }))
        val ten = checks.single { it.ratePercent.compareTo(BigDecimal(10)) == 0 }
        assertFalse(ten.ok)
        assertTrue(checks.single { it.ratePercent.compareTo(BigDecimal(4)) == 0 }.ok)
    }

    @Test fun headingSynonymsAndMisreads() {
        // "DESCRIZTONE", "QUANTTTA", "TVA" (misread IVA), "PREZZ0": still headings.
        val h = TableReader.header(
            listOf(row(10, 10 to "CODICE", 150 to "DESCRIZTONE", 500 to "QUANTTTA", 620 to "PREZZ0", 740 to "IMPORTO", 860 to "TVA")),
        )
        assertEquals(
            listOf(TableReader.Kind.CODE, TableReader.Kind.DESCRIPTION, TableReader.Kind.QUANTITY, TableReader.Kind.PRICE, TableReader.Kind.AMOUNT, TableReader.Kind.VAT),
            h!!.columns.map { it.kind },
        )
        // Synonyms: "ART.", "PRODOTTO", "Q.TA'", "COSTO", "VALORE", "ALIQ.", "BATCH".
        val s = TableReader.header(
            listOf(row(10, 10 to "ART.", 150 to "PRODOTTO", 500 to "Q.TA'", 620 to "COSTO", 740 to "VALORE", 860 to "ALIQ.", 960 to "BATCH")),
        )
        assertEquals(
            listOf(TableReader.Kind.CODE, TableReader.Kind.DESCRIPTION, TableReader.Kind.QUANTITY, TableReader.Kind.PRICE, TableReader.Kind.AMOUNT, TableReader.Kind.VAT, TableReader.Kind.LOT),
            s!!.columns.map { it.kind },
        )
    }

    @Test fun nameCleanup() {
        assertEquals("NATURBOSCO", DescriptionCleanup.clean("NATURBOSCo"))
        assertEquals("S/OSSO", DescriptionCleanup.clean("S/0ssO"))
        assertEquals("PREZZEM.MAZZI G500", DescriptionCleanup.clean("-PREZZEM.MAZZI G500"))
        assertEquals("ARO.SALVIA G200", DescriptionCleanup.clean("ARO.SALVIA G200")) // sizes untouched
        assertEquals("Mozzarella di bufala", DescriptionCleanup.clean("Mozzarella di bufala")) // mixed case kept
    }
}
