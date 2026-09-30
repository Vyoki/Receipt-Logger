package com.kitchenreceipts.core

import java.math.BigDecimal
import java.time.LocalDate

/**
 * How sure the parser is about an extracted value.
 * HIGH means the value was found next to an explicit label or cross-checked arithmetically.
 * LOW means it was inferred by position or heuristics and must be highlighted for review.
 */
enum class Confidence { HIGH, LOW }

/** A value found in the OCR text, with the snippet of text it came from. */
data class Extracted<T>(
    val value: T,
    val confidence: Confidence,
    val source: String,
)

/** Whether a price / line total includes VAT. Never mixed in one average. */
enum class VatBasis { INCLUSIVE, EXCLUSIVE, UNKNOWN }

enum class ParseWarning {
    /** No line items could be recognised. */
    NO_ITEMS_FOUND,
    /** quantity x unit price does not match the line total on at least one line. */
    LINE_TOTAL_MISMATCH,
    /** The sum of line totals matches neither the subtotal nor the total. */
    ITEMS_SUM_MISMATCH,
    /** subtotal + VAT does not match the total. */
    TOTALS_INCONSISTENT,
    /** A value labelled as a lot looked like a date, so it was not used as a lot number. */
    LOT_LOOKS_LIKE_DATE,
    /** Several different total-like amounts were found. */
    MULTIPLE_TOTALS,
    /** The lines of one VAT rate do not add up to that rate's taxable amount in the VAT summary. */
    VAT_GROUP_MISMATCH,
}

data class ParsedLineItem(
    /** Description exactly as read from the document (lot/expiry fragments removed). */
    val originalDescription: String,
    val quantity: Extracted<BigDecimal>?,
    val unit: Extracted<String>?,
    val unitPrice: Extracted<BigDecimal>?,
    val lineTotalCents: Extracted<Long>?,
    val vatRatePercent: Extracted<BigDecimal>?,
    val lotNumber: Extracted<String>?,
    /** Kept separately so an expiry date is never stored as a lot number. */
    val expiryDate: Extracted<LocalDate>?,
    val warnings: Set<ParseWarning> = emptySet(),
    /** Supplier's article code printed at the start of the line ("2046225"), if any. */
    val itemCode: String? = null,
    /** "Colli": number of packages/cartons as printed ("5", "1x6"). Never part of the description or the quantity. */
    val packages: Extracted<String>? = null,
    /** Several readings of this line's numbers add up equally well: the AI or the operator picks one. */
    val choices: List<LineChoice> = emptyList(),
)

data class ParsedDocument(
    val sellerName: Extracted<String>?,
    val documentDate: Extracted<LocalDate>?,
    val documentNumber: Extracted<String>?,
    val currency: Extracted<String>?,
    val subtotalCents: Extracted<Long>?,
    val vatCents: Extracted<Long>?,
    val totalCents: Extracted<Long>?,
    val vatBasis: Extracted<VatBasis>?,
    val lineItems: List<ParsedLineItem>,
    val warnings: Set<ParseWarning>,
    /** How the line items were read: "columns" (table layout from word positions) or "text". */
    val itemsReadBy: String = "text",
    /** Lines per VAT rate against the printed VAT summary (empty when there is no summary to check). */
    val vatChecks: List<VatSummary.Check> = emptyList(),
    /** The document prints lot numbers (a lot column or "lotto" anywhere): a line without one is worth a look. */
    val lotsPrinted: Boolean = true,
) {
    companion object {
        val EMPTY = ParsedDocument(
            null, null, null, null, null, null, null, null, emptyList(), setOf(ParseWarning.NO_ITEMS_FOUND),
        )
    }
}
