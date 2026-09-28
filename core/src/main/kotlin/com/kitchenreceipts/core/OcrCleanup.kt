package com.kitchenreceipts.core

/**
 * Repairs the most common OCR misreads on receipts before parsing. Only touches characters inside
 * tokens that are clearly numeric (amounts, dates, quantities), never words.
 *
 * - "2,5O" -> "2,50", "l4/03/2025" -> "14/03/2025", "1O%" -> "10%" (letter O / l / I / | inside numbers)
 * - "22 ,50" / "22, 50" -> "22,50" (space inside a decimal amount)
 * - "€22,50" / "22,50€" -> "22,50 €"
 */
object OcrCleanup {

    private val SPLIT_DECIMAL = Regex("(\\d) ?, (\\d{2})(?![\\d.,])|(\\d) , ?(\\d{2})(?![\\d.,])")
    private val EURO_GLUED = Regex("€(?=\\d)|(?<=\\d)€")
    private const val SEPARATORS = ".,/-:%"

    fun clean(text: String): String = text.lines().joinToString("\n") { cleanLine(it) }

    fun cleanLine(line: String): String {
        var s = line.replace(' ', ' ').replace('\t', ' ')
        s = EURO_GLUED.replace(s) { m -> if (m.range.first > 0 && s[m.range.first - 1].isDigit()) " €" else "€ " }
        s = s.split(' ').joinToString(" ") { fixWordToken(fixNumericToken(it)) }
        s = SPLIT_DECIMAL.replace(s) { m ->
            val g = m.groupValues
            if (g[1].isNotEmpty()) "${g[1]},${g[2]}" else "${g[3]},${g[4]}"
        }
        return s.replace(Regex(" {2,}"), " ").trim()
    }

    /**
     * "D0CUMENTO" -> "DOCUMENTO": a zero between letters in a word that has no other digits is a letter O.
     * Codes such as "KG1X16" or "38B/43056" have other digits and are left alone.
     */
    fun fixWordToken(token: String): String {
        if (token.count { it.isLetter() } < 3) return token
        val digits = token.filter { it.isDigit() }
        if (digits.isEmpty() || digits.any { it != '0' }) return token
        val sb = StringBuilder(token)
        for (i in 1 until token.length - 1) {
            if (token[i] == '0' && token[i - 1].isLetter() && token[i + 1].isLetter()) {
                sb.setCharAt(i, if (token[i - 1].isUpperCase()) 'O' else 'o')
            }
        }
        return sb.toString()
    }

    /** Replaces O/o with 0 and l/I/| with 1 inside a token that is otherwise numeric. */
    fun fixNumericToken(token: String): String {
        if (token.length < 2) return token
        val digits = token.count { it.isDigit() }
        if (digits == 0) return token
        val suspicious = token.count { it in "OoIl|" }
        if (suspicious == 0) return token
        // Everything must be digits, separators or suspicious letters, and digits must dominate.
        if (!token.all { it.isDigit() || it in SEPARATORS || it in "OoIl|" }) return token
        if (digits < suspicious) return token
        val sb = StringBuilder(token)
        for (i in token.indices) {
            val c = token[i]
            val prev = token.getOrNull(i - 1)
            val next = token.getOrNull(i + 1)
            fun numericish(ch: Char?) = ch != null && (ch.isDigit() || ch in SEPARATORS || ch in "OoIl|")
            when (c) {
                'O', 'o' -> if (numericish(prev) || numericish(next)) sb.setCharAt(i, '0')
                // "1l" (one litre) must stay: only replace l/I when something numeric follows.
                'l', 'I', '|' -> if (numericish(next) && (prev == null || numericish(prev))) sb.setCharAt(i, '1')
            }
        }
        return sb.toString()
    }
}
