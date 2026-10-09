package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class ReadingSanityTest {

    private fun line(q: String, unit: String, rate: String?) = ParsedLineItem(
        "MOZZARELLA", Extracted(BigDecimal(q), Confidence.HIGH, "t"), Extracted(unit, Confidence.HIGH, "t"),
        Extracted(BigDecimal("8.90"), Confidence.HIGH, "t"), Extracted(1780L, Confidence.HIGH, "t"),
        rate?.let { Extracted(BigDecimal(it), Confidence.HIGH, "t") }, null, null,
    )
    private val italian = "ABC S.r.l. P.IVA 01234567897"

    @Test fun aFractionalCountIsMarkedOnItsUnit() {
        val d = ReadingSanity.apply(ParsedDocument.EMPTY.copy(lineItems = listOf(line("2.5", "PZ", null), line("2", "PZ", null), line("2.5", "KG", null))), italian, null)
        assertEquals(Confidence.LOW, d.lineItems[0].unit?.confidence)
        assertEquals(Confidence.HIGH, d.lineItems[0].quantity?.confidence) // the number itself is not doubted
        assertEquals(Confidence.HIGH, d.lineItems[1].unit?.confidence)
        assertEquals(Confidence.HIGH, d.lineItems[2].unit?.confidence)
    }

    @Test fun anItalianSupplierHasOnlyItalianVatRates() {
        val d = ReadingSanity.apply(ParsedDocument.EMPTY.copy(lineItems = listOf(line("2", "KG", "10"), line("2", "KG", "12"))), italian, null)
        assertEquals(Confidence.HIGH, d.lineItems[0].vatRatePercent?.confidence)
        assertEquals(Confidence.LOW, d.lineItems[1].vatRatePercent?.confidence)
        // Not an Italian supplier (no Italian VAT number): any rate.
        val foreign = ReadingSanity.apply(ParsedDocument.EMPTY.copy(lineItems = listOf(line("2", "KG", "12"))), "Some shop Ltd", null)
        assertEquals(Confidence.HIGH, foreign.lineItems[0].vatRatePercent?.confidence)
    }

    @Test fun aPriceFarFromHistoryIsUnusualButNeverChanged() {
        fun change(p: String) = PriceChange(1, "MOZZARELLA", "kg", VatBasis.EXCLUSIVE, BigDecimal("8"), BigDecimal("8"), BigDecimal(p),
            null, "ABC S.r.l.", 1, null, "ABC S.r.l.", 2)
        assertEquals(1, PriceWatch.unusual(listOf(change("35.0"), change("12.0"))).size)
        assertEquals(1, PriceWatch.unusual(listOf(change("-40.0"))).size)
    }
}
