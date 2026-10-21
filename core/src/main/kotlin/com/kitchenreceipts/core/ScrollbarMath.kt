package com.kitchenreceipts.core

import kotlin.math.floor
import kotlin.math.max

/**
 * Where the fast-scroll thumb of a long list sits, and where a drag of it lands. Positions count items (the first
 * visible item plus the share of it already scrolled away), never estimated pixels: items of different heights (a
 * folded group, a long card) then move the thumb steadily instead of making it jump.
 */
object ScrollbarMath {

    /** A list is long enough for a thumb when its items fill at least this many screens (as Android does). */
    const val LONG_SCREENS = 4f

    /**
     * The thumb's place from 0 (top) to 1 (end). [firstIndex] is the first visible item, [scrolledFraction] how much of
     * it is scrolled away (0..1), [total] the item count, [onScreen] how many items fit on the screen (fractions count).
     */
    fun fraction(firstIndex: Int, scrolledFraction: Float, total: Int, onScreen: Float, canScrollBack: Boolean, canScrollForward: Boolean): Float {
        if (!canScrollBack) return 0f
        if (!canScrollForward) return 1f
        val range = max(1f, total - onScreen)
        return ((firstIndex + scrolledFraction.coerceIn(0f, 1f)) / range).coerceIn(0f, 1f)
    }

    /** The item to scroll to for a thumb at [f], and how far into it (0..1). The end of the track is the last item. */
    fun target(f: Float, total: Int, onScreen: Float): Pair<Int, Float> {
        if (total <= 0) return 0 to 0f
        if (f >= 0.999f) return (total - 1) to 0f
        val pos = f.coerceIn(0f, 1f) * max(0f, total - onScreen)
        val index = floor(pos).toInt().coerceIn(0, total - 1)
        return index to (pos - index)
    }

    fun isLong(total: Int, onScreen: Float): Boolean = onScreen > 0f && total / onScreen >= LONG_SCREENS

    /** The thumb's top for a track of [track] and a thumb of [thumb] (same unit). */
    fun thumbTop(f: Float, track: Float, thumb: Float): Float = f.coerceIn(0f, 1f) * max(0f, track - thumb)

    /** The fraction for a thumb dragged so that its top is at [top]. */
    fun fractionAt(top: Float, track: Float, thumb: Float): Float {
        val room = track - thumb
        return if (room <= 0f) 0f else (top / room).coerceIn(0f, 1f)
    }
}
