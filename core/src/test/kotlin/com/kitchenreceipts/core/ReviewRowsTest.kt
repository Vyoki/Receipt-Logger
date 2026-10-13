package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRowsTest {

    private fun line(key: Long, amount: String = "10,00", uncertain: Boolean = false) = LineItemDraft(
        key = key,
        description = DraftField("PASTA PENNE"),
        quantity = DraftField("2"), unit = DraftField("pz"), unitPrice = DraftField("5,00"),
        lineTotal = DraftField(amount, uncertain = uncertain),
    )

    private fun draft(vararg items: LineItemDraft) = DocumentDraft(
        seller = DraftField("ABC S.r.l."), date = DraftField("30/09/2026"), number = DraftField("12A/34567"),
        currency = DraftField("EUR"), subtotal = DraftField("40,00"), vat = DraftField("4,00"), total = DraftField("44,00"),
        lotsPrinted = false, items = items.toList(),
    )

    @Test
    fun linesThatCheckOutAreFoldedInTheirPlace() {
        val d = draft(line(1), line(2), line(3, "11,00", uncertain = true), line(4))
        val rows = lineRows(d, emptyMap(), keepOpen = setOf(3), initialKeys = setOf(1, 2, 3, 4), opened = emptySet(), openGroups = emptySet())
        assertEquals(listOf("g1", "i3", "g4"), rows.map { it.key })
        assertEquals(2000L, (rows[0] as GroupRow).totalCents)
        // An opened group shows its lines, one row each; a line opened by hand is a full card.
        val open = lineRows(d, emptyMap(), setOf(3), setOf(1, 2, 3, 4), opened = setOf(2), openGroups = setOf(1))
        assertEquals(listOf("g1", "c1", "i2", "i3", "g4"), open.map { it.key })
    }

    @Test
    fun aLineBeingCorrectedDoesNotFoldAwayAndAnAddedLineStaysOpen() {
        // Line 3 needed a look when the screen opened; now confirmed, it stays where the operator is working.
        val d = draft(line(1), line(3), line(9))
        val rows = lineRows(d, emptyMap(), keepOpen = setOf(3), initialKeys = setOf(1, 3), opened = emptySet(), openGroups = emptySet())
        assertEquals(listOf("g1", "i3", "i9"), rows.map { it.key })
        // Before the screen has taken its first look, everything is shown.
        assertTrue(lineRows(d, emptyMap(), null, null, emptySet(), emptySet()).all { it is CardRow })
    }

    @Test
    fun nextToCheckGoesDownThenStartsAgain() {
        val d = draft(line(1, uncertain = true), line(2), line(3, uncertain = true))
        val rows = listOf(TopRow("header")) + lineRows(d, emptyMap(), setOf(1, 3), setOf(1, 2, 3), emptySet(), emptySet())
        assertEquals(listOf("header", "i1", "g2", "i3"), rows.map { it.key })
        assertEquals(1, nextToCheck(rows, 0, d, emptyMap(), "header"))
        assertEquals(3, nextToCheck(rows, 1, d, emptyMap(), "header"))
        assertEquals(1, nextToCheck(rows, 3, d, emptyMap(), "header"))
        // An error in the header makes the header a place to go.
        assertEquals(0, nextToCheck(rows, 3, d, mapOf("date" to ErrorCode.INVALID_DATE), "header"))
        val done = draft(line(1), line(2))
        assertNull(nextToCheck(listOf(TopRow("header")) + lineRows(done, emptyMap(), emptySet(), setOf(1, 2), emptySet(), emptySet()), 0, done, emptyMap(), "header"))
    }
}
