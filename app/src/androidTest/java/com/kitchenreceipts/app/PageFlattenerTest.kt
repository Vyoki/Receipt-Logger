package com.kitchenreceipts.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.ocr.PageFlattener
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Scanner-style flattening on a synthetic photo: a sheet at an angle on a dark table. */
@RunWith(AndroidJUnit4::class)
class PageFlattenerTest {

    private fun photo(background: Int): Bitmap {
        val bmp = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(background)
        val sheet = Path().apply { moveTo(280f, 120f); lineTo(1320f, 180f); lineTo(1440f, 1120f); lineTo(160f, 1080f); close() }
        c.drawPath(sheet, Paint().apply { color = Color.rgb(235, 235, 230); isAntiAlias = true })
        val ink = Paint().apply { color = Color.rgb(30, 30, 30); textSize = 36f; isAntiAlias = true }
        for (i in 0 until 14) c.drawText("PRODOTTO DI PROVA $i   NR 2,000   1,500   3,00", 330f, 240f + i * 60f, ink)
        return bmp
    }

    @Test fun flattensASheetOnADarkTable() {
        val flat = PageFlattener.flatten(photo(Color.rgb(60, 45, 35)))
        assertNotNull(flat)
        flat!!
        // The table is cut away: the corners of the result are paper, not the dark background.
        listOf(flat.getPixel(20, 20), flat.getPixel(flat.width - 20, 20), flat.getPixel(20, flat.height - 20), flat.getPixel(flat.width - 20, flat.height - 20))
            .forEach { px -> assertTrue("corner is not paper: ${Integer.toHexString(px)}", Color.red(px) > 150) }
        assertTrue(flat.width in 1100..1600 && flat.height in 800..1200)
    }

    @Test fun leavesAPageOnAWhiteTableAlone() {
        assertNull(PageFlattener.flatten(photo(Color.rgb(228, 228, 228))))
    }
}
