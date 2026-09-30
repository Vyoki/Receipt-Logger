package com.kitchenreceipts.core

/**
 * What the app learns from the operator, per supplier, so the same doubt is not asked twice and the AI sees how
 * that supplier's lines were read before. Only this: remembered choices ([ChoiceRule]) and a few confirmed lines
 * ([AiReader.RowExample]). The AI model itself is never changed.
 */
object SupplierMemory {

    const val MAX_EXAMPLES = 3

    /** The supplier's key: its Partita IVA when printed (reliable), else its normalised name. */
    fun key(documentText: String, ownVatNumber: String?, sellerName: String?): String? =
        SellerProfiles.supplierVatNumber(documentText, ownVatNumber)?.let { "vat:$it" }
            ?: DuplicateDetector.normalizeSeller(sellerName)?.takeIf { it.isNotBlank() }?.let { "name:$it" }

    /**
     * Lines of a saved document worth showing the AI next time: complete, confirmed, with the row as the OCR
     * read it. Lines the operator had to correct come first (they teach the most).
     */
    fun examplesFrom(saved: DocumentDraft, corrected: Set<Long> = emptySet()): List<AiReader.RowExample> =
        saved.items.asSequence()
            .filter { it.quantity.text.isNotBlank() && it.unitPrice.text.isNotBlank() && it.lineTotal.text.isNotBlank() }
            .filter { it.uncertainCount == 0 && it.choices.isEmpty() }
            .mapNotNull { item ->
                val row = item.lineTotal.source ?: item.quantity.source ?: return@mapNotNull null
                if (row.length > 300 || row.contains('\n') && row.length > 200) return@mapNotNull null
                item.key to AiReader.RowExample(
                    rowText = row, code = item.itemCode, colli = item.packages.text.ifBlank { null },
                    description = item.description.text, unit = item.unit.text.ifBlank { null },
                    quantity = item.quantity.text, price = item.unitPrice.text, amount = item.lineTotal.text,
                    vatRate = item.vatRate.text.ifBlank { null },
                )
            }
            .sortedByDescending { it.first in corrected }
            .map { it.second }
            .take(MAX_EXAMPLES)
            .toList()

    /** Newest examples first, no two for the same row, at most [MAX_EXAMPLES]. */
    fun mergeExamples(old: List<AiReader.RowExample>, new: List<AiReader.RowExample>): List<AiReader.RowExample> =
        (new + old).distinctBy { it.rowText }.take(MAX_EXAMPLES)
}
