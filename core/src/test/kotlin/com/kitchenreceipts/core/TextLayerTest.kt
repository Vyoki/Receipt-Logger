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

    @Test fun theTextAloneIsUsedOnlyWhenItReadsLikeADocument() {
        // A page that draws no picture is read from its own text alone: it must read like a document.
        assertTrue(TextLayer.readsAlone(TextLayer.lines(page)))
        // A font that maps its letters to others: the same page, every letter shifted, names no word documents print.
        val shifted = page.map { g -> g.copy(text = g.text.map { c -> if (c.isLetter()) c + 1 else c }.joinToString("")) }
        assertFalse(TextLayer.readsAlone(TextLayer.lines(shifted)))
        // Too little text (a page number).
        assertFalse(TextLayer.readsAlone(TextLayer.lines(put("Pag. 1", 100f, 100f))))
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

    @Test fun aTextLayerThatOverlapsNoneOfTheOcrIsNotUsed() {
        // The same page read by the camera, but the hidden text sits far lower on the page (the scan was straightened after).
        val shifted = TextLayer.lines(page.map { g -> g.copy(top = g.top + 1500, bottom = g.bottom + 1500) })
        val seen = listOf(ocr("FATTURA N. B26 305511 DEL 03/09/2026", 100, 300), ocr("Mandorle pelate Kg. 1", 100, 400), ocr("TOTALE IMPONIBILE", 100, 600))
        assertFalse(TextLayer.agrees(shifted, seen))
        // A page the camera read nothing on still takes the text.
        assertTrue(TextLayer.agrees(TextLayer.lines(page), emptyList()))
    }

    @Test fun textPrintedOverOtherTextIsTwoWords() {
        // "12,50" and "MISURABILE" drawn on one baseline, the word starting over the price's last digit: read left to
        // right their letters would interleave ("12,5M0ISURABILE"). They are two words; the row stays one row.
        fun run(text: String, left: Float, cw: Float) = text.mapIndexed { i, c -> TextLayer.Glyph(c.toString(), left + i * cw, 100f, left + (i + 1) * cw, 130f) }
        val glyphs = run("1,00", 300f, 19f) + run("12,50", 583f, 19f) + run("MISURABILE", 645f, 21f) + run("22,00", 985f, 19f) + run("12,50", 1279f, 19f)
        val words = TextLayer.lines(glyphs).flatMap { l -> l.words.ifEmpty { listOf(l) } }.map { it.text }
        assertTrue(words.toString(), "12,50" in words && "MISURABILE" in words)
        // Letters tucked under the one before (kerning, "AV") stay one word.
        val kerned = listOf(TextLayer.Glyph("A", 100f, 100f, 122f, 130f), TextLayer.Glyph("V", 119f, 100f, 141f, 130f), TextLayer.Glyph("E", 141f, 100f, 160f, 130f))
        assertEquals(listOf("AVE"), TextLayer.lines(kerned).map { it.text })
    }

    /**
     * The glyphs the phone's PDF reader gives for a PDF made on the phone (PdfTextTest, CI run 142), in its order: boxes
     * overlap their neighbours a little, by different amounts. Ordinary text: every word reads as printed.
     */
    @Test fun thePhonesPdfGlyphsReadAsPrinted() {
        fun g(t: String, l: Float, r: Float, top: Float, b: Float) = TextLayer.Glyph(t, l, top, r, b)
        val row = listOf(g("M", 161.3f, 196.6f, 774f, 815f), g("a", 197.6f, 219.6f, 774f, 815f), g("n", 217.8f, 240.1f, 774f, 815f), g("d", 242.0f, 264.8f, 774f, 815f), g("o", 266.2f, 289.2f, 774f, 815f), g("r", 290.4f, 304.1f, 774f, 815f), g("l", 302.5f, 312.3f, 774f, 815f), g("e", 310.6f, 332.0f, 774f, 815f), g(" ", 330.8f, 340.8f, 774f, 815f), g("p", 338.8f, 361.5f, 774f, 815f), g("e", 363.0f, 384.4f, 774f, 815f), g("l", 383.2f, 393.0f, 774f, 815f), g("a", 391.3f, 413.2f, 774f, 815f), g("t", 411.4f, 424.6f, 774f, 815f), g("e", 423.5f, 444.9f, 774f, 815f), g(" ", 443.7f, 453.7f, 774f, 815f), g("K", 451.8f, 477.1f, 774f, 815f), g("g", 475.4f, 498.1f, 774f, 815f), g(".", 499.6f, 510.3f, 774f, 815f), g(" ", 511.7f, 521.8f, 774f, 815f), g("1", 519.8f, 542.5f, 774f, 815f), g("1", 1210.1f, 1232.8f, 774f, 815f), g(",", 1234.3f, 1242.2f, 774f, 815f), g("0", 1242.4f, 1265.0f, 774f, 815f), g("0", 1266.6f, 1289.2f, 774f, 815f), g("0", 1290.8f, 1313.4f, 774f, 815f), g("1", 1815.1f, 1837.8f, 774f, 815f), g("0", 1839.3f, 1862.0f, 774f, 815f), g(",", 1863.5f, 1871.5f, 774f, 815f), g("6", 1871.6f, 1894.3f, 774f, 815f), g("9", 1895.8f, 1918.5f, 774f, 815f))
        val head = listOf(g("F", 161.3f, 183.6f, 371f, 411f), g("A", 182.2f, 208.5f, 371f, 411f), g("T", 207.9f, 231.9f, 371f, 411f), g("T", 232.4f, 256.5f, 371f, 411f), g("U", 256.6f, 282.7f, 371f, 411f), g("R", 280.8f, 305.6f, 371f, 411f), g("A", 305.0f, 331.3f, 371f, 411f), g(" ", 333.2f, 343.2f, 371f, 411f), g("N", 341.3f, 370.1f, 371f, 411f), g(".", 369.5f, 380.2f, 371f, 411f), g(" ", 381.6f, 391.6f, 371f, 411f), g("B", 389.7f, 414.8f, 371f, 411f), g("2", 413.9f, 436.6f, 371f, 411f), g("6", 438.1f, 460.8f, 371f, 411f), g(" ", 462.3f, 472.3f, 371f, 411f), g("3", 470.4f, 493.0f, 371f, 411f), g("0", 494.6f, 517.2f, 371f, 411f), g("5", 518.8f, 541.4f, 371f, 411f), g("5", 543.0f, 565.6f, 371f, 411f), g("1", 567.2f, 589.8f, 371f, 411f), g("1", 591.4f, 614.1f, 371f, 411f), g(" ", 615.6f, 625.6f, 371f, 411f), g("D", 623.7f, 650.1f, 371f, 411f), g("E", 651.9f, 674.8f, 371f, 411f), g("L", 676.1f, 697.8f, 371f, 411f), g(" ", 696.3f, 706.3f, 371f, 411f), g("0", 704.3f, 727.0f, 371f, 411f), g("3", 728.5f, 751.2f, 371f, 411f), g("/", 752.7f, 769.4f, 371f, 411f), g("0", 768.9f, 791.5f, 371f, 411f), g("9", 793.1f, 815.7f, 371f, 411f), g("/", 817.3f, 833.9f, 371f, 411f), g("2", 833.4f, 856.1f, 371f, 411f), g("0", 857.6f, 880.3f, 371f, 411f))
        assertEquals(listOf("Mandorle pelate Kg. 1", "1,000", "10,69"), TextLayer.lines(row).map { it.text })
        // (the dump stops at "2026"'s "20": the rest of the line is not needed)
        assertEquals(listOf("FATTURA N. B26 305511 DEL 03/09/20"), TextLayer.lines(head).map { it.text })
    }
}
