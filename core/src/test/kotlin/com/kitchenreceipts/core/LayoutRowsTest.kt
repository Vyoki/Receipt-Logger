package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutRowsTest {

    @Test fun columnsReturnedSeparatelyAreRejoinedIntoRows() {
        // What an OCR engine may return: first the description column, then the amounts column.
        val lines = listOf(
            OcrLine("Mozzarella fior di latte kg", 10, 100, 400, 130),
            OcrLine("Ricotta vaccina kg", 10, 140, 300, 170),
            OcrLine("2,500 8,90 22,25", 500, 102, 700, 131),
            OcrLine("1,000 6,40 6,40", 500, 141, 700, 169),
            OcrLine("CASEIFICIO VALVERDE S.R.L.", 10, 10, 400, 40),
        )
        assertEquals(
            "CASEIFICIO VALVERDE S.R.L.\nMozzarella fior di latte kg 2,500 8,90 22,25\nRicotta vaccina kg 1,000 6,40 6,40",
            LayoutRows.toText(lines),
        )
    }

    @Test fun slightlySkewedRowStillGroups() {
        val lines = listOf(OcrLine("Burro", 10, 200, 100, 230), OcrLine("9,40", 600, 210, 680, 240))
        assertEquals("Burro 9,40", LayoutRows.toText(lines))
    }

    @Test fun stackedLinesInSameColumnStaySeparate() {
        val lines = listOf(OcrLine("Totale", 10, 100, 100, 130), OcrLine("Lotto 123", 10, 116, 120, 146))
        assertEquals(2, LayoutRows.rows(lines).size)
    }

    @Test fun parsesAfterRegrouping() {
        val lines = listOf(
            OcrLine("Zucchine kg", 10, 100, 300, 130),
            OcrLine("8 1,80 14,40", 500, 100, 700, 130),
        )
        val item = ReceiptParser.parseItemLine(LayoutRows.toText(lines))!!
        assertEquals(1440L, item.lineTotalCents?.value)
    }

    private fun tiltedTable(degrees: Double): List<OcrLine> {
        val slope = kotlin.math.tan(Math.toRadians(degrees))
        val rows = listOf("Mozzarella kg" to "2,500 8,90 22,25", "Ricotta kg" to "1,000 6,40 6,40", "Burro pz" to "4 2,35 9,40")
        return rows.flatMapIndexed { i, (desc, nums) ->
            val y = 100 + i * 45
            listOf(
                OcrLine(desc, 10, (y + 200 * slope).toInt(), 390, (y + 200 * slope).toInt() + 30),
                OcrLine(nums, 700, (y + 800 * slope).toInt(), 900, (y + 800 * slope).toInt() + 30),
            )
        }
    }

    @Test fun tiltedPhotoRowsAreStillJoined() {
        for (deg in listOf(2.0, -2.0, 3.5)) {
            assertEquals(
                "tilt $deg",
                "Mozzarella kg 2,500 8,90 22,25\nRicotta kg 1,000 6,40 6,40\nBurro pz 4 2,35 9,40",
                LayoutRows.toText(tiltedTable(deg)),
            )
        }
    }
}
