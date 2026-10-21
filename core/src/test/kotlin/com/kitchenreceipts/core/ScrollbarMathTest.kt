package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollbarMathTest {

    @Test fun topAndEnd() {
        assertEquals(0f, ScrollbarMath.fraction(0, 0f, 100, 10f, canScrollBack = false, canScrollForward = true))
        assertEquals(1f, ScrollbarMath.fraction(90, 0f, 100, 10f, canScrollBack = true, canScrollForward = false))
    }

    @Test fun movesSteadilyWhateverTheItemHeights() {
        // Scrolling item by item, with any share of the first item scrolled: the thumb only ever goes down.
        var last = -1f
        for (i in 0 until 89) for (part in listOf(0f, 0.3f, 0.99f)) {
            val f = ScrollbarMath.fraction(i, part, 100, 10.5f, canScrollBack = i > 0 || part > 0, canScrollForward = true)
            assertTrue("$i $part", f >= last)
            last = f
        }
    }

    @Test fun aDragLandsWhereTheThumbSays() {
        for (index in listOf(0, 7, 33, 60, 88)) {
            val f = ScrollbarMath.fraction(index, 0.5f, 100, 10f, canScrollBack = true, canScrollForward = true)
            val (back, part) = ScrollbarMath.target(f, 100, 10f)
            assertEquals(index, back)
            assertEquals(0.5f, part, 0.01f)
        }
        assertEquals(99 to 0f, ScrollbarMath.target(1f, 100, 10f))
        assertEquals(0 to 0f, ScrollbarMath.target(-1f, 100, 10f))
        assertEquals(0 to 0f, ScrollbarMath.target(0.5f, 0, 10f))
    }

    @Test fun longLists() {
        assertTrue(ScrollbarMath.isLong(30, 10f))
        assertFalse(ScrollbarMath.isLong(29, 10f))
        assertFalse(ScrollbarMath.isLong(30, 0f))
    }

    @Test fun thumbPlace() {
        assertEquals(0f, ScrollbarMath.thumbTop(0f, 1000f, 100f))
        assertEquals(900f, ScrollbarMath.thumbTop(1f, 1000f, 100f))
        assertEquals(0.5f, ScrollbarMath.fractionAt(450f, 1000f, 100f))
        assertEquals(1f, ScrollbarMath.fractionAt(5000f, 1000f, 100f))
        assertEquals(0f, ScrollbarMath.fractionAt(10f, 50f, 100f))
    }
}
