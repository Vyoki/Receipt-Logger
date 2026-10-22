package com.kitchenreceipts.app

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.jobs.BundleScanner
import com.kitchenreceipts.app.ocr.PdfText
import com.kitchenreceipts.core.InvoiceBundle
import com.kitchenreceipts.core.TextLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The text of a PDF made on the phone comes back with its positions, cut into columns like the OCR's lines. */
@RunWith(AndroidJUnit4::class)
class PdfTextTest {

    private val app = ApplicationProvider.getApplicationContext<KitchenReceiptsApp>().also { it.container } // initialises the PDF reader

    @Test fun readsTheTextOfADigitalPdf() {
        val f = File(app.cacheDir, "pdftext-test.pdf")
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val p = Paint().apply { textSize = 10f; isAntiAlias = true }
        page.canvas.drawText("FATTURA N. B26 305511 DEL 03/09/2026", 40f, 100f, p)
        page.canvas.drawText("Mandorle pelate Kg. 1", 40f, 200f, p)
        page.canvas.drawText("1,000", 300f, 200f, p)
        page.canvas.drawText("10,69", 450f, 200f, p)
        pdf.finishPage(page)
        f.outputStream().use { pdf.writeTo(it) }
        pdf.close()

        val glyphs = PdfText.glyphs(f, 0)
        assertNotNull(glyphs)
        val lines = TextLayer.lines(PdfText.scaled(glyphs!!, 2400, 3396))
        assertTrue(lines.joinToString("\n") { it.text }, lines.any { it.text == "FATTURA N. B26 305511 DEL 03/09/2026" })
        val row = lines.filter { it.text in setOf("Mandorle pelate Kg. 1", "1,000", "10,69") }
        assertEquals(lines.joinToString("\n") { it.text }, 3, row.size)
        // Placed where printed: 40 pt of 595 → about 161 px of 2400.
        assertTrue(row.first { it.text.startsWith("Mandorle") }.left in 140..180)
    }

    private fun scanned(f: File, from: Int = 0, isActive: () -> Boolean = { true }): Pair<Int, Map<Int, List<TextLayer.Glyph>?>> {
        var count = -1
        val pages = linkedMapOf<Int, List<TextLayer.Glyph>?>()
        PdfText.scanPages(f, from, File(app.cacheDir, "pdfbox-test"), isActive, onOpen = { count = it }) { i, glyphs, aspect ->
            // A4 standing up.
            assertEquals(595.27f / 841.89f, aspect, 0.01f)
            pages[i] = glyphs
        }
        return count to pages
    }

    @Test fun lookingThroughTheFileOnceGivesTheSameCharactersAsOnePageAtATime() {
        val f = TestPdfs.write(File(app.cacheDir, "scan-3.pdf"), 3)
        val (count, pages) = scanned(f)
        assertEquals(3, count)
        assertEquals(listOf(0, 1, 2), pages.keys.toList())
        for (i in 0 until 3) {
            val one = PdfText.glyphs(f, i)
            assertNotNull(one)
            assertEquals("page ${i + 1}", one, pages[i])
            val text = TextLayer.lines(PdfText.scaled(one!!, 2400, 3394)).joinToString("\n") { it.text }
            assertTrue(text, text.replace(" ", "").contains("PAGINA${i + 1}"))
        }
        f.delete()
    }

    @Test fun stopsWhenAskedAndStartsFromAGivenPage() {
        val f = TestPdfs.write(File(app.cacheDir, "scan-stop.pdf"), 3)
        var asked = 0
        val (_, stopped) = scanned(f, isActive = { asked++ < 1 })
        assertEquals(listOf(0), stopped.keys.toList())
        // From = 2: the third page only.
        val (count, rest) = scanned(f, from = 2)
        assertEquals(3, count)
        assertEquals(listOf(2), rest.keys.toList())
        assertEquals(PdfText.glyphs(f, 2), rest[2])
        f.delete()
    }

    @Test fun aTurnedPageGivesNoCharacters() {
        val f = TestPdfs.write(File(app.cacheDir, "scan-turned.pdf"), 3, rotated = setOf(1))
        val (_, pages) = scanned(f)
        assertEquals(3, pages.size)
        assertNull(pages[1])
        assertNull(PdfText.glyphs(f, 1))
        assertTrue(!pages[0].isNullOrEmpty() && !pages[2].isNullOrEmpty())
        f.delete()
    }

    @Test fun aProtectedFileIsLockedAndABrokenOneDamaged() {
        val locked = TestPdfs.write(File(app.cacheDir, "scan-locked.pdf"), 3, userPassword = "prova-2031")
        assertThrows(PdfText.Locked::class.java) { scanned(locked) }
        val broken = File(app.cacheDir, "scan-broken.pdf").apply { writeText("%PDF-1.4\nnot really a PDF") }
        assertThrows(PdfText.Damaged::class.java) { scanned(broken) }
        locked.delete()
        broken.delete()
    }

    @Test fun theScanStopsWhenCancelledAndResumesFromItsCheckpoint() = runBlocking {
        val f = TestPdfs.write(File(app.cacheDir, "scan-resume.pdf"), 3)
        var saved: List<InvoiceBundle.PageSignals> = emptyList()
        var cancelled = false
        val job = launch(Dispatchers.Default) {
            try {
                BundleScanner.scan(f, null, onProgress = { page, _, _ -> if (page == 1) cancel() }, onCheckpoint = { saved = it })
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        job.join()
        assertTrue(cancelled)
        // The page looked through is handed out before stopping.
        assertEquals(1, saved.size)
        assertTrue(saved[0].hasText)
        val pagesSeen = mutableListOf<Int>()
        val result = BundleScanner.scan(f, null, previous = saved, onProgress = { page, of, _ -> pagesSeen += page; assertEquals(3, of) })
        assertEquals(listOf(2, 3), pagesSeen)
        assertTrue(result.complete)
        assertEquals(3, result.pageCount)
        assertEquals(listOf(0, 1, 2), result.signals.map { it.index })
        assertTrue(result.signals.all { it.hasText && it.rowTexts.isEmpty() })
        f.delete()
    }
}
