package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLoopTest {
    private fun page(rows: List<List<Pair<String, Int>>>): List<OcrLine> = rows.flatMapIndexed { r, cells ->
        val y = 100 + r * 32
        cells.filter { it.first.isNotEmpty() }.map { (t, x) -> OcrLine(t, x, y, x + t.length * 9, y + 16) }
    }

    private val header = listOf("CODICE" to 40, "DESCRIZIONE" to 150, "U.M." to 520, "QUANTITA'" to 580, "PREZZO" to 700, "IMPORTO" to 820)
    private fun row(c: String, d: String, u: String, q: String, p: String, a: String) = listOf(c to 40, d to 150, u to 520, q to 600, p to 710, a to 830)

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
    private val text = LayoutRows.toText(lines)
    private fun start() = ReceiptParser.parsePages(listOf(lines))

    /** A pretend AI: what it answers to each kind of question. */
    private fun ai(rowAnswer: String): (AiTarget) -> String = { t ->
        when (t) {
            is AiTarget.Row -> rowAnswer
            is AiTarget.Number -> if (t.field == AiTarget.Field.AMOUNT) (if (t.rowText.contains("SALE")) "4,22" else ItalianNumbers.toEditText(t.expected)) else "10"
            is AiTarget.Header -> """{"seller":"ESEMPIO FORNITURE S.R.L.","seller_vat":null,"number":"45","date":"12/09/2026"}"""
            is AiTarget.Totals -> """{"subtotal":"70,03","vat":null,"total":"76,43"}"""
            else -> ""
        }
    }

    private fun run(answer: (AiTarget) -> String): Pair<AiLoop, List<AiTarget>> {
        val loop = AiLoop(listOf(lines), text, ParseOptions(), start())
        val asked = mutableListOf<AiTarget>()
        while (true) {
            val t = loop.next() ?: break
            asked += t
            loop.answer(t, answer(t))
        }
        return loop to asked
    }

    @Test fun theLineThatDoesNotAddUpIsAskedFirstAndSettled() {
        val good = """{"code":"10003","colli":null,"description":"SALE MARINO FINO","unit":"NR","quantity":"10","price":"0,422","discount":null,"amount":"4,22","vat_rate":null,"lot":null}"""
        val (loop, asked) = run(ai(good))
        assertTrue(asked.first() is AiTarget.Row)
        assertEquals(422L, loop.doc.lineItems[2].lineTotalCents?.value)
        // Never the same question twice; stops on its own, well under the limit.
        assertEquals(asked.size, asked.map { AiLoop.key(it) }.distinct().size)
        assertTrue(asked.size < AiLoop.MAX_QUESTIONS)
        assertTrue(asked.none { it is AiTarget.Row && it.rowText.contains("SALE") && asked.indexOf(it) > 0 })
    }

    @Test fun aWholeLineNotProvenIsAskedAgainOneNumberAtATime() {
        // The AI's whole-line answer does not add up (it shifted the columns): nothing is taken from it ...
        val shifted = """{"code":"10003","colli":"10","description":"SALE MARINO FINO","unit":"NR","quantity":"0,422","price":"4,62","discount":null,"amount":"4,62","vat_rate":null,"lot":null}"""
        val (loop, asked) = run(ai(shifted))
        // ... so the amount alone is asked next, and the AI's 4,22 makes the line add up: taken.
        val i = asked.indexOfFirst { it is AiTarget.Row }
        val next = asked[i + 1]
        assertTrue(next is AiTarget.Number && next.field == AiTarget.Field.AMOUNT && !next.verify)
        assertEquals(422L, loop.doc.lineItems[2].lineTotalCents?.value)
        assertEquals(Confidence.HIGH, loop.doc.lineItems[2].lineTotalCents?.confidence)
    }

    @Test fun theSameAnswersGiveTheSameDocumentAgain() {
        val (loop, _) = run(ai("""{"code":"10003","colli":null,"description":"SALE MARINO FINO","unit":"NR","quantity":"10","price":"0,422","discount":null,"amount":"4,22","vat_rate":null,"lot":null}"""))
        val again = AiLoop.replay(listOf(lines), text, ParseOptions(), start(), loop.answers)
        assertEquals(loop.doc.lineItems.map { it.lineTotalCents?.value }, again.lineItems.map { it.lineTotalCents?.value })
        assertEquals(loop.doc.aiCheck?.checked, again.aiCheck?.checked)
    }

    @Test fun limitStopsTheLoop() {
        val loop = AiLoop(listOf(lines), text, ParseOptions(), start(), maxQuestions = 1)
        val t = loop.next()!!
        loop.answer(t, "")
        assertNull(loop.next())
    }
}
