package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A unit filled in from the supplier's earlier invoices is told on the line, while checked and once saved. */
class UnitHistoryTest {

    private val line = LineItemDraft(1, DraftField("PECORINO PROVA"), quantity = DraftField("2"), unit = DraftField("kg", source = UnitHistory.source("kg")))

    @Test fun theUnitFilledInIsFromHistoryUntilAnotherIsTyped() {
        assertTrue(UnitHistory.isFrom(line))
        assertFalse(UnitHistory.isFrom(line.copy(unit = line.unit.confirmed("pz"))))
        assertFalse(UnitHistory.isFrom(line.copy(unit = DraftField("kg"))))
    }

    @Test fun savedWithAMarkAndBackOnItsFieldWhenOpenedAgain() {
        val marks = UnitHistory.marksToSave(line.copy(marks = listOf(ItemMark("storage", "F", "fresco"))))
        assertTrue(UnitHistory.isFrom(marks))
        // The page's own codes are kept; the unit's origin is no code of the line.
        assertEquals(listOf(ItemMark("storage", "F", "fresco")), UnitHistory.without(marks))
        val reopened = UnitHistory.restore(LineItemDraft(2, DraftField("PECORINO PROVA"), unit = DraftField("kg"), marks = marks))
        assertTrue(UnitHistory.isFrom(reopened))
        assertEquals(listOf(ItemMark("storage", "F", "fresco")), reopened.marks)
        // A unit typed over it is saved without the mark.
        assertFalse(UnitHistory.isFrom(UnitHistory.marksToSave(reopened.copy(unit = reopened.unit.confirmed("pz")))))
    }

    @Test fun theSavedDocumentKeepsTheMark() {
        val d = DocumentDraft(seller = DraftField("CASEIFICIO PROVA"), items = listOf(line))
        val saved = (DraftValidator.validate(d) as ValidationResult.Valid).document
        assertTrue(UnitHistory.isFrom(saved.items[0].marks))
    }
}
