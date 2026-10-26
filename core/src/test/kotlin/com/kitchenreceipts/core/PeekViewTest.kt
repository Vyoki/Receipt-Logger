package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The photo viewer's arithmetic: a phone screen of 1080 x 1800 pixels, a page read at 1700 x 2400. */
class PeekViewTest {

    private val f = PeekView.Frame(1080f, 1800f, 1700f, 2400f)
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 0.5f)

    @Test fun wholePageFitsAndIsCentred() {
        val v = PeekView.whole(f)
        near(1080f / 1700f, v.scale)
        val b = PeekView.onScreen(v, PeekView.Box(0f, 0f, 1700f, 2400f))
        near(0f, b.left); near(1080f, b.right)
        // Shorter than the screen: the same band above and below.
        near(1800f - b.bottom, b.top)
    }

    @Test fun theValueIsShownLargeAndInTheMiddle() {
        // An amount at the right of the page, with a little of its row around it.
        val mark = PeekView.Box(1400f, 1200f, 1500f, 1230f)
        val area = PeekView.Box(1200f, 1180f, 1700f, 1250f)
        val v = PeekView.focus(f, area, mark, readableScale = 2f)
        near(1080f / 500f, v.scale)
        val m = PeekView.onScreen(v, mark)
        // Centred where the page allows; the page's right edge never leaves an empty band.
        assertTrue(m.left >= 0f && m.right <= 1080f)
        near(900f, (m.top + m.bottom) / 2)
        val page = PeekView.onScreen(v, PeekView.Box(0f, 0f, 1700f, 2400f))
        assertTrue(page.right >= 1080f - 0.5f)
    }

    @Test fun aWholeRowStartsAtItsDescriptionAndStaysReadable() {
        // A whole line of the table, as wide as the page: shown at a readable size from its left edge.
        val row = PeekView.Box(100f, 1000f, 1600f, 1030f)
        val v = PeekView.focus(f, PeekView.Box(80f, 990f, 1620f, 1040f), row, readableScale = 2.5f)
        near(2.5f, v.scale)
        val m = PeekView.onScreen(v, row)
        near(1080f * 0.05f, m.left)
        near(900f, (m.top + m.bottom) / 2)
    }

    @Test fun zoomKeepsThePointUnderTheFingerAndStaysInBounds() {
        val start = PeekView.focus(f, PeekView.Box(600f, 1100f, 900f, 1160f), PeekView.Box(700f, 1120f, 800f, 1140f), 2f)
        val zoomed = PeekView.zoom(start, 1.5f, 540f, 900f, f)
        near(start.scale * 1.5f, zoomed.scale)
        // The page point under (540, 900) stays there.
        val before = (540f - start.x) / start.scale
        val after = (540f - zoomed.x) / zoomed.scale
        near(before, after)
        // Never past the most, never smaller than the whole page.
        near(PeekView.MAX_SCALE, PeekView.zoom(zoomed, 100f, 540f, 900f, f).scale)
        near(f.fit, PeekView.zoom(zoomed, 0.001f, 540f, 900f, f).scale)
    }

    @Test fun panningStopsAtThePageEdges() {
        val v = PeekView.focus(f, PeekView.Box(0f, 0f, 400f, 60f), PeekView.Box(10f, 10f, 100f, 40f), 2f)
        val moved = PeekView.pan(v, 5000f, 5000f, f)
        near(0f, moved.x); near(0f, moved.y)
        val back = PeekView.pan(v, -50000f, -50000f, f)
        near(1080f - 1700f * v.scale, back.x); near(1800f - 2400f * v.scale, back.y)
    }

    @Test fun aValueAtTheFootIsShownWithoutAnEmptyBandUnderThePage() {
        val mark = PeekView.Box(1400f, 2340f, 1500f, 2370f)
        val v = PeekView.focus(f, PeekView.Box(1200f, 2320f, 1700f, 2390f), mark, readableScale = 2f)
        val page = PeekView.onScreen(v, PeekView.Box(0f, 0f, 1700f, 2400f))
        near(1800f, page.bottom)
        val m = PeekView.onScreen(v, mark)
        assertTrue(m.top > 900f && m.bottom <= 1800f)
    }

    @Test fun nothingToFocusShowsTheWholePage() {
        assertEquals(PeekView.whole(f), PeekView.focus(f, PeekView.Box(5f, 5f, 5f, 5f), PeekView.Box(5f, 5f, 5f, 5f), 2f))
    }
}
