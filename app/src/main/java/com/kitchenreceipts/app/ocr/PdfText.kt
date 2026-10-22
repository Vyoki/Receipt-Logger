package com.kitchenreceipts.app.ocr

import com.kitchenreceipts.core.TextLayer
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.IOException

/**
 * The text a digital PDF carries, character by character with its position (see TextLayer). Read on the phone;
 * nothing leaves it.
 */
object PdfText {

    /** Why a PDF cannot be looked through at all. */
    sealed class Problem(message: String, cause: Throwable?) : IOException(message, cause)

    /** Protected by a password. */
    class Locked(cause: Throwable? = null) : Problem("The PDF is protected by a password", cause)

    /** Not a PDF that can be opened (cut short, broken). */
    class Damaged(cause: Throwable? = null) : Problem("The PDF cannot be opened", cause)

    /**
     * The characters of page [pageIndex], each as a share of the page's width and height (0..1, top-left origin),
     * so they can be placed on an image of the page of any size. Empty for a page without text (a scan); null when
     * the PDF cannot be read this way (protected, damaged, a turned page).
     */
    fun glyphs(file: File, pageIndex: Int): List<TextLayer.Glyph>? = runCatching {
        PDDocument.load(file).use { doc ->
            if (pageIndex !in 0 until doc.numberOfPages) null else pageGlyphs(doc, pageIndex)
        }
    }.getOrNull()

    /**
     * Every page from [from] on, with the file opened once (a file of hundreds of invoices is looked through in
     * seconds; opening it for each page would take minutes). [onOpen] gets the number of pages first; [onPage] then
     * gets each page's characters as [glyphs] gives them (null for a turned or unreadable page) and the page's shape
     * (width / height). Stops before the next page when [isActive] turns false. The file's own data is kept in
     * temporary files under [tempDir] (when given) instead of memory. Throws [Locked] or [Damaged] when the file
     * cannot be opened.
     */
    fun scanPages(
        file: File,
        from: Int = 0,
        tempDir: File? = null,
        isActive: () -> Boolean = { true },
        onOpen: (pageCount: Int) -> Unit = {},
        onPage: (index: Int, glyphs: List<TextLayer.Glyph>?, aspect: Float) -> Unit,
    ) {
        val memory = if (tempDir != null && (tempDir.isDirectory || tempDir.mkdirs())) {
            MemoryUsageSetting.setupTempFileOnly().setTempDir(tempDir)
        } else {
            MemoryUsageSetting.setupMainMemoryOnly()
        }
        val doc = try {
            PDDocument.load(file, memory)
        } catch (e: InvalidPasswordException) {
            throw Locked(e)
        } catch (e: IOException) {
            throw Damaged(e)
        } catch (e: RuntimeException) {
            throw Damaged(e)
        }
        doc.use {
            val count = doc.numberOfPages
            onOpen(count)
            for (i in from.coerceAtLeast(0) until count) {
                if (!isActive()) return
                val aspect = runCatching {
                    val box = doc.getPage(i).cropBox
                    if (box.width > 0 && box.height > 0) box.width / box.height else null
                }.getOrNull() ?: A4_ASPECT
                onPage(i, pageGlyphs(doc, i), aspect)
            }
        }
    }

    /** One page's characters (see [glyphs]), from a document already open. */
    private fun pageGlyphs(doc: PDDocument, pageIndex: Int): List<TextLayer.Glyph>? = runCatching {
        val page = doc.getPage(pageIndex)
        // A turned page: its text runs another way than the image the OCR reads. The OCR alone, as before.
        if (page.rotation % 360 != 0) return@runCatching null
        val box = page.cropBox
        val w = box.width.takeIf { it > 0 } ?: return@runCatching null
        val h = box.height.takeIf { it > 0 } ?: return@runCatching null
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
    }.getOrNull()

    /** [glyphs] placed on an image [width] x [height] of the page. */
    fun scaled(glyphs: List<TextLayer.Glyph>, width: Int, height: Int): List<TextLayer.Glyph> =
        glyphs.map { g -> TextLayer.Glyph(g.text, g.left * width, g.top * height, g.right * width, g.bottom * height) }

    /** The shape of an A4 page standing up, for a page whose own size cannot be read. */
    private const val A4_ASPECT = 595f / 842f
}
