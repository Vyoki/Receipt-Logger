package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cash & carry invoice photographed by hand (content invented), with what a real one showed:
 * - page 1 photographed twice (the last photo is page 1 again): read once, and said;
 * - the bottom of page 1 tilted: each numbers row read above its name row, with the produce note ("Prov ITALIA Cat II
 *   Cal") in between: the names go with the right numbers, and are checked;
 * - lots printed at the end of the names ("ROASTBEEF ... AB500168"), once misread ("AB5O0168"): offered as the lot;
 * - one amount read a cent off ("18,75" for 18,76) on a document whose other lines are rounded: highlighted;
 * - the document number misread on page 1 ("12A/34s67") and read right on page 2.
 */
class CashCarryRepeatsTest {

    private fun row(y: Int, vararg cells: Pair<Int, String>): OcrLine {
        val words = cells.map { (x, t) -> OcrLine(t, x, y, x + 11 * t.length, y + 18) }
        return OcrLine(words.joinToString(" ") { it.text }, words.minOf { it.left }, y, words.maxOf { it.right }, y + 18, 0f, words)
    }

    private fun letterhead(page: Int, number: String): List<OcrLine> = listOf(
        row(20, 146 to "ABC"),
        row(60, 113 to "ABC", 160 to "S.r.l."),
        row(130, 130 to "Sede", 190 to "legale", 270 to "00100", 340 to "ROMA", 400 to "(RM)", 460 to "-", 480 to "VIA", 530 to "ESEMPIO,", 630 to "1"),
        row(165, 131 to "Reg.", 185 to "Imp.", 240 to "RM,", 290 to "C.F.", 345 to "-", 365 to "P.IVA", 440 to "01234567897", 580 to "-", 600 to "REA", 650 to "n.", 680 to "123456/RM"),
        row(240, 110 to "DESTINAZIONE", 260 to "MERCE", 720 to "SPETTABILE"),
        row(270, 720 to "RISTORANTE", 850 to "PROVA", 930 to "SAS"),
        row(300, 720 to "CODICE", 800 to "975000", 1000 to "PARTITA", 1090 to "IVA", 1170 to "09876543217"),
        row(340, 120 to "TIPO", 175 to "DOCUMENTO", 420 to "N.RO", 475 to "DOCUMENTO", 645 to "DATA", 700 to "DOCUMENTO", 915 to "CONDIZIONI", 1040 to "DI", 1070 to "PAGAMENTO", 1320 to "PAG."),
        row(370, 120 to "COPIA", 190 to "FATTURA", 470 to number, 670 to "30/09/2026", 960 to "RIMESSA", 1060 to "DIRETTA", 1330 to "$page/2"),
        row(420, 215 to "COLLI", 450 to "DESCRIZIONE", 590 to "BENI", 920 to "TIPO", 1010 to "TOT.", 1105 to "PREZZO", 1240 to "IMPORTO", 1350 to "COD"),
        row(445, 100 to "CODICE"),
        row(470, 215 to "N.xPz", 445 to "(", 455 to "Natura", 530 to "e", 545 to "qualità", 630 to ")", 905 to "CONF.", 995 to "PZ/KG", 1105 to "UNIT.", 1175 to "€", 1290 to "EUR", 1355 to "IVA"),
    )

    /** Page 1; [x14] is how the camera read "1x14" (two photos of one sheet never read the same). */
    private fun page1(x14: String) = letterhead(1, "12A/34s67") + listOf(
        row(510, 100 to "1000011", 225 to "1x10", 285 to "LATTE", 350 to "INTERO", 430 to "LT.1", 880 to "BT", 920 to "LT", 955 to "1", 1060 to "10", 1130 to "1,050", 1290 to "10,50", 1380 to "04"),
        row(540, 100 to "1000022", 225 to x14, 285 to "BISCOTTI", 380 to "FROLLINI", 470 to "GR.250", 880 to "AS", 920 to "GR", 955 to "250", 1060 to "14", 1130 to "1,200", 1290 to "16,80", 1380 to "10"),
        row(570, 100 to "1000033", 255 to "1", 285 to "ZUCCA", 350 to "VIOLINA", 920 to "KG", 1040 to "14,54", 1130 to "1,290", 1290 to "18,75", 1380 to "04"),
        row(600, 300 to "Prov", 350 to "ITALIA", 430 to "Cat", 470 to "II", 500 to "Cal"),
        row(630, 100 to "1000044", 255 to "3", 285 to "VERZE", 880 to "NC", 920 to "KG", 1040 to "9,70", 1130 to "1,380", 1290 to "13,39", 1380 to "04"),
        row(660, 100 to "1000055", 255 to "1", 285 to "LIMONI", 350 to "SFUSI", 880 to "NC", 920 to "KG", 1040 to "6,32", 1130 to "2,180", 1290 to "13,78", 1380 to "04"),
        row(690, 100 to "1000066", 255 to "1", 285 to "PECORINO", 390 to "ROMANO", 880 to "BS", 920 to "KG", 1040 to "2,01", 1120 to "20,770", 1290 to "41,75", 1380 to "10"),
        // The tilted bottom: numbers first, then the name.
        row(722, 920 to "KG", 1040 to "4,58", 1130 to "2,890", 1290 to "13,24", 1380 to "04"),
        row(744, 100 to "1000077", 255 to "2", 285 to "CICORIA", 380 to "CAMPAGNOLA", 500 to "CRT"),
        row(766, 300 to "Prov", 350 to "ITALIA", 430 to "Cat", 470 to "II", 500 to "Cal"),
        row(788, 885 to "CS", 925 to "KG", 1040 to "3,59", 1130 to "7,700", 1290 to "27,64", 1380 to "10"),
        row(810, 100 to "1000088", 255 to "1", 285 to "COPPA", 350 to "SUINO", 420 to "S/OSSO"),
        row(832, 885 to "CS", 925 to "KG", 1040 to "5,06", 1120 to "18,690", 1290 to "94,57", 1380 to "10"),
        row(854, 100 to "1000099", 255 to "1", 285 to "ROASTBEEF", 400 to "B.AD.TR.S/V", 540 to "AB500168"),
        row(876, 885 to "CS", 925 to "KG", 1040 to "5,02", 1120 to "18,690", 1290 to "93,82", 1380 to "10"),
        row(898, 100 to "1000099", 255 to "1", 285 to "ROASTBEEF", 400 to "B.AD.TR.S/V", 540 to "AB5O0168"),
        row(1000, 1230 to "SEGUE", 1300 to ">>>"),
    )

    private val page2 = letterhead(2, "12A/34567") + listOf(
        row(800, 240 to "IMPONIBILI", 500 to "IVA", 660 to "IMPORTO", 760 to "IVA", 880 to "DESCRIZIONE", 1010 to "COD", 1060 to "IVA"),
        row(830, 330 to "69,67", 510 to "04", 760 to "2,79", 880 to "ALIQUOTA", 990 to "4%"),
        row(860, 320 to "274,58", 510 to "10", 760 to "27,46", 880 to "ALIQUOTA", 990 to "10%"),
        row(900, 185 to "TOTALE", 260 to "IMPONIBILI", 730 to "344,25"),
        row(930, 185 to "TOTALE", 260 to "IVA", 740 to "30,25"),
        row(960, 185 to "TOTALE", 260 to "DOCUMENTO", 730 to "374,50"),
    )

    private val options = ParseOptions(ownVatNumber = "09876543217")

    private fun read(layout: SupplierLayout? = null) =
        ReceiptParser.parsePages(listOf(page1("1x14"), page2, page1("Ix14")), options.copy(layout = layout))

    private fun ParsedDocument.line(word: String) = lineItems.single { it.originalDescription.contains(word) }

    @Test
    fun aPagePhotographedTwiceIsReadOnceAndSaid() {
        assertEquals(mapOf(2 to 0), DuplicatePages.find(listOf(page1("1x14"), page2, page1("Ix14"))))
        val d = read()
        assertTrue(ParseWarning.DUPLICATE_PAGE in d.warnings)
        assertEquals(1, d.lineItems.count { it.originalDescription.contains("LATTE") })
        assertEquals(10, d.lineItems.size)
        // Pages that share only the letterhead are different pages.
        assertTrue(DuplicatePages.find(listOf(page1("1x14"), page2)).isEmpty())
    }

    @Test
    fun aTiltedPageGivesEachNameItsOwnNumbersAndAsks() {
        val d = read()
        assertTrue(d.lineItems.none { it.originalDescription.startsWith("PROV", ignoreCase = true) })
        val cicoria = d.line("CICORIA")
        assertEquals(1324L, cicoria.lineTotalCents?.value)
        assertEquals("1000077", cicoria.itemCode)
        assertTrue(cicoria.nameDoubt)
        assertEquals(2764L, d.line("COPPA").lineTotalCents?.value)
        assertEquals(listOf(9457L, 9382L), d.lineItems.filter { it.originalDescription.contains("ROASTBEEF") }.map { it.lineTotalCents?.value })
    }

    @Test
    fun aLotAtTheEndOfTheNameIsOfferedThenTakenOnceLearned() {
        val roast = read().lineItems.filter { it.originalDescription.contains("ROASTBEEF") }
        // The camera's "AB5O0168" is the same batch: the reading with the most digits is offered for both.
        assertEquals(listOf("AB500168", "AB500168"), roast.map { it.lotNumber?.value })
        assertTrue(roast.all { it.lotNumber?.confidence == Confidence.LOW })
        assertTrue(roast.none { it.originalDescription.contains("AB5") })
        // No pack size or code taken for a lot.
        assertNull(read().line("BISCOTTI").lotNumber)
        assertNull(read().line("LATTE").lotNumber)

        val learned = read(SupplierLayout(lotsInNames = true)).lineItems.filter { it.originalDescription.contains("ROASTBEEF") }
        assertTrue(learned.all { it.lotNumber?.confidence == Confidence.HIGH })
        assertEquals(SupplierLayout(lotsInNames = true, documents = 1), SupplierLayout.decode(SupplierLayout(lotsInNames = true, documents = 1).encode()))
    }

    @Test
    fun batchCodesAreNotSizes() {
        assertEquals("AB500168", LotsInNames.candidate("ROASTBEEF B.AD.TR.S/V AB500168"))
        assertEquals("XY0321", LotsInNames.candidate("NOCE BOVINO ADULTO XY0321"))
        assertNull(LotsInNames.candidate("PREZZEMOLO MAZZI G500"))
        assertNull(LotsInNames.candidate("COCA COLA VETRO CC330"))
        assertNull(LotsInNames.candidate("TOVAGLIOLI VANTO P100"))
        assertNull(LotsInNames.candidate("AROMI TIMO G30"))
        assertNull(LotsInNames.candidate("ACETO AB1234")) // too little name before it
    }

    @Test
    fun oneCentOffOnARoundedDocumentIsHighlighted() {
        val zucca = read().line("ZUCCA")
        assertEquals(1875L, zucca.lineTotalCents?.value)
        assertEquals(Confidence.LOW, zucca.lineTotalCents?.confidence)
        // The other lines stay as read.
        assertEquals(Confidence.HIGH, read().line("VERZE").lineTotalCents?.confidence)
    }

    @Test
    fun aDocumentNumberMisreadOnOnePageTakesTheDigitsOfAnother() {
        val n = read().documentNumber
        assertEquals("12A/34567", n?.value)
        assertEquals(Confidence.LOW, n?.confidence)
    }

    @Test
    fun theFieldPreviewFindsTheValueOrNothing() {
        val pages = listOf(page1("1x14"), page2)
        // The AI's answer is not a printed row: the row that prints the number (as misread) is shown.
        val spot = FieldLocator.locate(pages, "AI number: 12A/34567", "12A/34567")
        assertNotNull(spot)
        assertEquals(370, pages[spot!!.page].first { it.text.contains("FATTURA") }.top)
        assertTrue(spot.mark.top in 360..390)
        // A code printed nowhere: no preview rather than a wrong place.
        assertNull(FieldLocator.locate(pages, "lot by position: the same place under 3 items", "ZZ998877"))
        // A lot offered from the name: the row with the name.
        val lot = FieldLocator.locate(pages, "lot at the end of the name: ROASTBEEF B.AD.TR.S/V AB500168", "AB500168")
        assertNotNull(lot)
        assertTrue(lot!!.mark.top in 850..880)
    }

    @Test
    fun aSupplierVatNumberMisreadByOneDigitStillFindsTheSavedSupplier() {
        assertTrue(SellerProfiles.isValidPartitaIva("01234567897"))
        val misread = "01234567897".replaceRange(6, 7, "1") // one digit read wrong
        assertFalse(SellerProfiles.isValidPartitaIva(misread))
        val text = listOf(listOf("ABE S.r.l.", "Reg. Imp. C.F. - P.IVA $misread - REA n. 123", "SPETTABILE", "RISTORANTE PROVA SAS", "PARTITA IVA 09876543217"))
        val known = ParseOptions(ownVatNumber = "09876543217", supplierByVat = { v -> if (v == "01234567897") "ABC S.r.l." else null })
        val r = SupplierProof.judge(SupplierProof.collect(text, Extracted("ABE S.r.l.", Confidence.LOW, "ABE S.r.l."), null, known))
        assertEquals(SupplierProof.State.PROVEN, r.state)
        assertEquals("ABC S.r.l.", r.name)
        // A lowercase "s" read for a 5 inside the number is repaired.
        assertEquals(listOf("01234567897"), SellerProfiles.vatNumbers("P.IVA 01234s67897"))
    }
}
