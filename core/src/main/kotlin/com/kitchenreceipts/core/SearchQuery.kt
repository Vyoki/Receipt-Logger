package com.kitchenreceipts.core

import java.time.LocalDate
import java.time.YearMonth

/**
 * Interprets what the user types in the documents search box.
 * Dates become a date range, the rest stays free text:
 *  "14/03/2025" -> that day · "03/2025", "3-2025", "2025-03", "marzo 2025", "mar 25" -> that month
 *  "rossi 03/2025" -> text "rossi" within March 2025.
 */
data class SearchQuery(val text: String, val from: LocalDate?, val to: LocalDate?) {

    companion object {
        private val MONTHS = mapOf(
            "gennaio" to 1, "gen" to 1, "january" to 1, "jan" to 1,
            "febbraio" to 2, "feb" to 2, "february" to 2,
            "marzo" to 3, "mar" to 3, "march" to 3,
            "aprile" to 4, "apr" to 4, "april" to 4,
            "maggio" to 5, "mag" to 5, "may" to 5,
            "giugno" to 6, "giu" to 6, "june" to 6, "jun" to 6,
            "luglio" to 7, "lug" to 7, "july" to 7, "jul" to 7,
            "agosto" to 8, "ago" to 8, "august" to 8, "aug" to 8,
            "settembre" to 9, "set" to 9, "september" to 9, "sep" to 9,
            "ottobre" to 10, "ott" to 10, "october" to 10, "oct" to 10,
            "novembre" to 11, "nov" to 11, "november" to 11,
            "dicembre" to 12, "dic" to 12, "december" to 12, "dec" to 12,
        )
        private val MONTH_YEAR = Regex("(?<![\\d/.\\-])(\\d{1,2})[/.\\-](\\d{4}|\\d{2})(?![\\d/.\\-])")
        private val YEAR_MONTH = Regex("(?<![\\d/.\\-])(\\d{4})[/.\\-](\\d{1,2})(?![\\d/.\\-])")
        private val NAMED_MONTH = Regex("(?i)\\b(" + MONTHS.keys.sortedByDescending { it.length }.joinToString("|") + ")\\.?\\s+(\\d{4}|\\d{2})\\b")

        fun parse(raw: String): SearchQuery {
            var text = raw.trim()
            // A full date first.
            ItalianDates.findDates(text).firstOrNull()?.let { d ->
                return SearchQuery(text.removeRange(d.range).trim().replace(Regex("\\s+"), " "), d.date, d.date)
            }
            fun month(y: Int, m: Int): YearMonth? =
                if (m in 1..12 && y in 1990..2100) YearMonth.of(y, m) else null
            fun year(s: String) = if (s.length == 2) 2000 + s.toInt() else s.toInt()

            var ym: YearMonth? = null
            var range: IntRange? = null
            NAMED_MONTH.find(text)?.let { m ->
                ym = month(year(m.groupValues[2]), MONTHS.getValue(m.groupValues[1].lowercase())); range = m.range
            }
            if (ym == null) YEAR_MONTH.find(text)?.let { m ->
                ym = month(m.groupValues[1].toInt(), m.groupValues[2].toInt()); range = m.range
            }
            if (ym == null) MONTH_YEAR.find(text)?.let { m ->
                ym = month(year(m.groupValues[2]), m.groupValues[1].toInt()); range = m.range
            }
            val found = ym
            val r = range
            if (found != null && r != null) {
                text = text.removeRange(r).trim().replace(Regex("\\s+"), " ")
                return SearchQuery(text, found.atDay(1), found.atEndOfMonth())
            }
            return SearchQuery(text, null, null)
        }
    }
}
