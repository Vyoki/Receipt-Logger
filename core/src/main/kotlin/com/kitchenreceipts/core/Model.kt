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
    /** An e-invoice addressed to another company (the buyer's VAT number is not this restaurant's). */
    OTHER_BUYER,
    /** A page is a second photo of another page: read once; the page that was never photographed is missing. */
    DUPLICATE_PAGE,
    /** The document numbers its pages ("1/6") and some were not photographed, and the sums do not prove nothing is missing. */
    PAGES_MISSING,
    /** The document's own sums made the reading add or leave out lines (see Reconcile): shown for the operator to confirm. */
    CHECKED_BY_TOTALS,
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
    /** How much one pack holds, as printed ("500 g" for "GR 500"): the quantity counts packs of this size. */
    val packSize: Extracted<String>? = null,
    /**
     * The name was printed on another row than the numbers and taken from the row below them (a tilted photo reads
     * the numbers first). Arithmetic proves the numbers, never which name goes with them: someone (the AI, or the
     * operator) must confirm it.
     */
    val nameDoubt: Boolean = false,
    /** A discount or charge line ("Sconto del 4%", "Spese bancarie"): part of the total, never a product (see Adjustments). */
    val adjustment: Boolean = false,
    /** The discount printed on the line, in percent ("30", "10+5"): quantity x price less it is the amount (see LineDiscount). */
    val discount: Extracted<String>? = null,
    /**
     * What the AI read for this line where it differs and was not taken ("description", "quantity", "amount"):
     * offered to the operator as a replacement, never applied by itself.
     */
    val aiRead: Map<String, String> = emptyMap(),
    /**
     * What the page says about this line through its own codes and group lines (see PageCodes): the storage letter
     * and its meaning from the page's legend ("F" = "fresco"), the row type, the group printed above the line.
     */
    val marks: List<ItemMark> = emptyList(),
    /**
     * Why the name needs a look although its numbers may be proven ("the name continues on the next printed line"):
     * the name is shown uncertain, so the document is not saved by itself. The AI agreeing with the name does not
     * clear it.
     */
    val nameNote: String? = null,
    /**
     * The name's first printed line, when the name wrapped onto the next line and was joined (see TableReader): what
     * earlier documents may have saved the product under.
     */
    val nameFirstLine: String? = null,
)

/** One code the page explains ([kind] "storage", "tipo riga", "group"...): [code] as printed (none for a group line), [meaning] from the page. */
data class ItemMark(val kind: String, val code: String?, val meaning: String) {
    companion object {
        /** Kept in one text column: one mark per line, "kind<TAB>code<TAB>meaning" (null when there are none). */
        fun encode(marks: List<ItemMark>): String? = marks.takeIf { it.isNotEmpty() }?.joinToString("\n") { m ->
            listOf(m.kind, m.code ?: "", m.meaning).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
        }
        fun decode(text: String?): List<ItemMark> = text.orEmpty().lines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size == 3 && p[0].isNotBlank() && p[2].isNotBlank()) ItemMark(p[0], p[1].ifEmpty { null }, p[2]) else null
        }
    }
}

/** The AI's double-check: how many values it read again, and where it read something else ("line 12 amount: 25,34 / AI 25,84"). */
data class AiCheck(
    val checked: Int,
    val disagreements: List<String>,
    /** What the AI read in the header and totals where it differs from the reading ("seller", "number", "date", "subtotal", "vat", "total"). */
    val header: Map<String, String> = emptyMap(),
    /** The same disagreements, for the screen to word in the operator's language. */
    val details: List<AiDisagreement> = emptyList(),
)

/**
 * One value the AI read differently: [field] is "name", "quantity", "amount" (of line [line], from 1), or "supplier",
 * "number", "date", "subtotal", "vat", "total"; [ours] and [ai] are written the Italian way ("12,50", "03/09/2026").
 */
data class AiDisagreement(val field: String, val line: Int?, val ours: String, val ai: String)

/** Where the supplier's VAT number was read. */
enum class SupplierVatSource {
    /** In the supplier's own box, under its role label ("Cedente/prestatore"): never the customer's number. */
    ROLE_BOX,
    /** Anywhere else on the page (the letterhead, a footer). */
    OTHER,
}

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
    /** The AI's double-check of this reading, if it ran. */
    val aiCheck: AiCheck? = null,
    /** What was used from the supplier's learned layout (or the AI's answer about the headings); null = nothing. */
    val layout: SupplierLayout? = null,
    /** Lot numbers were found on the row under each item. */
    val lotsUnderItems: Boolean = false,
    /** Lots were found at the end of product names (see [LotsInNames]). */
    val lotsInNames: Boolean = false,
    /** Most likely handwritten (or a very poor photo): the OCR was unsure of many words (see Handwriting). */
    val handwritten: Boolean = false,
    /** Where the supplier's name was read and whether those places agree (see [SupplierProof]); null = not checked. */
    val supplierProof: SupplierProof.Result? = null,
    /** What the document's own sums (VAT summary, packages, page count) say about this reading (see [Reconcile]). */
    val reconciled: Reconcile.Report? = null,
    /** Where the supplier's VAT number was read; null when none was found (or not looked for). */
    val supplierVatSource: SupplierVatSource? = null,
) {
    companion object {
        val EMPTY = ParsedDocument(
            null, null, null, null, null, null, null, null, emptyList(), setOf(ParseWarning.NO_ITEMS_FOUND),
        )
    }
}

/**
 * Taxable amount + VAT = total, to the cent. Printed documents are exact; a reading off by a cent (a "1" read as
 * "l", a 7 as a 1) must not count as proven. Searches for which printed number is which may allow a cent; this is
 * the proof.
 */
fun totalsAddUp(subtotalCents: Long, vatCents: Long, totalCents: Long): Boolean = subtotalCents + vatCents == totalCents
