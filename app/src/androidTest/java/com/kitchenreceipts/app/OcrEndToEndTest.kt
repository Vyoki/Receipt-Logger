package com.kitchenreceipts.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.ocr.MlKitOcrEngine
import com.kitchenreceipts.core.LayoutRows
import com.kitchenreceipts.core.ReceiptParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End to end with the real on-device OCR: draws page 1 of a cash & carry invoice (invented content,
 * same layout), warps it like a phone photo taken at an angle, runs ML Kit, rebuilds rows, parses.
 * The full OCR output is written to files/ocr-e2e.txt so CI can publish it.
 */
@RunWith(AndroidJUnit4::class)
class OcrEndToEndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val columnsX = listOf(60f, 160f, 230f, 900f, 1040f, 1130f, 1260f, 1360f)
    private val table = listOf(
        listOf("1000001", "1x1", "BISCOTTI FROLLINI 800 - MARCA A", "SK GR 800", "1", "3,450", "3,45", "10"),
        listOf("1000002", "1x1", "FETTE BISCOTTATE GR.250 - MARCA B", "SC GR 250", "1", "1,090", "1,09", "04"),
        listOf("O 1000003", "2x3", "CANDEGGINA NORMALE LT.5 - MARCA C", "FL LT 5", "6", "1,790", "10,74", "22"),
        listOf("1000004", "1", "FILONE SUINO SV - .", "NC KG", "4,45", "4,390", "19,54", "10"),
        listOf("O 1000005", "1", "FILETTO B/A KG 3,5+ S/V - .", "CS KG", "4,24", "29,900", "126,78", "10"),
        listOf("1000006", "1x6", "ACQUA MINERALE NAT.1,5 - MARCA D", "PT CL 150", "6", "0,420", "2,52", "22"),
        listOf("1000007", "2x1", "UOVA MEDIE 180 - MARCA E", "VA KG 11.3", "2", "40,900", "81,80", "10"),
        listOf("1000008", "1x4", "PARMIGIANO DOP 15M S/V 800", "KG 0.8", "4", "15,550", "62,20", "04"),
        listOf("1000009", "1", "SALAMELLA DOLCE", "CF GR", "0,48", "10,210", "4,90", "10"),
        listOf("1000010", "1x1", "CARBONE VEGETALE KG.10", "NC PZ 1", "1", "11,320", "11,32", "22"),
        listOf("1000012", "1x10", "CIPOLLA ROSSA KG1X16 CRT - PG", "NC KG 1", "10", "1,490", "14,90", "04"),
        listOf("1000013", "1x2", "RICOTTA KG.1,5 - MARCA F", "CF GR 1500", "2", "4,850", "9,70", "04"),
    )
    private val expectedTotals = listOf(345L, 109L, 1074L, 1954L, 12678L, 252L, 8180L, 6220L, 490L, 1132L, 1490L, 970L)

    private fun renderPage(): Bitmap {
        val page = Bitmap.createBitmap(1440, 700, Bitmap.Config.ARGB_8888)
        val c = Canvas(page)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 21f; typeface = Typeface.SANS_SERIF }
        val bold = Paint(p).apply { typeface = Typeface.DEFAULT_BOLD }
        c.drawText("ABC S.r.l.", 60f, 50f, bold)
        c.drawText("Reg. Imp. PF, C.F. - P.IVA 01234567897", 60f, 80f, p)
        c.drawText("SPETTABILE", 800f, 50f, p)
        c.drawText("RISTORANTE PROVA SAS", 800f, 80f, bold)
        c.drawText("TIPO DOCUMENTO", 60f, 130f, p); c.drawText("N.RO DOCUMENTO", 420f, 130f, p); c.drawText("DATA DOCUMENTO", 700f, 130f, p)
        c.drawText("COPIA FATTURA", 60f, 158f, bold); c.drawText("12A/34567", 440f, 158f, bold); c.drawText("23/09/2026", 720f, 158f, bold)
        listOf("CODICE", "COLLI", "DESCRIZIONE BENI", "TIPO CONF.", "TOT.", "PREZZO", "IMPORTO", "COD").forEachIndexed { i, h ->
            c.drawText(h, columnsX[i], 205f, p)
        }
        table.forEachIndexed { r, row ->
            val y = 240f + r * 30f
            row.forEachIndexed { i, t -> c.drawText(t, columnsX[i], y, p) }
        }
        return page
    }

    /** Places the page on a grey background as a trapezoid: taken from slightly above and to the side. */
    private fun photograph(page: Bitmap, keystone: Boolean, rotateDeg: Float = 0f): Bitmap {
        val out = Bitmap.createBitmap(1560, 820, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(80, 80, 80))
        val m = Matrix()
        val w = page.width.toFloat(); val h = page.height.toFloat()
        val dst = if (keystone) floatArrayOf(90f, 70f, 1480f, 30f, 1530f, 790f, 20f, 760f) else floatArrayOf(60f, 60f, 1500f, 60f, 1500f, 760f, 60f, 760f)
        m.setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, dst, 0, 4)
        if (rotateDeg != 0f) m.postRotate(rotateDeg, out.width / 2f, out.height / 2f)
        c.drawBitmap(page, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun runCase(name: String, keystone: Boolean, rotateDeg: Float = 0f): Int = runBlocking {
        val lines = MlKitOcrEngine().recognize(photograph(renderPage(), keystone, rotateDeg))
        val text = LayoutRows.toText(lines)
        val d = ReceiptParser.parse(text)
        val found = d.lineItems.mapNotNull { it.lineTotalCents?.value }
        val matched = expectedTotals.count { it in found }
        File(context.filesDir, "ocr-e2e.txt").appendText(buildString {
            append("===== $name: ${d.lineItems.size} items, $matched/${expectedTotals.size} expected totals found\n")
            append("seller=${d.sellerName?.value} number=${d.documentNumber?.value} date=${d.documentDate?.value}\n")
            d.lineItems.forEach { append("ITEM ${it.originalDescription} | q=${it.quantity?.value} ${it.unit?.value} p=${it.unitPrice?.value} t=${it.lineTotalCents?.value} vat=${it.vatRatePercent?.value}\n") }
            append("--- rows\n").append(text).append('\n')
            append("--- raw OCR lines (left,top,right,bottom,angle)\n")
            lines.forEach { append("${it.left},${it.top},${it.right},${it.bottom},${it.angle} | ${it.text}\n") }
        })
        matched
    }

    @Test fun straightPhoto() {
        File(context.filesDir, "ocr-e2e.txt").delete()
        val matched = runCase("straight", keystone = false)
        assertTrue("only $matched of ${expectedTotals.size} lines read correctly (see ocr-e2e.txt)", matched >= 10)
    }

    @Test fun angledPhoto() {
        val matched = runCase("angled", keystone = true)
        assertTrue("only $matched of ${expectedTotals.size} lines read correctly (see ocr-e2e.txt)", matched >= 10)
    }

    @Test fun angledAndRotatedPhoto() {
        val matched = runCase("angled + rotated 3°", keystone = true, rotateDeg = 3f)
        assertTrue("only $matched of ${expectedTotals.size} lines read correctly (see ocr-e2e.txt)", matched >= 10)
    }
}
