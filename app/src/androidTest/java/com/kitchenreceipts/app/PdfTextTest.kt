package com.kitchenreceipts.app

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.ocr.PdfText
import com.kitchenreceipts.core.TextLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The text of a PDF made on the phone comes back with its positions, cut into columns like the OCR's lines. */
@RunWith(AndroidJUnit4::class)
class PdfTextTest {

    @Test fun readsTheTextOfADigitalPdf() {
        val app = ApplicationProvider.getApplicationContext<KitchenReceiptsApp>()
        app.container // initialises the PDF reader
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
}
