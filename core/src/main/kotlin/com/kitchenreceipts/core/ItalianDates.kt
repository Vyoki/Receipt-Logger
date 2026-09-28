package com.kitchenreceipts.core

import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

/** A date found inside a line of text, with the character range it occupied. */
data class DateMatch(val date: LocalDate, val range: IntRange, val text: String)

/**
 * Italian date formats: 14/03/2025, 14-03-25, 14.03.2025, 2025-03-14, "14 marzo 2025", "14 mar. 2025".
 * Day-first is always assumed for numeric dates (never US month-first).
 */
object ItalianDates {

    private val MONTHS: Map<String, Int> = buildMap {
        val full = listOf(
            "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
            "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
        )
        val short = listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")
        full.forEachIndexed { i, m -> put(m, i + 1) }
        short.forEachIndexed { i, m -> put(m, i + 1) }
    }

    private val NUMERIC = Regex("(?<!\\d)(\\d{1,2})\\s?[/.\\-]\\s?(\\d{1,2})\\s?[/.\\-]\\s?(\\d{4}|\\d{2})(?!\\d)")
    private val ISO = Regex("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)")
    private val TEXTUAL = Regex(
        "(?i)(?<!\\d)(\\d{1,2})\\s+(" + MONTHS.keys.sortedByDescending { it.length }.joinToString("|") +
            ")\\.?\\s+(\\d{4})(?!\\d)",
    )

    fun findDates(line: String): List<DateMatch> {
        val found = mutableListOf<DateMatch>()
        for (m in ISO.findAll(line)) {
            build(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                ?.let { found += DateMatch(it, m.range, m.value) }
        }
        for (m in NUMERIC.findAll(line)) {
            if (found.any { it.range.overlaps(m.range) }) continue
            val year = normalizeYear(m.groupValues[3])
            build(year, m.groupValues[2].toInt(), m.groupValues[1].toInt())
                ?.let { found += DateMatch(it, m.range, m.value) }
        }
        for (m in TEXTUAL.findAll(line)) {
            if (found.any { it.range.overlaps(m.range) }) continue
            val month = MONTHS[m.groupValues[2].lowercase()] ?: continue
            build(m.groupValues[3].toInt(), month, m.groupValues[1].toInt())
                ?.let { found += DateMatch(it, m.range, m.value) }
        }
        return found.sortedBy { it.range.first }
    }

    /** Parses a string that should contain exactly one date (user input). */
    fun parse(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim()
        val matches = findDates(t)
        val m = matches.singleOrNull() ?: return null
        // The whole input must be the date (allowing surrounding whitespace only).
        return if (m.range.first == 0 && m.range.last == t.length - 1) m.date else null
    }

    fun format(date: LocalDate): String =
        String.format(java.util.Locale.ROOT, "%02d/%02d/%04d", date.dayOfMonth, date.monthValue, date.year)

    fun formatMonth(month: YearMonth): String {
        val names = listOf(
            "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
            "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre",
        )
        return "${names[month.monthValue - 1]} ${month.year}"
    }

    private fun normalizeYear(y: String): Int = if (y.length == 2) 2000 + y.toInt() else y.toInt()

    private fun build(year: Int, month: Int, day: Int): LocalDate? {
        if (year !in 1990..2100) return null
        return try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
}
