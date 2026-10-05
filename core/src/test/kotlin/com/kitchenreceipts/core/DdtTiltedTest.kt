package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A delivery note photographed about 2.5 degrees off (geometry of a real photo, content invented; see
 * fixtures/ddt_tilted_sections_boxes.txt): over the page width the tilt is a whole row, section headings
 * ("Merce non deperibile - Congelato", read "Nerce") sit between the lines, a lot under each line, the 22% row of the
 * VAT summary stained ("28,9 )"), the number glued to the date, and an article code with a Z for a 2.
 */
class DdtTiltedTest {

    private fun pages(): List<List<OcrLine>> {
        val text = javaClass.classLoader!!.getResource("fixtures/ddt_tilted_sections_boxes.txt")!!.readText()
        val pages = mutableListOf<MutableList<OcrLine>>()
        val word = Regex("(-?\\d+)-(-?\\d+):(\\S+)")
        for (l in text.lines()) {
            if (l.startsWith("=== Raw lines")) { pages += mutableListOf<OcrLine>(); continue }
            if (l.startsWith("#") || !l.contains(" | ")) continue
            val (g, rest) = l.split(" | ", limit = 2)
            val n = g.split(",")
            val bracket = rest.lastIndexOf("  [")
            val boxed = bracket >= 0 && rest.endsWith("]")
            val words = if (boxed) rest.substring(bracket + 3, rest.length - 1).split(' ').mapNotNull { w ->
                word.matchEntire(w)?.let { m -> OcrLine(m.groupValues[3], m.groupValues[1].toInt(), n[1].toInt(), m.groupValues[2].toInt(), n[3].toInt()) }
            } else emptyList()
            pages.last() += OcrLine(if (boxed) rest.substring(0, bracket) else rest, n[0].toInt(), n[1].toInt(), n[2].toInt(), n[3].toInt(), n[4].toFloat(), words)
        }
        return pages
    }

    @Test fun everyLineWithItsOwnNumbers() {
        val d = ReceiptParser.parsePages(pages(), ParseOptions(null, "09876543217"))
        // The amounts printed at the right end of each row, not on the section heading above it.
        assertEquals(listOf(9536L, 2646L, 527L, 1021L, 844L, 422L, 4356L, 1624L), d.lineItems.map { it.lineTotalCents?.value })
        assertTrue(d.lineItems.none { ReceiptParser.isSectionHeading(it.originalDescription) })
        assertEquals("23741", d.lineItems[4].itemCode)
        // The stained taxable amount comes from the total taxable minus the other rows, proven by the printed VAT.
        assertEquals(3, d.vatChecks.size)
        assertTrue(d.vatChecks.all { it.ok })
        assertEquals("B26 305511", d.documentNumber?.value)
        assertEquals(LocalDate.of(2026, 9, 15), d.documentDate?.value)
        assertEquals(23127L, d.totalCents?.value)
    }
}
