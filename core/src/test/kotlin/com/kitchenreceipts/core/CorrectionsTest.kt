package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionsTest {
    @Test fun listsEveryChange() {
        val initial = DocumentDraft(
            seller = DraftField("CASEIFICI0", uncertain = true),
            date = DraftField(""),
            total = DraftField("71,06", uncertain = true),
            items = listOf(
                LineItemDraft(1, DraftField("Mozzarella"), quantity = DraftField("2,5"), lineTotal = DraftField("22,25")),
                LineItemDraft(2, DraftField("Pagamento"), lineTotal = DraftField("71,06")),
            ),
        )
        val final = initial.copy(
            seller = DraftField("Caseificio Valverde"),
            date = DraftField("14/03/2025"),
            total = DraftField("71,06"),
            vatBasis = VatBasis.EXCLUSIVE,
            items = listOf(
                initial.items[0].copy(lot = DraftField("L24-118")),
                LineItemDraft(3, DraftField("Burro"), lineTotal = DraftField("9,40")),
            ),
        )
        val d = Corrections.diff(initial, final)
        assertTrue(d.contains("seller: 'CASEIFICI0' -> 'Caseificio Valverde'"))
        assertTrue(d.contains("date: (missing) -> '14/03/2025'"))
        assertTrue(d.contains("total: confirmed '71,06'"))
        assertTrue(d.contains("vatBasis: UNKNOWN -> EXCLUSIVE"))
        assertTrue(d.contains("item removed: 'Pagamento' '71,06'"))
        assertTrue(d.contains("item added: 'Burro' '9,40'"))
        assertTrue(d.contains("item 'Mozzarella' lot: (missing) -> 'L24-118'"))
        assertEquals(7, d.size)
    }

    @Test fun noChangesNoLines() {
        val draft = DocumentDraft(seller = DraftField("X"))
        assertTrue(Corrections.diff(draft, draft).isEmpty())
    }
}
