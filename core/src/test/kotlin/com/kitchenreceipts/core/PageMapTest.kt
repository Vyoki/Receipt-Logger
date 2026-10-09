package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMapTest {
    /** One OCR line per cell, 9 px per character, rows 32 px apart. */
    private fun page(rows: List<List<Pair<String, Int>>>): List<OcrLine> = rows.flatMapIndexed { r, cells ->
        val y = 100 + r * 32
        cells.filter { it.first.isNotEmpty() }.map { (t, x) -> OcrLine(t, x, y, x + t.length * 9, y + 16) }
    }
    private val header = listOf("CODICE" to 40, "DESCRIZIONE" to 150, "U.M." to 520, "QUANTITA'" to 580, "PREZZO" to 700, "IMPORTO" to 820)
    private fun row(c: String, d: String, u: String, q: String, p: String, a: String) = listOf(c to 40, d to 150, u to 520, q to 600, p to 710, a to 830)
    private val top = listOf(
        listOf("ESEMPIO FORNITURE S.R.L." to 40),
        listOf("FATTURA N. 45 DEL 12/09/2026" to 40),
    )
    private val foot = listOf(
        listOf("TOTALE IMPONIBILE" to 520, "66,03" to 830),
        listOf("TOTALE DOCUMENTO" to 520, "72,63" to 830),
    )

    @Test fun aReadingThatExplainsEveryWordLeavesNothing() {
        val lines = page(top + listOf(header,
            row("10001", "MOZZARELLA FIOR DI LATTE", "KG", "2,500", "8,90", "22,25"),
            row("10002", "POMODORI PELATI", "CT", "2", "21,89", "43,78"),
        ) + foot)
        val doc = ReceiptParser.parsePages(listOf(lines))
        val map = PageMap.build(listOf(lines), doc)
        assertEquals(map.describeUnexplained(), 0, map.unexplained.size)
        assertEquals(2, map.items.size)
    }

    @Test fun aLotUnderTheItemCodeWithNoHeadingIsNoticed() {
        // No LOTTO heading anywhere: the reading cannot know these numbers are lots; the map sees words it cannot explain.
        val lines = page(top + listOf(header,
            row("10001", "MOZZARELLA FIOR DI LATTE", "KG", "2,500", "8,90", "22,25"),
            listOf("2610263" to 40),
            row("10002", "POMODORI PELATI", "CT", "2", "21,89", "43,78"),
            listOf("2610297" to 40),
        ) + foot)
        val doc = ReceiptParser.parsePages(listOf(lines))
        val map = PageMap.build(listOf(lines), doc)
        val left = map.unexplained.filter { it.region == PageMap.Region.TABLE }
        // Either the reading took them as lots, or the map reports them, under the code column of their own item.
        val lots = doc.lineItems.mapNotNull { it.lotNumber?.value }
        if (lots.size < 2) {
            assertTrue(map.describeUnexplained(), left.any { it.word.text == "2610263" && it.item == 0 && it.column == "CODICE" })
            assertTrue(map.describeUnexplained(), left.any { it.word.text == "2610297" && it.item == 1 && it.column == "CODICE" })
        }
    }

    @Test fun theSecondLineOfANameInTheSupplierBoxIsNoticed() {
        // The name runs over two lines; the reading took only the first.
        val boxed = page(listOf(
            listOf("Cedente/prestatore (fornitore)" to 40, "Cessionario/committente (cliente)" to 500),
            listOf("Identificativo fiscale ai fini IVA: IT01234567897" to 40, "Identificativo fiscale ai fini IVA: IT09876543217" to 500),
            listOf("Denominazione: MACELLERIA ROSSI DI ROSSI" to 40, "Denominazione: RISTORANTE PROVA SAS" to 500),
            listOf("MARIO" to 40),
            listOf("Indirizzo: VIA ROMA 1" to 40, "Indirizzo: VIA VERDI 2" to 500),
            listOf("FATTURA N. 45 DEL 12/09/2026" to 40),
            header,
            row("10001", "MOZZARELLA FIOR DI LATTE", "KG", "2,500", "8,90", "22,25"),
            row("10002", "POMODORI PELATI", "CT", "2", "21,89", "43,78"),
        ) + foot)
        // The reading as it went on the photo: the name cut after its first line.
        val doc = ReceiptParser.parsePages(listOf(boxed), ParseOptions(ownVatNumber = "09876543217"))
            .copy(sellerName = Extracted("MACELLERIA ROSSI DI ROSSI", Confidence.HIGH, "page 1: Denominazione: MACELLERIA ROSSI DI ROSSI"))
        val map = PageMap.build(listOf(boxed), doc, "09876543217")
        // MARIO is the only word of the supplier's box nothing explains.
        assertEquals(map.describeUnexplained(), listOf("MARIO"), map.unexplained.filter { it.region == PageMap.Region.SUPPLIER_BOX }.map { it.word.text })
        // With the whole name read, nothing is left over.
        val whole = PageMap.build(listOf(boxed), doc.copy(sellerName = Extracted("MACELLERIA ROSSI DI ROSSI MARIO", Confidence.HIGH, "x")), "09876543217")
        assertEquals(whole.describeUnexplained(), 0, whole.unexplained.size)
    }
}
