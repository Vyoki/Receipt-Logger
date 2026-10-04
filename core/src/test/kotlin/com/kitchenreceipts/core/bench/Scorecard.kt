package com.kitchenreceipts.core.bench

import com.kitchenreceipts.core.AutoAccept
import com.kitchenreceipts.core.DocumentDraft
import com.kitchenreceipts.core.DraftField
import com.kitchenreceipts.core.DuplicateDetector
import com.kitchenreceipts.core.ItalianDates
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.ParseOptions
import com.kitchenreceipts.core.ReceiptParser
import com.kitchenreceipts.core.SmartMatcher
import com.kitchenreceipts.core.VatBasis
import java.math.BigDecimal

/**
 * Scores a reading the way the operator meets it: each field of the review screen is
 * - SURE: right and not highlighted (no work);
 * - CHECK: right but highlighted (a glance);
 * - FLAGGED: wrong or missing, and highlighted or empty (the operator fixes it);
 * - SILENT: wrong and NOT highlighted (the worst: it goes into the books unless someone notices).
 * A document is "auto" when the app would save it without the operator; an auto document with any wrong field is
 * the most expensive mistake there is.
 */
enum class Outcome { SURE, CHECK, FLAGGED, SILENT }

class FieldStats {
    val counts = IntArray(Outcome.entries.size)
    fun add(o: Outcome) { counts[o.ordinal]++ }
    val total: Int get() = counts.sum()
    fun pct(o: Outcome): Double = if (total == 0) 0.0 else 100.0 * counts[o.ordinal] / total
    /** Right, whether highlighted or not. */
    val right: Double get() = if (total == 0) 0.0 else 100.0 * (counts[0] + counts[1]) / total
}

class Scorecard {
    val fields = linkedMapOf<String, FieldStats>()
    var docs = 0
    var autoRight = 0
    var autoWrong = 0
    var allRight = 0
    val worst = mutableListOf<String>()
    /** Documents the app would save without the operator although something is wrong: the costliest mistakes. */
    val savedWithErrors = mutableListOf<String>()

    private fun stat(name: String) = fields.getOrPut(name) { FieldStats() }

    private fun outcome(right: Boolean, f: DraftField): Outcome = when {
        right && !f.uncertain -> Outcome.SURE
        right -> Outcome.CHECK
        f.isMissing || f.uncertain -> Outcome.FLAGGED
        else -> Outcome.SILENT
    }

    fun score(doc: BenchDoc): List<String> {
        val options = ParseOptions(null, doc.ownVat)
        val parsed = if (doc.text != null) ReceiptParser.parse(doc.text, options) else ReceiptParser.parsePages(doc.pages, options)
        var draft = DocumentDraft.fromParsed(parsed)
        if (draft.vatBasis == VatBasis.UNKNOWN || draft.vatBasisUncertain) {
            AutoAccept.inferVatBasis(draft)?.let { draft = draft.copy(vatBasis = it, vatBasisUncertain = false) }
        }
        draft = AutoAccept.settleProven(draft)
        val t = doc.truth
        val problems = mutableListOf<String>()
        val outcomes = mutableListOf<Outcome>()
        fun field(name: String, f: DraftField, right: Boolean) {
            val o = outcome(right, f)
            stat(name).add(o); stat("all fields").add(o); outcomes += o
            if (o == Outcome.SILENT || o == Outcome.FLAGGED) problems += "$name: '${f.text}' ${if (o == Outcome.SILENT) "SILENT" else "flagged"}"
        }
        fun cents(f: DraftField) = ItalianNumbers.parseCents(f.text)
        fun num(f: DraftField) = ItalianNumbers.parse(f.text)

        field("supplier", draft.seller, sameName(draft.seller.text, t.seller))
        if (!sameName(draft.seller.text, t.seller)) problems += "(supplier printed: '${t.seller}')"
        t.number?.let { n ->
            field("number", draft.number, key(draft.number.text) == key(n))
            if (key(draft.number.text) != key(n)) problems += "(number printed: '$n')"
        }
        field("date", draft.date, ItalianDates.parse(draft.date.text) == t.date)
        field("total", draft.total, cents(draft.total) == t.totalCents)
        t.subtotalCents?.let { s -> field("taxable", draft.subtotal, cents(draft.subtotal) == s) }
        t.vatCents?.let { v -> field("vat", draft.vat, cents(draft.vat) == v) }

        t.items?.let { truthItems ->
            val free = draft.items.toMutableList()
            for (ti in truthItems) {
                val match = free.firstOrNull { cents(it.lineTotal) == ti.amountCents && similar(it.description.text, ti.description) }
                    ?: free.firstOrNull { similar(it.description.text, ti.description) }
                    ?: free.firstOrNull { cents(it.lineTotal) == ti.amountCents }
                if (match == null) {
                    // A line not read at all: every field of it is work for the operator.
                    listOf("line", "quantity", "price", "amount").forEach { stat(it).add(Outcome.FLAGGED); stat("all fields").add(Outcome.FLAGGED) }
                    outcomes += Outcome.FLAGGED
                    problems += "missing line: ${ti.description}"
                    continue
                }
                free.remove(match)
                stat("line").add(Outcome.SURE)
                field("quantity", match.quantity, num(match.quantity)?.compareTo(ti.quantity) == 0)
                field("price", match.unitPrice, num(match.unitPrice)?.compareTo(ti.unitPrice) == 0)
                field("amount", match.lineTotal, cents(match.lineTotal) == ti.amountCents)
                ti.vatRate?.let { v -> field("VAT rate", match.vatRate, num(match.vatRate)?.compareTo(BigDecimal(v)) == 0) }
                ti.lot?.let { l -> field("lot", match.lot, match.lot.text.trim().equals(l, true)) }
            }
            // Lines read that are not on the document (a heading or a total taken as a product).
            for (extra in free) {
                val flagged = extra.uncertainCount > 0
                stat("extra line").add(if (flagged) Outcome.FLAGGED else Outcome.SILENT)
                outcomes += if (flagged) Outcome.FLAGGED else Outcome.SILENT
                problems += "extra line: '${extra.description.text}' ${cents(extra.lineTotal)}" + if (flagged) "" else " SILENT"
            }
        }
        docs++
        val right = outcomes.none { it == Outcome.FLAGGED || it == Outcome.SILENT }
        if (right) allRight++
        val auto = AutoAccept.reasons(draft).isEmpty()
        if (auto && right) autoRight++
        if (auto && !right) { autoWrong++; problems += "SAVED WITHOUT REVIEW WITH ERRORS" }
        if (problems.isNotEmpty()) worst += "${doc.name}: " + problems.take(6).joinToString("; ")
        if (auto && !right) savedWithErrors += "${doc.name}: " + problems.joinToString("; ")
        return problems
    }

    fun report(title: String): String = buildString {
        append("== $title: $docs documents\n")
        append("documents fully right: ${pct(allRight)}  saved without review: right ${pct(autoRight)}, WITH ERRORS ${pct(autoWrong)}\n")
        append(String.format("%-12s %7s %7s %7s %7s %7s\n", "field", "right", "sure", "check", "fixed", "SILENT"))
        fields.forEach { (name, s) ->
            append(String.format("%-12s %6.1f%% %6.1f%% %6.1f%% %6.1f%% %6.1f%%  (n=%d)\n", name, s.right, s.pct(Outcome.SURE), s.pct(Outcome.CHECK), s.pct(Outcome.FLAGGED), s.pct(Outcome.SILENT), s.total))
        }
    }

    private fun pct(n: Int) = if (docs == 0) "0%" else String.format("%.1f%%", 100.0 * n / docs)

    companion object {
        /** Document numbers compare on letters and digits, OCR look-alikes folded ("B26 204177" = "B26204177"). */
        fun key(s: String): String = s.uppercase().filter(Char::isLetterOrDigit).replace('O', '0').replace('I', '1').replace('L', '1')

        fun sameName(read: String, truth: String): Boolean {
            val a = DuplicateDetector.normalizeSeller(read) ?: return false
            val b = DuplicateDetector.normalizeSeller(truth) ?: return false
            if (a.isEmpty() || b.isEmpty()) return false
            return a == b || SmartMatcher.damerau(a, b, 3) <= 2
        }

        /** Same product line: most words of the printed description in the reading. */
        fun similar(read: String, truth: String): Boolean {
            val w = { s: String -> s.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.length >= 3 }.toSet() }
            val t = w(truth)
            if (t.isEmpty()) return false
            return t.count { it in w(read) } * 2 >= t.size
        }
    }
}
