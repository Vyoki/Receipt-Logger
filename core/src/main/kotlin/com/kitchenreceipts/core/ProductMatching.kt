package com.kitchenreceipts.core

import java.text.Normalizer

data class ProductRef(val id: Long, val name: String)
data class ProductSuggestion(val product: ProductRef, val score: Double)

/**
 * Helps the user assign receipt descriptions to canonical products.
 *
 * Nothing here merges products. [aliasKey] is an exact lookup key for descriptions the user
 * has ALREADY assigned (remembered per seller). [suggest] only ranks candidates to show in the
 * picker; the user must tap one to assign it.
 */
object ProductMatching {

    private val STOPWORDS = setOf("di", "da", "del", "della", "al", "alla", "con", "per", "e", "in", "the", "and")

    /** Exact, case/accents/spacing-insensitive key. "MOZZARELLA  Fior di latte" == "mozzarella fior di latte". */
    fun aliasKey(description: String): String =
        Normalizer.normalize(description, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9%]+"), " ")
            .trim()

    private fun tokens(s: String): Set<String> =
        aliasKey(s).split(' ').filter { it.length > 1 && it !in STOPWORDS && !it.all(Char::isDigit) }.toSet()

    /** Alias key for a supplier's article code: stable even when the description is misread. */
    fun codeKey(itemCode: String): String = "#" + itemCode.trim()

    /** Candidates ranked by token overlap. Score 0..1. Only for display, never auto-applied. */
    fun suggest(description: String, products: List<ProductRef>, limit: Int = 5): List<ProductSuggestion> {
        val a = tokens(description)
        if (a.isEmpty()) return emptyList()
        return products.map { p ->
            val b = tokens(p.name)
            val inter = a.count { t -> b.any { it == t || (t.length >= 4 && it.length >= 4 && (it.startsWith(t) || t.startsWith(it))) } }
            val union = (a + b).size
            ProductSuggestion(p, if (union == 0) 0.0 else inter.toDouble() / union)
        }.filter { it.score > 0.0 }
            .sortedWith(compareByDescending<ProductSuggestion> { it.score }.thenBy { it.product.name })
            .take(limit)
    }

    /** A readable default name for a new product created from a description: codes and extra spaces removed. */
    fun proposeName(description: String): String {
        val words = description.trim().split(Regex("\\s+"))
            .filterIndexed { i, w -> !(i == 0 && w.length >= 3 && w.any(Char::isDigit) && w.none(Char::isLowerCase)) }
        val joined = words.joinToString(" ").trim()
        if (joined.isEmpty()) return description.trim()
        val lower = joined.lowercase()
        return lower.replaceFirstChar { it.titlecase() }
    }
}
