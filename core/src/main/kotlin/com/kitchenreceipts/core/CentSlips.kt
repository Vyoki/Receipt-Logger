package com.kitchenreceipts.core

import java.math.RoundingMode

/**
 * A line amount one cent away from quantity x price passes the line's own check (some suppliers cut the third
 * decimal instead of rounding it). The document's other lines say which the supplier does: when every line whose
 * product has a third decimal is rounded to the nearest cent, a line one cent off is a misread digit ("18,75" read for
 * a printed "18,76"), not the supplier's rounding. That amount is highlighted, with quantity x price offered.
 */
object CentSlips {

    /** Lines that show the supplier's rounding before one cent off counts as a misread. */
    private const val MIN_EVIDENCE = 3

    fun apply(doc: ParsedDocument): ParsedDocument {
        data class L(val i: Int, val up: Long, val down: Long, val printed: Long)
        val lines = doc.lineItems.mapIndexedNotNull { i, it ->
            if (it.adjustment || LineDiscount.percent(it.discount?.value)?.signum()?.let { s -> s != 0 } == true) return@mapIndexedNotNull null
            val q = it.quantity?.value ?: return@mapIndexedNotNull null
            val p = it.unitPrice?.value ?: return@mapIndexedNotNull null
            val t = it.lineTotalCents?.value ?: return@mapIndexedNotNull null
            val exact = q.multiply(p)
            fun cents(m: RoundingMode) = exact.setScale(2, m).movePointRight(2).toLong()
            L(i, cents(RoundingMode.HALF_UP), cents(RoundingMode.DOWN), t)
        }.filter { it.up != it.down } // an exact product says nothing about the rounding
        val rounded = lines.count { it.printed == it.up }
        val cut = lines.count { it.printed == it.down }
        // The misread line itself looks cut ("18,75" for 18,7566): one such line among many rounded ones is the slip.
        if (rounded < MIN_EVIDENCE || cut * 4 > rounded) return doc
        val slips = lines.filter { kotlin.math.abs(it.printed - it.up) == 1L }.map { it.i }.toSet()
        if (slips.isEmpty()) return doc
        return doc.copy(lineItems = doc.lineItems.mapIndexed { i, it ->
            val t = it.lineTotalCents
            if (i !in slips || t == null) it else it.copy(
                lineTotalCents = t.copy(confidence = Confidence.LOW, source = (t.source ?: "") + " (one cent off quantity x price; the other lines are rounded)"),
                warnings = it.warnings + ParseWarning.LINE_TOTAL_MISMATCH,
            )
        })
    }

}
