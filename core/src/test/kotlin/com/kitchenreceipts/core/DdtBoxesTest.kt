package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A delivery note photographed with a phone, as ML Kit returns it (line and word boxes, content invented; see
 * fixtures/ddt_surgelati_boxes.txt): column headings split over several boxes and not recognised as a table header,
 * "NUMERO" with the customer's postcode and town under it, the number and date further down, lots on the row under
 * each item (one with the item's amount on the same row), "4,900" read for 4,000, the conservation letter after the
 * quantity, colli glued to the description ("7/CINGHIALE", "2/0LIO"), "4.02" with a dot in the VAT summary.
 */
class DdtBoxesTest {

    private fun pages(): List<List<OcrLine>> {
        val text = javaClass.classLoader!!.getResource("fixtures/ddt_surgelati_boxes.txt")!!.readText()
        val pages = mutableListOf<MutableList<OcrLine>>()
        val word = Regex("(-?\\d+)-(-?\\d+):(\\S+)")
        for (l in text.lines()) {
            if (l.startsWith("=== Raw lines")) { pages += mutableListOf<OcrLine>(); continue }
            if (l.startsWith("#") || !l.contains(" | ")) continue
            val (g, rest) = l.split(" | ", limit = 2)
            val n = g.split(",")
            val top = n[1].toInt()
            val bottom = n[3].toInt()
            val bracket = rest.lastIndexOf("  [")
            val boxed = bracket >= 0 && rest.endsWith("]")
            val words = if (boxed) {
                rest.substring(bracket + 3, rest.length - 1).split(' ').mapNotNull { w ->
                    word.matchEntire(w)?.let { m -> OcrLine(m.groupValues[3], m.groupValues[1].toInt(), top, m.groupValues[2].toInt(), bottom) }
                }
            } else emptyList()
            pages.last() += OcrLine(if (boxed) rest.substring(0, bracket) else rest, n[0].toInt(), top, n[2].toInt(), bottom, n[4].toFloat(), words)
        }
        return pages
    }

    private val options = ParseOptions(null, "09876543217")
    private fun parse() = ReceiptParser.parsePages(pages(), options)

    @Test fun headerNumberIsNotThePostcode() {
        val d = parse()
        assertEquals("VERDE FRESCO S.p.A.", d.sellerName?.value)
        assertEquals("B26 204177", d.documentNumber?.value)
        assertEquals(Confidence.HIGH, d.documentNumber?.confidence)
        assertEquals(LocalDate.of(2026, 9, 22), d.documentDate?.value)
        assertEquals(26459L, d.totalCents?.value)
        assertEquals(24602L, d.subtotalCents?.value)
    }

    @Test fun plausibleNumbers() {
        assertFalse(ReceiptParser.plausibleDocNumber("00100 ROMA"))
        assertFalse(ReceiptParser.plausibleDocNumber("09876543217"))
        assertFalse(ReceiptParser.plausibleDocNumber("VIA ROMA 12"))
        assertTrue(ReceiptParser.plausibleDocNumber("B26 204177"))
    }

    @Test fun linesLotsAndChecks() {
        val d = parse()
        assertEquals(7, d.lineItems.size)
        assertEquals(listOf(5808L, 5722L, 1893L, 3828L, 1126L, 1869L, 4356L), d.lineItems.map { it.lineTotalCents?.value })
        // Every lot under the "ID LOTTO" heading, including "792983" printed on the row with the amount.
        assertEquals(listOf("789431", "792983", "B2611-745", "791891", "B2611-742", "797512", "792620"), d.lineItems.map { it.lotNumber?.value })
        assertTrue(d.lineItems.all { it.lotNumber?.confidence == Confidence.HIGH })
        // "4,900" for 4,000: amount / price says 4, proven by the VAT summary.
        val pepper = d.lineItems[2]
        assertEquals(0, BigDecimal(4).compareTo(pepper.quantity!!.value))
        // TORTA's VAT rate was printed out of place: the only rate that makes both VAT groups add up.
        assertEquals(0, BigDecimal(10).compareTo(d.lineItems[1].vatRatePercent!!.value))
        assertEquals(2, d.vatChecks.size)
        assertTrue(d.vatChecks.all { it.ok })
        // Colli: "7/CINGHIALE" is 1 (the total printed is "N. COLLI 10").
        assertEquals(10, d.lineItems.sumOf { it.packages?.value?.toIntOrNull() ?: 0 })
        assertEquals("1", d.lineItems[0].packages?.value)
        assertTrue(ReceiptParser.isConfident(d))
    }

    @Test fun photoStripIsSmallAndAroundTheValue() {
        val pages = pages()
        val d = ReceiptParser.parsePages(pages, options)
        val price = d.lineItems[1].unitPrice!!
        val spot = FieldLocator.locate(pages, price.source, "2,384")!!
        assertTrue(spot.exact)
        assertTrue(spot.mark.left >= spot.area.left && spot.mark.right <= spot.area.right)
        assertTrue(spot.mark.top >= spot.area.top && spot.mark.bottom <= spot.area.bottom)
        // Wide enough for some context, never the whole page width, and low (about three lines).
        assertTrue(spot.area.width < 2000)
        assertTrue(spot.area.height < spot.area.width / 3)
    }

    @Test fun aiAsksSmallQuestionsNotTheWholePage() {
        val pages = pages()
        val d = ReceiptParser.parsePages(pages, options)
        val plan = AiTargets.plan(pages, d, spotCheck = true)
        assertNotNull("headings not recognised must not mean reading the whole page", plan)
        assertTrue(plan!!.any { it is AiTarget.Header })
        assertTrue(plan.any { it is AiTarget.Totals })
        assertTrue(plan.none { it is AiTarget.Row && it.itemIndex == null })
        val numbers = plan.filterIsInstance<AiTarget.Number>()
        // Every line adds up as printed: one look at the largest amount (and the worked-out quantity, unless the VAT summary proves it).
        assertTrue(numbers.size in 1..2)
        assertTrue(numbers.all { it.boxes.isNotEmpty() && it.boxes.all { b -> b.width > 0 && b.height > 0 } })
    }
}
