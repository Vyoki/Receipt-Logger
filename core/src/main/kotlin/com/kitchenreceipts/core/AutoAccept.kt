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
    /** The lines of one VAT rate do not add up to the VAT summary (a line was misread or missed). */
    VAT_GROUP_MISMATCH,
    /** The document prints lot numbers, but some lines have none (traceability: every lot must be recorded). */
    LOTS_MISSING,
    /** An e-invoice addressed to another company. */
    OTHER_BUYER,
    /** A price 30% or more away from what was paid before for the product (plausible, but worth a look). */
    UNUSUAL_PRICE,
    /** The same page was photographed twice (and another page maybe not at all). */
    DUPLICATE_PAGE,
    /** Pages the document numbers were not photographed, and the sums do not prove no line is missing. */
    PAGES_MISSING,
    /** The document's own sums made the reading add or leave out lines: the operator confirms them. */
    CHECKED_BY_TOTALS,
    /** One invoice cut from a file of many whose pages may be cut or joined wrongly (see InvoiceBundle.Flag). */
    BUNDLE_CHECK,
    /** A reverse-charge form (TD16-TD19): logged without the VAT it prints; the operator sees why once. */
    REVERSE_CHARGE,
}

/**
 * Decides when a scan can be saved without the operator touching it: every field was read with
 * confidence and the arithmetic proves it (the lines add up to the printed taxable amount or total).
 * If anything is missing or doubtful the document goes to review, with the reasons listed.
 */
object AutoAccept {

    /**
     * [bundleFlags]: the flags of the invoice's pages when it was cut from a file of many invoices (InvoiceBundle.Flag
     * names). Any flag holds it for the operator: its pages may be cut or joined wrongly.
     */
    fun reasons(d: DocumentDraft, bundleFlags: Set<String> = emptySet()): List<ReviewReason> {
        val out = mutableListOf<ReviewReason>()
        if (d.seller.isMissing) out += ReviewReason.SELLER_MISSING
        if (d.date.isMissing || ItalianDates.parse(d.date.text) == null) out += ReviewReason.DATE_MISSING
        val total = cents(d.total.text)
        if (total == null) out += ReviewReason.TOTAL_MISSING
        if (d.items.isEmpty()) out += ReviewReason.NO_ITEMS
        if (d.uncertainCount > 0) out += ReviewReason.UNCERTAIN_VALUES
        // A VAT rate on some lines but not on others: one was not read.
        // Discount and charge lines need only their amount (no quantity, unit or VAT rate of their own).
        val goods = d.items.filter { !it.isCharge }
        val someRates = goods.any { it.vatRate.text.isNotBlank() }
        if (goods.any { it.description.isMissing || ItalianNumbers.parse(it.quantity.text) == null || it.unit.isMissing } ||
            d.items.any { cents(it.lineTotal.text) == null } ||
            (someRates && goods.any { it.vatRate.text.isBlank() })
        ) {
            out += ReviewReason.INCOMPLETE_ITEMS
        }
        // Lots read on some lines but not on others: the missing ones were not read.
        if (d.lotsPrinted && goods.any { it.lot.text.isNotBlank() } && goods.any { it.lot.text.isBlank() }) out += ReviewReason.LOTS_MISSING
        val sumOk = sumMatches(d)
        if (d.items.isNotEmpty() && total != null && ReviewReason.INCOMPLETE_ITEMS !in out && !sumOk) {
            out += ReviewReason.SUM_MISMATCH
        }
        if (d.vatGroupProblems.isNotEmpty()) out += ReviewReason.VAT_GROUP_MISMATCH
        if (ParseWarning.OTHER_BUYER in d.warnings) out += ReviewReason.OTHER_BUYER
        if (ParseWarning.DUPLICATE_PAGE in d.warnings) out += ReviewReason.DUPLICATE_PAGE
        if (ParseWarning.PAGES_MISSING in d.warnings) out += ReviewReason.PAGES_MISSING
        if (ParseWarning.CHECKED_BY_TOTALS in d.warnings) out += ReviewReason.CHECKED_BY_TOTALS
        if (ParseWarning.REVERSE_CHARGE in d.warnings) out += ReviewReason.REVERSE_CHARGE
        if (d.vatBasis == VatBasis.UNKNOWN && inferVatBasis(d) == null && sumOk) {
            out += ReviewReason.VAT_BASIS_UNKNOWN
        }
        if (bundleFlags.isNotEmpty()) out += ReviewReason.BUNDLE_CHECK
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
        // Prices without VAT only when the document shows VAT on top: a total above the taxable amount, or a VAT
        // amount. "Subtotal = total" with no VAT printed is a receipt with VAT inside as much as a net one: asked.
        if (subtotal != null && kotlin.math.abs(sum - subtotal) <= tolerance &&
            (total == null || total > subtotal || (vat != null && vat > 0))
        ) {
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
            if (it.isCharge) return@all cents(it.lineTotal.text) != null
            val q = ItalianNumbers.parse(it.quantity.text) ?: return@all false
            val p = ItalianNumbers.parse(it.unitPrice.text) ?: return@all false
            val t = cents(it.lineTotal.text) ?: return@all false
            LineDiscount.matches(q, p, it.discount.text, t)
        }
        if (!lineOk) return d
        val sub = cents(d.subtotal.text); val vat = cents(d.vat.text); val tot = cents(d.total.text)
        val headerOk = sub == null || vat == null || tot == null || totalsAddUp(sub, vat, tot)
        if (!headerOk) return d
        val sum = d.items.sumOf { cents(it.lineTotal.text)!! }
        // Settled only to the cent (see sumMatches).
        val tolerance = 0L
        // A value the AI's double-check read differently stays highlighted, whatever the arithmetic says.
        fun disputed(f: DraftField) = f.source?.contains(AiReader.DISAGREE) == true
        fun settle(f: DraftField, provenBy: Boolean) = if (provenBy && !disputed(f)) f.copy(uncertain = false) else f
        fun clear(f: DraftField) = if (disputed(f)) f else f.copy(uncertain = false)
        // A quantity worked out as amount / price was never printed: the sums prove the amounts, not that quantity.
        fun clearQuantity(f: DraftField) = if (f.source?.endsWith(ReceiptParser.WORKED_OUT) == true) f else clear(f)
        // Likewise a price taken as the amount of one piece (no price printed).
        fun clearPrice(f: DraftField) = if (f.source?.endsWith(ReceiptParser.FROM_AMOUNT) == true) f else clear(f)
        return d.copy(
            items = d.items.map { it.copy(quantity = clearQuantity(it.quantity), unitPrice = clearPrice(it.unitPrice), lineTotal = clear(it.lineTotal), discount = clear(it.discount)) },
            subtotal = settle(d.subtotal, sub != null && kotlin.math.abs(sub - sum) <= tolerance),
            total = settle(d.total, tot != null && (kotlin.math.abs(tot - sum) <= tolerance || (sub != null && vat != null && totalsAddUp(sub, vat, tot) && kotlin.math.abs(sub - sum) <= tolerance))),
            vat = settle(d.vat, vat != null && sub != null && tot != null && totalsAddUp(sub, vat, tot) && kotlin.math.abs(sub - sum) <= tolerance),
        )
    }

    /**
     * The document's money is proven by its own arithmetic, to the cent: every line has its amount, and quantity x
     * price = amount on every goods line; the lines add up to the printed taxable amount or total; taxable + VAT =
     * total when all three are printed; the VAT summary agrees; no page is missing and nothing was added or left out
     * by the totals check. After [settleProven], no money field is left uncertain (a quantity worked out from the
     * amount, or a value the AI read differently, stays uncertain and so is not proven).
     *
     * What arithmetic cannot prove (the supplier, the date, the number, a lot) is not part of it: those still go to
     * the operator when doubtful. Used to spare the slow AI double-check on a PDF's exact text (see ImportProcessor).
     */
    fun moneyProven(d: DocumentDraft): Boolean {
        if (d.items.isEmpty() || !sumMatches(d) || d.vatGroupProblems.isNotEmpty()) return false
        if (d.warnings.any { it in MONEY_DOUBTS }) return false
        val lineOk = d.items.all {
            if (cents(it.lineTotal.text) == null) return@all false
            if (it.isCharge) return@all true
            val q = ItalianNumbers.parse(it.quantity.text) ?: return@all false
            val p = ItalianNumbers.parse(it.unitPrice.text) ?: return@all false
            LineDiscount.matches(q, p, it.discount.text, cents(it.lineTotal.text)!!)
        }
        if (!lineOk) return false
        val sub = cents(d.subtotal.text); val vat = cents(d.vat.text); val tot = cents(d.total.text) ?: return false
        if (sub != null && vat != null && !totalsAddUp(sub, vat, tot)) return false
        val s = settleProven(d)
        if (s.total.uncertain || s.subtotal.uncertain || s.vat.uncertain) return false
        return s.items.all { !it.lineTotal.uncertain && !it.quantity.uncertain && !it.unitPrice.uncertain && !it.discount.uncertain }
    }

    private val MONEY_DOUBTS = setOf(
        ParseWarning.LINE_TOTAL_MISMATCH, ParseWarning.ITEMS_SUM_MISMATCH, ParseWarning.TOTALS_INCONSISTENT,
        ParseWarning.MULTIPLE_TOTALS, ParseWarning.VAT_GROUP_MISMATCH, ParseWarning.PAGES_MISSING,
        ParseWarning.DUPLICATE_PAGE, ParseWarning.CHECKED_BY_TOTALS, ParseWarning.NO_ITEMS_FOUND,
    )

    /**
     * The line amounts add up to the printed taxable amount or total, to the cent: printed documents are exact, and a
     * sum a few cents off is a misread digit somewhere (a line or the total), never a proof.
     */
    fun sumMatches(d: DocumentDraft): Boolean {
        val lines = d.items.map { cents(it.lineTotal.text) ?: return false }
        if (lines.isEmpty()) return false
        val sum = lines.sum()
        return listOfNotNull(cents(d.subtotal.text), cents(d.total.text)).any { sum == it }
    }

    private fun cents(text: String): Long? {
        val v = ItalianNumbers.parse(text) ?: return null
        if (v.abs() > BigDecimal("10000000")) return null
        return ItalianNumbers.toCents(v)
    }
}
