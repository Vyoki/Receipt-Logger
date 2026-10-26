package com.kitchenreceipts.core

import kotlin.math.max
import kotlin.math.min

/**
 * Where the photo viewer of a field puts the page on the screen: [scale] screen pixels per page pixel, and the page's
 * top-left corner at ([x], [y]). Plain arithmetic on sizes in pixels, so it is tested without a phone.
 */
data class PeekView(val scale: Float, val x: Float, val y: Float) {

    /** A rectangle in page pixels. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /** The screen ([viewW] x [viewH]) and the page ([pageW] x [pageH]), in pixels. */
    data class Frame(val viewW: Float, val viewH: Float, val pageW: Float, val pageH: Float, val maxScale: Float = MAX_SCALE) {
        /** The whole page on the screen. */
        val fit: Float get() = min(viewW / pageW, viewH / pageH)
        val most: Float get() = max(maxScale, fit)
    }

    companion object {
        /** The most a page pixel is enlarged (the page is drawn at the resolution it was read at). */
        const val MAX_SCALE = 6f

        /** The whole page, centred. */
        fun whole(f: Frame): PeekView = clamp(PeekView(f.fit, 0f, 0f), f)

        /**
         * The value first: [area] (the value and a little of its row) as large as the screen allows, and never smaller
         * than [readableScale] (print of [lineHeight] page pixels readable on the phone), centred on [mark]. A mark wider
         * than the screen at that size starts at its left edge (a row is read from its description).
         */
        fun focus(f: Frame, area: Box, mark: Box, readableScale: Float): PeekView {
            if (area.width <= 0f || area.height <= 0f) return whole(f)
            val s = max(min(f.viewW / area.width, f.viewH / area.height), readableScale).coerceIn(f.fit, f.most)
            val cy = (mark.top + mark.bottom) / 2
            val x = if (mark.width * s <= f.viewW * 0.9f) f.viewW / 2 - (mark.left + mark.right) / 2 * s
                else f.viewW * 0.05f - mark.left * s
            return clamp(PeekView(s, x, f.viewH / 2 - cy * s), f)
        }

        /** [v] enlarged by [factor] around the screen point ([px], [py]), between the whole page and [Frame.most]. */
        fun zoom(v: PeekView, factor: Float, px: Float, py: Float, f: Frame): PeekView {
            val s = (v.scale * factor).coerceIn(f.fit, f.most)
            val k = s / v.scale
            return clamp(PeekView(s, px - (px - v.x) * k, py - (py - v.y) * k), f)
        }

        /** [v] moved by ([dx], [dy]) screen pixels. */
        fun pan(v: PeekView, dx: Float, dy: Float, f: Frame): PeekView = clamp(v.copy(x = v.x + dx, y = v.y + dy), f)

        /**
         * No empty band where the page could fill the screen: a page larger than the screen keeps its edges at or past
         * the screen's; a smaller one is centred.
         */
        fun clamp(v: PeekView, f: Frame): PeekView {
            val w = f.pageW * v.scale
            val h = f.pageH * v.scale
            val x = if (w <= f.viewW) (f.viewW - w) / 2 else v.x.coerceIn(f.viewW - w, 0f)
            val y = if (h <= f.viewH) (f.viewH - h) / 2 else v.y.coerceIn(f.viewH - h, 0f)
            return PeekView(v.scale, x, y)
        }

        /** Where [b] (page pixels) is on the screen. */
        fun onScreen(v: PeekView, b: Box): Box = Box(v.x + b.left * v.scale, v.y + b.top * v.scale, v.x + b.right * v.scale, v.y + b.bottom * v.scale)
    }
}
