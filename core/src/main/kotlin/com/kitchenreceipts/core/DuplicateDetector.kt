package com.kitchenreceipts.core

import java.text.Normalizer
import java.time.LocalDate

data class DocumentFingerprint(
    val id: Long,
    val sellerName: String?,
    val documentNumber: String?,
    val date: LocalDate?,
    val totalCents: Long?,
    val fileSha256: String?,
    /** The supplier's VAT number (11 digits), when known: the same number is the same supplier, however the name reads. */
    val sellerVat: String? = null,
    /** A reverse-charge form's (see ReverseCharge): the number and date of the supplier's own invoice it completes. */
    val refNumber: String? = null,
    val refDate: LocalDate? = null,
)

enum class DuplicateReason {
    /** Exactly the same file bytes were already imported. */
    SAME_FILE,
    /** Same seller (by name or VAT number) and same document number. */
    SAME_SELLER_AND_NUMBER,
    /** Same seller (by name or VAT number), same date and same total. */
    SAME_SELLER_DATE_TOTAL,
    /** Different or unknown seller, but same date and same total (weaker hint). */
    SAME_DATE_AND_TOTAL,
    /**
     * A reverse-charge form (TD16-TD19) and the supplier's own invoice it completes (same supplier, the number the
     * form names): one purchase, logged twice if both are saved.
     */
    SAME_PURCHASE,
}

data class DuplicateMatch(val existingId: Long, val reasons: Set<DuplicateReason>) {
    val isStrong: Boolean get() = reasons.any { it != DuplicateReason.SAME_DATE_AND_TOTAL }
}

object DuplicateDetector {

    private val LEGAL_SUFFIX = Regex("\\b(srls|srl|spa|snc|sas|soc coop|coop|societa|di)\\b")

    fun normalizeSeller(name: String?): String? = if (name.isNullOrBlank()) null else sellerKeys(name)
    private val sellerKeys = Memo { name: String ->
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replace(rx("\\p{M}+"), "")
        val s = ascii.lowercase()
            .replace(rx("\\b([a-z])\\.(?=[a-z]\\.)"), "$1") // s.r.l. -> srl.
            .replace(rx("[^a-z0-9 ]"), " ")
            .replace(rx("\\b(s r l s|s r l|s p a|s n c|s a s)\\b")) { it.value.replace(" ", "") }
        LEGAL_SUFFIX.replace(s, " ").replace(rx("\\s+"), " ").trim().ifEmpty { null }
    }

    /** "FT 0145/2025" and "ft-145/2025" normalise the same way. */
    fun normalizeNumber(number: String?): String? {
        if (number.isNullOrBlank()) return null
        val parts = number.uppercase().split(rx("[^A-Z0-9]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.joinToString("/") { p -> if (p.all { it.isDigit() }) p.trimStart('0').ifEmpty { "0" } else p }
    }

    /** The 11 digits of an Italian VAT number ("IT 01234567897" too); anything else is not used. */
    private fun vatDigits(vat: String?): String? =
        vat?.filter { it.isDigit() }?.takeIf { it.length == 11 && it.trimStart('0').isNotEmpty() }

    fun findDuplicates(candidate: DocumentFingerprint, existing: List<DocumentFingerprint>): List<DuplicateMatch> {
        val cSeller = normalizeSeller(candidate.sellerName)
        val cNumber = normalizeNumber(candidate.documentNumber)
        val cVat = vatDigits(candidate.sellerVat)
        return existing.asSequence()
            .filter { it.id != candidate.id }
            .mapNotNull { e ->
                val reasons = mutableSetOf<DuplicateReason>()
                if (candidate.fileSha256 != null && candidate.fileSha256 == e.fileSha256) reasons += DuplicateReason.SAME_FILE
                // A name read differently ("Rossi Carni" for "Rossi Carni S.r.l. Unipersonale") is still the same
                // supplier when both carry the same VAT number.
                val sameSeller = (cSeller != null && cSeller == normalizeSeller(e.sellerName)) ||
                    (cVat != null && cVat == vatDigits(e.sellerVat))
                // Invoice numbers start again every year: number 12 of last year is another document.
                val sameYear = candidate.date == null || e.date == null || candidate.date.year == e.date.year
                if (sameSeller && sameYear && cNumber != null && cNumber == normalizeNumber(e.documentNumber)) {
                    reasons += DuplicateReason.SAME_SELLER_AND_NUMBER
                }
                // The form names the invoice by its number (and date): either may be saved first.
                fun completes(form: DocumentFingerprint, invoice: DocumentFingerprint): Boolean {
                    val ref = normalizeNumber(form.refNumber) ?: return false
                    if (ref != normalizeNumber(invoice.documentNumber)) return false
                    return if (form.refDate != null && invoice.date != null) form.refDate == invoice.date
                    else form.date == null || invoice.date == null || form.date.year == invoice.date.year
                }
                if (sameSeller && (completes(candidate, e) || completes(e, candidate))) reasons += DuplicateReason.SAME_PURCHASE
                val sameDateTotal = candidate.date != null && candidate.date == e.date &&
                    candidate.totalCents != null && candidate.totalCents == e.totalCents
                if (sameDateTotal) {
                    reasons += if (sameSeller) DuplicateReason.SAME_SELLER_DATE_TOTAL else DuplicateReason.SAME_DATE_AND_TOTAL
                }
                if (reasons.isEmpty()) null else DuplicateMatch(e.id, reasons)
            }
            .sortedByDescending { it.reasons.size }
            .toList()
    }
}
