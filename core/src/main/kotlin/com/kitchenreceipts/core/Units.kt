package com.kitchenreceipts.core

import java.math.BigDecimal

/**
 * Unit normalisation. Only units of the same physical dimension (mass, volume) are ever
 * converted automatically. Counts (pieces, packs, cartons, bottles...) are each their own
 * unit and are never combined unless the user defines an explicit conversion.
 */
object Units {

    enum class Dimension { MASS, VOLUME }

    private val ALIASES: Map<String, String> = mapOf(
        "kg" to "kg", "kgs" to "kg", "kilo" to "kg", "kili" to "kg", "chilo" to "kg", "chili" to "kg",
        "kilogrammi" to "kg", "kilogrammo" to "kg", "chilogrammi" to "kg",
        "hg" to "hg", "etto" to "hg", "etti" to "hg",
        "g" to "g", "gr" to "g", "grammi" to "g", "grammo" to "g",
        "l" to "l", "lt" to "l", "ltr" to "l", "litro" to "l", "litri" to "l",
        "cl" to "cl", "ml" to "ml",
        "pz" to "pz", "pezzi" to "pz", "pezzo" to "pz", "pc" to "pz", "pcs" to "pz", "nr" to "pz", "n" to "pz",
        "num" to "pz",
        "conf" to "conf", "cf" to "conf", "cnf" to "conf", "confezione" to "conf", "confezioni" to "conf",
        "pack" to "conf", "pk" to "conf",
        "ct" to "ct", "crt" to "ct", "cartone" to "ct", "cartoni" to "ct",
        "cs" to "cs", "cassa" to "cs", "casse" to "cs",
        "bt" to "bt", "btg" to "bt", "bott" to "bt", "bottiglia" to "bt", "bottiglie" to "bt",
        "sacco" to "sacco", "sacchi" to "sacco", "sc" to "sacco",
        "vasc" to "vaschetta", "vaschetta" to "vaschetta", "vaschette" to "vaschetta",
        "latta" to "latta", // not "latte": that is milk
        "mz" to "mazzo", "mazzo" to "mazzo", "mazzi" to "mazzo",
    )

    /** Unit -> (dimension, factor to the dimension's base unit kg / l). */
    private val PHYSICAL: Map<String, Pair<Dimension, BigDecimal>> = mapOf(
        "kg" to (Dimension.MASS to BigDecimal.ONE),
        "hg" to (Dimension.MASS to BigDecimal("0.1")),
        "g" to (Dimension.MASS to BigDecimal("0.001")),
        "l" to (Dimension.VOLUME to BigDecimal.ONE),
        "cl" to (Dimension.VOLUME to BigDecimal("0.01")),
        "ml" to (Dimension.VOLUME to BigDecimal("0.001")),
    )

    /** Returns the canonical unit for a known alias, or null if unknown. */
    fun normalizeKnown(raw: String?): String? {
        if (raw == null) return null
        val key = raw.trim().lowercase().trimEnd('.').removePrefix("€/").removePrefix("/")
        return ALIASES[key]
    }

    /** Canonical form for storage: known aliases are mapped, unknown units kept lower-cased. */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return normalizeKnown(raw) ?: raw.trim().lowercase().trimEnd('.')
    }

    fun dimension(unit: String): Dimension? = PHYSICAL[unit]?.first

    fun baseUnit(dimension: Dimension): String = when (dimension) {
        Dimension.MASS -> "kg"
        Dimension.VOLUME -> "l"
    }

    /** Factor to multiply a quantity in [unit] by to obtain its dimension's base unit. */
    fun factorToBase(unit: String): BigDecimal? = PHYSICAL[unit]?.second

    val commonUnits: List<String> = listOf("kg", "g", "l", "ml", "pz", "conf", "ct", "cs", "bt")
}
