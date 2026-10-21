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

    private fun run(answer: (AiTarget) -> String, scope: AiScope? = null): Pair<AiLoop, List<AiTarget>> {
        val loop = if (scope == null) AiLoop(listOf(lines), text, ParseOptions(), start()) else AiLoop(listOf(lines), text, ParseOptions(), start(), scope = scope)
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
        // The supplier's name is printed in one place only (not proven): who issued it is asked first, then the line.
        assertTrue(asked.first() is AiTarget.Supplier)
        assertTrue(asked[1] is AiTarget.Row)
        assertEquals(422L, loop.doc.lineItems[2].lineTotalCents?.value)
        // Never the same question twice; stops on its own, well under the limit.
        assertEquals(asked.size, asked.map { AiLoop.key(it) }.distinct().size)
        assertTrue(asked.size < AiLoop.MAX_QUESTIONS)
        assertEquals(1, asked.count { it is AiTarget.Row && it.rowText.contains("SALE") })
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

    @Test fun afterAnUpdateEachAnswerGoesBackToItsOwnQuestion() {
        val (loop, asked) = run(ai("""{"code":"10003","colli":null,"description":"SALE MARINO FINO","unit":"NR","quantity":"10","price":"0,422","discount":null,"amount":"4,22","vat_rate":null,"lot":null}"""))
        assertTrue(asked.first() is AiTarget.Supplier)
        // Since then the supplier became known (saved under its VAT number): its question is no longer asked, and the
        // line's question moved up to the first place. The supplier's answer must not be read as the line's.
        val known = start().let { d -> d.copy(sellerName = d.sellerName!!.copy(confidence = Confidence.HIGH)) }
        val again = AiLoop.replay(listOf(lines), text, ParseOptions(), known, loop.answers, loop.picks, loop.keys)
        assertEquals(422L, again.lineItems[2].lineTotalCents?.value)
        assertEquals(Confidence.HIGH, again.lineItems[2].lineTotalCents?.confidence)
    }

    @Test fun theFollowUpOnTheSamePictureIsNotPrecededByAChoice() {
        // The whole-line answer does not add up: the line's amount alone comes next, on the picture just processed.
        val shifted = """{"code":"10003","colli":"10","description":"SALE MARINO FINO","unit":"NR","quantity":"0,422","price":"4,62","discount":null,"amount":"4,62","vat_rate":null,"lot":null}"""
        val loop = AiLoop(listOf(lines), text, ParseOptions(), start())
        while (true) {
            val t = loop.next() ?: break
            loop.answer(t, ai(shifted)(t))
            if (t is AiTarget.Row) break
        }
        val open = loop.candidates()
        assertTrue(open.first() is AiTarget.Number)
        // Asking the model which comes next would make the phone process the picture again for nothing.
        assertTrue(!loop.worthDeciding(open))
    }

    private val good = """{"code":"10003","colli":null,"description":"SALE MARINO FINO","unit":"NR","quantity":"10","price":"0,422","discount":null,"amount":"4,22","vat_rate":null,"lot":null}"""

    @Test fun headerTotalsLoopNeverAsksAboutLines() {
        val (loop, asked) = run(ai(good), AiScope.HEADER_TOTALS)
        assertTrue(asked.isNotEmpty())
        // The supplier double-check stays, and comes first.
        assertTrue(asked.first() is AiTarget.Supplier)
        assertTrue(asked.none { it is AiTarget.Row || it is AiTarget.Number || it is AiTarget.Choice })
        // The line that does not add up is left as read: the arithmetic still flags it for the operator.
        assertEquals(462L, loop.doc.lineItems[2].lineTotalCents?.value)
        assertTrue(ParseWarning.LINE_TOTAL_MISMATCH in loop.doc.lineItems[2].warnings)
        assertEquals(AiScope.HEADER_TOTALS, loop.copy().scope)
        assertTrue(loop.copy().candidates().isEmpty())
    }

    @Test fun headerTotalsLoopStillFlagsWhatTheAiReadsDifferently() {
        assertEquals(Confidence.HIGH, start().documentNumber?.confidence)
        // The AI reads another number in the header and another printed amount as the total.
        val misread: (AiTarget) -> String = { t ->
            when (t) {
                is AiTarget.Header -> """{"seller":"ESEMPIO FORNITURE S.R.L.","seller_vat":null,"number":"54","date":"12/09/2026"}"""
                is AiTarget.Totals -> """{"subtotal":"70,03","vat":null,"total":"70,03"}"""
                else -> ai(good)(t)
            }
        }
        val (loop, asked) = run(misread, AiScope.HEADER_TOTALS)
        assertTrue(asked.any { it is AiTarget.Header } && asked.any { it is AiTarget.Totals })
        // The number read with confidence is highlighted (LOW), with the AI's reading next to it.
        assertEquals("45", loop.doc.documentNumber?.value)
        assertEquals(Confidence.LOW, loop.doc.documentNumber?.confidence)
        assertTrue(loop.doc.aiCheck!!.disagreements.any { it.startsWith("number") })
        // The total is never replaced by an amount that does not add up: only offered.
        assertEquals(7643L, loop.doc.totalCents?.value)
        assertEquals(Confidence.LOW, loop.doc.totalCents?.confidence)
        assertEquals("70,03", loop.doc.aiCheck!!.header["total"])
    }

    @Test fun headerTotalsLoopDoesNotFollowUpOnALine() {
        // Even after a whole-line answer that does not add up, no follow-up number is asked in HEADER_TOTALS.
        val loop = AiLoop(listOf(lines), text, ParseOptions(), start(), scope = AiScope.HEADER_TOTALS)
        val row = AiTargets.plan(listOf(lines), start(), spotCheck = true)!!.filterIsInstance<AiTarget.Row>().single()
        loop.answer(row, """{"code":"10003","colli":"10","description":"SALE MARINO FINO","unit":"NR","quantity":"0,422","price":"4,62","discount":null,"amount":"4,62","vat_rate":null,"lot":null}""")
        assertTrue(loop.candidates().all { it is AiTarget.Supplier || it is AiTarget.Header || it is AiTarget.Totals })
    }

    @Test fun headerTotalsReplayGivesTheSameDocument() {
        val (loop, _) = run(ai(good), AiScope.HEADER_TOTALS)
        val byKeys = AiLoop.replay(listOf(lines), text, ParseOptions(), start(), loop.answers, loop.picks, loop.keys, AiScope.HEADER_TOTALS)
        assertEquals(loop.doc, byKeys)
        // Older readings without keys: the picks, in the same scope.
        val byPicks = AiLoop.replay(listOf(lines), text, ParseOptions(), start(), loop.answers, loop.picks, scope = AiScope.HEADER_TOTALS)
        assertEquals(loop.doc, byPicks)
    }

    @Test fun allScopeAsksTheSameAsBefore() {
        val (today, asked) = run(ai(good))
        val (all, askedAll) = run(ai(good), AiScope.ALL)
        assertEquals(asked, askedAll)
        assertEquals(today.doc, all.doc)
        assertEquals(today.doc, AiLoop.replay(listOf(lines), text, ParseOptions(), start(), today.answers, today.picks, today.keys))
        // The questions asked on this page before the scope existed, in order.
        assertEquals(
            listOf("supplier", "row:10003 SALE MARINO FINO NR 10 0,422 4,62", "totals", "number:AMOUNT:true:10002 POMODORI PELATI CT 2 21,78 43,56", "header"),
            asked.map { AiLoop.key(it) },
        )
    }

    @Test fun limitStopsTheLoop() {
        val loop = AiLoop(listOf(lines), text, ParseOptions(), start(), maxQuestions = 1)
        val t = loop.next()!!
        loop.answer(t, "")
        assertNull(loop.next())
    }
}
