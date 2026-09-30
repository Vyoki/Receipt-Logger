package com.kitchenreceipts.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCornersTest {
    private val w = 400
    private val h = 300

    /** A dark table with a sheet of paper photographed at an angle (a trapezoid), with lines of dark text on it. */
    private fun photo(corners: List<PageCorners.Pt>, background: Int = 45, paper: Int = 215): IntArray {
        val img = IntArray(w * h) { background }
        fun inside(x: Double, y: Double): Boolean {
            var sign = 0
            for (i in 0 until 4) {
                val a = corners[i]; val b = corners[(i + 1) % 4]
                val c = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
                val s = if (c > 0) 1 else if (c < 0) -1 else 0
                if (s != 0) { if (sign == 0) sign = s else if (s != sign) return false }
            }
            return true
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (!inside(x.toDouble(), y.toDouble())) continue
            // Text: thin dark rows every 12 px, broken into words.
            val text = y % 12 in 5..6 && (x / 25) % 2 == 0
            img[y * w + x] = if (text) 30 else paper
        }
        return img
    }

    private fun near(a: PageCorners.Pt, b: PageCorners.Pt) = a.dist(b) < 4.0

    @Test fun findsATiltedPage() {
        val truth = listOf(PageCorners.Pt(70.0, 30.0), PageCorners.Pt(330.0, 45.0), PageCorners.Pt(360.0, 280.0), PageCorners.Pt(40.0, 270.0))
        val q = PageCorners.find(photo(truth), w, h)
        assertNotNull(q)
        q!!.points.zip(truth).forEach { (found, t) -> assertTrue("found $found expected $t", near(found, t)) }
        assertTrue(q.outWidth > 290 && q.outHeight > 230)
    }

    @Test fun whitePaperOnAWhiteTableIsLeftAlone() {
        val truth = listOf(PageCorners.Pt(70.0, 30.0), PageCorners.Pt(330.0, 45.0), PageCorners.Pt(360.0, 280.0), PageCorners.Pt(40.0, 270.0))
        assertNull(PageCorners.find(photo(truth, background = 200, paper = 215), w, h))
    }

    @Test fun pageFillingThePhotoIsLeftAlone() {
        val full = listOf(PageCorners.Pt(0.0, 0.0), PageCorners.Pt(399.0, 0.0), PageCorners.Pt(399.0, 299.0), PageCorners.Pt(0.0, 299.0))
        assertNull(PageCorners.find(photo(full), w, h))
    }

    @Test fun smallBrightThingIsNotAPage() {
        val small = listOf(PageCorners.Pt(10.0, 10.0), PageCorners.Pt(90.0, 10.0), PageCorners.Pt(90.0, 60.0), PageCorners.Pt(10.0, 60.0))
        assertNull(PageCorners.find(photo(small), w, h))
    }
}
