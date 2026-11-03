package com.kitchenreceipts.core

/**
 * Why an invoice waits for a check, counted on the phone over a file of many (nothing leaves it): which kinds of
 * values are still unsure, and why its lines do not add up to the printed taxable amount or total.
 */
object ReadingDetails {

    /** The kinds of values left unsure in [d] (after the arithmetic proved what it could), in a fixed order. */
    fun doubts(d: DocumentDraft): Set<String> {
        val out = linkedSetOf<String>()
        fun f(kind: String, x: DraftField) { if (x.uncertain) out += kind }
        f(SUPPLIER, d.seller); f(NUMBER, d.number); f(DATE, d.date)
        f(TAXABLE, d.subtotal); f(VAT, d.vat); f(TOTAL, d.total)
        if (d.vatBasisUncertain) out += VAT_BASIS
        for (l in d.items) {
            f(NAME, l.description); f(QUANTITY, l.quantity); f(UNIT, l.unit); f(PRICE, l.unitPrice); f(AMOUNT, l.lineTotal)
            f(VAT_RATE, l.vatRate); f(LOT, l.lot); f(EXPIRY, l.expiry)
            if (l.packages.uncertain || l.packSize.uncertain) out += PACK
            f(DISCOUNT, l.discount)
        }
        return out
    }

    const val SUPPLIER = "supplier"
    const val NUMBER = "number"
    const val DATE = "date"
    const val TAXABLE = "taxable"
    const val VAT = "vat"
    const val TOTAL = "total"
    const val VAT_BASIS = "vatBasis"
    const val NAME = "name"
    const val QUANTITY = "quantity"
    const val UNIT = "unit"
    const val PRICE = "price"
    const val AMOUNT = "amount"
    const val VAT_RATE = "vatRate"
    const val LOT = "lot"
    const val EXPIRY = "expiry"
    const val PACK = "pack"
    const val DISCOUNT = "discount"

    /** Why the lines do not add up, most telling first. */
    enum class SumCause {
        /** A few cents apart: rounding, or a cent misread. */
        CENTS,
        /** One line's amount is not its quantity x price, and quantity x price would make the lines add up. */
        AMOUNT_MISREAD,
        /** The difference is one line's amount, printed on two lines: a line read twice. */
        LINE_TWICE,
        /** The difference is one line's amount: a line that is no product (a heading, a subtotal) read as one. */
        LINE_EXTRA,
        /** The difference is twice a discount or charge line: it was counted the wrong way. */
        DISCOUNT_SIGN,
        /** The lines add up to less: some were not read (a line, or a page). */
        LINES_SHORT,
        /** The lines add up to more, and no single line explains it. */
        LINES_OVER,
    }

    /**
     * Why the lines of [d] do not add up to its taxable amount or total; null when they do, or when it cannot be told
     * (no amount on a line, no taxable amount or total read).
     */
    fun sumCause(d: DocumentDraft): SumCause? {
        if (d.items.isEmpty() || AutoAccept.sumMatches(d)) return null
        val amounts = d.items.map { ItalianNumbers.parseCents(it.lineTotal.text) ?: return null }
        val targets = listOfNotNull(ItalianNumbers.parseCents(d.subtotal.text), ItalianNumbers.parseCents(d.total.text))
        if (targets.isEmpty()) return null
        val sum = Money.linesSum(d.items) ?: amounts.sum()
        if (targets.any { kotlin.math.abs(sum - it) <= maxOf(2L, amounts.size.toLong()) }) return SumCause.CENTS
        // A line whose own arithmetic gives the amount that closes the sum.
        val misread = d.items.indices.any { i ->
            val computed = d.items[i].computedTotalCents() ?: return@any false
            computed != amounts[i] && targets.any { t -> sum - amounts[i] + computed == t }
        }
        if (misread) return SumCause.AMOUNT_MISREAD
        // One line too many (to the taxable amount or to the total).
        for (t in targets) {
            val same = amounts.count { it == sum - t }
            if (sum > t && same >= 2) return SumCause.LINE_TWICE
        }
        if (targets.any { t -> sum > t && amounts.count { it == sum - t } == 1 }) return SumCause.LINE_EXTRA
        val charged = d.items.indices.filter { d.items[it].isCharge && amounts[it] != 0L }.map { kotlin.math.abs(amounts[it]) }
        if (targets.any { t -> charged.any { c -> kotlin.math.abs(sum - t) == 2 * c } }) return SumCause.DISCOUNT_SIGN
        // Short or over against what the lines should add up to: the taxable amount when prices are without VAT.
        val sub = ItalianNumbers.parseCents(d.subtotal.text)
        val tot = ItalianNumbers.parseCents(d.total.text)
        val target = when (d.vatBasis) {
            VatBasis.EXCLUSIVE -> sub ?: tot
            VatBasis.INCLUSIVE -> tot ?: sub
            else -> targets.minBy { kotlin.math.abs(sum - it) }
        }!!
        val diff = sum - target
        return if (diff < 0) SumCause.LINES_SHORT else SumCause.LINES_OVER
    }
}
