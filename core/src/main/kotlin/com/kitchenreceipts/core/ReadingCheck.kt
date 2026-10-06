package com.kitchenreceipts.core

import java.time.LocalDate

/**
 * Re-reading saved documents with a new version of the app: every saved document is a test whose answers the
 * operator confirmed. A version that reads one of them worse than the version before is caught on the phone itself,
 * on real documents, without anything leaving the phone.
 */
object ReadingCheck {

    /** What the operator saved (confirmed) for one document. */
    data class Confirmed(
        val date: LocalDate?,
        val number: String?,
        val totalCents: Long?,
        val subtotalCents: Long?,
        val vatCents: Long?,
        val lineAmounts: List<Long>,
    )

    /** How a reading compares with the confirmed values: fields right out of fields checked, and what was different. */
    data class Score(val right: Int, val of: Int, val differences: List<String>)

    fun score(c: Confirmed, d: ParsedDocument): Score {
        var right = 0
        var of = 0
        val diff = mutableListOf<String>()
        fun money(v: Long?) = v?.let { ItalianNumbers.formatCents(it) } ?: "–"
        fun <T> field(name: String, want: T?, got: T?, show: (T?) -> String = { it?.toString() ?: "–" }, same: (T, T) -> Boolean = { a, b -> a == b }) {
            if (want == null) return
            of++
            if (got != null && same(want, got)) right++ else diff += "$name: saved ${show(want)}, read ${show(got)}"
        }
        field("date", c.date, d.documentDate?.value, { it?.let(ItalianDates::format) ?: "–" })
        field("number", c.number?.takeIf { it.isNotBlank() }, d.documentNumber?.value) { a, b -> key(a) == key(b) }
        field("total", c.totalCents, d.totalCents?.value, ::money)
        field("taxable", c.subtotalCents, d.subtotalCents?.value, ::money)
        field("VAT", c.vatCents, d.vatCents?.value, ::money)
        // Lines: each saved line amount found among the lines read (each read line used once).
        val read = d.lineItems.mapNotNull { it.lineTotalCents?.value }.toMutableList()
        val missing = mutableListOf<Long>()
        for (a in c.lineAmounts) {
            of++
            val i = read.indexOf(a)
            if (i >= 0) { right++; read.removeAt(i) } else missing += a
        }
        if (missing.isNotEmpty()) diff += "lines not read: " + missing.joinToString(", ") { money(it) }
        if (read.isNotEmpty()) diff += "extra lines read: " + read.joinToString(", ") { money(it) }
        return Score(right, of, diff)
    }

    private fun key(s: String) = s.uppercase().filter(Char::isLetterOrDigit)

    /** One document's result in a check, and in the check before it (same document, previous version). */
    data class Compared(val documentId: Long, val label: String, val now: Score, val before: Int?) {
        val worse: Boolean get() = before != null && now.right < before
        val better: Boolean get() = before != null && now.right > before
    }
}
