package com.kitchenreceipts.app.ocr

import com.kitchenreceipts.core.TextLayer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * The text a digital PDF carries, character by character with its position (see TextLayer). Read on the phone;
 * nothing leaves it.
 */
object PdfText {

    /**
     * The characters of page [pageIndex], each as a share of the page's width and height (0..1, top-left origin),
     * so they can be placed on an image of the page of any size. Empty for a page without text (a scan); null when
     * the PDF cannot be read this way (protected, damaged, a turned page).
     */
    fun glyphs(file: File, pageIndex: Int): List<TextLayer.Glyph>? = runCatching {
        PDDocument.load(file).use { doc ->
            if (pageIndex !in 0 until doc.numberOfPages) return@use null
            val page = doc.getPage(pageIndex)
            // A turned page: its text runs another way than the image the OCR reads. The OCR alone, as before.
            if (page.rotation % 360 != 0) return@use null
            val box = page.cropBox
            val w = box.width.takeIf { it > 0 } ?: return@use null
            val h = box.height.takeIf { it > 0 } ?: return@use null
            val out = ArrayList<TextLayer.Glyph>()
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                    textPositions?.forEach { p ->
                        val s = p.unicode ?: return@forEach
                        if (p.dir != 0f) return@forEach // vertical text in a margin: not part of the reading
                        val size = p.fontSizeInPt.takeIf { it > 0f } ?: p.heightDir.takeIf { it > 0f } ?: return@forEach
                        val x = p.xDirAdj
                        val base = p.yDirAdj
                        out += TextLayer.Glyph(s, x / w, (base - 0.8f * size) / h, (x + p.widthDirAdj) / w, (base + 0.2f * size) / h)
                    }
                }
            }
            stripper.sortByPosition = true
            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1
            stripper.getText(doc)
            out
        }
    }.getOrNull()

    /** [glyphs] placed on an image [width] x [height] of the page. */
    fun scaled(glyphs: List<TextLayer.Glyph>, width: Int, height: Int): List<TextLayer.Glyph> =
        glyphs.map { g -> TextLayer.Glyph(g.text, g.left * width, g.top * height, g.right * width, g.bottom * height) }
}
