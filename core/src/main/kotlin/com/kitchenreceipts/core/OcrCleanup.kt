package com.kitchenreceipts.core

import java.text.Normalizer

/**
 * Repairs the most common OCR misreads on receipts before parsing. Only touches characters inside
 * tokens that are clearly numeric (amounts, dates, quantities), never words.
 *
 * - "2,5O" -> "2,50", "l4/03/2025" -> "14/03/2025", "1O%" -> "10%" (letter O / l / I / | inside numbers)
 * - "22 ,50" / "22, 50" -> "22,50" (space inside a decimal amount)
 * - "€22,50" / "22,50€" -> "22,50 €"
 * - "20417722/09/2026" -> "204177 22/09/2026" (document number and date printed without a space)
 * - "24,000c" -> "24,000 C" (quantity glued to the storage letter C/F/S/CN of the next column)
 */
object OcrCleanup {

    private val SPLIT_DECIMAL = Regex("(\\d) ?, (\\d{2})(?![\\d.,])|(\\d) , ?(\\d{2})(?![\\d.,])")
    private val EURO_GLUED = Regex("€(?=\\d)|(?<=\\d)€")
    private const val SEPARATORS = ".,/-:%"

    /**
     * A number glued to a date. The day is taken as wide as the month is printed ("22/09/2026", not "2/09/2026"):
     * a document that zero-pads the month zero-pads the day too.
     */
    private val GLUED_DATE = Regex("(?<![\\d/.,-])(\\d{3,})(\\d{2})([/.-])(\\d{2})\\3(\\d{4})(?!\\d)")
    private val GLUED_FLAG = Regex("^(\\d{1,3}(?:\\.\\d{3})*,\\d{2,3})(c|C|f|F|s|S|cn|CN)$")

    private fun splitGluedDate(s: String): String = GLUED_DATE.replace(s) { m ->
        val (num, day, sep, month, year) = m.destructured
        val ok = day.toInt() in 1..31 && month.toInt() in 1..12 && year.toInt() in 1990..2099
        if (ok) "$num $day$sep$month$sep$year" else m.value
    }

    /** "GR.12c0" -> "GR.1200": in a pack size, a c or o between digits is a zero. */
    private val SIZE_TOKEN = Regex("^(?i)(gr|kg|lt|ml|cl|g|l)\\.?\\d[\\dcoO]*\\d$")

    private fun fixSizeToken(token: String): String {
        if (!SIZE_TOKEN.matches(token)) return token
        val start = token.indexOfFirst { it.isDigit() }
        return token.substring(0, start) + token.substring(start).map { if (it in "coO") '0' else it }.joinToString("")
    }

    private fun splitGluedFlag(token: String): String =
        GLUED_FLAG.find(token)?.let { m -> m.groupValues[1] + " " + m.groupValues[2].uppercase() } ?: token

    /** A number with a table's vertical rule read on its side ("12,40)", "10,00}", "|1.234,56|"). */
    private val RULED_NUMBER = Regex("^[|¦\\[\\](){}]*(-?\\d[\\d.,]*%?)[|¦\\[\\](){}]+$|^[|¦\\[\\](){}]+(-?\\d[\\d.,]*%?)$")
    private val RULE_ONLY = Regex("^[|¦]+$")

    /**
     * Table rules read as characters: a "|" on its own is dropped, and a bracket or bar stuck to a number is taken
     * off it (a number never ends in ")" or "}" on a document). Words keep their boxes; the line's text is rebuilt.
     */
    fun stripRules(lines: List<OcrLine>): List<OcrLine> = lines.mapNotNull { l ->
        fun fix(t: String): String? {
            if (RULE_ONLY.matches(t)) return null
            val m = RULED_NUMBER.matchEntire(t) ?: return t
            val n = m.groupValues[1].ifEmpty { m.groupValues[2] }
            return if (ItalianNumbers.parse(n.trimEnd('%')) != null) n else t
        }
        if (l.words.isNotEmpty()) {
            val ws = l.words.mapNotNull { w -> fix(w.text)?.let { w.copy(text = it) } }
            if (ws.isEmpty()) return@mapNotNull null
            if (ws.size == l.words.size && ws.zip(l.words).all { (a, b) -> a.text == b.text }) l
            else l.copy(text = ws.joinToString(" ") { it.text }, words = ws, left = ws.minOf { it.left }, right = ws.maxOf { it.right })
        } else {
            val t = l.text.split(' ').filter { it.isNotEmpty() }.mapNotNull(::fix).joinToString(" ")
            if (t.isBlank()) null else if (t == l.text) l else l.copy(text = t)
        }
    }

    /**
     * Heading and label words with a camera slip ("ID LOTTO0", "T0TALE", "QUANTlTA", "IMP0NIBILE") restored to their
     * proper spelling, before any part of the reading looks for them: one stray character must not switch off a whole
     * column or a total. Only in rows that are labels (another label word on the row, no prose), only for words of
     * five letters or more, and only when exactly one label word is that close (look-alike letters folded, at most
     * one letter more, less or different). Product names are never touched: their rows hold no label words.
     */
    fun repairLabels(lines: List<OcrLine>): List<OcrLine> = lines.map { l ->
        val tokens = if (l.words.isNotEmpty()) l.words.map { it.text } else l.text.split(' ').filter { it.isNotEmpty() }
        val exact = tokens.count { t -> isLabelWord(t) }
        if (exact == 0 || tokens.size > 10) return@map l
        fun fix(t: String): String {
            if (isLabelWord(t)) return t
            val core = t.trimEnd('.', ':', ',', '|')
            if (core.count(Char::isLetter) < 4 || core.length < 5) return t
            // A camera slip leaves a trace: a digit or a symbol inside a word, or a small letter inside a capitalised
            // one. A clean word is left alone, even when it is close to a label ("FORNITURE" is a word, not a slip).
            val letters = core.filter(Char::isLetter)
            val trace = core.any { !it.isLetter() } || (letters.count(Char::isUpperCase) >= letters.length - 2 && letters.any(Char::isLowerCase))
            if (!trace) return t
            val f = foldLabel(core)
            // The label it is once the look-alikes are folded; failing that, the one label a single letter away.
            val near = LABEL_VOCAB.filter { w -> foldLabel(w) == f }
                .ifEmpty { LABEL_VOCAB.filter { w -> SmartMatcher.damerau(foldLabel(w), f, 1) <= 1 && w.length >= 5 } }
            return if (near.size == 1) near.single() + t.substring(core.length) else t
        }
        if (l.words.isNotEmpty()) {
            val ws = l.words.map { w -> fix(w.text).let { if (it == w.text) w else w.copy(text = it) } }
            if (ws.zip(l.words).all { (a, b) -> a.text == b.text }) l else l.copy(text = ws.joinToString(" ") { it.text }, words = ws)
        } else {
            val t = tokens.joinToString(" ") { fix(it) }
            if (t == tokens.joinToString(" ")) l else l.copy(text = t)
        }
    }

    /** Label and heading words of supplier documents (Italian; the reading's other languages have their own). */
    private val LABEL_VOCAB = listOf(
        "LOTTO", "LOTTI", "TOTALE", "TOTALI", "IMPONIBILE", "IMPORTO", "QUANTITA", "PREZZO", "DESCRIZIONE", "CODICE",
        "SCADENZA", "ARTICOLO", "ALIQUOTA", "SCONTO", "FATTURA", "DOCUMENTO", "NUMERO", "COLLI", "UNITARIO", "CONSEGNA",
        "DESTINATARIO", "DESTINAZIONE", "FORNITORE", "CLIENTE", "PAGAMENTO", "TRASPORTO", "IMPOSTA", "NETTO",
        "DENOMINAZIONE", "FISCALE", "PARTITA", "CEDENTE", "PRESTATORE", "CESSIONARIO", "COMMITTENTE", "RIEPILOGO",
        "COMPLESSIVO", "PRODOTTO", "BENI", "MERCE",
    )
    /** Short words found only in labels (not units such as NR, which are printed on product rows too). */
    private val SHORT_LABELS = setOf("ID", "QTA", "U.M", "UM", "DATA", "COD", "ART", "SC.%")

    private fun isLabelWord(t: String): Boolean {
        val u = Normalizer.normalize(t.uppercase(), Normalizer.Form.NFD).replace(rx("\\p{M}+"), "").trim('.', ':', ',', '|', '\'', '"')
        return u in LABEL_VOCAB || u in SHORT_LABELS || u.split('.', '/').filter { it.isNotEmpty() }.let { ps -> ps.size > 1 && ps.any { p -> p in SHORT_LABELS || p in LABEL_VOCAB } && ps.all { p -> p in SHORT_LABELS || p in LABEL_VOCAB || p == "M" || p == "LOT" } }
    }

    /** The camera's look-alikes folded to letters: 0 O, 1 I, 5 S, 8 B, 6 G; accents dropped. */
    private fun foldLabel(s: String): String = Normalizer.normalize(s.uppercase(), Normalizer.Form.NFD).replace(rx("\\p{M}+"), "")
        .map { c -> when (c) { '0' -> 'O'; '1', 'L', '|' -> if (c == 'L') 'L' else 'I'; '5' -> 'S'; '8' -> 'B'; '6' -> 'G'; else -> c } }
        .joinToString("").filter { it.isLetter() }

    fun clean(text: String): String = text.lines().joinToString("\n") { cleanLine(it) }

    fun cleanLine(line: String): String {
        var s = line.replace(' ', ' ').replace('\t', ' ')
        s = EURO_GLUED.replace(s) { m -> if (m.range.first > 0 && s[m.range.first - 1].isDigit()) " €" else "€ " }
        s = s.split(' ').joinToString(" ") { splitGluedFlag(fixSizeToken(fixWordToken(fixNumericToken(it)))) }
        s = splitGluedDate(s)
        s = SPLIT_DECIMAL.replace(s) { m ->
            val g = m.groupValues
            if (g[1].isNotEmpty()) "${g[1]},${g[2]}" else "${g[3]},${g[4]}"
        }
        return s.replace(rx(" {2,}"), " ").trim()
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
