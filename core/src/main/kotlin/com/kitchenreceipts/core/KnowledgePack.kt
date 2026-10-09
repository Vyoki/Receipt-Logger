package com.kitchenreceipts.core

/**
 * Public knowledge that comes INTO the phone (never anything going out): suppliers known by their VAT number, so a
 * supplier never seen on this phone is still named right from its first document. The same file for everyone:
 * bundled with the app, opened from a file, or downloaded whole (see the app's network setting).
 */
data class KnowledgePack(
    /** Pack date, "yyyy-MM-dd" (newer packs replace older ones). */
    val version: String,
    val suppliers: List<Supplier> = emptyList(),
) {
    /** A company as registered: [vat] is the number without country prefix, [country] the ISO code ("IT"). */
    data class Supplier(val country: String, val vat: String, val name: String)

    /** A supplier of the pack recognised on a document, and whether the name read agrees with it. */
    data class Found(val supplier: Supplier, val agreesWithReading: Boolean)

    private val byVat: Map<String, Supplier> by lazy { suppliers.associateBy { it.vat.filter(Char::isLetterOrDigit).uppercase() } }

    /**
     * The supplier of a document, by the supplier's VAT number printed on it (checksum-valid, chosen as the supplier's
     * and not the customer's, never the operator's own). Null when the number is not in the pack.
     */
    fun supplierOf(documentText: String, ownVatNumber: String?, readName: String?): Found? {
        if (byVat.isEmpty()) return null
        val vat = SellerProfiles.supplierVatNumber(documentText, ownVatNumber) ?: return null
        val s = byVat[vat] ?: return null
        return Found(s, readName != null && SellerProfiles.sameCompanyOrMisread(readName, s.name))
    }

    companion object {
        const val FORMAT = 1
        val EMPTY = KnowledgePack("0000-00-00")

        /** The newer of two packs (by date; the first when equal). */
        fun newer(a: KnowledgePack?, b: KnowledgePack?): KnowledgePack? = when {
            a == null -> b
            b == null -> a
            b.version > a.version -> b
            else -> a
        }

        /** A supplier entry is kept only if it is well formed (an Italian number must pass its checksum). */
        fun valid(s: Supplier): Boolean {
            if (s.name.isBlank() || s.name.length > 120) return false
            if (!rx("[A-Z]{2}").matches(s.country)) return false
            return if (s.country == "IT") SellerProfiles.isValidPartitaIva(s.vat) else rx("[A-Z0-9]{4,15}").matches(s.vat)
        }
    }
}
