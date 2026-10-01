package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer

/**
 * Reads the item table by columns, the way a person does: it finds the heading row (CODICE, COLLI,
 * DESCRIZIONE, U.M., QUANTITÀ, PREZZO, SCONTO, IMPORTO, IVA...), measures where each heading sits on the
 * page, and puts every word of every row under the heading it stands beneath.
 *
 * This removes the guessing a text-only reading has to do: the number under COLLI is the colli (never
 * part of the name), the number under QUANTITÀ is the quantity and the one under PREZZO the price,
 * whatever order the supplier prints them in. Quantity x price = amount is still checked on every line.
 *
 * Positions come from the OCR word boxes, corrected for the tilt of the photo (the same slope used to
 * rebuild the rows) and, row by row, for perspective (the amount column is used as an anchor).
 */
object TableReader {

    enum class Kind { CODE, PACKAGES, DESCRIPTION, UNIT, PACK_TYPE, PACK_SIZE, QUANTITY, PRICE, DISCOUNT, AMOUNT, VAT, LOT, EXPIRY }

    data class Column(val kind: Kind, val left: Double, val right: Double) {
        val center: Double get() = (left + right) / 2
    }

    /** [qtyPzOrKg]: the quantity heading says "PZ/KG" (pieces or kilos, depending on the product). */
    data class Header(val columns: List<Column>, val hasLotColumn: Boolean = false, val qtyPzOrKg: Boolean = false)

    private data class Word(val text: String, val left: Double, val right: Double) {
        val center: Double get() = (left + right) / 2
    }

    // Heading words -> column kind. "cont" = continues the previous heading ("PREZZO UNIT.", "DESCRIZIONE BENI").
    private enum class H { CODE, ARTICOLO, PACKAGES, DESCRIPTION, UNIT, PACK_TYPE, PACK_SIZE, QUANTITY, TOT, PRICE, DISCOUNT, AMOUNT, VAT, LOT, EXPIRY, CONT, PERCENT }

    /**
     * Heading words and their synonyms, as suppliers print them. A word the OCR slightly misread ("DESCRIZTONE",
     * "QUANTTTA", "TVA", "IMP0RTO") is recognised too: see [heading].
     */
    private val HEADINGS: Map<String, H> = buildMap {
        fun put(h: H, vararg words: String) = words.forEach { put(it, h) }
        put(H.CODE, "codice", "cod", "cod.art", "codart", "cod.articolo", "art", "cod. art", "codice articolo", "rif", "cod.prod", "codprod",
            "ean", "sku", "cod.int", "codice prodotto", "cod.forn", "riferimento")
        put(H.ARTICOLO, "articolo")
        put(H.PACKAGES, "colli", "n.colli", "ncolli", "cartoni", "colli/pz", "imballi", "cartone", "cartoni/pz")
        put(H.DESCRIPTION, "descrizione", "prodotto", "denominazione", "descr", "prodotti", "voce", "descrizioni", "beni/servizi")
        put(H.CONT, "beni", "merce", "articoli", "unit", "unitario", "netto", "lordo", "doc", "cessione", "servizi")
        put(H.UNIT, "u.m", "um", "u/m", "unita", "mis", "misura", "u.mis", "unita di misura", "udm", "u.d.m")
        put(H.PACK_TYPE, "tipo", "imballo")
        put(H.PACK_SIZE, "conf", "confez", "formato", "pezzatura", "peso", "confezione", "grammatura")
        put(H.QUANTITY, "quantita", "q.ta", "qta", "qt", "quant", "q.tà", "qtà", "pezzi", "n.pz", "nr.pz", "q.ta'", "qty", "quantità")
        put(H.TOT, "tot")
        put(H.PRICE, "prezzo", "prz", "pr.unit", "p.unit", "p.u", "pu", "prezzi", "listino", "costo", "prezzo unitario", "pr.unitario", "p.zo")
        put(H.DISCOUNT, "sconto", "sconti", "sc", "sc.%", "sc%", "%sc", "magg", "sconto%", "sc.1", "sc.2", "abbuono")
        put(H.AMOUNT, "importo", "totale", "valore", "imponibile", "ammontare", "importi", "imp", "tot.riga", "totale riga")
        put(H.VAT, "iva", "%iva", "aliq", "aliquota", "c.iva", "cod.iva", "ci", "ali", "c.i", "iva%")
        put(H.LOT, "lotto", "lotti", "lot", "lott", "n.lotto", "batch", "l.to")
        put(H.EXPIRY, "scadenza", "scad", "tmc", "data sc", "scadenze")
        put(H.PERCENT, "%")
    }

    /** OCR slips in heading words: digits read for letters and a few look-alikes ("TVA" for "IVA"). */
    private fun ocrNormal(w: String): String {
        var t = if (w.count(Char::isLetter) >= 3) w.replace('0', 'o').replace('1', 'i') else w
        if (t == "tva" || t == "lva" || t == "1va") t = "iva"
        return t
    }

    /**
     * Headings learned for the supplier being read (see [SupplierLayout.headings]); only consulted for words the
     * built-in list does not know. Set for the duration of one [read] or [header] call.
     */
    private val learned = ThreadLocal<Map<String, Kind>>()

    private fun <T> withLearned(headings: Map<String, Kind>, block: () -> T): T {
        val before = learned.get()
        learned.set(headings)
        try { return block() } finally { learned.set(before) }
    }

    private fun hOf(k: Kind): H = when (k) {
        Kind.CODE -> H.CODE; Kind.PACKAGES -> H.PACKAGES; Kind.DESCRIPTION -> H.DESCRIPTION; Kind.UNIT -> H.UNIT
        Kind.PACK_TYPE -> H.PACK_TYPE; Kind.PACK_SIZE -> H.PACK_SIZE; Kind.QUANTITY -> H.QUANTITY; Kind.PRICE -> H.PRICE
        Kind.DISCOUNT -> H.DISCOUNT; Kind.AMOUNT -> H.AMOUNT; Kind.VAT -> H.VAT; Kind.LOT -> H.LOT; Kind.EXPIRY -> H.EXPIRY
    }

    /** Whether [word] begins a heading the app knows by itself ("PREZZO", not "UNIT." which continues one). */
    fun startsHeading(word: String): Boolean = withLearned(emptyMap()) { heading(word).let { it != null && it != H.CONT && it != H.PERCENT } }

    /** Whether [word] is a heading the app knows by itself (without anything learned). */
    fun isKnownHeading(word: String): Boolean = withLearned(emptyMap()) { heading(word) != null }

    private fun heading(word: String): H? {
        val w = ocrNormal(norm(word).trim('.', ':', ',', '\'', '’', '"', '|', '(', ')'))
        if (w.isEmpty()) return null
        HEADINGS[w]?.let { return it }
        // A slightly misread long heading word ("descrizt one" -> "descriztone" -> descrizione): one letter off, same start.
        if (w.length >= 6 && w.all { it.isLetter() }) {
            val near = HEADINGS.entries.filter { (k, _) -> k.length >= 6 && k.all(Char::isLetter) && k[0] == w[0] && SmartMatcher.damerau(k, w, 1) <= 1 }
            if (near.map { it.value }.distinct().size == 1) return near.first().value
        }
        learned.get()?.let { m -> (m[w] ?: m[SupplierLayouts.headingKey(word)])?.let { return hOf(it) } }
        return null
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

    /** Finds the column layout in a heading row, or null if the row is not an item table heading. */
    fun header(rowWords: List<OcrLine>, slope: Double = 0.0, headings: Map<String, Kind> = emptyMap()): Header? =
        withLearned(headings) { headerOf(rowWords.flatMap { words(it, slope) }.sortedBy { it.left }) }

    private fun headerOf(words: List<Word>): Header? {
        if (words.size < 3) return null
        if (words.any { it.text.contains(',') && it.text.any(Char::isDigit) }) return null // amounts: not a heading
        data class Raw(var h: H, var left: Double, var right: Double)
        val raw = mutableListOf<Raw>()
        var pendingPercent: Word? = null
        for (w in words) {
            val h = heading(w.text)
            when {
                h == null -> { pendingPercent = null }
                h == H.PERCENT -> pendingPercent = w
                h == H.CONT -> raw.lastOrNull()?.let { if (w.left - it.right < 3 * (w.right - w.left) + 40) it.right = w.right }
                raw.isNotEmpty() && raw.last().h == h -> raw.last().right = w.right
                else -> {
                    val left = pendingPercent?.left ?: w.left
                    raw += Raw(h, left, w.right)
                    pendingPercent = null
                }
            }
        }
        val kinds = raw.map { it.h }.toSet()
        val hasDescription = H.DESCRIPTION in kinds || H.ARTICOLO in kinds
        if (!hasDescription || (H.AMOUNT !in kinds && H.PRICE !in kinds) || kinds.size < 3) return null
        val columns = mutableListOf<Column>()
        var seenDescription = false
        var seenPrice = false
        var seenAmount = false
        val hasQtyWord = H.QUANTITY in kinds
        for (r in raw) {
            val kind = when (r.h) {
                H.ARTICOLO -> if (H.DESCRIPTION in kinds) Kind.CODE else Kind.DESCRIPTION
                H.CODE -> if (seenAmount) Kind.VAT else if (seenDescription) null else Kind.CODE // "... IMPORTO COD" = VAT code
                H.TOT -> if (!hasQtyWord && !seenPrice) Kind.QUANTITY else if (!seenAmount) Kind.AMOUNT else null
                H.PACKAGES -> Kind.PACKAGES
                H.DESCRIPTION -> Kind.DESCRIPTION
                H.UNIT -> Kind.UNIT
                H.PACK_TYPE -> Kind.PACK_TYPE
                H.PACK_SIZE -> Kind.PACK_SIZE
                H.QUANTITY -> Kind.QUANTITY
                H.PRICE -> Kind.PRICE
                H.DISCOUNT -> Kind.DISCOUNT
                H.AMOUNT -> Kind.AMOUNT
                H.VAT -> Kind.VAT
                H.LOT -> Kind.LOT
                H.EXPIRY -> Kind.EXPIRY
                H.CONT, H.PERCENT -> null
            } ?: continue
            if (kind == Kind.DESCRIPTION) seenDescription = true
            if (kind == Kind.PRICE) seenPrice = true
            if (kind == Kind.AMOUNT) seenAmount = true
            columns += Column(kind, r.left, r.right)
        }
        if (columns.none { it.kind == Kind.DESCRIPTION }) return null
        val pzKg = words.any { norm(it.text).trim('.', ':') in setOf("pz/kg", "kg/pz") }
        return Header(columns.sortedBy { it.left }, hasLotColumn = columns.any { it.kind == Kind.LOT }, qtyPzOrKg = pzKg)
    }

    /** Words of an OCR line with deskewed x positions, OCR slips repaired ("PREZZ0", "l4,50"), glued code+colli split. */
    private fun words(line: OcrLine, slope: Double): List<Word> = rawWords(line, slope).flatMap { w ->
        val text = OcrCleanup.fixWordToken(OcrCleanup.fixNumericToken(w.text))
        val m = CODE_WITH_COLLI.find(text)
        val g = GLUED_SIZE.matchEntire(text)
        if (g != null && m == null) {
            // "KG1", "GR400": pack size printed without a space.
            val cut = w.left + (w.right - w.left) * g.groupValues[1].length / text.length
            listOf(Word(g.groupValues[1], w.left, cut), Word(g.groupValues[2], cut, w.right))
        } else if (m != null) {
            // "10000032x3": article code 1000003 and colli 2x3 printed without a space.
            val cut = w.left + (w.right - w.left) * m.groupValues[1].length / text.length
            listOf(Word(m.groupValues[1], w.left, cut), Word(m.groupValues[2], cut, w.right))
        } else {
            listOf(w.copy(text = text))
        }
    }

    private fun rawWords(line: OcrLine, slope: Double): List<Word> {
        val y = line.centerY
        if (line.words.isNotEmpty()) {
            return line.words.filter { it.text.isNotBlank() }.map { Word(it.text.trim(), it.left + slope * it.centerY, it.right + slope * it.centerY) }
        }
        val text = line.text
        if (text.isBlank()) return emptyList()
        val perChar = line.width.toDouble() / text.length
        val out = mutableListOf<Word>()
        var i = 0
        while (i < text.length) {
            if (text[i] == ' ') { i++; continue }
            val start = i
            while (i < text.length && text[i] != ' ') i++
            out += Word(text.substring(start, i), line.left + start * perChar + slope * y, line.left + i * perChar + slope * y)
        }
        return out
    }

    // ------------------------------------------------------------------ rows

    private val MONEY = Regex("^-?€?\\d{1,3}(?:\\.\\d{3})*,\\d{2,4}-?€?$|^-?€?\\d+[.,]\\d{2,4}-?€?$")
    private val NUMBER = Regex("^-?\\d+(?:[.,]\\d+)*(?:%)?$")
    private val LONG_CODE = Regex("^\\d{4,}$")
    private val CODE_WITH_COLLI = Regex("^(\\d{5,})(\\d{1,2}[xX×]\\d{1,3})$")
    private val VAT_RATES = setOf("0", "4", "5", "10", "22")
    /** "111,4104": amount and VAT code printed without a space. */
    private val AMOUNT_WITH_VAT = Regex("^(\\d{1,3}(?:\\.\\d{3})*,\\d{2})(04|05|10|22|4|5)$")
    /** An amount whose decimal comma the OCR lost ("753" for 7,53). */
    private val DIGITS_ONLY = Regex("^\\d{3,6}$")
    private val GLUED_SIZE = Regex("^(?i)(KG|GR|LT|ML|CL|PZ)(\\d+(?:[.,]\\d+)?)$")
    private val TWO_LETTER = Regex("^[A-Z]{1,2}$")
    private val NUMBER_KINDS = setOf(Kind.QUANTITY, Kind.PRICE, Kind.DISCOUNT, Kind.AMOUNT, Kind.PACKAGES)

    private fun isNumeric(t: String) = NUMBER.matches(t.trim('€', ' '))
    private fun isMoney(t: String) = MONEY.matches(t.trim())

    /**
     * Reads the items of every page that has an item table heading. Returns null when no page has one
     * (the text reading is then used).
     */
    fun read(pages: List<LayoutRows.Layout>, headings: Map<String, Kind> = emptyMap()): List<ParsedLineItem>? =
        withLearned(headings) { readPages(pages) }

    private fun readPages(pages: List<LayoutRows.Layout>): List<ParsedLineItem>? {
        var any = false
        val items = mutableListOf<ParsedLineItem>()
        for (layout in pages) {
            val rows = layout.rows.map { row -> row.flatMap { words(it, layout.slope) }.sortedBy { it.left } }
            val hIdx = rows.indexOfFirst { headerOf(it) != null }
            if (hIdx < 0) continue
            any = true
            var header = headerOf(rows[hIdx])!!
            var start = hIdx + 1
            // Headings printed on two or three lines ("TIPO / CONF.", "TOT. / PZ/KG", "CODICE" alone on its own line):
            // the lines around the heading row are merged into it, column by column.
            val stray = mutableListOf<Word>()
            for (j in listOf(hIdx - 1, hIdx + 1, hIdx + 2)) {
                val r = rows.getOrNull(j) ?: continue
                if (!isHeadingLine(r)) { if (j > hIdx) break else continue }
                header = mergeHeading(header, r)
                if (j >= start) start = j + 1
                // Numbers that landed on a heading line (a tilted photo): they belong to the first product row.
                stray += r.filter { heading(it.text) == null && (isMoney(it.text) || isNumeric(it.text)) && it.left > (header.columns.firstOrNull { c -> c.kind == Kind.DESCRIPTION }?.right ?: 0.0) }
            }
            // A second heading row ("ID LOTTO QTA.LOT."): lots are printed on the row under each item.
            val second = rows.getOrNull(start)
            if (second != null && second.none { isMoney(it.text) } && second.any { heading(it.text) == H.LOT }) {
                header = header.copy(hasLotColumn = true)
                start++
            }
            val body = rows.drop(start).toMutableList()
            if (stray.isNotEmpty()) {
                val firstItem = body.indexOfFirst { r -> r.any { it.text.count(Char::isDigit) >= 5 } }
                if (firstItem >= 0 && body[firstItem].none { isMoney(it.text) && it.left > (header.columns.lastOrNull { c -> c.kind == Kind.PRICE }?.right ?: Double.MAX_VALUE) }) {
                    body[firstItem] = (body[firstItem] + stray).sortedBy { it.left }
                }
            }
            items += readRows(body, header)
        }
        return if (any) items else null
    }

    /** A line of headings under (or over) the main heading row: heading words, no article code, no product. */
    private fun isHeadingLine(row: List<Word>): Boolean {
        if (row.isEmpty()) return false
        if (row.any { w -> w.text.count(Char::isDigit) >= 5 }) return false
        val headings = row.count { heading(it.text) != null || norm(it.text).trim('.') in EXTRA_HEADING_WORDS }
        return headings >= 1 && row.count { it.text.count(Char::isLetter) >= 4 && heading(it.text) == null && norm(it.text).trim('.', '(', ')') !in EXTRA_HEADING_WORDS } <= 3
    }

    private val EXTRA_HEADING_WORDS = setOf("pz/kg", "kg/pz", "n.xpz", "nxpz", "n.xxpz", "natura", "naturae", "qualita", "eur", "euro", "€")

    /** Adds the heading words of [row] to [header]: a word under an existing column joins it, one on its own adds a column. */
    private fun mergeHeading(header: Header, row: List<Word>): Header {
        val cols = header.columns.toMutableList()
        var pzKg = header.qtyPzOrKg
        for (w in row) {
            val n = norm(w.text).trim('.', ':')
            if (n == "pz/kg" || n == "kg/pz") pzKg = true
            val h = heading(w.text) ?: continue
            val idx = cols.indexOfFirst { c -> w.right > c.left - 8 && w.left < c.right + 8 }
            if (idx >= 0) {
                val c = cols[idx]
                // "TIPO" over "CONF.": the column holds packaging and pack size ("BT LT 1", "CS KG").
                val kind = if (c.kind == Kind.PACK_TYPE && h == H.PACK_SIZE) Kind.PACK_SIZE else c.kind
                cols[idx] = Column(kind, minOf(c.left, w.left), maxOf(c.right, w.right))
            } else {
                val kind = when (h) {
                    H.CODE -> if (cols.none { it.kind == Kind.CODE } && cols.all { it.left > w.right }) Kind.CODE else null
                    H.PACKAGES -> Kind.PACKAGES
                    H.UNIT -> Kind.UNIT
                    H.LOT -> Kind.LOT
                    H.EXPIRY -> Kind.EXPIRY
                    H.DISCOUNT -> Kind.DISCOUNT
                    else -> null
                } ?: continue
                if (cols.none { it.kind == kind }) cols += Column(kind, w.left, w.right)
            }
        }
        cols.sortBy { it.left }
        return header.copy(columns = cols, hasLotColumn = header.hasLotColumn || cols.any { it.kind == Kind.LOT }, qtyPzOrKg = pzKg)
    }

    private fun readRows(rows: List<List<Word>>, header: Header): List<ParsedLineItem> {
        val items = mutableListOf<ParsedLineItem>()
        var pendingDescription: String? = null
        // Numbers that landed on a section title ("Merce non alimentare"): they belong to the product on the next row.
        var headingNumbers: ParsedLineItem? = null
        for (row in rows) {
            if (row.isEmpty()) continue
            val text = row.joinToString(" ") { it.text }
            if (ReceiptParser.isFooterRow(text)) break
            if (ReceiptParser.isNotAnItemRow(text)) { pendingDescription = null; headingNumbers = null; continue }
            val cells = assign(row, header)
            val item = buildItem(cells, text, header)
            val scan = LotExtractor.scan(text)
            val waiting = headingNumbers
            if (waiting != null) {
                headingNumbers = null
                val desc = cells[Kind.DESCRIPTION].orEmpty().joinToString(" ") { it.text }
                if (item == null && desc.count(Char::isLetter) >= 3 && !ReceiptParser.isSectionHeading(desc)) {
                    val code = cells[Kind.CODE].orEmpty().firstOrNull { it.text.count(Char::isDigit) >= 4 }?.text
                    val colli = cells[Kind.PACKAGES].orEmpty().joinToString("") { it.text }.ifEmpty { null }
                    items += waiting.copy(
                        originalDescription = cleanDescription(desc.split(' ')),
                        itemCode = code ?: waiting.itemCode,
                        packages = colli?.let { Extracted(it, Confidence.HIGH, text) } ?: waiting.packages,
                    )
                    continue
                }
            }
            if (item != null && ReceiptParser.isSectionHeading(item.originalDescription)) {
                headingNumbers = item
                continue
            }
            when {
                item == null -> {
                    val prev = items.lastOrNull()
                    val desc = cells[Kind.DESCRIPTION].orEmpty().joinToString(" ") { it.text }
                    val onlyCode = row.size <= 2 && row.all { it.text.any(Char::isDigit) } && ItalianDates.findDates(text).isEmpty()
                    if (prev != null && (scan.lot != null || scan.expiry != null) && desc.count(Char::isLetter) < 12) {
                        items[items.lastIndex] = prev.copy(lotNumber = prev.lotNumber ?: scan.lot, expiryDate = prev.expiryDate ?: scan.expiry)
                        pendingDescription = null
                    } else if (prev != null && header.hasLotColumn && onlyCode && prev.lotNumber == null) {
                        items[items.lastIndex] = prev.copy(lotNumber = Extracted(row.first().text, Confidence.LOW, text))
                        pendingDescription = null
                    } else {
                        pendingDescription = desc.takeIf { it.count(Char::isLetter) >= 3 }
                    }
                }
                item.originalDescription.isBlank() -> {
                    // Amounts on the row below a description-only row: one item over two rows.
                    val d = pendingDescription
                    if (d != null) items += item.copy(originalDescription = d)
                    pendingDescription = null
                }
                else -> {
                    items += item.copy(lotNumber = item.lotNumber ?: scan.lot, expiryDate = scan.expiry)
                    pendingDescription = null
                }
            }
        }
        return items
    }

    /** Puts each word of a row under a column. */
    private fun assign(row: List<Word>, header: Header): Map<Kind, List<Word>> {
        val cols = header.columns
        val mapped = mapRow(row, header)
        val out = linkedMapOf<Kind, MutableList<Word>>()
        var lastKind: Kind? = null
        for ((w, x) in mapped) {
            val idx = columnIndex(x, cols)
            var kind = cols[idx].kind
            val t = w.text
            val numeric = isNumeric(t) || ReceiptParser.COLLI_PATTERN.matches(t)
            val letters = t.count(Char::isLetter)
            kind = when {
                // The first word of the name printed close to the code or Pkgs ("1x10 LATTE ARBOREA", "1 COPPA SUINO").
                !numeric && letters >= 2 && kind in setOf(Kind.CODE, Kind.PACKAGES) && !(t.length == 1 && lastKind == null) &&
                    Units.normalizeKnown(t.trimEnd('.')) == null -> Kind.DESCRIPTION
                // A unit word under a number column ("NR 40,000" with no U.M. heading).
                !numeric && Units.normalizeKnown(t.trimEnd('.')) != null && kind in NUMBER_KINDS -> Kind.UNIT
                // Words spill over from the description into the columns on its right.
                !numeric && letters >= 1 && kind !in setOf(Kind.DESCRIPTION, Kind.UNIT, Kind.PACK_TYPE, Kind.PACK_SIZE, Kind.LOT, Kind.CODE, Kind.VAT, Kind.EXPIRY) ->
                    if (lastKind == Kind.DESCRIPTION || x < cols[idx].left) Kind.DESCRIPTION else Kind.PACK_TYPE
                !numeric && kind == Kind.PACK_TYPE && letters >= 4 && Units.normalizeKnown(t) == null && lastKind == Kind.DESCRIPTION -> Kind.DESCRIPTION
                !numeric && kind == Kind.PACK_SIZE && letters >= 4 && Units.normalizeKnown(t.trimEnd('.')) == null && lastKind == Kind.DESCRIPTION -> Kind.DESCRIPTION
                !numeric && kind == Kind.UNIT && Units.normalizeKnown(t) == null && letters >= 3 && lastKind == Kind.DESCRIPTION -> Kind.DESCRIPTION
                !numeric && kind == Kind.CODE && letters >= 3 && !t.any(Char::isDigit) -> Kind.DESCRIPTION
                else -> kind
            }
            // An article code is unmistakable (5+ digits at the start of the row), wherever the photo shifted it.
            if (LONG_CODE.matches(t) && t.length >= 5 && lastKind == null || (LONG_CODE.matches(t) && t.length >= 5 && kind == Kind.PACKAGES && out[Kind.CODE].isNullOrEmpty())) {
                if (cols.any { it.kind == Kind.CODE }) kind = Kind.CODE
            }
            // "KG" / "GR" / "LT" printed before the pack size, drifting into the TIPO column.
            if (!numeric && kind == Kind.PACK_TYPE && cols.any { it.kind == Kind.PACK_SIZE }) {
                val u = Units.normalizeKnown(t.trimEnd('.'))
                if (u != null && Units.dimension(u) != null) kind = Kind.PACK_SIZE
            }
            // Single-letter marker before the code ("O" offer, read as "0").
            if (lastKind == null && t.length == 1 && (t == "0" || t == "O")) continue
            out.getOrPut(kind) { mutableListOf() } += w
            lastKind = kind
        }
        overflowLeft(out, cols)
        return out
    }

    /**
     * One number per number column: when the photo shifts a number into the next column, that column ends up
     * with two. The left one then belongs to the column on its left ("GR 1500 | 2" read as TOT = "1500 2").
     */
    private fun overflowLeft(cells: MutableMap<Kind, MutableList<Word>>, cols: List<Column>) {
        val order = cols.map { it.kind }.distinct()
        for (k in listOf(Kind.AMOUNT, Kind.PRICE, Kind.DISCOUNT, Kind.QUANTITY)) {
            val list = cells[k] ?: continue
            val idx = order.indexOf(k)
            if (idx <= 0) continue
            val left = order[idx - 1]
            while (list.count { isNumeric(it.text) } > 1) {
                val first = list.first { isNumeric(it.text) }
                list.remove(first)
                cells.getOrPut(left) { mutableListOf() } += first
            }
        }
        for ((_, list) in cells) list.sortBy { it.left }
    }

    /**
     * Maps a row's x positions onto the heading's scale. A photo taken at an angle makes the page a trapezoid,
     * so rows lower down are wider or narrower than the heading row: the amount (the last money on the row)
     * is lined up with the amount column, and the row start with the first column when it clearly belongs there.
     */
    private fun mapRow(row: List<Word>, header: Header): List<Pair<Word, Double>> {
        val cols = header.columns
        val amountCol = cols.lastOrNull { it.kind == Kind.AMOUNT } ?: return row.map { it to it.center }
        val amount = row.lastOrNull { isMoney(it.text) } ?: return row.map { it to it.center }
        val prevNumeric = cols.lastOrNull { it.left < amountCol.left && it.kind in setOf(Kind.PRICE, Kind.DISCOUNT, Kind.QUANTITY) }
        val maxShift = if (prevNumeric != null) 0.45 * (amountCol.center - prevNumeric.center) else 0.5 * (amountCol.right - amountCol.left) + 20
        var shift = amount.right - amountCol.right
        if (kotlin.math.abs(shift) > maxShift) {
            // Right-aligned amounts end near the heading's right edge; centred ones near its centre.
            shift = amount.center - amountCol.center
            if (kotlin.math.abs(shift) > maxShift) return row.map { it to it.center }
        }
        val first = cols.first()
        val firstWord = row.first()
        val startsAtFirst = (first.kind == Kind.CODE && firstWord.text.count(Char::isDigit) >= 4) ||
            (first.kind == Kind.DESCRIPTION && firstWord.text.any(Char::isLetter))
        if (startsAtFirst) {
            val rowSpan = amount.right - firstWord.left
            val hdrSpan = amountCol.right - first.left
            if (rowSpan > 0 && hdrSpan > 0) {
                val scale = hdrSpan / rowSpan
                if (scale in 0.8..1.25) return row.map { it to first.left + (it.center - firstWord.left) * scale }
            }
        }
        return row.map { it to it.center - shift }
    }

    /** Column whose region (midpoints between neighbouring headings) contains x. */
    private fun columnIndex(x: Double, cols: List<Column>): Int {
        for (i in cols.indices) {
            val hi = if (i == cols.lastIndex) Double.MAX_VALUE else (cols[i].right + cols[i + 1].left) / 2
            if (x < hi) return i
        }
        return cols.lastIndex
    }

    private fun buildItem(cells: Map<Kind, List<Word>>, source: String, header: Header): ParsedLineItem? {
        fun cell(k: Kind) = cells[k].orEmpty()
        val amountWord = cell(Kind.AMOUNT).lastOrNull { isMoney(it.text) }
        val amount = amountWord?.let { ItalianNumbers.parseCents(it.text.trim('€')) }
        var amountRepaired = false
        var qty = cell(Kind.QUANTITY).mapNotNull { numberIn(it.text) }.lastOrNull()
        // Without a U.M. column, unit words come from neighbouring columns ("CF GR 0,48"): kg/g/l beat packaging codes.
        val units = cell(Kind.UNIT).mapNotNull { Units.normalizeKnown(it.text.trimEnd('.')) }
        var unit: String? = if (header.columns.any { it.kind == Kind.UNIT }) units.firstOrNull()
        else units.firstOrNull { Units.dimension(it) != null } ?: units.firstOrNull()
        // "4,45KG" in the quantity column
        if (unit == null) cell(Kind.QUANTITY).firstNotNullOfOrNull { Regex("^[\\d.,]+([A-Za-z]{1,3})\\.?$").find(it.text)?.groupValues?.get(1)?.let(Units::normalizeKnown) }?.let { unit = it }
        var price = cell(Kind.PRICE).mapNotNull { numberIn(it.text) }.lastOrNull()
        val discount = discountOf(cell(Kind.DISCOUNT).joinToString("") { it.text })
        var vat = cell(Kind.VAT).mapNotNull { numberIn(it.text) }.firstOrNull { it.stripTrailingZeros().toPlainString() in VAT_RATES }

        var code: String? = null
        // A long number under COLLI is the article code (a table without a CODICE heading, or a shifted photo).
        val pkWords = cell(Kind.PACKAGES).toMutableList()
        pkWords.firstOrNull { LONG_CODE.matches(it.text) && it.text.length >= 5 }?.let { code = it.text; pkWords.remove(it) }
        var packages: String? = pkWords.joinToString("") { it.text }.ifEmpty { null }
        for (w in cell(Kind.CODE)) {
            val m = CODE_WITH_COLLI.find(w.text)
            when {
                m != null -> { code = m.groupValues[1]; if (packages == null) packages = m.groupValues[2] }
                LONG_CODE.matches(w.text) || (w.text.any(Char::isDigit) && w.text.length >= 4) -> if (code == null || code == w.text) code = w.text
                ReceiptParser.COLLI_PATTERN.matches(w.text) -> if (packages == null) packages = w.text
            }
        }
        // Colli printed at the start of the description column ("1x6 ACQUA ...").
        var descWords = cell(Kind.DESCRIPTION).map { it.text }
        if (packages == null && descWords.size > 1 && ReceiptParser.COLLI_PATTERN.matches(descWords.first())) {
            packages = descWords.first(); descWords = descWords.drop(1)
        }
        if (code == null && descWords.size > 1 && LONG_CODE.matches(descWords.first()) && descWords.first().length >= 5) {
            code = descWords.first(); descWords = descWords.drop(1)
        }
        // Pack size column ("GR 800", "KG 0.8", "LT 5"): part of what the product is, not the quantity.
        val packWords = cell(Kind.PACK_SIZE).map { it.text }
        val packUnits = packWords.mapNotNull { Units.normalizeKnown(it.trimEnd('.')) }
        val packUnit = packUnits.firstOrNull { Units.dimension(it) != null } ?: packUnits.firstOrNull()
        val packHasNumber = packWords.any { it.any(Char::isDigit) }
        if (packWords.isNotEmpty() && packHasNumber) {
            // The pack size ("LT 1", "GR 500") is part of what the product is; the packaging code before it ("BT", "NC") is not.
            var from = packWords.indexOfFirst { Units.normalizeKnown(it.trimEnd('.'))?.let(Units::dimension) != null || it.any(Char::isDigit) }
            if (from > 0 && packWords[from].any(Char::isDigit) && Units.normalizeKnown(packWords[from - 1].trimEnd('.')) != null) from--
            val size = if (from >= 0) packWords.drop(from) else packWords
            // Not when the name already says it ("BURRO KG.1" + "KG 1", "LATTE LT.1" + "LT 1").
            val named = SmartMatcher.signature(descWords.joinToString(" ")).sizes
            val added = SmartMatcher.signature("X " + size.joinToString(" ")).sizes
            if (added.isEmpty() || !named.containsAll(added)) descWords = descWords + size.map { it.uppercase() }
        }
        val packSize = if (packHasNumber) PackSizes.parse(packWords) else null
        val unitColumn = header.columns.any { it.kind == Kind.UNIT }
        if (packUnit != null && (unit == null || !unitColumn)) {
            // "GR 800" + TOT 1 = one 800 g pack; "KG" + TOT 4,45 = 4,45 kg weighed.
            unit = if (packHasNumber) "pz" else packUnit
        }
        var description = cleanDescription(descWords)
        if (amount == null && (qty == null || price == null)) return null
        if (description.count(Char::isLetter) < 2) description = ""

        var total = amount
        var consistent = false
        var qtyWorkedOut = false
        var priceRepaired = false
        // The decimal comma is the easiest mark for the OCR to lose: "3450" under PREZZO with an amount of 3,45.
        val priceWord = cell(Kind.PRICE).lastOrNull { numberIn(it.text) != null }?.text
        // ... or read as a thousands dot: "3.450".
        val commaLost = priceWord != null && ((priceWord.all(Char::isDigit) && priceWord.length >= 3) || Regex("^\\d{1,3}\\.\\d{3}$").matches(priceWord))
        if (total != null && price != null && commaLost) {
            val q = qty ?: BigDecimal.ONE
            if (!ReceiptParser.matches(q, price, total)) {
                listOf(2, 3, 4).map { price.movePointLeft(it) }.firstOrNull { ReceiptParser.matches(q, it, total) }?.let {
                    price = it; if (qty == null) { qty = BigDecimal.ONE; qtyWorkedOut = true }; priceRepaired = true; consistent = true
                }
            }
        }
        // "111,4104" read as an amount with 4 decimals: amount 111,41 and VAT code 04, when quantity x price proves it.
        if (vat == null && amountWord != null && qty != null && price != null) {
            AMOUNT_WITH_VAT.matchEntire(amountWord.text)?.let { g ->
                val cents = ItalianNumbers.parseCents(g.groupValues[1])
                if (cents != null && ReceiptParser.matches(qty!!, price!!, cents)) { total = cents; vat = BigDecimal(g.groupValues[2]) }
            }
        }
        // Amount and VAT code glued ("111,4104"), or the amount's comma lost ("753"): repaired only when quantity x price proves it.
        if (qty != null && price != null && (total == null || !ReceiptParser.matches(qty!!, price!!, total!!))) {
            for (w in cell(Kind.AMOUNT) + cell(Kind.VAT)) {
                val glued = AMOUNT_WITH_VAT.matchEntire(w.text)
                val cents = glued?.let { ItalianNumbers.parseCents(it.groupValues[1]) }
                    ?: w.text.takeIf { DIGITS_ONLY.matches(it) }?.toLongOrNull()
                if (cents != null && ReceiptParser.matches(qty!!, price!!, cents)) {
                    total = cents
                    if (glued != null) { if (vat == null) vat = BigDecimal(glued.groupValues[2]) } else amountRepaired = true
                    break
                }
            }
        }
        if (qty != null && price != null && total != null) {
            consistent = ReceiptParser.matches(qty, price, total) || discountMatches(qty, price, discount, total)
        }
        if (!consistent && total != null) {
            // Columns did not line up on this row: fall back to the arithmetic on the numbers as printed.
            val nums = listOf(Kind.PACKAGES, Kind.PACK_SIZE, Kind.QUANTITY, Kind.PRICE, Kind.DISCOUNT)
                .flatMap { k -> cell(k).mapNotNull { w -> numberIn(w.text)?.let { w to it } } }
                .sortedBy { it.first.left }.map { it.second }
            val fixed = pairFor(nums, total)
            if (fixed != null) { qty = fixed.first; price = fixed.second; consistent = true }
            else if (qty == null && price != null && price.signum() > 0) {
                val q = ItalianNumbers.centsToDecimal(total).divide(price, 3, RoundingMode.HALF_UP)
                if (q.stripTrailingZeros().scale() <= 0 && q >= BigDecimal.ONE && q <= BigDecimal(500) && ReceiptParser.matches(q, price, total)) {
                    // The quantity the OCR missed ("GR 400 · · 3,780 · 3,78"): amount / price, marked for a look.
                    qty = q.stripTrailingZeros(); qtyWorkedOut = true; consistent = true
                }
            }
        }
        if (total == null && qty != null && price != null) {
            total = null // never computed: the amount stays missing and the review offers qty x price
        }
        // "TOT. PZ/KG" heading and nothing else says: a whole number is pieces, decimals are kilos.
        if (unit == null && header.qtyPzOrKg && qty != null) {
            unit = if (qty!!.stripTrailingZeros().scale() <= 0) "pz" else "kg"
        }
        // "GR 0,48" on a weighed item means kilograms.
        val q0 = qty
        if (q0 != null && q0.stripTrailingZeros().scale() > 0 && q0 < BigDecimal(100)) {
            if (unit == "g") unit = "kg"
            if (unit == "ml") unit = "l"
        }
        val conf = if (consistent) Confidence.HIGH else Confidence.LOW
        val warnings = if (qty != null && price != null && total != null && !consistent) setOf(ParseWarning.LINE_TOTAL_MISMATCH) else emptySet()
        val lotWord = cell(Kind.LOT).firstOrNull { it.text.any(Char::isDigit) && ItalianDates.findDates(it.text).isEmpty() }
        return ParsedLineItem(
            originalDescription = description,
            quantity = qty?.let { Extracted(it, if (qtyWorkedOut) Confidence.LOW else conf, source) },
            unit = unit?.let { Extracted(it, conf, source) },
            // A repaired decimal comma adds up, but is shown for a look.
            unitPrice = price?.let { Extracted(it, if (priceRepaired) Confidence.LOW else conf, source) },
            lineTotalCents = total?.let { Extracted(it, if (!amountRepaired && (consistent || (qty == null && price == null))) Confidence.HIGH else Confidence.LOW, source) },
            vatRatePercent = vat?.let { Extracted(it.stripTrailingZeros(), Confidence.HIGH, source) },
            lotNumber = lotWord?.let { Extracted(it.text, Confidence.LOW, source) },
            expiryDate = null,
            warnings = warnings,
            itemCode = code,
            packSize = packSize?.takeIf { unit == "pz" || unit == null }?.let { Extracted(it.text, conf, source) },
            packages = packages?.let { p -> Extracted(p.map { c -> when (c) { 'l', 'I', 'L' -> '1'; 'O' -> '0'; 'X', '×', '*' -> 'x'; else -> c } }.joinToString(""), Confidence.HIGH, source) },
        )
    }

    private fun numberIn(t: String): BigDecimal? {
        val s = t.trim('€', ' ', '-').replace(Regex("[A-Za-z.]+$"), "").trimEnd('.')
        if (s.isEmpty() || !s.any(Char::isDigit) || !s.all { it.isDigit() || it in ".," }) return null
        return ItalianNumbers.parse(s)
    }

    /** "10", "10,00", "10+5" (percent, cascaded) -> total discount as a fraction of 100, or null. */
    private fun discountOf(raw: String): BigDecimal? {
        val parts = raw.trim('%', ' ').split('+').mapNotNull { ItalianNumbers.parse(it.trim('%', ' ')) }
        if (parts.isEmpty() || parts.any { it.signum() < 0 || it >= BigDecimal(100) }) return null
        var keep = BigDecimal.ONE
        for (p in parts) keep = keep.multiply(BigDecimal.ONE.subtract(p.movePointLeft(2)))
        return BigDecimal.ONE.subtract(keep).movePointRight(2)
    }

    private fun discountMatches(q: BigDecimal, p: BigDecimal, d: BigDecimal?, total: Long): Boolean {
        if (d == null || d.signum() == 0) return false
        val net = q.multiply(p).multiply(BigDecimal(100).subtract(d)).divide(BigDecimal(100), 6, RoundingMode.HALF_UP)
        return kotlin.math.abs(ItalianNumbers.toCents(net) - total) <= 2
    }

    /** Two numbers (in printed order) whose product is the amount; the pair nearest the amount wins. */
    private fun pairFor(nums: List<BigDecimal>, total: Long): Pair<BigDecimal, BigDecimal>? {
        for (j in nums.indices.reversed()) for (i in j - 1 downTo 0) {
            if (ReceiptParser.matches(nums[i], nums[j], total)) return nums[i] to nums[j]
        }
        return null
    }

    private fun cleanDescription(words: List<String>): String {
        var t = words.filter { it.isNotBlank() }
        while (t.isNotEmpty() && t.last().all { it in "-.,:;/" }) t = t.dropLast(1)
        while (t.isNotEmpty() && t.first().all { it in "-.,:;/*" }) t = t.drop(1)
        // A single marker letter before the name ("O" offer).
        if (t.size > 1 && t.first().length == 1 && (TWO_LETTER.matches(t.first()) || t.first() == "0")) t = t.drop(1)
        return t.joinToString(" ").trim().trimEnd(':', '-', '.', ',', ' ').trim()
    }
}
