package com.kitchenreceipts.core

import java.time.LocalDate

/** Result of scanning one line for lot and expiry information. */
data class LotScan(
    val lot: Extracted<String>?,
    val expiry: Extracted<LocalDate>?,
    /** Character ranges consumed by lot / expiry fragments, so callers can remove them. */
    val consumed: List<IntRange>,
    val lotRejectedAsDate: Boolean,
)

/**
 * Finds lot / batch numbers and expiry dates.
 *
 * Only an explicit label counts as a lot: "Lotto", "Lot", "Lot.", "Batch", "N. lotto".
 * Expiry labels ("Scad.", "Scadenza", "Exp", "TMC", "Da consumarsi entro", "Best before", "BB")
 * are matched first, and anything they label is treated as an expiry date and never as a lot.
 * A value labelled as a lot that is itself a date is rejected rather than guessed.
 * A bare "L." prefix is NOT accepted as a lot label because on Italian documents it also means litres.
 */
object LotExtractor {

    private val EXPIRY = Regex(
        "(?i)\\b(?:da\\s+consumarsi\\s+(?:preferibilmente\\s+)?entro(?:\\s+il)?|consumare\\s+entro|" +
            "scadenza|scad\\.?|scade(?:\\s+il)?|exp(?:iry)?\\.?|tmc|best\\s+before|bb)\\s*[:.]?\\s*" +
            "(\\d{1,2}\\s?[/.\\-]\\s?\\d{1,2}\\s?[/.\\-]\\s?\\d{2,4}|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}\\s?[/.\\-]\\s?\\d{2,4})",
    )

    private val LOT = Regex(
        "(?i)(?:\\bn\\.?\\s*lotto|\\blotto|\\blot|\\bbatch)\\b\\.?\\s*(?:n\\.?|nr\\.?|no\\.?|num\\.?|n°)?\\s*[:#]?\\s*" +
            "([A-Za-z0-9][A-Za-z0-9\\-/.]{0,29})",
    )

    fun scan(line: String): LotScan {
        val consumed = mutableListOf<IntRange>()
        var expiry: Extracted<LocalDate>? = null

        for (m in EXPIRY.findAll(line)) {
            consumed += m.range
            if (expiry == null) {
                val raw = m.groupValues[1]
                val date = ItalianDates.findDates(raw).firstOrNull()?.date ?: parseMonthYear(raw)
                if (date != null) expiry = Extracted(date, Confidence.HIGH, m.value.trim())
            }
        }

        var lot: Extracted<String>? = null
        var rejected = false
        for (m in LOT.findAll(line)) {
            if (consumed.any { it.first <= m.range.first && m.range.first <= it.last }) continue
            val group = m.groups[1] ?: continue
            val value = group.value.trimEnd('.', '/', '-')
            // Take the whole labelled fragment out of the line, even when it is rejected.
            consumed += m.range
            if (!value.any { it.isDigit() }) continue
            if (ItalianDates.findDates(value).isNotEmpty()) {
                rejected = true
                continue
            }
            if (lot == null) lot = Extracted(value, Confidence.HIGH, m.value.trim())
        }
        return LotScan(lot, expiry, consumed.sortedBy { it.first }, rejected)
    }

    /** Removes the consumed ranges from [line]. */
    fun strip(line: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return line
        val sb = StringBuilder()
        var i = 0
        for (r in ranges.sortedBy { it.first }) {
            if (r.first > i) sb.append(line, i, r.first)
            i = maxOf(i, r.last + 1)
        }
        if (i < line.length) sb.append(line, i, line.length)
        return sb.toString().replace(rx("\\s{2,}"), " ").trim().trimEnd('-', ',', ';', '|').trim()
    }

    /** "09/2025" -> last day of that month (the usual meaning of a month-only expiry). */
    private fun parseMonthYear(raw: String): LocalDate? {
        val m = rx("^(\\d{1,2})\\s?[/.\\-]\\s?(\\d{4}|\\d{2})$").find(raw.trim()) ?: return null
        val month = m.groupValues[1].toInt()
        if (month !in 1..12) return null
        val y = m.groupValues[2].let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
        return java.time.YearMonth.of(y, month).atEndOfMonth()
    }
}
