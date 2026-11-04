package com.kitchenreceipts.core

/**
 * A product name as printed split into what it is, its size or pack, and the supplier's article code
 * ("SALE MARINO GROSSO GR.1000" -> "SALE MARINO GROSSO", "1000 g"). Rules only, from a fixed list of units: nothing
 * keyed on a supplier, a brand or a layout. When a rule is not sure, the part stays in the name: a wrong split is worse
 * than a long name. The raw text is left as it is and stays what matching, price history and orders use; [split] is
 * worked out from it and nothing is stored.
 */
object ProductName {

    /** [name]: what is left, case as printed; [size]: number first, unit in lowercase ("1000 g", "250 g x 6", "x 16"). */
    data class Parts(val name: String, val size: String?, val code: String?)

    /** Measures as printed (upper or lower case) and how they are written in [Parts.size]. No conversion. */
    private val UNITS = mapOf(
        "g" to "g", "gr" to "g", "hg" to "hg", "kg" to "kg", "mg" to "mg",
        "l" to "l", "lt" to "l", "cl" to "cl", "ml" to "ml", "cc" to "cc",
        "cm" to "cm", "mm" to "mm", "pz" to "pz",
    )
    private const val UNIT = "(GR|HG|KG|MG|LT|CL|ML|CC|CM|MM|PZ|G|L)"
    private const val NUMBER = "(\\d+(?:[.,]\\d+)?)"

    /** "GR.1000", "KG.1", "CC.700", "G250X6", "KG.5X2": the unit, then its number (with a dot, or two digits or more). */
    private val UNIT_NUMBER = Regex("(?i)^$UNIT(\\.)?$NUMBER(?:X(\\d+))?$")
    /** "21CM", "500G", "1,5KG", "250GX6": the number, then its unit. */
    private val NUMBER_UNIT = Regex("(?i)^$NUMBER$UNIT(?:X(\\d+))?$")
    /** "LT", "G", "PZ" alone, next to a number word ("LT 0,4", "1000 G", "6 PZ"). */
    private val UNIT_WORD = Regex("(?i)^$UNIT\\.?$")
    private val NUMBER_WORD = Regex("^$NUMBER$")
    /** "X16", "MX10": a pack of so many. */
    private val PACK = Regex("(?i)^M?X(\\d+)$")
    /** A leading article code: 4 digits or more, or 5 characters or more of letters and digits mixed (2 digits or more). */
    private val DIGITS_CODE = Regex("^\\d{4,}$")
    private val MIXED_CODE = Regex("^[A-Za-z0-9]{5,}$")
    /** A count glued to a word ("12TAPPI", "G200AGAR"): digits, then four letters or more to the end. No code. */
    private val COUNT_AND_WORD = Regex("^[A-Za-z]*\\d+[A-Za-z]{4,}$")

    private data class Size(val start: Int, val end: Int, val measure: String?, val pack: String?)

    /** "X" or "x" alone: joins two numbers (a dimension, "40CM X 50M") or a measure and its pack ("CC.180 X 4"). */
    private fun isX(w: String?) = w == "X" || w == "x"
    private val WHOLE_NUMBER = Regex("^\\d+$")
    /** A unit after these is what a price is per ("AL KG.61025"), not a size. */
    private val PER_UNIT = setOf("al", "per", "a")
    /** A word that only leads into the size ("x 125 gr", "DA 5 KG"): it goes out with the size. */
    private val CONNECTORS = setOf("x", "da")

    fun split(raw: String): Parts {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Parts("", null, null)
        val sizes = mutableListOf<Size>()
        // A number printed in two words before it ("1 5L", "( 1 2 MM") or a number on both sides of a unit word
        // ("0 KG 1", "ML 750 PZ 50"): which number is the size is a guess, so none is taken.
        var ambiguous = false
        fun numberBefore(k: Int) = k > 0 && (NUMBER_WORD.matches(words[k - 1]) || words[k - 1] == "(")
        fun numberAt(k: Int) = words.getOrNull(k)?.let { NUMBER_WORD.matches(it) } == true
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val next = words.getOrNull(i + 1)
            val unitNumber = UNIT_NUMBER.matchEntire(w)
            val numberUnit = NUMBER_UNIT.matchEntire(w)
            when {
                unitNumber != null && (unitNumber.groupValues[2].isNotEmpty() || unitNumber.groupValues[3].length >= 2) -> {
                    val (unit, _, n, pack) = unitNumber.destructured
                    sizes += Size(i, i, "$n ${UNITS.getValue(unit.lowercase())}", pack.ifEmpty { null }); i++
                }
                numberUnit != null -> {
                    // "3G 2,5": a measure next to a second bare number is a guess.
                    if (numberBefore(i) || numberAt(i + 1)) ambiguous = true
                    val (n, unit, pack) = numberUnit.destructured
                    sizes += Size(i, i, "$n ${UNITS.getValue(unit.lowercase())}", pack.ifEmpty { null }); i++
                }
                UNIT_WORD.matches(w) && next != null && NUMBER_WORD.matches(next) -> {
                    if (numberBefore(i)) ambiguous = true
                    sizes += Size(i, i + 1, "$next ${UNITS.getValue(w.trimEnd('.').lowercase())}", null); i += 2
                }
                NUMBER_WORD.matches(w) && next != null && UNIT_WORD.matches(next) -> {
                    if (numberBefore(i) || numberAt(i + 2)) ambiguous = true
                    sizes += Size(i, i + 1, "$w ${UNITS.getValue(next.trimEnd('.').lowercase())}", null); i += 2
                }
                PACK.matches(w) -> { sizes += Size(i, i, null, PACK.matchEntire(w)!!.groupValues[1]); i++ }
                else -> i++
            }
        }
        // What stands around a measure: an X joining it to another number makes it a dimension ("16.5 X 7.7 X 2.8CM",
        // "40CM X 50M"): no size. A lone X and a bare whole number after it is its pack ("CC.180 X 4" -> 180 cc x 4).
        // After a per-unit word it is a price basis ("AL KG.61025"): left whole.
        val placed = mutableListOf<Size>()
        for (c in sizes) {
            if (c.measure == null) { placed += c; continue }
            if (words.getOrNull(c.start - 1)?.lowercase() in PER_UNIT) continue
            if (isX(words.getOrNull(c.start - 1)) && words.getOrNull(c.start - 2)?.any(Char::isDigit) == true) { ambiguous = true; continue }
            val after = words.getOrNull(c.end + 2)
            if (isX(words.getOrNull(c.end + 1)) && after != null) {
                if (WHOLE_NUMBER.matches(after) && c.pack == null) { placed += c.copy(end = c.end + 2, pack = after); continue }
                if (after.any(Char::isDigit)) { ambiguous = true; continue }
            }
            placed += c
        }
        sizes.clear(); sizes += placed
        // One measure at most (two would be a guess), and one pack at most: either may stand alone.
        val measures = sizes.filter { it.measure != null }
        val packs = sizes.filter { it.measure == null }
        val taken = if (ambiguous || measures.size > 1 || packs.size > 1 || (measures.firstOrNull()?.pack != null && packs.isNotEmpty())) emptyList() else sizes
        val size = taken.takeIf { it.isNotEmpty() }?.let { t ->
            val measure = t.firstNotNullOfOrNull { it.measure }
            val pack = t.firstNotNullOfOrNull { it.pack }
            listOfNotNull(measure, pack?.let { "x $it" }).joinToString(" ")
        }
        // A lone connector right before a taken measure ("x 125 gr", "da 0.75 l") goes out with it.
        val takenIndexes = taken.flatMap { t ->
            val from = if (t.measure != null && words.getOrNull(t.start - 1)?.lowercase() in CONNECTORS) t.start - 1 else t.start
            (from..t.end).toList()
        }.toSet()
        var rest = words.filterIndexed { k, _ -> k !in takenIndexes }
        // The code: the first word only, when unmistakable and not the whole name.
        val first = rest.firstOrNull()
        val code = first?.takeIf { rest.size > 1 && 0 !in takenIndexes && isCode(it) }
        if (code != null) rest = rest.drop(1)
        val name = rest.joinToString(" ").trim(' ', '.', '-', '–')
        if (name.isEmpty()) return Parts(clean(raw), null, null)
        return Parts(name, size, code)
    }

    private fun isCode(w: String): Boolean =
        DIGITS_CODE.matches(w) ||
            (MIXED_CODE.matches(w) && w.count(Char::isDigit) >= 2 && w.any(Char::isLetter) && !COUNT_AND_WORD.matches(w))

    private fun clean(raw: String) = raw.trim().split(Regex("\\s+")).joinToString(" ").trim(' ', '.', '-', '–')
}
