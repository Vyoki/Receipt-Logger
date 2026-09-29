package com.kitchenreceipts.core

import java.math.BigDecimal

/** Why a scanned document still needs the operator's eyes. */
enum class ReviewReason {
    SELLER_MISSING,
    DATE_MISSING,
    TOTAL_MISSING,
    NO_ITEMS,
    /** Some values were read with low confidence (highlighted in the review). */
    UNCERTAIN_VALUES,
    /** A line lacks quantity, unit or amount. */
    INCOMPLETE_ITEMS,
    /** The lines do not add up to the taxable amount or the total. */
    SUM_MISMATCH,
    /** Could not tell whether prices include VAT. */
    VAT_BASIS_UNKNOWN,
}

/**
 * Decides when a scan can be saved without the operator touching it: every field was read with
 * confidence and the arithmetic proves it (the lines add up to the printed taxable amount or total).
 * If anything is missing or doubtful the document goes to review, with the reasons listed.
 */
object AutoAccept {

    fun reasons(d: DocumentDraft): List<ReviewReason> {
        val out = mutableListOf<ReviewReason>()
        if (d.seller.isMissing) out += ReviewReason.SELLER_MISSING
        if (d.date.isMissing || ItalianDates.parse(d.date.text) == null) out += ReviewReason.DATE_MISSING
        val total = cents(d.total.text)
        if (total == null) out += ReviewReason.TOTAL_MISSING
        if (d.items.isEmpty()) out += ReviewReason.NO_ITEMS
        if (d.uncertainCount > 0) out += ReviewReason.UNCERTAIN_VALUES
        if (d.items.any { it.description.isMissing || ItalianNumbers.parse(it.quantity.text) == null || it.unit.isMissing || cents(it.lineTotal.text) == null }) {
            out += ReviewReason.INCOMPLETE_ITEMS
        }
        val sumOk = sumMatches(d)
        if (d.items.isNotEmpty() && total != null && ReviewReason.INCOMPLETE_ITEMS !in out && !sumOk) {
            out += ReviewReason.SUM_MISMATCH
        }
        if (d.vatBasis == VatBasis.UNKNOWN && inferVatBasis(d) == null && sumOk) {
            out += ReviewReason.VAT_BASIS_UNKNOWN
        }
        return out
    }

    /**
     * What the line amounts prove about VAT: they add up to the taxable amount (prices without VAT)
     * or to the total with the total above the taxable amount / no VAT printed (prices with VAT).
     * Null when they add up to neither.
     */
    fun inferVatBasis(d: DocumentDraft): VatBasis? {
        val lines = d.items.map { cents(it.lineTotal.text) ?: return null }
        if (lines.isEmpty()) return null
        val sum = lines.sum()
        val tolerance = maxOf(2L, lines.size.toLong())
        val subtotal = cents(d.subtotal.text)
        val total = cents(d.total.text)
        val vat = cents(d.vat.text)
        if (subtotal != null && kotlin.math.abs(sum - subtotal) <= tolerance && (total == null || total >= subtotal)) {
            return VatBasis.EXCLUSIVE
        }
        if (total != null && kotlin.math.abs(sum - total) <= tolerance) {
            val vatShown = (subtotal != null && subtotal < total) || (vat != null && vat > 0)
            return if (vatShown) VatBasis.INCLUSIVE else d.vatBasis.takeIf { it != VatBasis.UNKNOWN }
        }
        return null
    }

    /**
     * When the arithmetic closes over the whole document, the numbers are proven and need no confirmation:
     * every line has quantity x price = amount, the lines add up to the printed taxable amount or total, and
     * taxable amount + VAT = total when all three are printed. Their "uncertain" marks are cleared; names, dates,
     * lots and everything else keep theirs.
     */
    fun settleProven(d: DocumentDraft): DocumentDraft {
        if (d.items.isEmpty() || !sumMatches(d)) return d
        val lineOk = d.items.all { it ->
            val q = ItalianNumbers.parse(it.quantity.text) ?: return@all false
            val p = ItalianNumbers.parse(it.unitPrice.text) ?: return@all false
            val t = cents(it.lineTotal.text) ?: return@all false
            ReceiptParser.matches(q, p, t)
        }
        if (!lineOk) return d
        val sub = cents(d.subtotal.text); val vat = cents(d.vat.text); val tot = cents(d.total.text)
        val headerOk = sub == null || vat == null || tot == null || kotlin.math.abs(sub + vat - tot) <= 1
        if (!headerOk) return d
        val sum = d.items.sumOf { cents(it.lineTotal.text)!! }
        val tolerance = maxOf(2L, d.items.size.toLong())
        fun settle(f: DraftField, provenBy: Boolean) = if (provenBy) f.copy(uncertain = false) else f
        return d.copy(
            items = d.items.map { it.copy(quantity = it.quantity.copy(uncertain = false), unitPrice = it.unitPrice.copy(uncertain = false), lineTotal = it.lineTotal.copy(uncertain = false)) },
            subtotal = settle(d.subtotal, sub != null && kotlin.math.abs(sub - sum) <= tolerance),
            total = settle(d.total, tot != null && (kotlin.math.abs(tot - sum) <= tolerance || (sub != null && vat != null && kotlin.math.abs(sub + vat - tot) <= 1 && kotlin.math.abs(sub - sum) <= tolerance))),
            vat = settle(d.vat, vat != null && sub != null && tot != null && kotlin.math.abs(sub + vat - tot) <= 1 && kotlin.math.abs(sub - sum) <= tolerance),
        )
    }

    /** The line amounts add up to the printed taxable amount or total. */
    fun sumMatches(d: DocumentDraft): Boolean {
        val lines = d.items.map { cents(it.lineTotal.text) ?: return false }
        if (lines.isEmpty()) return false
        val sum = lines.sum()
        val tolerance = maxOf(2L, lines.size.toLong())
        return listOfNotNull(cents(d.subtotal.text), cents(d.total.text)).any { kotlin.math.abs(sum - it) <= tolerance }
    }

    private fun cents(text: String): Long? {
        val v = ItalianNumbers.parse(text) ?: return null
        if (v.abs() > BigDecimal("10000000")) return null
        return ItalianNumbers.toCents(v)
    }
}
