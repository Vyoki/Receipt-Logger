package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented supplier "ESEMPIO FORNITURE S.R.L."; codes and legends in the shapes suppliers print them. */
class PageCodesTest {

    @Test fun legendsInTheirUsualShapes() {
        assertEquals(mapOf("C" to "congelato", "S" to "surgelato", "F" to "fresco"), PageCodes.parse("*C congelato / S surgelato/ F fresco")!!.second)
        // A legend followed by other print on the same line: the last meaning stops where the others do.
        assertEquals("fresco", PageCodes.parse("* C congelato / S surgelato / F fresco nome del venditore e sede dello stabilimento")!!.second["F"])
        val rowType = PageCodes.parse("TIPO RIGA : O - OFFERTE | S - SCONTO | C - CAMBIO PREZZO")!!
        assertEquals("tipo riga", rowType.first)
        assertEquals(mapOf("O" to "offerte", "S" to "sconto", "C" to "cambio prezzo"), rowType.second)
        val eq = PageCodes.parse("Legenda: C=congelato S=surgelato F=fresco CN=conserva")!!
        assertNull(eq.first)
        assertEquals("conserva", eq.second["CN"])
        assertEquals(mapOf("C" to "congelato", "S" to "surgelato", "F" to "fresco"), PageCodes.parse("(*) C congelato - S surgelato - F fresco")!!.second)
        assertEquals("ambient", PageCodes.parse("Storage: F=frozen C=chilled A=ambient")!!.second["A"])
    }

    @Test fun ordinaryLinesAreNoLegend() {
        for (t in listOf(
            "VIA ROMA 1 / 00100 ROMA", "COD.ART. COLLI DESCRIZIONE BENI U.M. QUANTITA PREZZO", "IVA 22% / 10%",
            "MOZZARELLA FIOR DI LATTE KG 2,500 8,90 22,25", "TOTALE DOCUMENTO 72,63", "S.p.A. - Cap. Soc. 10.000",
            "DI ROSSI MARIO", "Merce non deperibile - Congelato", "UM QTA / PREZZO",
        )) assertNull(t, PageCodes.parse(t))
    }

    // ------------------------------------------------------------------ on a page

    /** One OCR line per cell, 9 px per character, rows 32 px apart. */
    private fun page(rows: List<List<Pair<String, Int>>>): List<OcrLine> = rows.flatMapIndexed { r, cells ->
        val y = 100 + r * 32
        cells.filter { it.first.isNotEmpty() }.map { (t, x) -> OcrLine(t, x, y, x + t.length * 9, y + 16) }
    }
    private val top = listOf(listOf("ESEMPIO FORNITURE S.R.L." to 40), listOf("FATTURA N. 45 DEL 12/09/2026" to 40))
    private val header = listOf("CODICE" to 40, "DESCRIZIONE" to 150, "U.M." to 520, "QUANTITA'" to 580, "*" to 680, "PREZZO" to 720, "IMPORTO" to 840)
    private fun row(c: String, d: String, u: String, q: String, s: String, p: String, a: String) =
        listOf(c to 40, d to 150, u to 520, q to 600, s to 680, p to 730, a to 850)

    @Test fun storageLettersAreReadWithTheLegendsMeaning() {
        val lines = page(top + listOf(header,
            row("10001", "MOZZARELLA FIOR DI LATTE", "KG", "2,500", "F", "8,90", "22,25"),
            row("10002", "POMODORI PELATI", "CT", "2", "CN", "21,89", "43,78"),
            row("10003", "PISELLI FINI", "KG", "2,500", "S", "4,00", "10,00"),
        ) + listOf(
            listOf("TOTALE DOCUMENTO" to 520, "76,03" to 850),
            listOf("* C congelato / S surgelato / F fresco / ST stagionato / CN conserva" to 40),
        ))
        val doc = ReceiptParser.parsePages(listOf(lines))
        assertEquals(listOf("fresco", "conserva", "surgelato"), doc.lineItems.map { it.marks.single { m -> m.kind == "storage" }.meaning })
        assertEquals("CN", doc.lineItems[1].marks.single().code)
        // The letters are explained, not left over.
        val map = PageMap.build(listOf(lines), doc)
        assertTrue(map.describeUnexplained(), map.unexplained.none { it.word.text == "CN" })
    }

    @Test fun aLetterGluedToTheQuantityIsTheSameCode() {
        val lines = page(top + listOf(header,
            row("10001", "MOZZARELLA FIOR DI LATTE", "KG", "2,500F", "", "8,90", "22,25"),
            row("10002", "POMODORI PELATI", "CT", "2,000", "CN", "21,89", "43,78"),
        ) + listOf(listOf("TOTALE DOCUMENTO" to 520, "66,03" to 850), listOf("C congelato / F fresco / CN conserva" to 40)))
        val doc = ReceiptParser.parsePages(listOf(lines))
        assertEquals(listOf("fresco", "conserva"), doc.lineItems.map { it.marks.firstOrNull()?.meaning })
    }

    @Test fun withoutALegendALetterMeansNothing() {
        val lines = page(top + listOf(header,
            row("10001", "MOZZARELLA FIOR DI LATTE", "KG", "2,500", "F", "8,90", "22,25"),
            row("10002", "POMODORI PELATI", "CT", "2", "CN", "21,89", "43,78"),
        ) + listOf(listOf("TOTALE DOCUMENTO" to 520, "66,03" to 850)))
        val doc = ReceiptParser.parsePages(listOf(lines))
        assertTrue(doc.lineItems.all { it.marks.isEmpty() })
    }

    @Test fun aLegendLetterInOnePlaceOnlyIsNotTaken() {
        // "S" appears once, in a name: one item, no column, not before the code: no proof.
        val lines = page(top + listOf(header,
            row("10001", "PATATE S FRITTE", "KG", "2,500", "", "8,90", "22,25"),
            row("10002", "POMODORI PELATI", "CT", "2", "", "21,89", "43,78"),
        ) + listOf(listOf("TOTALE DOCUMENTO" to 520, "66,03" to 850), listOf("C congelato / S surgelato / F fresco" to 40)))
        val doc = ReceiptParser.parsePages(listOf(lines))
        assertTrue(doc.lineItems.all { it.marks.isEmpty() })
    }

    @Test fun twoLegendsSharingLettersAreToldApartByWhereTheLettersStand() {
        // "C" is congelato in the storage column and "cambio prezzo" before an item code.
        val lines = page(top + listOf(header,
            listOf("C" to 14) + row("10001", "PISELLI FINI", "KG", "2,500", "C", "4,00", "10,00"),
            row("10002", "MOZZARELLA FIOR DI LATTE", "KG", "2,500", "F", "8,90", "22,25"),
            row("10003", "SPINACI CUBETTI", "KG", "1,000", "C", "6,00", "6,00"),
        ) + listOf(
            listOf("TOTALE DOCUMENTO" to 520, "38,25" to 850),
            listOf("* C congelato / S surgelato / F fresco" to 40),
            listOf("TIPO RIGA : O - OFFERTE | S - SCONTO | C - CAMBIO PREZZO" to 40),
        ))
        val doc = ReceiptParser.parsePages(listOf(lines))
        assertEquals(listOf("congelato", "fresco", "congelato"), doc.lineItems.map { it.marks.firstOrNull { m -> m.kind == "storage" }?.meaning })
        assertEquals(listOf("cambio prezzo", null, null), doc.lineItems.map { it.marks.firstOrNull { m -> m.kind == "tipo riga" }?.meaning })
    }

    @Test fun groupLinesHeadTheirItems() {
        val lines = page(top + listOf(header,
            listOf("Merce non deperibile - Congelato" to 150),
            row("10001", "PISELLI FINI", "KG", "2,500", "", "4,00", "10,00"),
            listOf("Merce deperibile - Fresco" to 150),
            row("10002", "MOZZARELLA FIOR DI LATTE", "KG", "2,500", "", "8,90", "22,25"),
            row("10003", "RICOTTA FRESCA", "KG", "1,000", "", "6,00", "6,00"),
            listOf("Merce non alimentare" to 150),
            row("10004", "SACCHI SPAZZATURA NERI", "CT", "1", "", "38,18", "38,18"),
        ) + listOf(listOf("TOTALE DOCUMENTO" to 520, "76,43" to 850)))
        val doc = ReceiptParser.parsePages(listOf(lines))
        assertEquals(4, doc.lineItems.size)
        assertEquals(
            listOf("Merce non deperibile - Congelato", "Merce deperibile - Fresco", "Merce deperibile - Fresco", "Merce non alimentare"),
            doc.lineItems.map { it.marks.firstOrNull { m -> m.kind == PageCodes.GROUP }?.meaning },
        )
        val map = PageMap.build(listOf(lines), doc)
        assertTrue(map.describeUnexplained(), map.unexplained.isEmpty())
    }

    @Test fun theRestOfANameUnderAnItemIsNoGroup() {
        // Words under an item with no group line above the first item: a note or the rest of a name, never a group.
        val lines = page(top + listOf(header,
            row("10001", "MOZZARELLA FIOR DI LATTE", "KG", "2,500", "", "8,90", "22,25"),
            listOf("Merce deperibile - Fresco" to 150),
            row("10002", "POMODORI PELATI", "CT", "2", "", "21,89", "43,78"),
        ) + listOf(listOf("TOTALE DOCUMENTO" to 520, "66,03" to 850)))
        val doc = ReceiptParser.parsePages(listOf(lines))
        assertTrue(doc.lineItems.all { it.marks.isEmpty() })
    }
}
