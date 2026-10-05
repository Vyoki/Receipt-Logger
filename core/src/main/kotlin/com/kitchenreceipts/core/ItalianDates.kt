package com.kitchenreceipts.core

import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

/** A date found inside a line of text, with the character range it occupied. */
data class DateMatch(val date: LocalDate, val range: IntRange, val text: String)

/**
 * Date formats: 14/03/2025, 14-03-25, 14.03.2025, 2025-03-14, 2025/03/14, "14 marzo 2025", "14 mar. 2025",
 * "8. September 2022", "08-Sep-22", "September 8, 2022" (months in Italian, English, French, German, Spanish, Dutch).
 * Numeric dates are day first; month first only when no other reading exists ("08/25/2022").
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
        // Other languages on suppliers' documents: English, French, German, Spanish, Dutch (full names and the usual
        // abbreviations; month by position). Italian wins where a spelling is shared.
        val other = listOf(
            "january jan janvier janv januar jän enero ene januari",
            "february feb février fevrier févr fevr februar febrero februari",
            "march mar mars märz marz maerz marzo maart mrt",
            "april apr avril avr abril",
            "may mai mayo mei",
            "june jun juin juni junio",
            "july jul juillet juil juli julio",
            "august aug août aout agosto augustus",
            "september sep sept septembre septiembre setiembre",
            "october oct octobre oktober okt octubre",
            "november nov novembre noviembre",
            "december dec décembre decembre déc dezember dez diciembre",
        )
        other.forEachIndexed { i, names -> names.split(' ').forEach { if (it !in this) put(it, i + 1) } }
    }

    private val NUMERIC = Regex("(?<!\\d)(\\d{1,2})\\s?[/.\\-]\\s?(\\d{1,2})\\s?[/.\\-]\\s?(\\d{4}|\\d{2})(?!\\d)")
    private val ISO = Regex("(?<!\\d)(\\d{4})([-/.])(\\d{1,2})\\2(\\d{1,2})(?!\\d)")
    private val MONTH_WORDS = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
    /** "14 marzo 2025", "8. September 2022", "08-Sep-22", "8 sept. 2022". */
    private val TEXTUAL = Regex(
        "(?i)(?<![\\d\\p{L}])(\\d{1,2})\\.?[\\s\\-]*($MONTH_WORDS)\\.?[\\s\\-,]+(\\d{4}|\\d{2})(?![\\d\\p{L}])",
    )
    /** "September 8, 2022", "Sep 08 2022" (English order). */
    private val MONTH_FIRST = Regex(
        "(?i)(?<![\\p{L}])($MONTH_WORDS)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})(?!\\d)",
    )

    fun findDates(line: String): List<DateMatch> {
        val found = mutableListOf<DateMatch>()
        for (m in ISO.findAll(line)) {
            build(m.groupValues[1].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
                ?.let { found += DateMatch(it, m.range, m.value) }
        }
        for (m in NUMERIC.findAll(line)) {
            if (found.any { it.range.overlaps(m.range) }) continue
            val year = normalizeYear(m.groupValues[3])
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            // Day first, always; month first only when it is the one possible reading ("08/25/2022", four-digit year,
            // slashes: "12.31.00" is a time).
            (build(year, b, a) ?: if (b > 12 && a <= 12 && m.groupValues[3].length == 4 && '/' in m.value) build(year, a, b) else null)
                ?.let { found += DateMatch(it, m.range, m.value) }
        }
        for (m in TEXTUAL.findAll(line)) {
            if (found.any { it.range.overlaps(m.range) }) continue
            val month = MONTHS[m.groupValues[2].lowercase()] ?: continue
            // A two-digit year only in the compact form "08-Sep-22" ("1 SET 12" on a product line is no date).
            if (m.groupValues[3].length == 2 && m.value.count { it == '-' } < 2) continue
            build(normalizeYear(m.groupValues[3]), month, m.groupValues[1].toInt())
                ?.let { found += DateMatch(it, m.range, m.value) }
        }
        for (m in MONTH_FIRST.findAll(line)) {
            if (found.any { it.range.overlaps(m.range) }) continue
            val month = MONTHS[m.groupValues[1].lowercase()] ?: continue
            build(m.groupValues[3].toInt(), month, m.groupValues[2].toInt())
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
