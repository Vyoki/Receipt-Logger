package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Turns OCR text of an Italian supplier receipt / invoice / delivery note into a [ParsedDocument].
 *
 * Design rules:
 * - A field that is not found stays null. Nothing is guessed or defaulted.
 * - Every value carries a [Confidence]; LOW values are highlighted in the review screen.
 * - Arithmetic cross-checks (qty x price = total, subtotal + VAT = total) raise confidence
 *   or add warnings, but never overwrite what was read.
 */
object ReceiptParser {

    private val COMPANY_SUFFIX = Regex(
        "(?i)(\\bs\\.?\\s?r\\.?\\s?l\\.?s?\\b|\\bs\\.?\\s?p\\.?\\s?a\\.?(?=\\s|$|,)|\\bs\\.?\\s?n\\.?\\s?c\\.?(?=\\s|$|,)|" +
            "\\bs\\.?\\s?a\\.?\\s?s\\.?(?=\\s|$|,)|\\bsoc\\.?\\s?coop|\\bcooperativa\\b)",
    )
    private val CUSTOMER_LABEL = Regex("(?i)\\b(spett\\.?\\s*le|spettabile|cliente|destinatario|intestatario|destinazione|fatturare\\s+a)\\b")
    private val NOT_SELLER = Regex(
        "(?i)\\b(fattura|documento|ddt|d\\.d\\.t|scontrino|ricevuta|data|pagina|pag\\.|tel\\.?|telefono|fax|e-?mail|" +
            "p\\.?\\s?iva|partita|c\\.?f\\.?|cod\\.?\\s?fisc|via|viale|piazza|corso|cap|www\\.|pec|iban|rea)\\b|@|\\d{5}",
    )
    private val DOC_NUMBER_LABELED = Regex(
        "(?i)\\b(?:fattura(?:\\s+(?:accompagnatoria|immediata|differita))?|ft\\.?|documento(?:\\s+di\\s+trasporto)?|doc\\.?|" +
            "ddt|d\\.d\\.t\\.?|scontrino|ricevuta|bolla|nota\\s+di\\s+credito)\\s*" +
            "(?:n(?:r|um)?\\.?|n°|nº|numero|#)\\s*[:.]?\\s*([A-Za-z0-9][A-Za-z0-9\\-/]*)",
    )
    private val DOC_NUMBER_BARE = Regex("(?i)^(?:n\\.|n°|nº|numero|num\\.)\\s*(?:doc\\.?|documento)?\\s*[:.]?\\s*([A-Za-z0-9][A-Za-z0-9\\-/]*)")
    private val DATE_LABEL = Regex("(?i)\\b(data(?:\\s+(?:documento|fattura|doc\\.?|emissione|ddt))?|del|emessa\\s+il)\\b")
    private val TABLE_HEADER = Regex("(?i)\\b(descrizione|articolo|prodotto|q\\.?\\s?t[àa']?\\.?|quantit[àa]|prezzo|importo)\\b")

    private val TOTAL_STRONG = Regex(
        "(?i)\\b(totale\\s+(?:documento|fattura|da\\s+pagare|complessivo|euro|eur|generale|a\\s+pagare)|" +
            "netto\\s+a\\s+pagare|importo\\s+(?:totale|da\\s+pagare)|totale\\s+€|da\\s+pagare)\\b",
    )
    private val TOTAL_WEAK = Regex("(?i)^\\s*(totale|tot\\.?|total)\\b")
    private val SUBTOTAL = Regex(
        "(?i)\\b(imponibile|sub\\s?-?totale|totale\\s+imponibile|totale\\s+merce|totale\\s+netto|tot\\.?\\s+imponibile)\\b",
    )
    private val VAT_TOTAL = Regex("(?i)\\b(totale\\s+(?:iva|i\\.v\\.a\\.?|imposta|imposte)|tot\\.?\\s+iva|di\\s+cui\\s+iva)\\b")
    private val VAT_LINE = Regex("(?i)^\\s*(iva|i\\.v\\.a\\.?|imposta)\\b")
    private val VAT_ID = Regex("(?i)(p\\.?\\s?iva|partita\\s+iva|c\\.?\\s?f\\.?\\b|cod(?:ice)?\\.?\\s+fisc)")
    private val NON_ITEM = Regex(
        "(?i)\\b(resto|contanti|pagamento|pagato|bancomat|carta|pos|ricevuto|documento\\s+commerciale|" +
            "vendita|rt\\b|matricola|arrotondamento|sconto\\s+totale|scadenza\\s+pagamento|iban|abi|cab|banca|" +
            "trasporto\\s+a\\s+cura|peso\\s+lordo|colli|vettore|causale|aliquota|riepilogo|cassiere|grazie)\\b",
    )
    private val INCLUSIVE_HINT = Regex("(?i)\\b(di\\s+cui\\s+iva|iva\\s+inclusa|iva\\s+compresa|prezzi\\s+ivati|ivato|compresa\\s+iva|incl\\.?\\s+iva)\\b")
    private val EXCLUSIVE_HINT = Regex("(?i)(\\biva\\s+esclusa\\b|\\+\\s*iva\\b|\\bal\\s+netto\\s+(?:di\\s+)?iva\\b|\\bprezzi\\s+netti\\b|\\besclusa\\s+iva\\b)")
    private val CURRENCY = Regex("(?i)(€|\\beur\\b|\\beuro\\b)")
    private val PERCENT = Regex("[-+]?\\d{1,3}(?:[.,]\\d{1,2})?\\s?%")
    private val AMOUNT_IN_TEXT = Regex("(?<![\\w/.,])-?\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,4})?(?![\\w/])|(?<![\\w/.,])-?\\d+(?:[.,]\\d{1,4})?-?(?![\\w/.,]*\\d)")
    private val QTY_WITH_UNIT = Regex("^(\\d+(?:[.,]\\d{1,3})?)([A-Za-z]{1,10}\\.?)$")
    private val TIMES = setOf("x", "X", "×", "*")
    private val ADDRESS_OR_CONTACT = Regex(
        "(?i)(^|\\s)(via|viale|v\\.le|piazza|p\\.zza|corso|c\\.so|loc\\.|localit[àa]|tel\\.?|telefono|cell\\.?|fax|" +
            "e-?mail|pec|www\\.|cap)(\\s|:|$)|@",
    )
    private val DECIMAL_AMOUNT = Regex("[.,]\\d{2,4}-?$")

    fun parse(text: String): ParsedDocument {
        val lines = text.lines().map { it.replace('\t', ' ').replace(Regex(" {2,}"), " ").trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ParsedDocument.EMPTY

        val consumed = mutableSetOf<Int>()
        val warnings = mutableSetOf<ParseWarning>()

        val docNumber = findDocumentNumber(lines, consumed)
        val date = findDocumentDate(lines, consumed)
        val seller = findSeller(lines)
        val currency = CURRENCY.find(text)?.let { Extracted("EUR", Confidence.HIGH, it.value) }

        // Totals section
        var subtotal: Extracted<Long>? = null
        var vat: Extracted<Long>? = null
        val vatLines = mutableListOf<Pair<Long, String>>()
        var total: Extracted<Long>? = null
        val weakTotals = mutableListOf<Pair<Long, String>>()
        var firstTotalsLine = lines.size

        lines.forEachIndexed { i, line ->
            if (VAT_ID.containsMatchIn(line) && !VAT_TOTAL.containsMatchIn(line) && !SUBTOTAL.containsMatchIn(line)) return@forEachIndexed
            val amount = lastAmountCents(line)
            when {
                SUBTOTAL.containsMatchIn(line) -> {
                    if (amount != null && subtotal == null) {
                        subtotal = Extracted(amount, Confidence.HIGH, line)
                    }
                    consumed += i; firstTotalsLine = minOf(firstTotalsLine, i)
                }
                VAT_TOTAL.containsMatchIn(line) -> {
                    if (amount != null && vat == null) vat = Extracted(amount, Confidence.HIGH, line)
                    consumed += i; firstTotalsLine = minOf(firstTotalsLine, i)
                }
                TOTAL_STRONG.containsMatchIn(line) -> {
                    if (amount != null) {
                        if (total == null) total = Extracted(amount, Confidence.HIGH, line)
                        else if (total!!.value != amount) warnings += ParseWarning.MULTIPLE_TOTALS
                    }
                    consumed += i; firstTotalsLine = minOf(firstTotalsLine, i)
                }
                TOTAL_WEAK.containsMatchIn(line) -> {
                    if (amount != null) weakTotals += amount to line
                    consumed += i; firstTotalsLine = minOf(firstTotalsLine, i)
                }
                VAT_LINE.containsMatchIn(line) -> {
                    if (amount != null) vatLines += amount to line
                    consumed += i; firstTotalsLine = minOf(firstTotalsLine, i)
                }
            }
        }
        if (vat == null && vatLines.isNotEmpty()) {
            vat = Extracted(vatLines.sumOf { it.first }, if (vatLines.size == 1) Confidence.HIGH else Confidence.LOW,
                vatLines.joinToString(" | ") { it.second })
        }
        if (total == null && weakTotals.isNotEmpty()) {
            // The largest plain "Totale" is usually the grand total; still LOW confidence.
            val best = weakTotals.maxBy { it.first }
            if (weakTotals.map { it.first }.distinct().size > 1) warnings += ParseWarning.MULTIPLE_TOTALS
            total = Extracted(best.first, Confidence.LOW, best.second)
        }

        // Line items
        val headerIdx = lines.indexOfFirst { TABLE_HEADER.findAll(it).count() >= 2 }
        val start = if (headerIdx >= 0 && headerIdx < firstTotalsLine) headerIdx + 1 else 0
        val items = mutableListOf<ParsedLineItem>()
        var lotRejected = false
        for (i in start until firstTotalsLine) {
            if (i in consumed || i == headerIdx) continue
            val line = lines[i]
            val scan = LotExtractor.scan(line)
            if (scan.lotRejectedAsDate) lotRejected = true
            val rest = LotExtractor.strip(line, scan.consumed)
            val isInfoOnly = (scan.lot != null || scan.expiry != null || scan.lotRejectedAsDate) &&
                (rest.isBlank() || !rest.any { it.isLetter() } || parseItemLine(rest) == null)
            if (isInfoOnly) {
                // A "Lotto ... / Scad. ..." line belongs to the item right above it.
                val prev = items.lastOrNull()
                if (prev != null) {
                    items[items.lastIndex] = prev.copy(
                        lotNumber = prev.lotNumber ?: scan.lot,
                        expiryDate = prev.expiryDate ?: scan.expiry,
                    )
                }
                continue
            }
            if (NON_ITEM.containsMatchIn(line) || VAT_ID.containsMatchIn(line) || ADDRESS_OR_CONTACT.containsMatchIn(line)) continue
            if (seller != null && seller.source == line) continue
            val item = parseItemLine(rest) ?: continue
            items += item.copy(lotNumber = scan.lot, expiryDate = scan.expiry)
        }
        if (lotRejected) warnings += ParseWarning.LOT_LOOKS_LIKE_DATE
        if (items.isEmpty()) warnings += ParseWarning.NO_ITEMS_FOUND
        if (items.any { ParseWarning.LINE_TOTAL_MISMATCH in it.warnings }) warnings += ParseWarning.LINE_TOTAL_MISMATCH

        // Cross checks
        val s = subtotal; val v = vat; val t = total
        if (s != null && v != null && t != null) {
            if (kotlin.math.abs(s.value + v.value - t.value) > 1) warnings += ParseWarning.TOTALS_INCONSISTENT
            else {
                // subtotal + VAT = total: all three confirm each other.
                total = t.copy(confidence = Confidence.HIGH)
                vat = v.copy(confidence = Confidence.HIGH)
            }
        }
        val itemTotals = items.mapNotNull { it.lineTotalCents?.value }
        val itemsSum = if (itemTotals.size == items.size && items.isNotEmpty()) itemTotals.sum() else null
        val tolerance = maxOf(2L, items.size.toLong())
        val matchesSubtotal = itemsSum != null && s != null && kotlin.math.abs(itemsSum - s.value) <= tolerance
        val matchesTotal = itemsSum != null && total != null && kotlin.math.abs(itemsSum - total!!.value) <= tolerance
        if (itemsSum != null && (s != null || total != null) && !matchesSubtotal && !matchesTotal) {
            warnings += ParseWarning.ITEMS_SUM_MISMATCH
        }

        val vatBasis: Extracted<VatBasis>? = when {
            EXCLUSIVE_HINT.containsMatchIn(text) -> Extracted(VatBasis.EXCLUSIVE, Confidence.HIGH, EXCLUSIVE_HINT.find(text)!!.value)
            INCLUSIVE_HINT.containsMatchIn(text) -> Extracted(VatBasis.INCLUSIVE, Confidence.HIGH, INCLUSIVE_HINT.find(text)!!.value)
            // Line totals add up to the grand total: prices include VAT.
            matchesTotal && (v != null || (s != null && s.value != total!!.value)) ->
                Extracted(VatBasis.INCLUSIVE, Confidence.LOW, "somma righe = totale")
            // Line totals add up to the taxable amount, and VAT is added on top: prices exclude VAT.
            matchesSubtotal && v != null && v.value != 0L && !matchesTotal ->
                Extracted(VatBasis.EXCLUSIVE, Confidence.LOW, "somma righe = imponibile")
            else -> null
        }

        return ParsedDocument(
            sellerName = seller,
            documentDate = date,
            documentNumber = docNumber,
            currency = currency,
            subtotalCents = subtotal,
            vatCents = vat,
            totalCents = total,
            vatBasis = vatBasis,
            lineItems = items,
            warnings = warnings,
        )
    }

    // ---------------------------------------------------------------- header fields

    private fun findDocumentNumber(lines: List<String>, consumed: MutableSet<Int>): Extracted<String>? {
        lines.forEachIndexed { i, line ->
            val m = DOC_NUMBER_LABELED.find(line) ?: DOC_NUMBER_BARE.find(line)
            if (m != null) {
                val value = m.groupValues[1].trimEnd('/', '-')
                if (value.any { it.isDigit() } && ItalianDates.findDates(value).isEmpty()) {
                    consumed += i
                    return Extracted(value, Confidence.HIGH, line)
                }
            }
        }
        return null
    }

    private fun findDocumentDate(lines: List<String>, consumed: MutableSet<Int>): Extracted<LocalDate>? {
        // 1. A date right after a label such as "Data", "Data documento", "del".
        lines.forEachIndexed { i, line ->
            if (LotExtractor.scan(line).expiry != null && !DATE_LABEL.containsMatchIn(line)) return@forEachIndexed
            val dates = ItalianDates.findDates(line)
            for (label in DATE_LABEL.findAll(line)) {
                val d = dates.firstOrNull { it.range.first > label.range.last && it.range.first - label.range.last <= 6 }
                if (d != null && !isInsideExpiry(line, d)) {
                    consumed += i
                    return Extracted(d.date, Confidence.HIGH, line)
                }
            }
        }
        // 2. Otherwise the first date on a line that is not an expiry / lot line.
        lines.forEachIndexed { i, line ->
            val scan = LotExtractor.scan(line)
            if (scan.expiry != null || scan.lot != null || scan.lotRejectedAsDate) return@forEachIndexed
            val d = ItalianDates.findDates(line).firstOrNull()
            if (d != null) {
                consumed += i
                return Extracted(d.date, Confidence.LOW, line)
            }
        }
        return null
    }

    private fun isInsideExpiry(line: String, d: DateMatch): Boolean =
        LotExtractor.scan(line).consumed.any { d.range.first >= it.first && d.range.last <= it.last }

    private fun findSeller(lines: List<String>): Extracted<String>? {
        val head = lines.take(12)
        var skipUntil = -1
        var firstPlausible: String? = null
        head.forEachIndexed { i, line ->
            val customer = CUSTOMER_LABEL.find(line)
            if (customer != null) {
                // "Spett.le" alone -> the customer's name is on the next line; otherwise it is on this line.
                val rest = line.substring(customer.range.last + 1)
                skipUntil = if (rest.count { it.isLetter() } >= 3) i else i + 1
                return@forEachIndexed
            }
            if (i <= skipUntil) return@forEachIndexed
            val letters = line.count { it.isLetter() }
            if (letters < 3) return@forEachIndexed
            if (COMPANY_SUFFIX.containsMatchIn(line) && !NOT_SELLER.containsMatchIn(stripCompanySuffix(line))) {
                return Extracted(cleanSeller(line), Confidence.HIGH, line)
            }
            if (firstPlausible == null && !NOT_SELLER.containsMatchIn(line) && !TABLE_HEADER.containsMatchIn(line) &&
                lastAmountCents(line) == null && ItalianDates.findDates(line).isEmpty()
            ) {
                firstPlausible = line
            }
        }
        return firstPlausible?.let { Extracted(cleanSeller(it), Confidence.LOW, it) }
    }

    private fun stripCompanySuffix(line: String) = COMPANY_SUFFIX.replace(line, " ")

    private fun cleanSeller(line: String): String = line.trim().trim('*', '-', '=', '_', '|', ' ')

    // ---------------------------------------------------------------- amounts

    /** The last monetary amount on a line (ignoring percentages and dates), in cents. */
    fun lastAmountCents(line: String): Long? {
        var cleaned = line
        for (d in ItalianDates.findDates(cleaned).reversed()) cleaned = cleaned.replaceRange(d.range, " ")
        cleaned = PERCENT.replace(cleaned, " ")
        val candidates = AMOUNT_IN_TEXT.findAll(cleaned).map { it.value }.toList()
        // Prefer amounts with decimals: "Totale 3 colli 45,60" -> 45,60
        val withDecimals = candidates.filter { it.contains(',') || Regex("\\.\\d{2}$").containsMatchIn(it) }
        val pick = withDecimals.lastOrNull() ?: return null
        return ItalianNumbers.parseCents(pick)
    }

    // ---------------------------------------------------------------- items

    private sealed interface Tok {
        data class Num(val raw: String, val value: BigDecimal) : Tok
        data class QtyUnit(val value: BigDecimal, val unit: String, val raw: String) : Tok
        data class UnitTok(val unit: String) : Tok
        data class Rate(val value: BigDecimal) : Tok
        data object Times : Tok
        data object Currency : Tok
    }

    private fun classify(token: String): Tok? {
        val t = token.trim().trimEnd(',', ';')
        if (t.isEmpty()) return null
        if (t == "€" || t.equals("eur", true) || t.equals("euro", true)) return Tok.Currency
        if (t in TIMES) return Tok.Times
        if (t.endsWith("%")) {
            return ItalianNumbers.parse(t.dropLast(1))?.let { Tok.Rate(it) }
        }
        Units.normalizeKnown(t)?.let { return Tok.UnitTok(it) }
        val stripped = t.removePrefix("€").removeSuffix("€")
        if (stripped.any { it.isDigit() } && stripped.all { it.isDigit() || it in ".,-" }) {
            if (ItalianDates.findDates(stripped).isNotEmpty()) return null
            return ItalianNumbers.parse(stripped)?.let { Tok.Num(stripped, it) }
        }
        QTY_WITH_UNIT.find(t)?.let { m ->
            val unit = Units.normalizeKnown(m.groupValues[2])
            val v = ItalianNumbers.parse(m.groupValues[1])
            if (unit != null && v != null) return Tok.QtyUnit(v, unit, t)
        }
        // "2x" or "x2"
        if (t.length >= 2 && (t.first() in "xX" || t.last() in "xX")) {
            val n = ItalianNumbers.parse(t.trim('x', 'X'))
            if (n != null) return Tok.QtyUnit(n, "", t)
        }
        return null
    }

    /** Parses one item line such as "Mozzarella fiordilatte kg 2,500 8,90 22,25 10%". */
    fun parseItemLine(line: String): ParsedLineItem? {
        val tokens = line.split(' ').filter { it.isNotBlank() }
        if (tokens.size < 2) return null
        val tail = ArrayDeque<Tok>()
        var cut = tokens.size
        for (idx in tokens.indices.reversed()) {
            val tok = classify(tokens[idx]) ?: break
            tail.addFirst(tok)
            cut = idx
        }
        // "Farina 00 sacco 2 18,50 37,00": when qty, price and total follow the unit,
        // numbers before the unit are part of the description.
        val unitIdx = tail.indexOfFirst { it is Tok.UnitTok }
        if (unitIdx > 0 && tail.drop(unitIdx + 1).count { it is Tok.Num } >= 3) {
            repeat(unitIdx) { tail.removeFirst() }
            cut += unitIdx
        }
        // Description must remain and contain letters.
        var description = tokens.subList(0, cut).joinToString(" ").trim().trimEnd(':', '-', '.').trim()
        if (description.count { it.isLetter() } < 2) return null
        val nums = tail.filterIsInstance<Tok.Num>()
        // An item line must end in something that looks like money ("8,90"), not "Via Roma 12".
        if (nums.isEmpty() || !DECIMAL_AMOUNT.containsMatchIn(nums.last().raw)) return null

        var unit: String? = tail.filterIsInstance<Tok.UnitTok>().firstOrNull()?.unit
        var qtyFromUnitTok: BigDecimal? = null
        tail.filterIsInstance<Tok.QtyUnit>().firstOrNull()?.let {
            qtyFromUnitTok = it.value
            if (it.unit.isNotEmpty()) unit = it.unit
        }
        // Leading unit word inside the description, e.g. "KG Pomodori" is rare; "Pomodori kg" handled above.
        val rate = tail.filterIsInstance<Tok.Rate>().lastOrNull()?.value
        val hasTimes = tail.any { it is Tok.Times } || tail.any { it is Tok.QtyUnit && (it as Tok.QtyUnit).unit.isEmpty() }

        var qty: BigDecimal? = qtyFromUnitTok
        var price: BigDecimal? = null
        var totalCents: Long? = null
        val values = nums.map { it.value }
        var consistent = false

        when {
            qty != null -> when (values.size) {
                0 -> {}
                1 -> if (hasTimes) price = values[0] else totalCents = ItalianNumbers.toCents(values[0])
                else -> { price = values[values.size - 2]; totalCents = ItalianNumbers.toCents(values.last()) }
            }
            values.size == 1 -> totalCents = ItalianNumbers.toCents(values[0])
            values.size == 2 -> {
                qty = values[0]
                if (hasTimes) price = values[1] else totalCents = ItalianNumbers.toCents(values[1])
            }
            else -> {
                val last3 = values.takeLast(3)
                qty = last3[0]; price = last3[1]; totalCents = ItalianNumbers.toCents(last3[2])
                // Some layouts print price before quantity: try the swap if it is the only consistent reading.
                if (!matches(qty, price, totalCents) && matches(price, qty, totalCents) && isWholeNumber(last3[1]) && !isWholeNumber(last3[0])) {
                    val tmp = qty; qty = price; price = tmp
                }
            }
        }
        if (qty != null && price != null && totalCents != null) consistent = matches(qty, price, totalCents)

        // A unit may appear at the end of the description ("Farina 00 sacco").
        if (unit == null) {
            val lastWord = description.substringAfterLast(' ')
            Units.normalizeKnown(lastWord)?.let {
                if (description.contains(' ') && lastWord.length > 1) {
                    unit = it
                    description = description.substringBeforeLast(' ').trim()
                }
            }
        }

        val conf = if (consistent) Confidence.HIGH else Confidence.LOW
        val warnings = if (qty != null && price != null && totalCents != null && !consistent) {
            setOf(ParseWarning.LINE_TOTAL_MISMATCH)
        } else {
            emptySet()
        }
        val totalConf = if (consistent || (qty == null && price == null)) Confidence.HIGH else conf
        return ParsedLineItem(
            originalDescription = description,
            quantity = qty?.let { Extracted(it, conf, line) },
            unit = unit?.let { Extracted(it, if (consistent) Confidence.HIGH else Confidence.LOW, line) },
            unitPrice = price?.let { Extracted(it, conf, line) },
            lineTotalCents = totalCents?.let { Extracted(it, totalConf, line) },
            vatRatePercent = rate?.let { Extracted(it, Confidence.HIGH, line) },
            lotNumber = null,
            expiryDate = null,
            warnings = warnings,
        )
    }

    private fun isWholeNumber(v: BigDecimal) = v.stripTrailingZeros().scale() <= 0

    /** qty x price equals total within one cent (after rounding half-up to cents). */
    fun matches(qty: BigDecimal, price: BigDecimal, totalCents: Long): Boolean {
        val computed = qty.multiply(price).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
        return kotlin.math.abs(computed - totalCents) <= 1
    }
}
