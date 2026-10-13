package com.kitchenreceipts.core

/**
 * The review list, row by row in the order shown: the document's header, then its lines in the order printed. Lines
 * that need the operator are full cards; runs of lines that check out are folded into one row ("12 lines check
 * out"), opened with a tap. Built in one place so that "Next to check" and Save scroll to the right row.
 */
sealed interface ReviewRow { val key: String }

/** A fixed part of the screen (photo, banners, header, title...), by name. */
data class TopRow(override val key: String) : ReviewRow

/** A line shown in full, to check or correct. */
data class CardRow(val item: LineItemDraft) : ReviewRow { override val key: String get() = "i${item.key}" }

/** Lines in a row that check out, folded: [keys] in document order. */
data class GroupRow(val keys: List<Long>, val open: Boolean, val totalCents: Long?) : ReviewRow {
    override val key: String get() = "g${keys.first()}"
}

/** One line of an opened group, in one row (a tap shows it in full). */
data class CompactRow(val item: LineItemDraft) : ReviewRow { override val key: String get() = "c${item.key}" }

/** The line still needs a look: a highlighted or missing value, an error, numbers that do not add up, a reading to pick. */
fun LineItemDraft.needsLook(errors: Map<String, ErrorCode>, lotsPrinted: Boolean): Boolean {
    if (uncertainCount > 0 || errors.keys.any { it.startsWith("item:$key.") } || choices.size >= 2 || addsUp() == false) return true
    if (description.isMissing || lineTotal.isMissing) return true
    if (isCharge) return false
    return quantity.isMissing || unit.isMissing || (lotsPrinted && lot.isMissing)
}

/** The header still needs a look (a value highlighted or missing, an error, prices with or without VAT unsure). */
fun DocumentDraft.headerNeedsLook(errors: Map<String, ErrorCode>): Boolean =
    listOf(seller, date, number, currency, subtotal, vat, total).any { it.uncertain } || seller.isMissing || date.isMissing ||
        total.isMissing || vatBasisUncertain || errors.keys.any { !it.startsWith("item:") }

/**
 * The lines as rows. A line is a full card when it needs a look, when it needed one as the screen opened ([keepOpen]:
 * it does not fold away while being corrected), when the operator added it, or opened it ([opened]).
 */
fun lineRows(
    draft: DocumentDraft,
    errors: Map<String, ErrorCode>,
    keepOpen: Set<Long>?,
    initialKeys: Set<Long>?,
    opened: Set<Long>,
    openGroups: Set<Long>,
): List<ReviewRow> {
    val out = ArrayList<ReviewRow>()
    val run = ArrayList<LineItemDraft>()
    fun flush() {
        if (run.isEmpty()) return
        val keys = run.map { it.key }
        val open = keys.first() in openGroups
        val cents = run.map { ItalianNumbers.parseCents(it.lineTotal.text) }
        out += GroupRow(keys, open, if (cents.all { it != null }) cents.sumOf { it!! } else null)
        if (open) run.forEach { out += CompactRow(it) }
        run.clear()
    }
    for (item in draft.items) {
        val full = item.needsLook(errors, draft.lotsPrinted) || item.key in opened ||
            (keepOpen != null && item.key in keepOpen) || (initialKeys != null && item.key !in initialKeys) || keepOpen == null
        if (full) { flush(); out += CardRow(item) } else run += item
    }
    flush()
    return out
}

/**
 * Where "Next to check" goes: the first row after [from] (the row at the top of the screen) that needs a look, else
 * the first one from the top again; null when nothing is left to check. [header] is the key of the header's row.
 */
fun nextToCheck(rows: List<ReviewRow>, from: Int, draft: DocumentDraft, errors: Map<String, ErrorCode>, header: String): Int? {
    val targets = rows.indices.filter { i ->
        when (val r = rows[i]) {
            is CardRow -> r.item.needsLook(errors, draft.lotsPrinted)
            is TopRow -> r.key == header && draft.headerNeedsLook(errors)
            else -> false
        }
    }
    return targets.firstOrNull { it > from } ?: targets.firstOrNull()
}
