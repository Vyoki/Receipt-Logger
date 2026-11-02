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
        // A quantity read unsure that its line's arithmetic does not prove (3 x 8,90 is not 17,80).
        val unsureQty = proven.copy(items = listOf(proven.items[0].copy(quantity = DraftField("3", uncertain = true)), proven.items[1]))
        assertFalse(Tidy.onlyTidyLeft(unsureQty))
        // The VAT printed (taxable and total differ) but not read: missing, not to tidy.
        assertFalse(Tidy.onlyTidyLeft(proven.copy(vat = DraftField(""))))
    }

    @Test fun aUnitUnsureOrNotPrintedIsLeftToTidyWhenTheMoneyIsProven() {
        // Unsure: saved off the line, the reading kept to suggest; not printed: marked, the invoice not held.
        val unsure = proven.copy(items = listOf(proven.items[0].copy(unit = DraftField("kg", uncertain = true)), proven.items[1]))
        assertTrue(Tidy.onlyTidyLeft(unsure))
        val saved = Tidy.prepareForSave(unsure).items[0]
        assertEquals("", saved.unit.text)
        assertTrue(ItemMark(Tidy.KIND, Tidy.UNIT, "kg") in Tidy.of(saved))
        val missing = proven.copy(items = listOf(proven.items[0].copy(unit = DraftField("")), proven.items[1]))
        assertTrue(ReviewReason.INCOMPLETE_ITEMS !in AutoAccept.reasons(missing))
        assertTrue(ItemMark(Tidy.KIND, Tidy.UNIT, Tidy.NONE_READ) in Tidy.of(Tidy.prepareForSave(missing).items[0]))
        // A quantity missing is no unit to tidy: still incomplete.
        val noQty = proven.copy(items = listOf(proven.items[0].copy(quantity = DraftField(""), unit = DraftField("")), proven.items[1]))
        assertTrue(ReviewReason.INCOMPLETE_ITEMS in AutoAccept.reasons(noQty))
    }

    @Test fun aNewSuppliersNameReadWithItsCheckedVatNumberIsLeftToTidy() {
        val newSupplier = proven.copy(seller = DraftField("ABE S.r.l.", uncertain = true))
        // Without a VAT number read with the name: the name holds the invoice.
        assertTrue(ReviewReason.UNCERTAIN_VALUES in AutoAccept.reasons(newSupplier))
        // With it (check digits right): saved, the name kept as read and listed to tidy once.
        val checked = newSupplier.copy(sellerVatChecked = true)
        assertTrue(AutoAccept.reasons(checked).isEmpty())
        val saved = Tidy.prepareForSave(checked)
        assertFalse(saved.seller.uncertain)
        assertEquals("ABE S.r.l.", saved.seller.text)
        assertTrue(ItemMark(Tidy.KIND, Tidy.SUPPLIER, "ABE S.r.l.") in Tidy.of(saved.items[0]))
        // Opened again for editing: the name is shown as a doubt.
        assertTrue(Tidy.restore(saved).seller.uncertain)
    }

    @Test fun theVatAmountIsProvenByItsLinesAndRates() {
        // 17,80 + 12,60 at 10%: 3,04. Nothing printed proves the total (no total line): the lines' rates do.
        val d = proven.copy(total = DraftField(""), vat = DraftField("3,04", uncertain = true))
        assertEquals(304L to 1, AutoAccept.vatFromRates(d))
        assertFalse(AutoAccept.settleProven(d).vat.uncertain)
        // A VAT that the rates do not give stays to check.
        assertTrue(AutoAccept.settleProven(d.copy(vat = DraftField("3,40", uncertain = true))).vat.uncertain)
    }
}
