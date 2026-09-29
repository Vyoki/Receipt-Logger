package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLineCodecTest {
    @Test fun roundTrip() {
        val pages = listOf(
            listOf(
                OcrLine("PANE\tCASERECCIO 2,50", 10, 20, 200, 40, 1.5f, listOf(OcrLine("PANE", 10, 20, 60, 40), OcrLine("2,50", 150, 20, 200, 40))),
                OcrLine("TOTALE 2,50", 10, 60, 200, 80),
            ),
            listOf(OcrLine("PAGINA 2", 5, 5, 50, 20)),
        )
        val back = OcrLineCodec.decode(OcrLineCodec.encode(pages))
        assertEquals(2, back.size)
        assertEquals("PANE CASERECCIO 2,50", back[0][0].text)
        assertEquals(1.5f, back[0][0].angle)
        assertEquals(listOf("PANE", "2,50"), back[0][0].words.map { it.text })
        assertEquals(150, back[0][0].words[1].left)
        assertEquals("PAGINA 2", back[1].single().text)
        assertTrue(OcrLineCodec.decode("").isEmpty())
    }

    @Test fun imagePlanCutsTheBackgroundAndKeepsPrintLegible() {
        // A 4000x3000 photo: paper from x=500..3500, y=400..2600, text lines 44 px tall.
        val lines = (0 until 30).map { i -> OcrLine("RIGA $i ARTICOLO 1,00", 600, 500 + i * 70, 3400, 544 + i * 70) }
        val p = AiImagePlan.plan(lines, 4000, 3000)
        assertTrue(p.left in 400..600 && p.right in 3400..3600)
        assertTrue(p.top in 400..500)
        assertEquals(0.5, p.scale, 0.001)          // 44 px text -> 22 px
        assertTrue(p.outWidth <= AiImagePlan.MAX_SIDE)
        // Tiny text is never enlarged, and very large text is not shrunk below the minimum size.
        val small = AiImagePlan.plan(lines.map { it.copy(bottom = it.top + 12) }, 4000, 3000)
        assertEquals(minOf(1.0, AiImagePlan.MAX_SIDE / 3000.0), small.scale, 0.02)
    }
}
