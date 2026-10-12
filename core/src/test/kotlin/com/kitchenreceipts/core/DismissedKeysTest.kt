package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DismissedKeysTest {

    @Test fun marksFollowTheirLinesWhenADocumentIsSavedAgain() {
        val before = listOf(DismissedKeys.Line(10, "MOZZARELLA", 900), DismissedKeys.Line(11, "SPESE TRASPORTO", 500), DismissedKeys.Line(12, "RICOTTA", 400))
        // Saved again with the transport line removed: new ids, and the ricotta moved up one place.
        val after = listOf(DismissedKeys.Line(20, "MOZZARELLA", 900), DismissedKeys.Line(21, "RICOTTA", 400))
        val ids = DismissedKeys.match(before, after)
        assertEquals(mapOf(10L to 20L, 12L to 21L), ids)
        assertEquals("exp:21", DismissedKeys.remap("exp:12", ids))
        assertEquals("agreed:20", DismissedKeys.remap("agreed:10", ids))
        // In a DDT/invoice difference only the two line slots move; the invoice id (here equal to a line id) stays.
        assertEquals("ddt:12:PRICE:20:7", DismissedKeys.remap("ddt:12:PRICE:10:7", ids))
        assertNull(DismissedKeys.remap("exp:11", ids))
        assertNull(DismissedKeys.remap("exp:99", ids))
    }
}
