package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTasksTest {
    private fun page(rows: List<List<Pair<String, Int>>>): List<OcrLine> = rows.flatMapIndexed { r, cells ->
        val y = 100 + r * 32
        cells.filter { it.first.isNotEmpty() }.map { (t, x) -> OcrLine(t, x, y, x + t.length * 9, y + 16) }
    }
    private val lines = page(listOf(
        listOf("ESEMPIO FORNITURE S.R.L." to 40),
        listOf("FATTURA N. 45 DEL 12/09/2026" to 40),
        listOf("CODICE" to 40, "DESCRIZIONE" to 150, "U.M." to 520, "QUANTITA'" to 580, "PREZZO" to 700, "IMPORTO" to 820),
        listOf("10001" to 40, "MOZZARELLA FIOR DI LATTE" to 150, "KG" to 520, "2,500" to 600, "8,90" to 710, "22,25" to 830),
        listOf("10003" to 40, "SALE MARINO FINO" to 150, "NR" to 520, "10" to 600, "0,422" to 710, "4,62" to 830),
        listOf("TOTALE DOCUMENTO" to 520, "26,87" to 830),
    ))

    @Test fun compactQuestionsAreShortAndKeepTheGrammar() {
        val doc = ReceiptParser.parsePages(listOf(lines))
        val row = AiTargets.plan(listOf(lines), doc)!!.filterIsInstance<AiTarget.Row>().single()
        val (full, g1) = AiTasks.question(row, AiTasks.Style.FULL)
        val (compact, g2) = AiTasks.question(row, AiTasks.Style.COMPACT)
        assertEquals(g1, g2)
        assertTrue(compact.startsWith("ROW\n"))
        assertTrue(compact.length * 3 < full.length) // a short prompt: read several times faster on the phone
    }

    @Test fun theFollowUpSaysWhyTheLineIsAskedAgain() {
        val prev = """{"code":"10003","colli":null,"description":"SALE MARINO FINO","unit":"NR","quantity":"0,422","price":"4,62","discount":null,"amount":"4,62","vat_rate":null,"lot":null}"""
        assertEquals("qty 0,422 price 4,62 amount 4,62: 0,422 x 4,62 = 1,95", AiTasks.summary(prev))
    }

    @Test fun theAgentChoosesAmongTheOpenQuestions() {
        val doc = ReceiptParser.parsePages(listOf(lines))
        val loop = AiLoop(listOf(lines), LayoutRows.toText(lines), ParseOptions(), doc)
        val open = loop.candidates()
        assertTrue(open.size >= 2)
        val (prompt, grammar) = AiTasks.decide(loop.doc, open)
        assertTrue(prompt.startsWith("NEXT\nSTATE: "))
        assertTrue(prompt.contains("line 2: SALE MARINO FINO, 10 x 0,422 = 4,22, read 4,62"))
        assertTrue(grammar.startsWith("root ::= [AB"))
        assertEquals(1, AiTasks.decodeDecide("B", open))
        assertNull(AiTasks.decodeDecide("Z", open))
        // The chosen question is replayed the same way.
        val t = open[1]
        loop.answer(t, "", pick = 1)
        val again = AiLoop.replay(listOf(lines), LayoutRows.toText(lines), ParseOptions(), doc, listOf(""), listOf(1))
        assertEquals(loop.doc.lineItems.map { it.lineTotalCents?.value }, again.lineItems.map { it.lineTotalCents?.value })
    }
}
