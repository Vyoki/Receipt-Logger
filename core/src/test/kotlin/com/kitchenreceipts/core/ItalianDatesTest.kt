package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ItalianDatesTest {

    @Test fun numericFormatsAreDayFirst() {
        assertEquals(LocalDate.of(2025, 3, 4), ItalianDates.parse("04/03/2025"))
        assertEquals(LocalDate.of(2025, 3, 4), ItalianDates.parse("4-3-2025"))
        assertEquals(LocalDate.of(2025, 3, 14), ItalianDates.parse("14.03.25"))
        assertEquals(LocalDate.of(2025, 3, 14), ItalianDates.parse("2025-03-14"))
    }

    @Test fun textualMonths() {
        assertEquals(LocalDate.of(2025, 4, 3), ItalianDates.parse("3 aprile 2025"))
        assertEquals(LocalDate.of(2025, 12, 1), ItalianDates.parse("1 Dic. 2025"))
        assertEquals(LocalDate.of(2025, 6, 21), ItalianDates.parse("21 giugno 2025"))
    }

    @Test fun invalidDates() {
        assertNull(ItalianDates.parse("31/02/2025"))
        assertNull(ItalianDates.parse("13/13/2025"))
        assertNull(ItalianDates.parse("ciao"))
        assertNull(ItalianDates.parse("14/03/2025 extra"))
    }

    @Test fun findsDatesInsideLines() {
        val found = ItalianDates.findDates("FATTURA N. 0145/2025 del 14/03/2025")
        assertEquals(1, found.size)
        assertEquals(LocalDate.of(2025, 3, 14), found[0].date)
        assertTrue(ItalianDates.findDates("Totale 1.234,56").isEmpty())
    }

    @Test fun formatting() {
        assertEquals("04/03/2025", ItalianDates.format(LocalDate.of(2025, 3, 4)))
        assertEquals("Marzo 2025", ItalianDates.formatMonth(java.time.YearMonth.of(2025, 3)))
    }
}
