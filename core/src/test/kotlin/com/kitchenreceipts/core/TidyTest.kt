package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doubts left to tidy (invented invoice of ABC S.r.l.): they do not hold an invoice whose money is proven. */
class TidyTest {

    private fun line(key: Long, desc: String, qty: String, price: String, amount: String, lot: DraftField = DraftField(), nameDoubt: Boolean = false) = LineItemDraft(
        key, DraftField(desc, uncertain = nameDoubt, source = if (nameDoubt) "the name may go on over the next printed line" else null),
        quantity = DraftField(qty), unit = DraftField("kg"), unitPrice = DraftField(price), lineTotal = DraftField(amount), vatRate = DraftField("10"), lot = lot,
    )

    private val proven = DocumentDraft(
        seller = DraftField("ABC S.r.l."), date = DraftField("03/02/2031"), number = DraftField("77/ZZ"),
        subtotal = DraftField("30,40"), vat = DraftField("3,04"), total = DraftField("33,44"), vatBasis = VatBasis.EXCLUSIVE,
        items = listOf(
            line(1, "MOZZARELLA FIOR DI LATTE", "2", "8,90", "17,80", lot = DraftField("L123", uncertain = true)),
            line(2, "PASSATA DI POMODORO", "12", "1,05", "12,60", nameDoubt = true),
        ),
        lotsPrinted = true,
    )

    @Test fun aProvenInvoiceWithOnlyANameAndALotInDoubtSavesItself() {
        assertTrue(Tidy.onlyTidyLeft(proven))
        assertTrue(AutoAccept.reasons(proven).isEmpty())
        // As saved: the unsure lot is taken off its line (kept as the reading to suggest), the name kept and marked.
        val saved = Tidy.prepareForSave(proven)
        assertEquals("", saved.items[0].lot.text)
        assertEquals(listOf(ItemMark(Tidy.KIND, Tidy.LOT, "L123")), Tidy.of(saved.items[0]))
        assertEquals("PASSATA DI POMODORO", saved.items[1].description.text)
        // Its lot is missing while the other line printed one: that is to tidy too.
        assertEquals(setOf(Tidy.LOT, Tidy.NAME), Tidy.of(saved.items[1]).map { it.code }.toSet())
        // Opened again for editing: the doubts are shown as doubts, the marks gone.
        val back = Tidy.restore(saved.items[0])
        assertEquals("L123", back.lot.text)
        assertTrue(back.lot.uncertain)
        assertTrue(Tidy.of(back).isEmpty())
    }

    @Test fun aLotMissingWhereOthersArePrintedIsOneToTidy() {
        val d = proven.copy(items = listOf(proven.items[0].copy(lot = DraftField("L123")), proven.items[1].copy(description = DraftField("PASSATA DI POMODORO"))))
        assertTrue(AutoAccept.reasons(d).isEmpty())
        assertEquals(listOf(ItemMark(Tidy.KIND, Tidy.LOT, Tidy.NONE_READ)), Tidy.of(Tidy.prepareForSave(d).items[1]))
        assertEquals(null, Tidy.suggestion(Tidy.of(Tidy.prepareForSave(d).items[1]).single()))
    }

    @Test fun anythingElseInDoubtStillHoldsTheInvoice() {
        // The number unsure, an amount unsure, or the money not adding up: to check, as before.
        assertFalse(Tidy.onlyTidyLeft(proven.copy(number = DraftField("77/ZZ", uncertain = true))))
        assertTrue(ReviewReason.UNCERTAIN_VALUES in AutoAccept.reasons(proven.copy(number = DraftField("77/ZZ", uncertain = true))))
        val wrongSum = proven.copy(total = DraftField("34,44"))
        assertFalse(Tidy.onlyTidyLeft(wrongSum))
        assertTrue(AutoAccept.reasons(wrongSum).isNotEmpty())
        val unsureUnit = proven.copy(items = listOf(proven.items[0].copy(unit = DraftField("kg", uncertain = true)), proven.items[1]))
        assertFalse(Tidy.onlyTidyLeft(unsureUnit))
    }
}
