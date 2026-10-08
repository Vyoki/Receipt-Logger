package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HandwritingTest {

    /** A row of words 30 px high from x = 100, each word with the OCR's confidence. */
    private fun row(y: Int, vararg words: Pair<String, Float>): OcrLine {
        var x = 100
        val ws = words.map { (t, c) -> OcrLine(t, x, y, x + t.length * 18, y + 30, confidence = c).also { x += t.length * 18 + 40 } }
        return OcrLine(ws.joinToString(" ") { it.text }, ws.first().left, y, ws.last().right, y + 30, words = ws, confidence = ws.minOf { it.confidence })
    }

    private val sure = 0.95f
    private val page = listOf(
        row(100, "ABC" to sure, "S.r.l." to sure),
        row(150, "DDT" to sure, "N." to sure, "12A/34567" to 0.7f, "del" to sure, "07/10/2026" to 0.8f),
        // Handwritten lines: the OCR is unsure of the names, the numbers check each other.
        row(300, "Zvcchine" to 0.35f, "kg" to 0.5f, "5" to 0.7f, "2,50" to 0.6f, "12,50" to 0.65f),
        row(350, "Pomodcri" to 0.3f, "kg" to 0.5f, "3" to 0.7f, "1,80" to 0.7f, "5,90" to 0.4f),
        row(400, "Basilico" to 0.4f, "mazzi" to 0.45f, "2" to 0.7f, "1,00" to 0.7f, "2,00" to 0.7f),
    )

    @Test fun unsureNamesAreMarkedProvenNumbersStay() {
        val d = ReceiptParser.parsePages(listOf(page), ParseOptions(today = LocalDate.of(2026, 10, 8)))
        assertTrue(d.handwritten)
        val zucchine = d.lineItems.first { it.originalDescription.startsWith("Zvcchine") }
        assertTrue(zucchine.nameDoubt) // the name is looked at (and asked to the AI)
        assertEquals(1250L, zucchine.lineTotalCents?.value)
        assertEquals(Confidence.HIGH, zucchine.lineTotalCents?.confidence) // 5 x 2,50 = 12,50 proves it
        val pomodori = d.lineItems.first { it.originalDescription.startsWith("Pomodcri") }
        // 3 x 1,80 is not 5,90, and the OCR was unsure of "5,90": highlighted.
        assertEquals(Confidence.LOW, pomodori.lineTotalCents?.confidence)
    }

    @Test fun printedDocumentIsNotHandwritten() {
        val printed = page.map { l -> l.copy(confidence = 0.97f, words = l.words.map { it.copy(confidence = 0.97f) }) }
        val d = ReceiptParser.parsePages(listOf(printed), ParseOptions(today = LocalDate.of(2026, 10, 8)))
        assertFalse(d.handwritten)
        assertTrue(d.lineItems.none { it.nameDoubt })
        // Without any confidence from the engine nothing is said either way.
        assertFalse(Handwriting.known(listOf(page.map { l -> l.copy(confidence = 1f, words = l.words.map { it.copy(confidence = 1f) }) })))
    }

    @Test fun confidenceSurvivesStorage() {
        val back = OcrLineCodec.decode(OcrLineCodec.encode(listOf(page)))
        assertEquals(0.35f, back[0][2].words[0].confidence)
        assertEquals(1f, OcrLineCodec.decode("1,2,3,4,0.0\tX\t")[0][0].confidence)
    }
}
