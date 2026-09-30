package com.kitchenreceipts.core

import java.math.BigDecimal

/**
 * The size of one pack as printed in a "TIPO CONF." / "FORMATO" column: "LT 1" = 1 litre, "GR 500" = 500 grams,
 * "CL 66", "ML 1000", "KG 11.3", "PZ 10". The line keeps its printed count and price (4 packs x 0,540 €); the
 * size says how much each pack holds, so kilos and litres can be worked out for inventory and prices.
 */
object PackSizes {

    data class Size(val amount: BigDecimal, val unit: String) {
        /** "500 g", "1 l", "11,3 kg" */
        val text: String get() = ItalianNumbers.formatDecimal(amount, maxScale = 3) + " " + unit

        /** How much one pack holds in kg or l, or null for a count ("PZ 10"). */
        val base: Pair<BigDecimal, String>? get() {
            val f = Units.factorToBase(unit) ?: return null
            return amount.multiply(f).stripTrailingZeros() to Units.baseUnit(Units.dimension(unit)!!)
        }
    }

    private val GLUED = Regex("^(?i)(kg|gr|g|hg|lt|l|ml|cl|pz)\\.?(\\d+(?:[.,]\\d+)?)$")

    /** Reads "LT 1", "GR500", "KG 11.3", "PZ 10" from the words of a pack-size cell; null when there is no size. */
    fun parse(words: List<String>): Size? {
        val ws = words.flatMap { w -> GLUED.matchEntire(w.trim())?.let { listOf(it.groupValues[1], it.groupValues[2]) } ?: listOf(w.trim()) }
        for (i in ws.indices) {
            val unit = Units.normalizeKnown(ws[i].trimEnd('.')) ?: continue
            if (Units.dimension(unit) == null && unit != "pz") continue
            val n = ws.getOrNull(i + 1)?.let { ItalianNumbers.parse(it) ?: it.replace(',', '.').toBigDecimalOrNull() } ?: continue
            if (n.signum() <= 0) continue
            return Size(n.stripTrailingZeros(), unit)
        }
        return null
    }

    /** "500 g" back into a size (as stored on a saved line). */
    fun fromText(text: String?): Size? {
        val parts = text?.trim()?.split(' ') ?: return null
        if (parts.size != 2) return null
        val n = ItalianNumbers.parse(parts[0]) ?: return null
        val u = Units.normalize(parts[1]) ?: return null
        return Size(n, u)
    }
}
