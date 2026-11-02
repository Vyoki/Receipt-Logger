package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Why an invoice waits (invented invoice of ABC S.r.l.): the values left unsure and why its lines do not add up. */
class ReadingDetailsTest {

    private fun line(key: Long, desc: String, qty: String, price: String, amount: String) = LineItemDraft(
        key, DraftField(desc), quantity = DraftField(qty), unit = DraftField("kg"), unitPrice = DraftField(price),
        lineTotal = DraftField(amount), vatRate = DraftField("10"),
    )

    private val invoice = DocumentDraft(
        seller = DraftField("ABC S.r.l."), date = DraftField("03/02/2031"), number = DraftField("77/ZZ"),
        subtotal = DraftField("30,40"), vat = DraftField("3,04"), total = DraftField("33,44"), vatBasis = VatBasis.EXCLUSIVE,
        items = listOf(line(1, "MOZZARELLA FIOR DI LATTE", "2", "8,90", "17,80"), line(2, "PASSATA DI POMODORO", "12", "1,05", "12,60")),
    )

    @Test fun theKindsOfValuesLeftUnsure() {
        val d = invoice.copy(
            seller = DraftField("ABC S.r.l.", uncertain = true),
            items = listOf(invoice.items[0].copy(lot = DraftField("L1", uncertain = true)), invoice.items[1].copy(unit = DraftField("", uncertain = true))),
        )
        assertEquals(setOf(ReadingDetails.SUPPLIER, ReadingDetails.UNIT, ReadingDetails.LOT), ReadingDetails.doubts(d))
        assertEquals(emptySet<String>(), ReadingDetails.doubts(invoice))
    }

    @Test fun linesThatAddUpHaveNoCause() {
        assertNull(ReadingDetails.sumCause(invoice))
        // A line without an amount: no cause can be told.
        assertNull(ReadingDetails.sumCause(invoice.copy(items = listOf(invoice.items[0], invoice.items[1].copy(lineTotal = DraftField(""))))))
    }

    @Test fun whyTheLinesDoNotAddUp() {
        // A cent apart.
        assertEquals(ReadingDetails.SumCause.CENTS, ReadingDetails.sumCause(invoice.copy(subtotal = DraftField("30,41"), total = DraftField("33,45"))))
        // 17,80 read as 11,80: 2 x 8,90 gives the amount that closes the sum.
        assertEquals(ReadingDetails.SumCause.AMOUNT_MISREAD, ReadingDetails.sumCause(invoice.copy(items = listOf(invoice.items[0].copy(lineTotal = DraftField("11,80")), invoice.items[1]))))
        // The second line read twice.
        assertEquals(ReadingDetails.SumCause.LINE_TWICE, ReadingDetails.sumCause(invoice.copy(items = invoice.items + invoice.items[1].copy(key = 3))))
        // A subtotal line read as a product.
        assertEquals(ReadingDetails.SumCause.LINE_EXTRA, ReadingDetails.sumCause(invoice.copy(items = invoice.items + line(3, "TOTALE MERCE", "1", "30,40", "30,40"))))
        // A line not read at all.
        assertEquals(ReadingDetails.SumCause.LINES_SHORT, ReadingDetails.sumCause(invoice.copy(items = listOf(invoice.items[0]))))
        // More than the total, no single line explains it.
        assertEquals(ReadingDetails.SumCause.LINES_OVER, ReadingDetails.sumCause(invoice.copy(subtotal = DraftField("25,00"), vat = DraftField("2,50"), total = DraftField("27,50"))))
    }

    @Test fun aDiscountCountedTheWrongWay() {
        val discount = LineItemDraft(3, DraftField("SCONTO"), lineTotal = DraftField("2,00"), adjustment = true)
        // Printed -2,00: the taxable amount is 28,40; read as +2,00 the lines give 32,40.
        val d = invoice.copy(subtotal = DraftField("28,40"), vat = DraftField("2,84"), total = DraftField("31,24"), items = invoice.items + discount)
        assertEquals(ReadingDetails.SumCause.DISCOUNT_SIGN, ReadingDetails.sumCause(d))
    }
}
