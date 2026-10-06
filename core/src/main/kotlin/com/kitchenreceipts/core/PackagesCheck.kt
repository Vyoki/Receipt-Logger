package com.kitchenreceipts.core

/**
 * The number of packages printed at the foot of a delivery note ("N. COLLI 10", "TOTALE COLLI 76") against the Pkgs of
 * the lines. When they differ and reading a "7" as "1" (the OCR's most common slip on this column, "7/" for "1/")
 * explains the whole difference in exactly one way, those Pkgs are repaired. Only plain counts are checked ("1", "3";
 * not "1x10", whose meaning varies by supplier).
 */
object PackagesCheck {

    private val LABEL = Regex("(?i)(\\bn\\.?\\s*colli\\b|\\btotale\\s+colli\\b|\\bcolli\\s+totali\\b)")
    private val FIRST_INT = Regex("^\\D{0,3}(\\d{1,4})\\b")

    /** The printed total, from the label's line or the line below it. */
    fun printedTotal(text: String): Int? {
        val lines = text.lines()
        for ((i, line) in lines.withIndex()) {
            val m = LABEL.find(line) ?: continue
            val after = line.substring(m.range.last + 1)
            rx("\\b(\\d{1,4})\\b").find(after)?.let { return it.groupValues[1].toInt() }
            lines.getOrNull(i + 1)?.let { next -> FIRST_INT.find(next.trim())?.let { return it.groupValues[1].toInt() } }
        }
        return null
    }

    fun repair(items: List<ParsedLineItem>, text: String): List<ParsedLineItem> {
        val total = printedTotal(text) ?: return items
        val counts = items.map { it.packages?.value }
        if (counts.any { it != null && !it.all(Char::isDigit) }) return items
        val sum = counts.sumOf { it?.toIntOrNull() ?: 0 }
        if (sum == total) return items.map { it.copy(packages = it.packages?.copy(confidence = Confidence.HIGH)) }
        val sevens = items.indices.filter { counts[it] == "7" }
        val excess = sum - total
        if (excess <= 0 || excess % 6 != 0 || excess / 6 != sevens.size) return items
        // Every "7" must become "1" to match: one way only.
        return items.mapIndexed { i, it ->
            if (i in sevens) it.copy(packages = Extracted("1", Confidence.HIGH, "${it.packages?.source} (7 read for 1: printed total $total)")) else it
        }
    }
}
