package com.kitchenreceipts.core

import java.math.BigDecimal

/**
 * The page as a structure, every word accounted for.
 *
 * The reading finds fields by looking for what it knows ("Lotto", a LOTTO column, a name in the supplier's box...).
 * What it does not know, it does not see: a lot printed under the item code with no heading, the second line of a
 * name, a note. The page map turns the question around: every word on the page must be explained, as part of a field
 * that was read, or as standard print (labels, column headings, addresses, bank details, legal notices, ids, dates).
 * A word left over inside the item table or the supplier's box means the reading missed something there, whatever
 * the layout; it is reported with where it sits (which item, under which column), so it can be asked about.
 *
 * Built from the word positions only, for any layout:
 *  - rows rebuilt from the photo ([LayoutRows]);
 *  - the item table: from its column headings to its foot, each item a block of rows (its row and the rows under it,
 *    up to the next item), each word under the heading it stands beneath;
 *  - the supplier's box ([Parties]): every word in it is the name, a label, a label's value, or left over.
 *
 * Stage 1: it runs alongside the reading and changes nothing; it reports what was not explained.
 */
object PageMap {

    enum class Region { HEADER, SUPPLIER_BOX, TABLE, FOOT }
    enum class Role { FIELD, LABEL, PRINT, UNEXPLAINED }

    data class Word(val page: Int, val text: String, val box: PageBox, val row: Int)

    /** A word and what it is: [field] ("description", "quantity", "lot", "seller"...), [item] its line, [column] the heading above it. */
    data class Placed(val word: Word, val region: Region, val role: Role, val field: String? = null, val item: Int? = null, val column: String? = null)

    /** One item of the table: its row and the rows under it up to the next item. */
    data class ItemBlock(val page: Int, val item: Int, val rows: IntRange)

    data class Map(val words: List<Placed>, val items: List<ItemBlock>) {
        val unexplained: List<Placed> get() = words.filter { it.role == Role.UNEXPLAINED }
        /** Left over with a digit in it: a lot, a code, a quantity or a price the reading did not take (the costliest). */
        val unexplainedNumbers: List<Placed> get() = unexplained.filter { p -> p.word.text.any(Char::isDigit) }

        /** "item 3 under CODICE: L2345; supplier box: MARIO". */
        fun describeUnexplained(): String = unexplained.groupBy { Triple(it.region, it.item, it.column) }.entries.joinToString("; ") { (k, ws) ->
            val where = when (k.first) {
                Region.TABLE -> "item ${(k.second ?: -1) + 1}" + (k.third?.let { " under $it" } ?: "")
                Region.SUPPLIER_BOX -> "supplier box"
                else -> k.first.name.lowercase()
            }
            "$where: " + ws.joinToString(" ") { it.word.text }
        }
    }

    // ------------------------------------------------------------------ standard print

    private val LABEL_WORDS = setOf(
        // header and parties
        "fattura", "ddt", "documento", "doc", "numero", "num", "n", "nr", "del", "data", "pag", "pagina", "di", "cliente", "fornitore",
        "cedente", "prestatore", "cessionario", "committente", "denominazione", "ragione", "sociale", "codice", "fiscale", "partita",
        "iva", "p", "piva", "identificativo", "ai", "fini", "indirizzo", "comune", "provincia", "cap", "nazione", "regime", "ordinario",
        "forfettario", "sede", "legale", "tel", "telefono", "fax", "email", "e-mail", "mail", "pec", "web", "sito", "www", "spett", "spettle",
        "spettabile", "destinatario", "destinazione", "intestatario", "tipo", "causale", "trasporto", "vettore", "porto", "franco",
        "assegnato", "mittente", "colli", "peso", "aspetto", "esteriore", "beni", "agente", "ordine", "rif", "riferimento", "vs", "ns",
        // table headings
        "descrizione", "articolo", "art", "cod", "quantita", "quantità", "qta", "qt", "um", "u", "m", "prezzo", "unitario", "importo",
        "sconto", "sc", "aliquota", "lotto", "lotti", "scadenza", "scad", "conf", "confezione", "formato", "totale", "tot", "valore",
        "netto", "lordo", "imponibile", "imposta", "euro", "eur", "€", "id", "qtalot", "lot", "composto", "prodotto", "unita", "unità",
        // payment and foot
        "pagamento", "modalita", "modalità", "scadenze", "rimessa", "diretta", "bonifico", "bancario", "riba", "contanti", "banca", "iban",
        "abi", "cab", "bic", "swift", "esente", "art", "esenzione", "bollo", "virtuale", "spese", "incasso", "arrotondamento",
        "riepilogo", "riepiloghi", "totali", "cedenteprestatore", "cedentelprestatore", "tipologia", "documento",
    )
    private val PRINT_LINE = rx(
        "(?i)(\\bvia\\b|\\bviale\\b|\\bpiazza\\b|\\bcorso\\b|\\blocalit|\\btel\\b|\\bfax\\b|@|www\\.|\\biban\\b|\\bbanca\\b|\\bcap\\.?\\s*soc|" +
            "\\breg\\.?\\s*imp|\\brea\\b|\\bcapitale\\b|\\bsede\\b|\\bcontributo\\s+conai|\\bassolto\\b|\\bpagina\\b|\\bpag\\.?\\s*\\d)",
    )
    /**
     * Product information printed with an item that the reading does not keep (yet): storage ("Merce non deperibile -
     * Congelato"), origin, category and calibre of fruit and vegetables. Explained as a note, not left over.
     */
    private val NOTE_LINE = rx(
        "(?i)\\b(origine|provenienza|prov\\.?|paese|categoria|cat\\.?|calibro|cal\\.?|variet[aà]|classe|congelat[oi]|surgelat[oi]|" +
            "fresc[oh]i?|refrigerat[oi]|deperibile|alimentare|conservare|conservazione|temperatura|bio(logico)?|dop|igp|stg)\\b",
    )

    /** An id: a VAT number, a tax code, a postcode, a phone number, a long code: explained as print outside the table. */
    private val ID = rx("^(IT)?[0-9OIl]{11}$|^[A-Z]{6}[0-9LMNPQRSTUV]{2}[A-Z][0-9LMNPQRSTUV]{2}[A-Z][0-9LMNPQRSTUV]{3}[A-Z]$|^\\d{5}$|^\\+?\\d[\\d ./-]{7,}$")

    private fun norm(s: String) = s.lowercase().trim().trim('.', ',', ':', ';', '(', ')', '[', ']', '{', '}', '|', '/', '-', '*', '"', '\'', '?', '!')

    /** Words that need no explaining anywhere: punctuation, single letters, labels, dates, money signs. */
    private val FOLDED_LABELS: Set<String> by lazy { LABEL_WORDS.map(::fold).toSet() }

    private fun isPrint(t: String): Boolean {
        val n = norm(t)
        if (n.isEmpty() || n.length == 1) return true
        if (n in LABEL_WORDS || fold(n) in FOLDED_LABELS) return true
        // "Cedente/prestatore", "Cedentelprestatore" (the slash misread), "fiscale:VALUE" glued.
        val pieces = n.split('/', ':').filter { it.isNotEmpty() }
        if (pieces.size > 1 && pieces.all { p -> p in LABEL_WORDS || fold(p) in FOLDED_LABELS || p.length <= 1 }) return true
        if (n.split('.', '/', ':').all { it.isEmpty() || it in LABEL_WORDS }) return true // "P.IVA", "U.M.", "Q.TA"
        if (ItalianDates.findDates(t).isNotEmpty()) return true
        return false
    }

    // ------------------------------------------------------------------ building the map

    fun build(pages: List<List<OcrLine>>, doc: ParsedDocument, ownVatNumber: String? = null): Map {
        val out = mutableListOf<Placed>()
        val blocks = mutableListOf<ItemBlock>()
        val items = doc.lineItems
        pages.map { OcrCleanup.repairLabels(OcrCleanup.stripRules(it)) }.forEachIndexed { p, lines ->
            if (lines.isEmpty()) return@forEachIndexed
            val layout = LayoutRows.layout(lines)
            val rows = layout.rows.mapIndexed { r, row -> row.flatMap { piece -> wordsOf(piece) }.map { it.copy(page = p, row = r) } }
            val texts = layout.rows.map { r -> r.joinToString(" ") { it.text.trim() } }
            // The item table: from its column headings to its foot.
            val head = layout.rows.indexOfFirst { r -> TableReader.header(r, layout.slope, doc.layout?.headings.orEmpty()) != null }
                .takeIf { it >= 0 } ?: texts.indexOfFirst { ReceiptParser.isTableHeader(it) }
            val foot = if (head >= 0) (head + 1 until rows.size).firstOrNull { ReceiptParser.isFooterRow(texts[it]) } ?: rows.size else -1
            val headings = if (head >= 0) rows[head] else emptyList()

            // Each item's own row: the row that holds its amount and the most words of its description.
            val itemRow = mutableMapOf<Int, Int>()
            if (head >= 0) {
                // Items are printed in their order: each is looked for after the previous one (two items with the same
                // name are not confused), on the row with its amount and the most of its own values.
                var after = head
                items.forEachIndexed { i, it ->
                    fun score(r: Int) = rows[r].count { w -> itemField(it, w) != null } + if (it.lineTotalCents?.value?.let { c -> rows[r].any { w -> cents(w.text) == c } } == true) 3 else 0
                    val best = (after + 1 until foot).take(12).maxByOrNull { r -> score(r) * 100 - (r - after) }
                    if (best != null && score(best) >= 2) { itemRow[i] = best; after = best }
                }
            }
            // Item blocks: an item's row and the rows under it, up to the next item's row (or the foot).
            val starts = itemRow.entries.sortedBy { it.value }
            // Every other row of the table belongs to the item it describes: the one before it or the one after it,
            // whichever its words match (a name starting on the row above its amounts, "quantity x price" printed
            // above the name on receipts, a lot or a note under the item). A row that matches neither goes with the
            // item above it (lots, notes and the rest of a name are printed under the item), at most three rows
            // under the last one (further down is the foot).
            val rowItem = mutableMapOf<Int, Int>()
            for ((item, r) in starts) rowItem[r] = item
            if (starts.isNotEmpty()) {
                for (r in head + 1 until foot) {
                    if (r in rowItem) continue
                    val prev = starts.lastOrNull { it.value < r }
                    val next = starts.firstOrNull { it.value > r }
                    fun matches(e: kotlin.collections.Map.Entry<Int, Int>?) = e?.let { rows[r].count { w -> itemField(items[it.key], w) != null } } ?: -1
                    val sp = matches(prev); val sn = matches(next)
                    val owner = when {
                        next != null && sn > sp && sn > 0 -> next
                        prev != null && (next != null || r - prev.value <= 3) -> prev
                        else -> null
                    }
                    if (owner != null) rowItem[r] = owner.key
                }
            }
            rowItem.entries.groupBy({ it.value }, { it.key }).forEach { (item, rs) -> blocks += ItemBlock(p, item, rs.min()..rs.max()) }

            // Rows between the heading row and the first item: the heading's own second row ("ID LOTTO QTA.LOT.").
            val firstItemRow = rowItem.keys.minOrNull() ?: foot
            val headingRows = if (head >= 0) (head + 1 until firstItemRow).filter { r -> rows[r].none { w -> cents(w.text) != null } }.toSet() else emptySet()

            val box = if (p == 0) runCatching { Parties.supplier(listOf(layout), ownVatNumber) }.getOrNull()?.box else null
            val sellerWords = doc.sellerName?.value?.let(::descWords).orEmpty()

            rows.forEachIndexed { r, ws ->
                val printLine = PRINT_LINE.containsMatchIn(texts[r])
                for (w in ws) {
                    val inBox = box != null && w.box.left >= box.left - 8 && w.box.right <= box.right + 8 && w.box.top >= box.top - 4 && w.box.bottom <= box.bottom + 4
                    // The table first: a box drawn by the reading never reaches into it.
                    val region = when {
                        head >= 0 && r == head -> Region.TABLE
                        head >= 0 && r > head && r < foot -> Region.TABLE
                        head >= 0 && r >= foot -> Region.FOOT
                        inBox -> Region.SUPPLIER_BOX
                        else -> Region.HEADER
                    }
                    // The document's own header values explain themselves wherever they are printed.
                    val own = docField(doc, w)
                    if (own != null && (region != Region.TABLE || r == head || rowItem[r] == null)) { out += Placed(w, region, Role.FIELD, own); continue }
                    val item = rowItem[r]
                    val column = if (region == Region.TABLE && r != head) columnOf(w, headings) else null
                    out += when {
                        region == Region.TABLE && (r == head || r in headingRows) -> Placed(w, region, Role.LABEL)
                        region == Region.TABLE && item != null && r != itemRow[item] && NOTE_LINE.containsMatchIn(texts[r]) ->
                            Placed(w, region, Role.PRINT, "note", item, column)
                        region == Region.TABLE && item != null ->
                            itemField(items[item], w)?.let { f -> Placed(w, region, Role.FIELD, f, item, column) }
                                ?: if (isPrint(w.text)) Placed(w, region, Role.PRINT, item = item, column = column)
                                else Placed(w, region, Role.UNEXPLAINED, item = item, column = column)
                        region == Region.TABLE -> Placed(w, region, if (isPrint(w.text) || printLine) Role.PRINT else Role.UNEXPLAINED, column = column)
                        region == Region.SUPPLIER_BOX -> when {
                            norm(w.text) in sellerWords -> Placed(w, region, Role.FIELD, "seller")
                            isPrint(w.text) || ID.matches(w.text.trim().trim(':', '.')) || isTaxId(w.text) -> Placed(w, region, Role.PRINT)
                            // "fiscale:NAMEWORD" glued: a label and a word of the name.
                            w.text.contains(':') && w.text.split(':').let { ps -> ps.size == 2 && isPrint(ps[0]) && norm(ps[1]) in sellerWords } ->
                                Placed(w, region, Role.FIELD, "seller")
                            // The value of a label on the same row (address, town, tax regime): print.
                            labelBefore(ws, w) -> Placed(w, region, Role.PRINT)
                            else -> Placed(w, region, Role.UNEXPLAINED)
                        }
                        else -> Placed(w, region, Role.PRINT)
                    }
                }
            }
        }
        return Map(out, blocks)
    }

    /** A header or total value of the document: its number, the supplier's VAT number, the totals. */
    private fun docField(d: ParsedDocument, w: Word): String? {
        val t = w.text.trim()
        val n = norm(t)
        val c = cents(t)
        return when {
            d.documentNumber?.value?.let { v -> norm(v) == n || (n.length >= 2 && norm(v).split(' ', '/').contains(n)) } == true -> "number"
            c != null && c == d.totalCents?.value -> "total"
            c != null && c == d.subtotalCents?.value -> "taxable"
            c != null && c == d.vatCents?.value -> "vat"
            else -> null
        }
    }

    /** A VAT number or tax code with the camera's look-alikes (O for 0, I for 1). */
    private fun isTaxId(t: String): Boolean {
        val c = t.trim().trim(':', '.').uppercase().removePrefix("IT")
        val digits = c.map { ch -> when (ch) { 'O' -> '0'; 'I', 'L' -> '1'; else -> ch } }.joinToString("")
        if (digits.length == 11 && digits.all(Char::isDigit)) return true
        // A tax code: 16 characters, six letters, then digits (some read as letters), a letter at the end.
        return c.length == 16 && c.take(6).all(Char::isLetter) && c.last().isLetter() && c.drop(6).count(Char::isDigit) >= 5
    }

    /** Which field of [it] the word is: a number equal to one of its values, its code, lot, unit, or a word of its name. */
    private fun itemField(it: ParsedLineItem, w: Word): String? = itemField1(it, w)
        ?: w.text.trim().let { raw ->
            // Two of the line's values printed without a space ("13,20010+10": price and discount).
            (2 until raw.length - 1).firstNotNullOfOrNull { k ->
                val a = itemField1(it, w.copy(text = raw.substring(0, k))); val b = itemField1(it, w.copy(text = raw.substring(k)))
                if (a != null && b != null && a != "description" && b != "description") a else null
            }
        }

    private fun itemField1(it: ParsedLineItem, w: Word): String? {
        // "7/CINGHIALE" (packages glued to the name), "40,000C" (a storage letter glued to the quantity), "10%".
        val raw = w.text.trim().trimEnd('|', '}', ']', ')', '{')
        // The whole word first ("13/15" in a name), then its parts ("7/CINGHIALE": packages glued to the name).
        if (raw.contains('/') && norm(raw) in descWords(it.originalDescription)) return "description"
        // "4TORTA": the packages column glued to the name by the camera.
        rx("^(\\d{1,3})([A-Za-z].{2,})$").matchEntire(raw)?.let { m ->
            val a = itemField1(it, w.copy(text = m.groupValues[1])); val b = itemField1(it, w.copy(text = m.groupValues[2]))
            if (a != null && b == "description") return "description"
        }
        val parts = raw.split('/').filter { p -> p.isNotBlank() }
        if (parts.size > 1) return parts.map { p -> itemField1(it, w.copy(text = p)) }.takeIf { fs -> fs.all { f -> f != null } }?.first()
        if (raw.endsWith('%') && norm(raw) in descWords(it.originalDescription)) return "description"
        val t = raw.trimEnd('%')
        // A storage letter glued to a number ("40,000C"): the number alone, only when the word as printed means nothing.
        if (t.length >= 4 && t.last().isLetter() && t[t.length - 2].isDigit() && (t.contains(',') || t.contains('.'))) {
            itemField(it, w.copy(text = t.dropLast(1)))?.let { f -> return f }
        }
        val n = norm(t)
        val num = ItalianNumbers.parse(t.trim('€', ' '))
        // "26.900" printed with a point for the decimal comma: the other reading of the same digits.
        val alt = if (t.count { c -> c == '.' } == 1 && !t.contains(',')) ItalianNumbers.parse(t.replace('.', ',').trim('€', ' ')) else null
        fun eq(v: BigDecimal?) = v != null && ((num != null && v.compareTo(num) == 0) || (alt != null && v.compareTo(alt) == 0))
        return when {
            it.lotNumber?.value?.let { l -> norm(l) == n || norm(l).contains(n) && n.length >= 3 } == true -> "lot"
            it.itemCode?.let { c -> norm(c) == n || norm(c).replace(" ", "").contains(n) && n.length >= 3 } == true -> "code"
            it.lineTotalCents?.value?.let { c -> cents(t) == c || alt?.let { a -> ItalianNumbers.toCents(a) == c } == true } == true -> "amount"
            eq(it.quantity?.value) -> "quantity"
            eq(it.unitPrice?.value) -> "price"
            eq(it.vatRatePercent?.value) -> "vat"
            it.discount?.value?.let { d -> norm(d) == n } == true -> "discount"
            it.packages?.value?.let { k -> norm(k) == n || norm(k).contains(n) } == true -> "packages"
            it.unit?.value?.let { u -> norm(u) == n || (Units.normalize(u) != null && Units.normalize(u) == Units.normalize(n)) } == true -> "unit"
            it.packSize?.value?.let { s -> norm(s).split(' ').contains(n) || sameSize(s, t) } == true -> "pack size"
            sameSize(it.originalDescription, t) -> "description"
            n.length >= 2 && n in descWords(it.originalDescription) -> "description"
            // The camera's look-alikes (FI0R for FIOR, 26,l37 for 26,137): the same word once they are folded.
            n.length >= 3 && fold(n) in descWords(it.originalDescription).map(::fold) -> "description"
            num == null && fold(n).let { f -> listOfNotNull(it.lineTotalCents?.value?.let { c -> fold(ItalianNumbers.formatCents(c)) },
                it.unitPrice?.value?.let { v -> fold(ItalianNumbers.formatDecimal(v, maxScale = 4)) }, it.quantity?.value?.let { v -> fold(ItalianNumbers.formatDecimal(v, maxScale = 4)) })
                .any { v -> norm(v) == f } } -> "number (misread)"
            // Pack words the reading moves out of the name: "X24", "LT", "KG" next to a size.
            (rx("^x\\d{1,3}$").matches(n) || Units.normalizeKnown(n) != null) && (it.packSize != null || rx("\\d").containsMatchIn(it.originalDescription) || it.unit != null) -> "pack size"
            it.expiryDate != null && ItalianDates.findDates(t).isNotEmpty() -> "expiry"
            else -> null
        }
    }

    /** "700G" and "GR 700", "125G" and "125 g", "50M" and "50 M": the same size whatever the spelling. */
    private val SIZE = rx("(?i)^(\\d+(?:[.,]\\d+)?)\\s*([a-z]{1,2})\\.?$")
    private fun sameSize(text: String, token: String): Boolean {
        val m = SIZE.find(token.trim()) ?: return false
        val digits = m.groupValues[1]
        val unit = m.groupValues[2].lowercase().take(1)
        val compact = text.lowercase().replace(" ", "")
        return compact.contains(digits + unit) || compact.contains(unit + digits) || compact.contains("gr$digits") || compact.contains("${digits}gr")
    }

    private fun cents(t: String): Long? = ItalianNumbers.parse(t.trim('€', ' '))?.takeIf { t.contains(',') || t.contains('.') }?.let { ItalianNumbers.toCents(it) }

    /** Folds the camera's look-alikes to one form: O/0, I/l/1, S/5, B/8, G/6, Z/2. */
    private fun fold(s: String): String = s.lowercase().map { c ->
        when (c) { 'o' -> '0'; 'i', 'l' -> '1'; 's' -> '5'; 'b' -> '8'; 'g' -> '6'; 'z' -> '2'; else -> c }
    }.joinToString("")

    private fun descWords(s: String): Set<String> = s.split(rx("\\s+")).map(::norm).filter { it.isNotEmpty() }.toSet()

    /** The heading the word stands beneath: the heading word overlapping it most, else the nearest by centre. */
    private fun columnOf(w: Word, headings: List<Word>): String? {
        if (headings.isEmpty()) return null
        val overlapping = headings.maxByOrNull { h -> minOf(h.box.right, w.box.right) - maxOf(h.box.left, w.box.left) }
        if (overlapping != null && minOf(overlapping.box.right, w.box.right) - maxOf(overlapping.box.left, w.box.left) > 0) return overlapping.text
        val cx = (w.box.left + w.box.right) / 2.0
        return headings.minByOrNull { h -> kotlin.math.abs((h.box.left + h.box.right) / 2.0 - cx) }?.text
    }

    /** A label ending with ":" earlier on the same row, and no other label in between: the word is that label's value. */
    private fun labelBefore(row: List<Word>, w: Word): Boolean {
        val before = row.filter { it.box.right <= w.box.left + 2 }.sortedBy { it.box.left }
        val label = before.lastOrNull { it.text.trim().endsWith(":") } ?: return false
        val labelName = norm(label.text)
        // "Denominazione:" is the name's label: its value is the name, explained only as the name.
        return labelName !in setOf("denominazione", "ragione", "sociale", "ditta")
    }

    /** The single words of a piece, with their own boxes (estimated from the characters when the engine gives none). */
    private fun wordsOf(piece: OcrLine): List<Word> {
        if (piece.words.isNotEmpty()) return piece.words.map { Word(0, it.text, PageBox(it.left, it.top, it.right, it.bottom), 0) }
        val text = piece.text
        if (text.isBlank()) return emptyList()
        val per = piece.width.toDouble() / text.length.coerceAtLeast(1)
        val out = mutableListOf<Word>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i] == ' ') i++
            val s = i
            while (i < text.length && text[i] != ' ') i++
            if (i > s) out += Word(0, text.substring(s, i), PageBox(piece.left + (s * per).toInt(), piece.top, piece.left + (i * per).toInt(), piece.bottom), 0)
        }
        return out
    }
}
