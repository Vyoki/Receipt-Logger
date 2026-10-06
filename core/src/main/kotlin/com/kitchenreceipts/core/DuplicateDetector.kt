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
)

enum class DuplicateReason {
    /** Exactly the same file bytes were already imported. */
    SAME_FILE,
    /** Same seller and same document number. */
    SAME_SELLER_AND_NUMBER,
    /** Same seller, same date and same total. */
    SAME_SELLER_DATE_TOTAL,
    /** Different or unknown seller, but same date and same total (weaker hint). */
    SAME_DATE_AND_TOTAL,
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

    fun findDuplicates(candidate: DocumentFingerprint, existing: List<DocumentFingerprint>): List<DuplicateMatch> {
        val cSeller = normalizeSeller(candidate.sellerName)
        val cNumber = normalizeNumber(candidate.documentNumber)
        return existing.asSequence()
            .filter { it.id != candidate.id }
            .mapNotNull { e ->
                val reasons = mutableSetOf<DuplicateReason>()
                if (candidate.fileSha256 != null && candidate.fileSha256 == e.fileSha256) reasons += DuplicateReason.SAME_FILE
                val sameSeller = cSeller != null && cSeller == normalizeSeller(e.sellerName)
                if (sameSeller && cNumber != null && cNumber == normalizeNumber(e.documentNumber)) {
                    reasons += DuplicateReason.SAME_SELLER_AND_NUMBER
                }
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
