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
}
