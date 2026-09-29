package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAcceptTest {
    private val text = javaClass.classLoader!!.getResource("fixtures/fattura_caseificio.txt")!!.readText()

    @Test fun cleanInvoiceNeedsNoReviewOrOnlyForReadDoubts() {
        val d = DocumentDraft.fromParsed(ReceiptParser.parse(text))
        val reasons = AutoAccept.reasons(d)
        assertTrue(reasons.toString(), ReviewReason.SUM_MISMATCH !in reasons && ReviewReason.INCOMPLETE_ITEMS !in reasons)
    }

    @Test fun mismatchAndMissingAreReported() {
        val item = LineItemDraft(1, DraftField("Patate"), quantity = DraftField("10"), unit = DraftField("kg"), lineTotal = DraftField("9,00"))
        val ok = DocumentDraft(seller = DraftField("Alfa"), date = DraftField("01/09/2026"), total = DraftField("9,90"),
            subtotal = DraftField("9,00"), vat = DraftField("0,90"), items = listOf(item))
        assertEquals(emptyList<ReviewReason>(), AutoAccept.reasons(ok))
        assertEquals(VatBasis.EXCLUSIVE, AutoAccept.inferVatBasis(ok))
        val wrong = ok.copy(total = DraftField("19,90"), subtotal = DraftField("18,00"))
        assertEquals(listOf(ReviewReason.SUM_MISMATCH), AutoAccept.reasons(wrong))
        val noDate = ok.copy(date = DraftField(""), total = DraftField("9,90", uncertain = true))
        assertEquals(listOf(ReviewReason.DATE_MISSING, ReviewReason.UNCERTAIN_VALUES), AutoAccept.reasons(noDate))
    }

    @Test fun receiptWithVatIncluded() {
        val item = LineItemDraft(1, DraftField("Pane"), quantity = DraftField("2"), unit = DraftField("pz"), lineTotal = DraftField("3,00"))
        val d = DocumentDraft(seller = DraftField("Forno"), date = DraftField("01/09/2026"), total = DraftField("3,00"), vat = DraftField("0,27"), items = listOf(item))
        assertEquals(VatBasis.INCLUSIVE, AutoAccept.inferVatBasis(d))
        assertEquals(emptyList<ReviewReason>(), AutoAccept.reasons(d))
    }

    @Test fun numbersProvenByTheArithmeticNeedNoConfirmation() {
        val item = LineItemDraft(1, DraftField("Biscotti"), quantity = DraftField("1", uncertain = true), unit = DraftField("pz"),
            unitPrice = DraftField("3,450", uncertain = true), lineTotal = DraftField("3,45"))
        val d = DocumentDraft(seller = DraftField("Alfa"), date = DraftField("01/09/2026"), subtotal = DraftField("3,45"),
            vat = DraftField("0,35", uncertain = true), total = DraftField("3,80"), items = listOf(item))
        val settled = AutoAccept.settleProven(d)
        assertEquals(0, settled.uncertainCount)
        assertEquals(emptyList<ReviewReason>(), AutoAccept.reasons(settled))
        // Lines that do not add up to the total: nothing is settled.
        val off = d.copy(subtotal = DraftField("4,45"), total = DraftField("4,80"))
        assertEquals(d.copy(subtotal = off.subtotal, total = off.total).uncertainCount, AutoAccept.settleProven(off).uncertainCount)
    }
}
