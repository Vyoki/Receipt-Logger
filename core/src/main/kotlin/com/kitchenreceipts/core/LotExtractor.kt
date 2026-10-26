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

    private val DIMENSIONS = Regex("\\d+[X×]\\d+")

    /** Units and packs printed with numbers in a pack size ("KG9X7", "ML.333", "MT45X3PZ", "G300X4"). */
    private val PACK_WORDS = setOf(
        "G", "GR", "KG", "HG", "CC", "CL", "ML", "LT", "PZ", "X", "NR", "CF", "MM", "CM", "MT", "CT", "CRT", "PK", "CS",
        "BT", "SC", "CONF", "VS", "TB", "PET", "ROT", "RT", "BRIK", "BOX",
    )

    /**
     * A token made only of pack words and numbers ("KG9X7", "ML.333", "ROT.MT45X3PZ"): a pack size, never a lot.
     * A lone "L" (a lot prefix as often as litres, "L.0042") does not make a token a pack size on its own.
     */
    fun packSizeLike(token: String): Boolean {
        val t = token.uppercase()
        if (!t.any(Char::isDigit)) return false
        // Sizes and multipacks: "40X40X30", "G300X4".
        if (DIMENSIONS.containsMatchIn(t)) return true
        val words = Regex("[A-Z]+").findAll(t).map { it.value }.toList()
        return words.isNotEmpty() && words.all { it in PACK_WORDS }
    }

    /** A month and year ("03/2031"): an expiry or a period, never a lot. */
    private val MONTH_YEAR = Regex("^(0?[1-9]|1[0-2])[/.\\-](19|20)\\d{2}$")

    /** A code that is never offered as a lot when nothing labels it: a pack size ("KG9X7", "ML.333") or a month and year. */
    fun neverALot(token: String): Boolean = packSizeLike(token) || monthYear(token)

    /** "03/2031": a month and year (a best-before), never a lot even when labelled. */
    fun monthYear(token: String): Boolean = MONTH_YEAR.matches(token.trim())

    // "DDT", "D.D.T.", "DDT09" (a delivery-note type code), "Documento di trasporto".
    private val DELIVERY_NOTE = Regex("(?i)(\\bd\\.?\\s?d\\.?\\s?t(?:\\b\\.?|(?=\\d))|\\bdoc(?:umento|\\.)?\\s+di\\s+trasporto\\b)")

    /**
     * A delivery-note reference printed among the lines ("DDT 77/ZZ del 03-02-2031", "Documento di trasporto n. 12 del
     * 03/02/2031"): the note's name, a number and a date. It names another document: never a lot, never an item.
     */
    fun isDeliveryNoteRef(text: String): Boolean {
        val m = DELIVERY_NOTE.find(text) ?: return false
        val dates = ItalianDates.findDates(text)
        if (dates.isEmpty()) return false
        // A number of its own, besides the date: what is left once the label and the dates are taken out.
        val rest = strip(text, listOf(m.range) + dates.map { it.range })
        return rest.any(Char::isDigit)
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
