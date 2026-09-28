package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LotExtractorTest {

    @Test fun italianAndEnglishLabels() {
        assertEquals("L24-118", LotExtractor.scan("Lotto: L24-118").lot?.value)
        assertEquals("240312", LotExtractor.scan("Lot. 240312").lot?.value)
        assertEquals("GU-7781", LotExtractor.scan("Lotto n. GU-7781").lot?.value)
        assertEquals("A12", LotExtractor.scan("LOTTO A12").lot?.value)
        assertEquals("B-99/2", LotExtractor.scan("Batch B-99/2").lot?.value)
    }

    @Test fun expiryIsNeverALot() {
        val s = LotExtractor.scan("Scad. 20/03/2025")
        assertNull(s.lot)
        assertEquals(LocalDate.of(2025, 3, 20), s.expiry?.value)

        val both = LotExtractor.scan("Lotto: L24-118 Scad. 20/03/2025")
        assertEquals("L24-118", both.lot?.value)
        assertEquals(LocalDate.of(2025, 3, 20), both.expiry?.value)

        val tmc = LotExtractor.scan("Da consumarsi preferibilmente entro 30/06/2025")
        assertNull(tmc.lot)
        assertEquals(LocalDate.of(2025, 6, 30), tmc.expiry?.value)

        assertEquals(LocalDate.of(2025, 9, 30), LotExtractor.scan("TMC 09/2025").expiry?.value)
        assertEquals(LocalDate.of(2025, 1, 5), LotExtractor.scan("Exp 05-01-2025").expiry?.value)
    }

    @Test fun lotLabelFollowedByDateIsRejected() {
        val s = LotExtractor.scan("Lotto 12/03/2025")
        assertNull(s.lot)
        assertTrue(s.lotRejectedAsDate)
    }

    @Test fun noLabelNoLot() {
        assertNull(LotExtractor.scan("Mozzarella L24118 kg 2,5 8,90 22,25").lot) // bare code: not guessed
        assertNull(LotExtractor.scan("Latte intero 1 L 1,20").lot)                // "L" = litres
        assertNull(LotExtractor.scan("Slot machine 12345").lot)
        assertNull(LotExtractor.scan("Lotto unico").lot)                           // no digits
        assertFalse(LotExtractor.scan("Lotto unico").lotRejectedAsDate)
    }

    @Test fun stripRemovesFragments() {
        val line = "Guanciale kg 6,2 16,50 102,30 Lotto GU-7781 Scad. 30/06/2025"
        val s = LotExtractor.scan(line)
        assertEquals("Guanciale kg 6,2 16,50 102,30", LotExtractor.strip(line, s.consumed))
    }
}
