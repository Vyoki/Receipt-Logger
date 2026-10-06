package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.RoundingMode

/** A product the new line could belong to, with every description already linked to it (any supplier). */
data class ProductCandidate(val id: Long, val name: String, val knownDescriptions: Collection<String> = emptyList())

enum class MatchStrength {
    /** Same words after normalising case, accents, spacing, plurals and abbreviations. */
    SAME,
    /** Differs only by misreadings/typos of long words ("GROSOS" for "GROSSO"). */
    TYPO,
    /** Similar but not safely the same (different variant, extra words): offered, never applied automatically. */
    SIMILAR,
}

data class SmartMatch(val productId: Long, val name: String, val strength: MatchStrength, val score: Double) {
    /** Safe to link without asking: typing or reading errors never create a second product. */
    val automatic: Boolean get() = strength != MatchStrength.SIMILAR
}

/**
 * Recognises a product in a new description even when it is spelled differently, so that
 * the inventory does not grow a second "Mozzarela" next to "Mozzarella".
 *
 * Deliberately conservative, because a wrong link silently corrupts averages and inventory:
 * - words are compared after removing accents, case, plural endings and pack sizes;
 * - an abbreviation matches the full word ("MOZZ." = "mozzarella", "POM." = "pomodori");
 * - a typo is tolerated only in words of 6+ letters, with the same first letter, and never between two
 *   different food words the app knows ("pollo"/"polpo", "bovina"/"ovina");
 * - different pack sizes are different products (500 g vs 1 kg);
 * - a link is automatic only when every word matches and no other product is almost as close.
 * Anything less certain is returned as SIMILAR: shown as a suggestion, never applied.
 */
object SmartMatcher {

    private const val AUTO_THRESHOLD = 0.93
    private const val SUGGEST_THRESHOLD = 0.55

    private val STOPWORDS = setOf(
        "di", "da", "del", "della", "dei", "delle", "al", "alla", "allo", "ai", "con", "per", "e", "in", "il", "la",
        "lo", "le", "gli", "un", "una", "the", "and", "tipo", "x", "nr", "n", "pz", "conf", "ct", "cf",
    )

    // "GR.500", "500GR", "KG 5", "LT. 1", "1,5L", "CL33", "GR.2550(1650)X6"
    private val SIZE_AFTER = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*(kg|gr|g|hg|lt|l|ml|cl)\\b\\.?")
    private val SIZE_BEFORE = Regex("(?i)\\b(kg|gr|g|hg|lt|l|ml|cl)\\.?\\s*(\\d+(?:[.,]\\d+)?)")

    /** [words] are stems (plural endings removed); [raw] the same words as printed, for the dictionary checks. */
    data class Signature(val words: List<String>, val sizes: Set<String>, val raw: List<String> = words)

    fun signature(description: String): Signature = signatures(description)
    private val signatures = Memo { description: String ->
        val sizes = mutableSetOf<String>()
        var s = description
        for (re in listOf(SIZE_BEFORE, SIZE_AFTER)) {
            s = re.replace(s) { m ->
                val (num, unit) = if (re === SIZE_BEFORE) m.groupValues[2] to m.groupValues[1] else m.groupValues[1] to m.groupValues[2]
                sizeKey(num, unit)?.let { sizes += it }
                " "
            }
        }
        val raw = ProductMatching.aliasKey(s).split(' ')
            .filter { it.length > 1 && it !in STOPWORDS && !it.all(Char::isDigit) && it.any(Char::isLetter) }
            .filterNot { w -> w.count(Char::isDigit) >= 2 } // article codes glued to words
        Signature(raw.map(::stem), sizes.toSet(), raw)
    }

    fun bestMatch(description: String, candidates: List<ProductCandidate>): SmartMatch? =
        rank(description, candidates, 2).let { ranked ->
            val best = ranked.firstOrNull() ?: return null
            val second = ranked.getOrNull(1)
            // Two products almost equally close: do not choose for the operator.
            if (best.automatic && second != null && second.score >= best.score - 0.04) best.copy(strength = MatchStrength.SIMILAR) else best
        }

    fun rank(description: String, candidates: List<ProductCandidate>, limit: Int = 5): List<SmartMatch> {
        val a = signature(description)
        if (a.words.isEmpty()) return emptyList()
        val exactKey = ProductMatching.aliasKey(description)
        return candidates.mapNotNull { c ->
            if (ProductMatching.aliasKey(c.name) == exactKey || c.knownDescriptions.any { ProductMatching.aliasKey(it) == exactKey }) {
                return@mapNotNull SmartMatch(c.id, c.name, MatchStrength.SAME, 1.0)
            }
            (listOf(c.name) + c.knownDescriptions).mapNotNull { compare(a, signature(it)) }
                .maxByOrNull { it.first }
                ?.let { (score, typo) ->
                    val strength = when {
                        score >= 0.999 && !typo -> MatchStrength.SAME
                        score >= AUTO_THRESHOLD -> MatchStrength.TYPO
                        else -> MatchStrength.SIMILAR
                    }
                    SmartMatch(c.id, c.name, strength, score)
                }
        }.filter { it.score >= SUGGEST_THRESHOLD }
            .sortedWith(compareByDescending<SmartMatch> { it.score }.thenBy { it.name })
            .take(limit)
    }

    /** Pairs of existing products that look like the same thing (for the operator to merge, if they agree). */
    fun possibleDuplicates(products: List<ProductCandidate>): List<Pair<ProductCandidate, ProductCandidate>> {
        val out = mutableListOf<Pair<ProductCandidate, ProductCandidate>>()
        for (i in products.indices) for (j in i + 1 until products.size) {
            val m = rank(products[i].name, listOf(products[j].copy(knownDescriptions = emptyList())), 1).firstOrNull()
            if (m != null && m.automatic) out += products[i] to products[j]
        }
        return out
    }

    /** Dice similarity of the two word lists; null when pack sizes contradict. Second = a typo was needed. */
    private fun compare(a: Signature, b: Signature): Pair<Double, Boolean>? {
        if (b.words.isEmpty()) return null
        if (a.sizes.isNotEmpty() && b.sizes.isNotEmpty() && a.sizes.intersect(b.sizes).isEmpty()) return null
        val used = BooleanArray(b.words.size)
        var sum = 0.0
        var typo = false
        for ((i, w) in a.words.withIndex()) {
            var best = 0.0
            var bestJ = -1
            for ((j, v) in b.words.withIndex()) {
                if (used[j]) continue
                val s = if (w == v) 1.0 else wordSimilarity(a.raw[i], b.raw[j])
                if (s > best) { best = s; bestJ = j }
            }
            if (bestJ >= 0 && best > 0) {
                used[bestJ] = true
                sum += best
                if (best < 1.0) typo = true
            }
        }
        return (2 * sum / (a.words.size + b.words.size)) to typo
    }

    /** 1 = same word, 0.97 = abbreviation or small misreading, 0 = different. Words as printed (not stemmed). */
    fun wordSimilarity(a: String, b: String): Double {
        if (a == b || stem(a) == stem(b)) return 1.0
        val (short, long) = if (a.length <= b.length) a to b else b to a
        // Abbreviation: "mozz" -> "mozzarella", "pom" -> "pomodori". Not when the short word is a word of its own
        // ("pane" is not short for "panettone", "penne" not for "pennette").
        if (short.length >= 3 && long.startsWith(short) && !Categories.isKnownWord(short)) return 0.97
        if (short.length < 6 || a[0] != b[0]) return 0.0
        if (Categories.isKnownWord(a) && Categories.isKnownWord(b)) return 0.0 // two real, different words
        val limit = if (short.length >= 9) 2 else 1
        return if (damerau(a, b, limit) <= limit) 0.97 else 0.0
    }

    /** Optimal string alignment distance, stopping early above [limit]. */
    fun damerau(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
                rowMin = minOf(rowMin, v)
            }
            if (rowMin > limit) return limit + 1
        }
        return d[a.length][b.length]
    }

    /** Italian singular/plural: "pomodori"/"pomodoro" -> "pomodor", "mozzarelle" -> "mozzarell". */
    private fun stem(w: String): String = if (w.length >= 5 && w.last() in "aeio") w.dropLast(1) else w

    private fun sizeKey(num: String, unit: String): String? {
        val n = ItalianNumbers.parse(num) ?: num.replace(',', '.').toBigDecimalOrNull() ?: return null
        if (n.signum() <= 0) return null
        val (factor, base) = when (unit.lowercase()) {
            "kg" -> BigDecimal(1000) to "g"
            "hg" -> BigDecimal(100) to "g"
            "gr", "g" -> BigDecimal.ONE to "g"
            "lt", "l" -> BigDecimal(1000) to "ml"
            "cl" -> BigDecimal(10) to "ml"
            "ml" -> BigDecimal.ONE to "ml"
            else -> return null
        }
        return n.multiply(factor).setScale(0, RoundingMode.HALF_UP).toPlainString() + base
    }
}
