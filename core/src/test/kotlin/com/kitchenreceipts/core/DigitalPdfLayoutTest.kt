package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A digital PDF invoice read from its text layer: a discount column, discount lines, a charge in the foot and
 * the totals under their headings.
 */
class DigitalPdfLayoutTest {

    private fun lines(edit: (String) -> String = { it }): List<OcrLine> =
        javaClass.getResource("/fixtures/pdf_text_sconti.txt")!!.readText().lines().filter { it.contains(" | ") }.map { l0 ->
            val l = edit(l0)
            val (g, rest) = l.split(" | ", limit = 2)
            val n = g.split(",")
            val top = n[1].toInt(); val bottom = n[3].toInt()
            val words = rest.substringAfter("  [", "").removeSuffix("]").split(Regex(" (?=\\d+-\\d+:)")).mapNotNull { w ->
                val m = Regex("^(\\d+)-(\\d+):(.*)$").find(w) ?: return@mapNotNull null
                OcrLine(m.groupValues[3], m.groupValues[1].toInt(), top, m.groupValues[2].toInt(), bottom)
            }
            OcrLine(rest.substringBefore("  ["), n[0].toInt(), top, n[2].toInt(), bottom, 0f, words)
        }

    private fun parse(ls: List<OcrLine>) = ReceiptParser.parsePages(listOf(ls), ParseOptions(ownVatNumber = "09876543217", today = LocalDate.of(2026, 10, 7)))

    @Test fun readsEverythingRight() {
        val d = parse(lines())
        assertEquals(LocalDate.of(2026, 9, 3), d.documentDate?.value)
        assertEquals(23189L, d.subtotalCents?.value)
        assertEquals(1377L, d.vatCents?.value)
        assertEquals(24566L, d.totalCents?.value)
        assertEquals(Confidence.HIGH, d.totalCents?.confidence)
        val goods = d.lineItems.filter { !it.adjustment }
        assertEquals(listOf(1069L, 932L, 11534L, 2179L, 5808L, 1620L), goods.map { it.lineTotalCents?.value })
        // "9,000 10": price 9,000 and a 10% discount, not quantity 9 at 10 each.
        val noci = goods.last()
        assertEquals(0, BigDecimal("2").compareTo(noci.quantity!!.value))
        assertEquals(0, BigDecimal("9").compareTo(noci.unitPrice!!.value))
        // Discounts and the bank charge are lines of the document, never products.
        val adj = d.lineItems.filter { it.adjustment }
        assertEquals(listOf(-550L, -903L, 1500L), adj.map { it.lineTotalCents?.value })
        assertTrue(adj.all { it.quantity == null && it.unitPrice == null && it.unit == null })
        assertEquals(23189L, d.lineItems.sumOf { it.lineTotalCents!!.value })
        assertTrue(d.warnings.toString(), d.warnings.isEmpty())
        assertTrue(ReceiptParser.isConfident(d))
    }

    /** The same page as a phone OCR reads it: a price glued to its discount, a table rule glued to the unit. */
    @Test fun ocrSlipsOnTheSameLayout() {
        val d = parse(lines { l -> l.replace("1070,838,1197,863,0.0 | 13,200 10+10  [1070-1130:13,200 1148-1197:10+10]", "1070,838,1197,863,0.0 | 13,20010+10  [1070-1197:13,20010+10]")
            .replace("[799-849:26245 863-883:KG]", "[799-849:26245 860-883:|KG]") })
        val first = d.lineItems.first()
        assertEquals(0, BigDecimal("13.2").compareTo(first.unitPrice!!.value))
        assertTrue(d.lineItems.filter { !it.adjustment }.all { it.unit?.value == "kg" })
        assertTrue(d.warnings.toString(), d.warnings.isEmpty())
    }

    @Test fun adjustmentWords() {
        assertTrue(Adjustments.isAdjustment("Sconto del 4%", -903, null))
        assertTrue(Adjustments.isAdjustment("Sconto inc.", -550, null))
        assertTrue(Adjustments.isAdjustment("ABBUONO", -12, null))
        assertTrue(Adjustments.isAdjustment("Spese di trasporto", 1500, null))
        assertTrue(Adjustments.isAdjustment("Arrotondamento", -2, null))
        assertTrue(!Adjustments.isAdjustment("Scamorza affumicata", 900, BigDecimal.ONE))
        assertTrue(!Adjustments.isAdjustment("Mozzarella sconto merce", 900, BigDecimal.ONE)) // words in the middle: a product
        // A discount printed without its minus sign is still money back.
        val item = ParsedLineItem("Sconto 5%", null, null, null, Extracted(500L, Confidence.HIGH, "x"), null, null, null)
        val marked = Adjustments.mark(listOf(item)).single()
        assertTrue(marked.adjustment)
        assertEquals(-500L, marked.lineTotalCents?.value)
        assertNull(marked.quantity)
    }

    @Test fun freeGoodsAreGoodsAtPriceZero() {
        val two = BigDecimal("2")
        assertTrue(!Adjustments.isAdjustment("OMAGGIO MOZZARELLA 125G", 0, two))
        assertTrue(!Adjustments.isAdjustment("SCONTO MERCE PASSATA 700G", null, two))
        // Money off is still money off.
        assertTrue(Adjustments.isAdjustment("OMAGGIO", 0, two))
        assertTrue(Adjustments.isAdjustment("Sconto merce", -720, two))
        assertTrue(Adjustments.isAdjustment("PROMOZIONE -20%", -144, two))
        // Read as a line: quantity, unit, lot and expiry stay.
        val free = ParsedLineItem(
            "OMAGGIO MOZZARELLA 125G", Extracted(two, Confidence.HIGH, "x"), Extracted("pz", Confidence.HIGH, "x"), null,
            Extracted(0L, Confidence.HIGH, "x"), null, Extracted("L24A9", Confidence.HIGH, "x"), Extracted(LocalDate.of(2026, 10, 20), Confidence.HIGH, "x"),
        )
        val kept = Adjustments.mark(listOf(free)).single()
        assertTrue(!kept.adjustment)
        assertEquals(0, two.compareTo(kept.quantity!!.value))
        assertEquals("L24A9", kept.lotNumber?.value)
        assertEquals(LocalDate.of(2026, 10, 20), kept.expiryDate?.value)
    }

    @Test fun aDeliveryNoteReferenceBoxIsNotTheInvoicesNumberAndDate() {
        // The invoice's own number and date printed in one line, and lower down the delivery notes it charges,
        // in a box of their own ("N. DDT | DATA").
        val raw = javaClass.getResource("/fixtures/pdf_text_sconti.txt")!!.readText().lines().filter { it.contains(" | ") }
        val inline = raw.filterNot { it.contains("NR. DOCUMENTO") || it.contains("DATA DOCUMENTO") || it.contains("| B26 305511") || it.contains("| 03/09/2026") } +
            "1079,620,1500,646,0.0 | FATTURA N. B26 305511 DEL 03/09/2026  [1079-1180:FATTURA 1190-1210:N. 1220-1320:B26 1325-1400:305511 1410-1440:DEL 1450-1500:03/09/2026]"
        val withRef = inline + listOf(
            "128,2250,200,2264,0.0 | N. DDT  [128-140:N. 145-200:DDT]", "402,2250,450,2264,0.0 | DATA  [402-450:DATA]",
            "128,2276,160,2300,0.0 | 88  [128-160:88]", "402,2276,520,2300,0.0 | 28/08/2026  [402-520:28/08/2026]",
        )
        fun read(ls: List<String>) = parse(ls.map { l ->
            val (g, rest) = l.split(" | ", limit = 2)
            val n = g.split(",")
            val top = n[1].toInt(); val bottom = n[3].toInt()
            val words = rest.substringAfter("  [", "").removeSuffix("]").split(Regex(" (?=\\d+-\\d+:)")).mapNotNull { w ->
                val m = Regex("^(\\d+)-(\\d+):(.*)$").find(w) ?: return@mapNotNull null
                OcrLine(m.groupValues[3], m.groupValues[1].toInt(), top, m.groupValues[2].toInt(), bottom)
            }
            OcrLine(rest.substringBefore("  ["), n[0].toInt(), top, n[2].toInt(), bottom, 0f, words)
        })
        val d = read(withRef)
        assertTrue(d.documentNumber?.value != "88")
        assertTrue(d.documentDate?.value != LocalDate.of(2026, 8, 28))
        assertEquals(read(inline).documentNumber, d.documentNumber)
        assertEquals(read(inline).documentDate, d.documentDate)
    }
}
