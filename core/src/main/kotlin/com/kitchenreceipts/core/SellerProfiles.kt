package com.kitchenreceipts.core

import java.text.Normalizer

/** What the app knows about one supplier, learned from documents the operator saved. */
data class SellerCandidate(
    val id: Long,
    val name: String,
    /** Italian VAT number (11 digits), if learned. */
    val vatNumber: String?,
    /** Header words seen on this supplier's documents -> how many documents they appeared on. */
    val profile: Map<String, Int>,
    /** Normalised spellings of the name as the OCR read it, which the operator corrected to this supplier. */
    val aliasKeys: Set<String>,
)

enum class SellerMatchReason { VAT_NUMBER, NAME_ALIAS, LAYOUT }

data class SellerMatch(val sellerId: Long, val name: String, val reason: SellerMatchReason, val score: Double)

/**
 * Learns suppliers ("grossisti") from saved documents and recognises them on new scans:
 * 1. by VAT number (Partita IVA) printed on the document – exact and checksum-validated;
 * 2. by how the OCR misread the name before, when the operator corrected it;
 * 3. by the look of the document header (the set of words printed at the top), when both fail.
 * Everything is computed on the phone from the operator's own saved documents.
 */
object SellerProfiles {

    private val VAT_LABELED = Regex("(?i)(?:p\\.?\\s?iva|partita\\s+iva|vat(?:\\s+n[or]?\\.?)?)[\\s:.#n°]*(?:IT)?\\s?(\\d[\\d ]{9,13}\\d)")
    private val VAT_BARE = Regex("(?<![\\d])(?:IT)?(\\d{11})(?!\\d)")
    private val STOP = setOf(
        "fattura", "documento", "commerciale", "vendita", "prestazione", "scontrino", "ricevuta", "trasporto",
        "numero", "data", "pagina", "totale", "imponibile", "iva", "partita", "codice", "fiscale", "via", "viale",
        "piazza", "corso", "tel", "fax", "email", "mail", "pec", "www", "spett", "cliente", "destinatario",
        "descrizione", "quantita", "prezzo", "importo", "euro", "eur", "del", "della", "delle", "dei", "per",
        "con", "srl", "spa", "snc", "sas", "the", "and", "n", "nr",
    )
    private const val MAX_PROFILE_WORDS = 40

    /** Italian Partita IVA checksum (11 digits, Luhn-like). Filters OCR misreads. */
    fun isValidPartitaIva(s: String): Boolean {
        if (s.length != 11 || !s.all { it.isDigit() } || s == "00000000000") return false
        var sum = 0
        for (i in 0 until 10) {
            var d = s[i] - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return (10 - sum % 10) % 10 == s[10] - '0'
    }

    /** Valid Italian VAT numbers printed in [text], in order of appearance, without duplicates. */
    fun vatNumbers(text: String): List<String> =
        // Labelled numbers ("P.IVA 01234567897") first, then bare 11-digit numbers that pass the checksum.
        (VAT_LABELED.findAll(text).map { it.groupValues[1].replace(" ", "") } + VAT_BARE.findAll(text).map { it.groupValues[1] })
            .filter { isValidPartitaIva(it) }.distinct().toList()

    /** Distinctive words from the top of the document (the supplier's letterhead). */
    fun headerTokens(text: String, maxLines: Int = 12): Set<String> =
        text.lines().filter { it.isNotBlank() }.take(maxLines)
            .flatMap { line -> normalize(line).split(' ') }
            .filter { it.length >= 3 && it.any(Char::isLetter) && it !in STOP && it.count(Char::isDigit) < 3 }
            .toSet()

    fun parseProfile(s: String?): Map<String, Int> =
        s.orEmpty().split(';').mapNotNull { e ->
            val k = e.substringBefore(':', "")
            val v = e.substringAfter(':', "").toIntOrNull()
            if (k.isNotEmpty() && v != null) k to v else null
        }.toMap()

    /** Adds one document's header words to a stored profile; keeps the most frequent words. */
    fun mergeProfile(existing: String?, tokens: Set<String>): String {
        val counts = parseProfile(existing).toMutableMap()
        tokens.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_PROFILE_WORDS)
            .joinToString(";") { "${it.key}:${it.value}" }
    }

    /** Share of the supplier's usual header words (weighted by how often they appear) found on this document. */
    fun similarity(profile: Map<String, Int>, tokens: Set<String>): Double {
        val total = profile.values.sum()
        if (total == 0) return 0.0
        return profile.filterKeys { it in tokens }.values.sum().toDouble() / total
    }

    /**
     * Recognises the supplier of a new document.
     * [ownVatNumber] is the operator's own business VAT number, which also appears on invoices and is ignored.
     */
    fun identify(
        candidates: List<SellerCandidate>,
        documentText: String,
        ocrSellerName: String?,
        ownVatNumber: String? = null,
    ): SellerMatch? {
        if (candidates.isEmpty()) return null
        val own = ownVatNumber?.filter(Char::isDigit)
        val vats = vatNumbers(documentText).filter { it != own }
        for (v in vats) {
            candidates.firstOrNull { it.vatNumber == v }?.let { return SellerMatch(it.id, it.name, SellerMatchReason.VAT_NUMBER, 1.0) }
        }
        val key = DuplicateDetector.normalizeSeller(ocrSellerName)
        if (key != null) {
            candidates.firstOrNull { key in it.aliasKeys }?.let { return SellerMatch(it.id, it.name, SellerMatchReason.NAME_ALIAS, 0.9) }
        }
        val tokens = headerTokens(documentText)
        val scored = candidates
            .filter { c -> c.profile.values.sum() > 0 }
            .map { it to similarity(it.profile, tokens) }
            .sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)?.second ?: 0.0
        val matchedWords = best.first.profile.keys.count { it in tokens }
        return if (best.second >= 0.6 && matchedWords >= 3 && best.second - second >= 0.15) {
            SellerMatch(best.first.id, best.first.name, SellerMatchReason.LAYOUT, best.second)
        } else {
            null
        }
    }

    /** The first VAT number on the document that is not the operator's own: the supplier's. */
    fun supplierVatNumber(documentText: String, ownVatNumber: String?): String? {
        val own = ownVatNumber?.filter(Char::isDigit)
        return vatNumbers(documentText).firstOrNull { it != own }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
