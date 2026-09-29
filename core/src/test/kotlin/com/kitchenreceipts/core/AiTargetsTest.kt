package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTargetsTest {
    /** One OCR line per cell, 9 px per character, rows 32 px apart. */
    private fun page(rows: List<List<Pair<String, Int>>>): List<OcrLine> = rows.flatMapIndexed { r, cells ->
        val y = 100 + r * 32
        cells.filter { it.first.isNotEmpty() }.map { (t, x) -> OcrLine(t, x, y, x + t.length * 9, y + 16) }
    }

    private val header = listOf("CODICE" to 40, "DESCRIZIONE" to 150, "U.M." to 520, "QUANTITA'" to 580, "PREZZO" to 700, "IMPORTO" to 820)
    private fun row(c: String, d: String, u: String, q: String, p: String, a: String) =
        listOf(c to 40, d to 150, u to 520, q to 600, p to 710, a to 830)

    private val lines = page(listOf(
        listOf("ESEMPIO FORNITURE S.R.L." to 40),
        listOf("FATTURA N. 45 DEL 12/09/2026" to 40),
        header,
        row("10001", "MOZZARELLA FIOR DI LATTE", "KG", "2,500", "8,90", "22,25"),
        row("10002", "POMODORI PELATI", "CT", "2", "21,78", "43,56"),
        row("10003", "SALE MARINO FINO", "NR", "10", "0,422", "4,62"),   // misread amount: 10 x 0,422 = 4,22
        listOf("TOTALE IMPONIBILE" to 520, "70,03" to 830),
        listOf("TOTALE DOCUMENTO" to 520, "76,43" to 830),
    ))

    @Test fun onlyTheLineThatDoesNotAddUpIsAsked() {
        val doc = ReceiptParser.parsePages(listOf(lines))
        assertEquals(3, doc.lineItems.size)
        val targets = AiTargets.plan(listOf(lines), doc)!!
        val rows = targets.filterIsInstance<AiTarget.Row>()
        assertEquals(1, rows.size)
        assertEquals(2, rows.single().itemIndex)
        assertTrue(rows.single().rowText.contains("SALE MARINO FINO"))
        assertEquals(2, rows.single().boxes.size)                 // headings + the line
        assertTrue(rows.single().boxes[0].top < rows.single().boxes[1].top)
        assertTrue(targets.none { it is AiTarget.Header })        // supplier and date were read
    }

    @Test fun provenAnswerReplacesTheLine() {
        val doc = ReceiptParser.parsePages(listOf(lines))
        val target = AiTargets.plan(listOf(lines), doc)!!.filterIsInstance<AiTarget.Row>().single()
        val text = LayoutRows.toText(lines).replace("4,62", "4,22 4,62")
        val answer = """{"code":"10003","colli":null,"description":"SALE MARINO FINO","unit":"NR","quantity":"10","price":"0,422","discount":null,"amount":"4,22","vat_rate":null,"lot":null}"""
        val fixed = AiReader.applyTargets(doc, listOf(target to answer), text)
        assertEquals(422L, fixed.lineItems[2].lineTotalCents?.value)
        assertEquals(Confidence.HIGH, fixed.lineItems[2].quantity?.confidence)
        assertTrue(fixed.itemsReadBy.endsWith("+ai"))
        // An answer that does not add up changes nothing.
        val wrong = answer.replace("\"4,22\"", "\"9,99\"")
        assertEquals(462L, AiReader.applyTargets(doc, listOf(target to wrong), text).lineItems[2].lineTotalCents?.value)
    }

    @Test fun missingTotalAsksTheBottomOfThePage() {
        val noTotal = lines.filterNot { it.text.startsWith("TOTALE") || it.text == "70,03" || it.text == "76,43" }
        val doc = ReceiptParser.parsePages(listOf(noTotal))
        assertNull(doc.totalCents)
        val t = AiTargets.plan(listOf(noTotal), doc)!!
        assertNotNull(t.firstOrNull { it is AiTarget.Totals })
    }

    @Test fun grammarsAreWellFormed() {
        assertTrue(AiReader.ROW_GRAMMAR.startsWith("root ::= \"{\" ws \"\\\"code\\\":\" ws value"))
        assertTrue(AiReader.TOTALS_GRAMMAR.contains("\"\\\"total\\\":\" ws value ws \"}\""))
        assertTrue(AiReader.ROW_GRAMMAR.contains("value ::= \"null\" | \"\\\"\" char{0,100} \"\\\"\""))
        assertTrue(AiReader.ROW_GRAMMAR.contains("char ::= [^\"\\\\\\x00-\\x1F] | \"\\\\\" [\"\\\\/nt]"))
        assertTrue(AiReader.ROW_GRAMMAR.trimEnd().endsWith("ws ::= [ \\n]?"))
    }
}
