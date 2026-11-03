package com.kitchenreceipts.core

import java.math.BigDecimal

/**
 * Lines added up as printed. An e-invoice prints amounts with up to 8 decimals ("32,78688525"); its taxable amount is
 * their sum rounded once, not the sum of each rounded to the cent (4 x 32,78688525 = 131,15; 4 x 32,79 = 131,16).
 */
object Money {
    /** The lines' amounts added up exactly and rounded to the cent; null when a line has no amount. */
    fun linesSum(items: List<LineItemDraft>): Long? {
        if (items.isEmpty()) return null
        var sum = BigDecimal.ZERO
        for (l in items) sum += l.preciseTotal() ?: return null
        return ItalianNumbers.toCents(sum)
    }

    /** The same for read lines: each line's exact amount when it has one, else its cents. Null when a line has no amount. */
    fun parsedSum(items: List<ParsedLineItem>): Long? {
        if (items.isEmpty()) return null
        var sum = BigDecimal.ZERO
        for (l in items) sum += exact(l) ?: return null
        return ItalianNumbers.toCents(sum)
    }

    /** A read line's amount as printed: the exact one when it agrees with its cents, else its cents. */
    fun exact(l: ParsedLineItem): BigDecimal? {
        val c = l.lineTotalCents?.value ?: return null
        val e = l.exactTotal
        return if (e != null && ItalianNumbers.toCents(e) == c) e else ItalianNumbers.centsToDecimal(c)
    }
}
