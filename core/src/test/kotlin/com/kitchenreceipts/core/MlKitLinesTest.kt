package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real on-device OCR boxes of an invented invoice page, photographed four ways, read by columns. */
class MlKitLinesTest {
    private val cases: Map<String, List<OcrLine>> = run {
        val out = linkedMapOf<String, MutableList<OcrLine>>()
        var name = ""
        javaClass.classLoader!!.getResource("fixtures/ocr_mlkit_lines_4_photos.txt")!!.readText().lines().forEach { l ->
            if (l.startsWith("# ")) { name = l.drop(2); out[name] = mutableListOf() }
            else if (l.contains(" | ")) {
                val (g, t) = l.split(" | ", limit = 2)
                val n = g.split(",")
                out.getValue(name) += OcrLine(t, n[0].toInt(), n[1].toInt(), n[2].toInt(), n[3].toInt(), n[4].toFloat())
            }
        }
        out
    }
    private val expected = listOf(345L, 109L, 1074L, 1954L, 12678L, 252L, 8180L, 6220L, 490L, 1132L, 1490L, 970L)

    @Test fun everyPhotoReadsEveryLine() {
        assertEquals(4, cases.size)
        for ((name, lines) in cases) {
            val table = TableReader.read(listOf(LayoutRows.layout(lines)))!!
            assertEquals(name, expected, table.map { it.lineTotalCents?.value })
            assertTrue(name, table.count { it.quantity != null && it.unitPrice != null } == 12)
            assertTrue(name, table.all { it.originalDescription.first().isLetter() }) // colli never in the name
            assertTrue(name, table.count { it.packages != null } >= 10)
            val doc = ReceiptParser.parsePages(listOf(lines))
            assertEquals(name, expected, doc.lineItems.map { it.lineTotalCents?.value })
        }
    }
}
