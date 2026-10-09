package com.kitchenreceipts.core

import java.math.BigDecimal

/**
 * Checks that hold for any supplier, on values the arithmetic cannot judge alone. They never change a value: a
 * value that fails one is only marked for a look.
 *  - A count unit (pieces, packs, cartons, bottles...) with a fractional quantity: the quantity may be right (the
 *    line adds up), but then the unit was misread (it is weighed: kg, l).
 *  - On a document of an Italian supplier, a line's VAT rate must be one Italy has: 22, 10, 5, 4 or 0 %.
 */
object ReadingSanity {

    private val COUNT_UNITS = setOf("pz", "conf", "ct", "cs", "bt", "sacco", "vaschetta", "latta", "mazzo")
    val ITALIAN_VAT_RATES: Set<BigDecimal> = listOf("22", "10", "5", "4", "0").map { BigDecimal(it) }.toSet()

    fun apply(doc: ParsedDocument, documentText: String, ownVatNumber: String?): ParsedDocument {
        val italian = SellerProfiles.supplierVatNumber(documentText, ownVatNumber) != null
        val items = doc.lineItems.map { it ->
            var item = it
            val q = it.quantity?.value
            val unit = it.unit?.let { u -> Units.normalize(u.value) }
            if (q != null && unit in COUNT_UNITS && q.stripTrailingZeros().scale() > 0 && it.unit?.confidence == Confidence.HIGH) {
                item = item.copy(unit = it.unit.copy(confidence = Confidence.LOW, source = it.unit.source + " | fractional quantity for a count unit"))
            }
            val rate = it.vatRatePercent
            if (italian && rate != null && rate.confidence == Confidence.HIGH && ITALIAN_VAT_RATES.none { r -> r.compareTo(rate.value) == 0 }) {
                item = item.copy(vatRatePercent = rate.copy(confidence = Confidence.LOW, source = rate.source + " | not an Italian VAT rate"))
            }
            item
        }
        return if (items == doc.lineItems) doc else doc.copy(lineItems = items)
    }
}
