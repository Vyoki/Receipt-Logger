package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLayerTest {

    /** Characters of [text] from x, 20 px high, 12 px wide each (spaces become gaps, as in a PDF). */
    private fun put(text: String, x: Float, y: Float): List<TextLayer.Glyph> =
        text.mapIndexedNotNull { i, ch -> if (ch == ' ') null else TextLayer.Glyph(ch.toString(), x + i * 12, y, x + i * 12 + 11, y + 20) }

    private fun ocr(text: String, x: Int, y: Int) = OcrLine(text, x, y, x + text.length * 12, y + 20,
        words = text.split(' ').let { ws -> var at = x; ws.map { w -> OcrLine(w, at, y, at + w.length * 12, y + 20).also { at += (w.length + 1) * 12 } } })

    private val page = put("FATTURA N. B26 305511 DEL 03/09/2026", 100f, 300f) +
        put("Mandorle pelate Kg. 1", 100f, 400f) + put("KG", 600f, 400f) + put("1,000", 700f, 400f) + put("10,69", 900f, 400f) +
        put("Sconto del 4%", 100f, 440f) + put("-9,03", 900f, 440f) +
        put("TOTALE IMPONIBILE", 100f, 600f) + put("TOTALE DOCUMENTO", 700f, 600f) +
        put("231,89", 100f, 640f) + put("245,66", 700f, 640f)

    @Test fun linesAreCutAtColumnsAndWords() {
        val lines = TextLayer.lines(page)
        val row = lines.filter { it.top == 400 }.map { it.text }
        assertEquals(listOf("Mandorle pelate Kg. 1", "KG", "1,000", "10,69"), row)
        val first = lines.first { it.top == 400 }
        assertEquals(listOf("Mandorle", "pelate", "Kg.", "1"), first.words.map { it.text })
        assertEquals(listOf("TOTALE IMPONIBILE", "TOTALE DOCUMENTO"), lines.filter { it.top == 600 }.map { it.text })
    }

    @Test fun letterheadPictureComesFromTheOcr() {
        val text = TextLayer.lines(page)
        val seen = listOf(
            ocr("ABC S.r.l. - Via Roma 1", 100, 100), // the letterhead, a picture in the PDF
            ocr("Mandorle pe1ate Kg. 1", 100, 400), // the OCR's slip where the text layer is exact
            ocr("TOTALE IMPONIBILE", 100, 600), ocr("TOTALE DOCUMENTO", 700, 600),
            ocr("FATTURA N. B26 305511", 100, 300),
        )
        val merged = TextLayer.merge(text, seen)
        assertTrue(merged.any { it.text == "ABC S.r.l. - Via Roma 1" })
        assertTrue(merged.any { it.text == "Mandorle pelate Kg. 1" })
        assertFalse(merged.any { it.text.contains("pe1ate") })
    }

    @Test fun textThatIsNotWhatIsPrintedIsNotUsed() {
        // A font without a proper character map: the text layer is letters, but not the printed ones.
        val nonsense = TextLayer.lines(page.map { it.copy(text = (('a' + (it.text[0].code % 26)).toString())) })
        val seen = listOf(
            ocr("FATTURA N. B26 305511 DEL 03/09/2026", 100, 300), ocr("Mandorle pelate Kg. 1", 100, 400),
            ocr("Sconto del 4%", 100, 440), ocr("TOTALE IMPONIBILE", 100, 600), ocr("TOTALE DOCUMENTO", 700, 600),
        )
        assertFalse(TextLayer.agrees(nonsense, seen))
        assertEquals(seen, TextLayer.merge(nonsense, seen))
        assertTrue(TextLayer.agrees(TextLayer.lines(page), seen))
    }
}
