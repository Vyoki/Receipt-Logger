package com.kitchenreceipts.core

/**
 * Codes the page explains for itself.
 *
 * Suppliers print short codes on their lines and a legend somewhere on the page that says what they mean:
 * a storage letter next to the quantity ("C congelato / S surgelato / F fresco / ST stagionato / CN conserva"),
 * a row type before the article code ("TIPO RIGA : O - OFFERTE | S - SCONTO | C - CAMBIO PREZZO"), and so on.
 * Every supplier has its own letters, so none are assumed: the legend is read from the page, and the codes are
 * looked for in the item table.
 *
 * A letter is taken as a code only when the page proves it:
 *  - the legend defines it (two or more entries on one line, written "C congelato", "C=congelato" or "C - CONGELATO",
 *    separated by "/", "|", ";", "," or " - ");
 *  - it stands in the same place on two or more items (the same column, or glued to the same value), and that place
 *    holds this legend's codes more than any other legend's; one item alone is enough only when the letter stands
 *    right before the item's code (a row type has nothing else to line up with).
 *
 * Group lines are read the same way: rows of the table with no numbers that head a run of items ("Merce non
 * deperibile - Congelato", "Merce non alimentare"). They are taken when one of them heads the first item (a group
 * starts with the first line) and they look alike (the same first word, or product-information words); every item
 * then carries the group printed above it.
 *
 * What the codes mean is kept on the item ([ParsedLineItem.marks]) and shown with it; the page map explains these
 * words instead of leaving them over.
 */
object PageCodes {

    /** One legend: its title ("tipo riga"), or the kind its meanings show ("storage"), and code to meaning. */
    data class Legend(val name: String, val codes: kotlin.collections.Map<String, String>)

    const val GROUP = "group"

    // ------------------------------------------------------------------ legends

    private val CODE = rx("^[A-Z]{1,3}$")
    private val STORAGE_WORDS = rx("(?i)^(congel|surgel|fresc|stagion|conserv|refriger|frozen|chilled|ambient|dry|secc)")
    private val SEPARATORS = setOf("/", "|", ";", ",", "-", "–")

    /** The legends printed on the pages, lines next to each other with no title of their own joined into one. */
    fun legends(pages: List<List<OcrLine>>): List<Legend> {
        val found = mutableListOf<Pair<String?, kotlin.collections.Map<String, String>>>()
        for (lines in pages) {
            // Each line as read, and each row of the layout (a legend split in two pieces by the engine).
            val texts = lines.map { it.text } + runCatching { LayoutRows.layout(lines).rows.map { r -> r.joinToString(" ") { it.text.trim() } } }.getOrDefault(emptyList())
            for (t in texts) parse(t)?.let { found += it }
        }
        // The same legend read twice (a line and its row) or in pieces: titled ones by title, untitled by kind.
        val out = linkedMapOf<String, MutableMap<String, String>>()
        for ((title, codes) in found) {
            val name = title ?: kindOf(codes.values)
            val into = out.getOrPut(name) { linkedMapOf() }
            for ((c, m) in codes) if (c !in into || into[c]!!.length < m.length) into[c] = m
        }
        return out.map { (n, c) -> Legend(n, c) }
    }

    private fun kindOf(meanings: Collection<String>): String =
        if (meanings.count { m -> STORAGE_WORDS.containsMatchIn(m.trim()) } * 2 >= meanings.size) "storage" else "code"

    /**
     * "* C congelato / S surgelato/ F fresco ..." → (null, {C: congelato, S: surgelato, F: fresco});
     * "TIPO RIGA : O - OFFERTE | S - SCONTO" → ("tipo riga", {O: offerte, S: sconto}). Null when the line is no legend.
     */
    fun parse(text: String): Pair<String?, kotlin.collections.Map<String, String>>? {
        var t = text.trim().removePrefix("(*)").trimStart('*', ' ', '(', ')')
        // A title before ":" ("TIPO RIGA :", "Legenda:", "Storage:"), when what follows it is the legend.
        var title: String? = null
        val colon = t.indexOf(':')
        if (colon in 1..24) {
            val head = t.substring(0, colon).trim()
            if (head.isNotEmpty() && head.split(' ').size <= 3 && head.none(Char::isDigit) && !CODE.matches(head)) {
                title = head.lowercase().takeUnless { it in setOf("legenda", "legend", "note", "nota") }
                t = t.substring(colon + 1).trim()
            }
        }
        // Tokens: "C=congelato" split at the sign; separators as their own tokens.
        val tokens = t.replace(Regex("([A-Za-z])\\s*=\\s*"), "$1 = ").replace("|", " | ").replace("/", " / ").replace(";", " ; ")
            .split(Regex("\\s+")).map { it.trimStart('*') }.filter { it.isNotEmpty() }
        val codes = mutableListOf<String>()
        val meanings = mutableListOf<MutableList<String>>()
        var closed = true // the line's start: a code may follow
        var separated = false
        var ranOn = false
        loop@ for ((i, tok) in tokens.withIndex()) {
            val bare = tok.trimEnd(',', '.', ':')
            val next = tokens.drop(i + 1).firstOrNull { it != "=" && it != "-" && it != "–" }?.trimEnd(',', '.', ':')
            when {
                tok == "=" -> separated = true
                tok in SEPARATORS -> { if (meanings.lastOrNull()?.isNotEmpty() == true) { closed = true; separated = true } }
                // A code: short capitals that start an entry (the line's start, after a separator, or after a meaning
                // when entries are written "C congelato S surgelato"), followed by a word that is not a code.
                CODE.matches(bare) && bare !in codes && (closed || meanings.lastOrNull()?.isNotEmpty() == true) &&
                    next != null && next.length >= 3 && next.all { c -> c.isLetter() || c == '\'' } && !CODE.matches(next) -> {
                    codes += bare; meanings += mutableListOf<String>(); closed = false
                }
                meanings.isEmpty() -> return null // a legend starts with its first code
                // Prose after the legend, or a number: the legend has ended.
                closed || bare.any(Char::isDigit) || bare.none(Char::isLetter) -> { ranOn = true; break@loop }
                else -> { meanings.last() += bare; if (tok.endsWith(',') || tok.endsWith('.')) closed = true }
            }
        }
        if (codes.size < 2 || meanings.any { it.isEmpty() } || !(separated || title != null)) return null
        // The last meaning may run on into the text printed after the legend (a line that goes on, or more than three
        // words): then it is as long as the longest other one.
        val longest = meanings.dropLast(1).maxOf { it.size }.coerceIn(1, 3)
        if ((ranOn || meanings.last().size > 3) && meanings.last().size > longest) meanings[meanings.lastIndex] = meanings.last().take(longest).toMutableList()
        return title to codes.zip(meanings.map { it.joinToString(" ").lowercase() }).toMap()
    }

    // ------------------------------------------------------------------ on the items

    /** The page's codes and group lines put on its items ([ParsedLineItem.marks]). */
    fun apply(doc: ParsedDocument, pages: List<List<OcrLine>>, ownVatNumber: String?): ParsedDocument {
        if (doc.lineItems.isEmpty()) return doc
        val map = runCatching { PageMap.build(pages, doc, ownVatNumber) }.getOrNull() ?: return doc
        val marks = codesOn(map, legends(pages)) + groupsOn(map)
        if (marks.isEmpty()) return doc
        val items = doc.lineItems.mapIndexed { i, it ->
            val add = marks.filter { (item, _) -> item == i }.map { it.second }.filter { m -> it.marks.none { o -> o.kind == m.kind } }
            if (add.isEmpty()) it else it.copy(marks = it.marks + add)
        }
        return doc.copy(lineItems = items)
    }

    private val GLUED = rx("^[0-9][0-9.,]*[0-9]([A-Z]{1,3})$")

    /** The legends' codes printed on the items, where the page proves them (see the class notes). */
    internal fun codesOn(map: PageMap.Map, legends: List<Legend>): List<Pair<Int, ItemMark>> {
        if (legends.isEmpty()) return emptyList()
        val all = legends.flatMap { it.codes.keys }.toSet()
        data class Cand(val item: Int, val code: String, val x: Int, val word: PageMap.Placed)
        val cands = map.words.mapNotNull { p ->
            val item = p.item ?: return@mapNotNull null
            if (p.region != PageMap.Region.TABLE) return@mapNotNull null
            val t = p.word.text.trim().trim('.', ',', ':', ';', '|', '*', '(', ')')
            val code = when {
                p.role != PageMap.Role.FIELD && CODE.matches(t) -> t
                // Glued to a value the reading took ("20,000F").
                else -> GLUED.matchEntire(t)?.groupValues?.get(1)
            }
            if (code == null || code !in all) null else Cand(item, code, p.word.box.right, p)
        }
        if (cands.isEmpty()) return emptyList()
        // Places: candidates whose right edges line up (within three letters' height: a letter glued to the quantity
        // ends a little before one printed in its own column).
        val height = map.words.map { it.word.box.bottom - it.word.box.top }.sorted().let { hs -> hs[hs.size / 2] }.coerceAtLeast(8)
        val tol = height * 3
        val places = mutableListOf<MutableList<Cand>>()
        for (c in cands.sortedBy { it.x }) {
            val last = places.lastOrNull()
            if (last != null && c.x - last.last().x <= tol) last += c else places += mutableListOf(c)
        }
        val used = mutableSetOf<String>() // legends already given a place
        val out = mutableListOf<Pair<Int, ItemMark>>()
        // Each place goes to the legend that explains most of its codes, bigger places first; on a tie, to a legend
        // with no place yet (a page prints each legend's codes in one place).
        for (place in places.sortedByDescending { it.size }) {
            val legend = legends.maxByOrNull { l -> place.count { it.code in l.codes } * 2 + if (l.name in used) 0 else 1 } ?: continue
            val mine = place.filter { it.code in legend.codes }
            if (mine.size * 5 < place.size * 4) continue // the place mixes codes of several legends: not proven
            val items = mine.groupBy { it.item }
            val proven = items.size >= 2 || (items.size == 1 && mine.size == 1 && beforeCode(map, mine[0].word))
            if (!proven) continue
            used += legend.name
            for ((item, cs) in items) {
                val c = cs.first()
                if (out.none { (i, m) -> i == item && m.kind == legend.name }) out += item to ItemMark(legend.name, c.code, legend.codes.getValue(c.code))
            }
        }
        return out
    }

    /** The word stands right before the item's code on the same row (a row type: "S 12345"). */
    private fun beforeCode(map: PageMap.Map, w: PageMap.Placed): Boolean {
        val right = map.words.filter { it.word.page == w.word.page && it.word.row == w.word.row && it.word.box.left >= w.word.box.right - 2 }
            .minByOrNull { it.word.box.left } ?: return false
        return right.role == PageMap.Role.FIELD && right.field == "code" && right.item == w.item
    }

    private val GROUP_WORDS = rx("(?i)(deperibil|alimentar|congel|surgel|fresc|refriger|secc|frozen|chilled|ambient|non-?food|goods|merce|prodott)")

    /** A group line written for comparison: words only, lower case, look-alikes folded, separators dropped. */
    fun groupKey(text: String): String =
        text.split(Regex("\\s+")).map { PageMap.norm(it) }.filter { w -> w.isNotEmpty() && w.any(Char::isLetter) }.joinToString(" ") { PageMap.fold(it) }

    /** Group lines over runs of items (see the class notes): every item gets the group printed above it. */
    internal fun groupsOn(map: PageMap.Map): List<Pair<Int, ItemMark>> {
        if (map.tables.isEmpty() || map.items.isEmpty()) return emptyList()
        val ownRows = map.items.map { it.page to it.own }.toSet()
        data class Line(val page: Int, val row: Int, val text: String)
        val byRow = map.words.filter { it.region == PageMap.Region.TABLE }.groupBy { it.word.page to it.word.row }
        val cands = map.tables.flatMap { t ->
            (t.head + 1 until t.foot).mapNotNull { r ->
                if ((t.page to r) in ownRows) return@mapNotNull null
                val ws = byRow[t.page to r]?.sortedBy { it.word.box.left } ?: return@mapNotNull null
                val texts = ws.map { it.word.text }
                val letters = texts.filter { w -> w.count(Char::isLetter) >= 2 }
                when {
                    texts.any { w -> w.any(Char::isDigit) } -> null
                    letters.size < 2 -> null
                    // Taken by the reading as an item's value (the rest of a name), or a heading row of labels.
                    ws.any { it.role == PageMap.Role.FIELD } -> null
                    letters.count { w -> PageMap.isPrint(w) } * 2 > letters.size -> null
                    else -> Line(t.page, r, texts.joinToString(" ").trim().trim('*', '-', ' '))
                }
            }
        }
        fun first(l: Line) = PageMap.fold(PageMap.norm(l.text.split(' ').first()))
        fun alike(a: String, b: String) = a.length >= 4 && b.length == a.length && a.zip(b).count { (x, y) -> x != y } <= 1
        val groups = cands.filter { l -> GROUP_WORDS.containsMatchIn(l.text) || cands.any { o -> o != l && alike(first(o), first(l)) } }
        if (groups.isEmpty()) return emptyList()
        // A group starts with the first item: one of the lines must head it.
        val firstItem = map.items.minWithOrNull(compareBy({ it.page }, { it.own })) ?: return emptyList()
        if (groups.none { g -> g.page == firstItem.page && g.row < firstItem.own }) return emptyList()
        return map.items.mapNotNull { b ->
            val g = groups.filter { g -> g.page < b.page || (g.page == b.page && g.row < b.own) }.maxWithOrNull(compareBy({ it.page }, { it.row })) ?: return@mapNotNull null
            b.item to ItemMark(GROUP, null, g.text)
        }.distinctBy { it.first }
    }
}
