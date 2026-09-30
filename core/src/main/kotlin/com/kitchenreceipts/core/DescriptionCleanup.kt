package com.kitchenreceipts.core

/**
 * Tidies product names read by the OCR without changing what they say:
 * - case slips in an upper-case name: "S/oSso" -> "S/OSSO", "NATURBOSCo" -> "NATURBOSCO";
 * - a zero read inside a word: "S/0SSO" -> "S/OSSO" (only in a run of letters, never in a size such as "G200");
 * - a leading dash or dot the OCR picked up;
 * - lines with the same article code on one document are the same product: they get the same name (the spelling
 *   most of them share, else the cleanest one), so one misread line does not become a different product.
 * Nothing is translated, completed or guessed.
 */
object DescriptionCleanup {

    fun clean(name: String): String {
        var s = name.trim().trimStart('-', '.', ',', '*', ' ')
        val letters = s.filter { it.isLetter() }
        if (letters.length >= 4 && letters.count { it.isUpperCase() } >= letters.length * 0.5) s = s.uppercase()
        // A 0 between letters inside a word chunk ("S/0SSO", "C0PPA"): the letter O.
        s = Regex("(?<=[A-Za-z])0(?=[A-Za-z])|(?<=[/.\\s])0(?=[A-Za-z]{2})").replace(s) { m ->
            val chunkStart = s.lastIndexOfAny(charArrayOf(' ', '/', '.', ',', '-'), m.range.first - 1) + 1
            val chunkEnd = s.indexOfAny(charArrayOf(' ', '/', '.', ',', '-'), m.range.first).let { if (it < 0) s.length else it }
            val chunk = s.substring(chunkStart, chunkEnd)
            // Only when the chunk is a word (no other digits): "G200", "400X6" stay as they are.
            if (chunk.count { it.isDigit() } == 1 && chunk.count { it.isLetter() } >= 2) "O" else m.value
        }
        return s.trim()
    }

    /** Cleans every name, then gives lines with the same article code one shared name. */
    fun apply(items: List<ParsedLineItem>): List<ParsedLineItem> {
        val cleaned = items.map { it.copy(originalDescription = clean(it.originalDescription)) }
        val byCode = cleaned.filter { !it.itemCode.isNullOrBlank() && it.originalDescription.isNotBlank() }.groupBy { it.itemCode!! }
        val chosen = byCode.filterValues { it.size >= 2 }.mapValues { (_, lines) ->
            val counts = lines.groupingBy { it.originalDescription }.eachCount()
            counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { oddness(it.key) }.thenByDescending { it.key.length })
                .first().key
        }
        return cleaned.map { item ->
            val name = item.itemCode?.let { chosen[it] } ?: return@map item
            // Only a different spelling of the same name: never replace a name that says something else.
            if (similar(item.originalDescription, name)) {
                item.copy(originalDescription = name)
            } else {
                item
            }
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun similar(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        if (x == y) return true
        val limit = maxOf(1, minOf(x.length, y.length) / 8)
        return SmartMatcher.damerau(x, y, limit) <= limit
    }

    /** How odd a spelling looks: lower-case letters in an upper-case name, digits inside words. */
    private fun oddness(s: String): Int =
        s.count { it.isLowerCase() } + Regex("[A-Za-z][0-9][A-Za-z]").findAll(s).count() * 2
}
