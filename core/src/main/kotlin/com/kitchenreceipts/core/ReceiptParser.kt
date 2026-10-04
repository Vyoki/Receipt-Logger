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
 * - Real OCR is messy: the text is first repaired by [OcrCleanup], and the parser tolerates
 *   VAT codes after prices, quantities on their own line, rows split in two, and totals whose
 *   amount is printed on the line below the label.
 */
/** What the parser should know about the operator's own business. */
data class ParseOptions(
    val ownBusinessName: String? = null,
    val ownVatNumber: String? = null,
    /** How this supplier prints its documents, when known (learned, or the AI's answer about the headings). */
    val layout: SupplierLayout? = null,
    /** Finds a supplier's learned layout by its key ([SupplierMemory.key]); used when [layout] is not given. */
    val layoutLookup: ((String) -> SupplierLayout?)? = null,
)

object ReceiptParser {

    internal val COMPANY_SUFFIX = Regex(
        "(?i)(\\bs\\.?\\s?r\\.?\\s?[l1]\\.?\\s?s?\\.?(?=\\s|$|,|\\)|-)|\\bs\\.\\s?rl[e.]?(?=\\s|$)|\\bs\\.?\\s?p\\.?\\s?a\\.?(?=\\s|$|,|\\)|-)|" +
            "\\bs\\.?\\s?n\\.?\\s?c\\.?(?=\\s|$|,|\\)|-)|\\bs\\.?\\s?a\\.?\\s?s\\.?(?=\\s|$|,|\\)|-)|\\bsoc\\.?\\s?coop\\S*|" +
            "\\bcooperativa\\b|\\bs\\.?\\s?c\\.?\\s?a\\.?\\s?r\\.?\\s?l\\.?)",
    )
    internal val CUSTOMER_LABEL = Regex(
        "(?i)\\b(spett\\.?\\s*l[ei]|spettabile|cliente|destinatario|intestatario|destinazione|fatturare\\s+a|consegnare\\s+a|luogo\\s+di\\s+consegna)\\b",
    )
    private val NOT_SELLER = Regex(
        "(?i)\\b(fattura|documento|ddt|d\\.d\\.t|scontrino|ricevuta|data|pagina|pag\\.|tel\\.?|telefono|fax|e-?mail|" +
            "p\\.?\\s?iva|partita|c\\.?f\\.?|cod\\.?\\s?fisc|via|viale|piazza|corso|cap|www\\.|pec|iban|rea|" +
            "commerciale|vendita|prestazione|cassa|totale|numero|soggett\\w*|direzione|coordinamento|unipersonale|capitale|" +
            "sede\\s+legale|iscr\\w*|reg\\.?\\s*imp\\w*)\\b|@|\\d{5}",
    )
    private val DOC_NUMBER_LABELED = Regex(
        "(?i)\\b(?:fattura(?:\\s+(?:accompagnatoria|immediata|differita|elettronica))?|ft\\.?|documento(?:\\s+di\\s+trasporto)?|doc\\.?|" +
            "ddt|d\\.d\\.t\\.?|scontrino|ricevuta|bolla|nota\\s+di\\s+credito)\\s*" +
            "(?:n(?:r|um)?\\.?|n°|nº|numero|#)\\s*[:.]?\\s*([A-Za-z0-9][A-Za-z0-9\\-/]*)",
    )
    private val DOC_NUMBER_BARE = Regex("(?i)^(?:n\\.|n°|nº|numero|num\\.)\\s*(?:doc\\.?|documento)?\\s*[:.]?\\s*([A-Za-z0-9][A-Za-z0-9\\-/]*)")
    internal val NUMBER_LABEL = Regex("(?i)(\\bn\\.?\\s?ro\\b|\\bnumero\\b|\\bn\\.\\s*doc|\\bnum\\.)")
    /** "FATTURA 2025/0311 18/03/2025" (number without "n."), used only as a low-confidence fallback. */
    private val DOC_NUMBER_LOOSE = Regex("(?i)^\\s*(?:fattura|ft\\.?|ddt|d\\.d\\.t\\.?|bolla|ricevuta)\\s+([A-Za-z0-9][A-Za-z0-9\\-/]*)")
    private val DATE_LABEL = Regex("(?i)\\b(data(?:\\s+(?:documento|fattura|doc\\.?|emissione|ddt))?|del|emessa\\s+il)\\b(?!\\s+(?:scadenza|consegna|nascita|pagamento))")
    private val NOT_DOC_DATE = Regex("(?i)\\b(scad|scadenza|consegna|pagamento|nascita|valuta|entro)")
    private val TABLE_HEADER = Regex(
        "(?i)\\b(descrizione|articolo|prodotto|q\\.?\\s?t[àa']?\\.?|quantit[àa]|prezzo|importo|imponibile|" +
            "u\\.?\\s?m\\.?|codice|sconto|aliquota|iva|totale|valore)\\b",
    )

    /** Section titles inside the item table ("Merce non deperibile - Congelato", "Merce non alimentare"): never a product. */
    private val SECTION_HEADING = Regex(
        "(?i)^\\W*(merce\\s+(non\\s+)?(deperibil[ei]|alimentar[ei]|surgelat[ae]|congelat[ae]|fresc[ah]e?|secc[ah]e?)|" +
            "(prodotti|articoli|reparto|settore)\\s+(non\\s+)?(surgelati|congelati|freschi|secchi|refrigerati|alimentari|deperibili))\\b",
    )

    fun isSectionHeading(description: String): Boolean = SECTION_HEADING.containsMatchIn(description.trim())

    // "COD.ART.COLLIDESCRIZIONE": the OCR may glue the heading to the next one.
    private val COLLI_HEADING = Regex("(?i)\\b(colli|n\\.?\\s?colli|cartoni)(\\b|(?=descr))")
    /** Pkgs glued to the product name: "3TORTA", "1/CINGHIALE" (only after an article code, in a table with a COLLI column). */
    private val GLUED_COLLI = Regex("^(\\d{1,2})/?([A-Za-z0][A-Za-z].*)$")
    private val PRICE_HEADING = Regex("(?i)\\b(prezzo|prz\\.?|p\\.\\s?unit|pr\\.\\s?unit)")
    private val QTY_HEADING = Regex("(?i)(\\bq\\.?\\s?t[àa']?\\.?(?=\\W|$)|\\bquantit[àa]|\\bquant\\.|\\bquantity\\b|\\bpezzi\\b|\\btot\\.(?!\\w))")
    private val ITEM_COLUMN = Regex("(?i)\\b(descrizione|articolo|prodotto|q\\.?\\s?t[àa']?\\.?|quantit[àa]|prezzo|u\\.?\\s?m\\.?)(?=\\W|$)")
    private val TOTAL_STRONG = Regex(
        "(?i)\\b(totale\\s+(?:documento|fattura|da\\s+pagare|complessivo|euro|eur|generale|a\\s+pagare|dovuto)|" +
            "netto\\s+a\\s+pagare|importo\\s+(?:totale|da\\s+pagare|pagato)|totale\\s+€|da\\s+pagare)\\b",
    )
    private val VAT_SUMMARY_WORD = Regex("(?i)\\b(imponibil[ei]|importo|iva|aliquota|imposta)\\b")
    private val TOTALS_ROW = Regex("(?i)^\\s*totali\\b")
    private val NOT_A_TOTAL = Regex("(?i)\\b(sconto|offerta|offerte|punti|risparmi\\w*|premi|colli)\\b")
    private val TOTAL_WEAK = Regex("(?i)^\\s*(totale|tot\\.?|total)\\b")
    private val SUBTOTAL = Regex(
        "(?i)\\b(imponibil[ei]|sub\\s?-?totale|totale\\s+imponibil[ei]|totale\\s+merce|totale\\s+netto|tot\\.?\\s+imponibil[ei])\\b",
    )
    private val VAT_TOTAL = Regex("(?i)\\b(totale\\s+(?:iva|i\\.v\\.a\\.?|imposta|imposte)|tot\\.?\\s+iva|di\\s+cui\\s+iva)\\b")
    private val VAT_LINE = Regex("(?i)^\\s*(iva|i\\.v\\.a\\.?|imposta)\\b")
    // "C.F." needs its dots: a bare "CF" is a packaging code (confezione) on item lines.
    private val VAT_ID = Regex("(?i)(p\\.?\\s?iva|partita\\s+iva|\\bc\\.\\s?f\\.|cod(?:ice)?\\.?\\s+fisc)")
    private val NON_ITEM = Regex(
        "(?i)\\b(resto|contanti|contante|pagamento|pagato|bancomat|carta\\s+di\\s+(?:credito|debito)|pos|ricevuto|documento\\s+commerciale|" +
            "vendita|rt\\b|matricola|arrotondamento|sconto\\s+totale|scadenza\\s+pagamento|iban|abi|cab|banca|" +
            "trasporto\\s+a\\s+cura|peso\\s+lordo|colli|vettore|causale|aliquota|riepilogo|cassiere|grazie|" +
            "elettronico|non\\s+riscosso|operatore|transazione)\\b",
    )
    private val INCLUSIVE_HINT = Regex("(?i)\\b(di\\s+cui\\s+iva|iva\\s+inclusa|iva\\s+compresa|prezzi\\s+ivati|ivato|compresa\\s+iva|incl\\.?\\s+iva)\\b")
    private val EXCLUSIVE_HINT = Regex("(?i)(\\biva\\s+esclusa\\b|\\+\\s*iva\\b|\\bal\\s+netto\\s+(?:di\\s+)?iva\\b|\\bprezzi\\s+netti\\b|\\besclusa\\s+iva\\b)")
    private val CURRENCY = Regex("(?i)(€|\\beur\\b|\\beuro\\b)")
    private val PERCENT = Regex("[-+]?\\d{1,3}(?:[.,]\\d{1,2})?\\s?%")
    private val AMOUNT_IN_TEXT = Regex("(?<![\\w/.,])-?\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,4})?(?![\\w/])|(?<![\\w/.,])-?\\d+(?:[.,]\\d{1,4})?-?(?![\\w/.,]*\\d)")
    private val QTY_WITH_UNIT = Regex("^(\\d+(?:[.,]\\d{1,3})?)([A-Za-z]{1,10}\\.?)$")
    private val TIMES = setOf("x", "X", "×", "*")
    internal val ADDRESS_OR_CONTACT = Regex(
        "(?i)(^|\\s)(via|viale|v\\.le|piazza|p\\.zza|p\\.za|corso|c\\.so|loc\\.|localit[àa]|tel\\.?|telefono|cell\\.?|fax|" +
            "e-?mail|pec|www\\.|cap)(\\s|:|$)|@",
    )
    internal val DECIMAL_AMOUNT = Regex("[.,]\\d{2,4}-?$")
    /** VAT class printed after the price on Italian receipts: "2,50 B", "(A)", "*". */
    private val VAT_CODE_TOKEN = Regex("^\\(?([A-HJ-WYZa-hj-wyz]|\\*|#|[A-Z]\\d{1,2})\\)?$")
    /** Numeric VAT column after the amount: "3,45 10", "1,09 04", "10,74 22". */
    private val VAT_RATE_CODE = Regex("^(0?0|0?4|0?5|10|22|20|21)$")
    /** Item code (5+ digits), optionally marked with one letter, then colli ("1x1", "2x3", "1"). */
    private val ITEM_CODE = Regex("^\\d{5,}$")
    private val CONSERVATION = Regex("^(C|F|S|ST|CN|SG|FR|CG)$")
    /** Header of a table with a lot column ("ID LOTTO", "LOTTO"): bare codes under an item are its lot. */
    private val LOT_COLUMN = Regex("(?i)\\blott[oi]\\b")
    private val CODE_ONLY_LINE = Regex("^[A-Z0-9][A-Z0-9\\-/.]{3,}( [A-Z0-9][A-Z0-9\\-/.]{3,})?$")
    private val GLUED_UNIT = Regex("^(?i)(gr|kg|lt|ml|cl|pz|g|l)(\\d+(?:[.,]\\d+)?)$")
    /** Code and colli glued by the OCR: "10000032x3" = code 1000003 + colli 2x3. */
    private val CODE_WITH_COLLI = Regex("^(\\d{5,})(\\d{1,2}[xX×]\\d{1,3})$")
    private val COLLI = Regex("^(\\d{1,3}[xX×*]\\d{1,3}|\\d{1,2})$")
    /** "1x6", "2X1", "lx4" (OCR reads 1 as l): packages x pieces. Unambiguous even without a code before it. */
    internal val COLLI_PATTERN = Regex("^[\\dlIOL]{1,3}[xX×*][\\dlIO]{1,3}$")
    /** Two-letter packaging codes printed before the unit ("SK GR 800", "NC KG"); never part of a product name. */
    private val PACKAGING_CODE = Regex("^[A-Z]{2}$")
    private val SHORT_WORDS = setOf("DI", "DA", "AL", "IN", "LA", "IL", "UN", "DE", "EL", "LE", "LO", "SU", "ED", "OR", "NO", "BY")
    /** A quantity line on its own: "2 x 1,25", "2 X 1,25 2,50", "0,540 kg x 12,90 €/kg". */
    private val QTY_ONLY_LINE = Regex(
        "(?i)^\\s*(\\d+(?:[.,]\\d{1,3})?)\\s*([a-z]{1,4}\\.?)?\\s*[x×*]\\s*(?:€\\s*)?(\\d+[.,]\\d{2,4})\\s*(?:€?/?\\s*[a-z]{0,4})?(?:\\s+(?:€\\s*)?(\\d+[.,]\\d{2}))?\\s*$",
    )

    /** Separates pages / photos in the OCR text of a multi-page document. */
    const val PAGE_BREAK = '\u000C'

    /** Page-bottom running totals of multi-page invoices: neither items nor the document total. */
    private val CARRY_OVER = Regex("(?i)\\b(a\\s+riportare|riporto|totale\\s+pagina|totale\\s+parziale\\s+pagina|segue|continua\\s+a\\s+pagina)\\b")

    /**
     * Parses the OCR result of a document page by page, with word positions: rows are rebuilt from the boxes,
     * and the item table is also read by columns ([TableReader]). The column reading is used when it explains
     * the document better (more lines where quantity x price = amount, lines adding up to the total).
     */
    fun parsePages(pages: List<List<OcrLine>>, options: ParseOptions = ParseOptions()): ParsedDocument {
        val layouts = pages.map { LayoutRows.layout(it) }
        val text = layouts.joinToString("\n$PAGE_BREAK\n") { it.text }
        if (text.isBlank()) return ParsedDocument.EMPTY
        fun read(o: ParseOptions): ParsedDocument {
            val table = runCatching { TableReader.read(layouts, o.layout?.headings.orEmpty()) }.getOrNull()
            return parse(text, o, table)
        }
        val plain = read(options.copy(layoutLookup = null))
        if (options.layout != null || options.layoutLookup == null) return plain
        // A supplier seen before: read again with what was learned from its confirmed documents, and keep that
        // reading unless it explains the document worse.
        val key = SupplierMemory.key(text, options.ownVatNumber, plain.sellerName?.value) ?: return plain
        val learned = runCatching { options.layoutLookup.invoke(key) }.getOrNull() ?: return plain
        val withLayout = read(options.copy(layout = learned, layoutLookup = null))
        return if (quality(withLayout) >= quality(plain)) withLayout else plain
    }

    /** A row that ends the item table: totals, VAT summary, carry-over to the next page. */
    internal fun isFooterRow(line: String): Boolean =
        CARRY_OVER.containsMatchIn(line) || TOTALS_ROW.containsMatchIn(line) || TOTAL_STRONG.containsMatchIn(line) ||
            SUBTOTAL.containsMatchIn(line) || VAT_TOTAL.containsMatchIn(line) || TOTAL_WEAK.containsMatchIn(line) ||
            VAT_SUMMARY_WORD.findAll(line).count() >= 3

    /** Payment, address, VAT-number rows inside the table area. */
    internal fun isNotAnItemRow(line: String): Boolean =
        NON_ITEM.containsMatchIn(line) || VAT_ID.containsMatchIn(line) || ADDRESS_OR_CONTACT.containsMatchIn(line)

    /**
     * How well a list of items explains the document: lines where quantity x price = amount count most,
     * and lines that add up to the printed taxable amount or total make it clearly the better reading.
     */
    fun itemScore(items: List<ParsedLineItem>, subtotal: Long?, total: Long?): Int {
        val consistent = items.count { it.quantity?.confidence == Confidence.HIGH && it.unitPrice?.confidence == Confidence.HIGH && it.lineTotalCents != null }
        val mismatched = items.count { ParseWarning.LINE_TOTAL_MISMATCH in it.warnings }
        val sums = items.mapNotNull { it.lineTotalCents?.value }
        val tolerance = maxOf(2L, items.size.toLong())
        val sumMatches = sums.size == items.size && items.isNotEmpty() &&
            listOfNotNull(subtotal, total).any { kotlin.math.abs(sums.sum() - it) <= tolerance }
        val named = items.count { it.originalDescription.count(Char::isLetter) >= 3 && !isSectionHeading(it.originalDescription) } -
            3 * items.count { isSectionHeading(it.originalDescription) }
        return consistent * 3 + named - mismatched * 2 + (if (sumMatches) 12 else 0)
    }

    /** Overall quality of a reading, to choose between readings of the same document (higher is better). */
    fun quality(d: ParsedDocument): Int {
        fun <T> pts(e: Extracted<T>?, high: Int) = when { e == null -> 0; e.confidence == Confidence.HIGH -> high; else -> 1 }
        return itemScore(d.lineItems, d.subtotalCents?.value, d.totalCents?.value) +
            pts(d.documentDate, 3) + pts(d.totalCents, 3) + pts(d.sellerName, 2) + pts(d.documentNumber, 1)
    }

    /** Nothing left to improve by reading again: every line checks out and the lines add up to the total. */
    fun isConfident(d: ParsedDocument): Boolean {
        if (d.lineItems.isEmpty() || d.documentDate == null || d.totalCents == null) return false
        if (d.lineItems.any { ParseWarning.LINE_TOTAL_MISMATCH in it.warnings || it.lineTotalCents == null }) return false
        // A quantity worked out as amount / price is fine when every VAT group adds up to the printed summary.
        val provenByVat = d.vatChecks.isNotEmpty() && d.vatChecks.all { it.ok }
        if (d.lineItems.any { it.quantity != null && it.quantity.confidence != Confidence.HIGH && !(provenByVat && workedOut(it)) }) return false
        return ParseWarning.ITEMS_SUM_MISMATCH !in d.warnings && ParseWarning.VAT_GROUP_MISMATCH !in d.warnings
    }

    /** Quantity x price = amount with a whole quantity, price and amount read with confidence: the quantity was worked out. */
    fun workedOut(it: ParsedLineItem): Boolean {
        val q = it.quantity ?: return false
        val p = it.unitPrice ?: return false
        val t = it.lineTotalCents ?: return false
        return q.confidence == Confidence.LOW && p.confidence == Confidence.HIGH && t.confidence == Confidence.HIGH &&
            q.value.stripTrailingZeros().scale() <= 0 && matches(q.value, p.value, t.value)
    }

    fun parse(rawText: String, options: ParseOptions = ParseOptions(), tableItems: List<ParsedLineItem>? = null): ParsedDocument {
        val pages = rawText.split(PAGE_BREAK).map { page ->
            OcrCleanup.clean(page).lines().map { it.replace(Regex(" {2,}"), " ").trim() }.filter { it.isNotEmpty() }
        }.filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        val pageOf = mutableListOf<Int>()
        pages.forEachIndexed { p, pageLines ->
            // Photos of a long receipt usually overlap a little: drop lines repeated from the previous photo.
            val skip = if (p > 0) overlap(pages[p - 1], pageLines) else 0
            pageLines.drop(skip).forEach { lines += it; pageOf += p }
        }
        if (lines.isEmpty()) return ParsedDocument.EMPTY
        val text = lines.joinToString("\n")

        val consumed = mutableSetOf<Int>()
        val warnings = mutableSetOf<ParseWarning>()
        val headerIdx = lines.indexOfFirst { isTableHeader(it) }
        val headerText = if (headerIdx >= 0) lines[headerIdx] else ""
        val colliColumn = COLLI_HEADING.containsMatchIn(headerText)
        val priceFirst = run {
            val p = PRICE_HEADING.find(headerText)?.range?.first
            val q = QTY_HEADING.find(headerText)?.range?.first
            p != null && q != null && p < q
        }
        // The headings name both the quantity and the price column: their order is known, nothing to guess.
        val orderKnown = PRICE_HEADING.containsMatchIn(headerText) && QTY_HEADING.containsMatchIn(headerText)

        // The old first-match reading marks the number's line as not a product; the value comes from the evidence.
        val firstMatch = findDocumentNumber(lines, consumed).let { first -> numberAcrossPages(first, pages) }
            .let { found -> options.layout?.numberShape?.let { shape -> numberOfShape(found, pages, shape) } ?: found }
        val docNumber = HeaderEvidence.number(pages, options.layout) ?: firstMatch
        val date = findDocumentDate(lines, consumed)
        val seller = HeaderEvidence.seller(pages, options) ?: findSellerOnPages(pages, options)
        val currency = CURRENCY.find(text)?.let { Extracted("EUR", Confidence.HIGH, it.value) }

        // ------------------------------------------------------------ totals
        var subtotal: Extracted<Long>? = null
        var vat: Extracted<Long>? = null
        val vatLines = mutableListOf<Pair<Long, String>>()
        var total: Extracted<Long>? = null
        val weakTotals = mutableListOf<Pair<Long, String>>()
        var firstTotalsLine = lines.size

        data class TotalsLine(val index: Int, val kind: Int, val amount: Long, val source: String)
        val found = mutableListOf<TotalsLine>()
        lines.forEachIndexed { i, line ->
            if (CARRY_OVER.containsMatchIn(line)) {
                consumed += i
                if (lastAmountCents(line) == null && lines.getOrNull(i + 1)?.count { it.isLetter() } == 0) consumed += i + 1
                return@forEachIndexed
            }
            if (i == headerIdx || isTableHeader(line)) return@forEachIndexed
            // "ACQUISTI IN OFFERTA € 144,86 TOTALE IMPONIBILE SCONTO € 13,23", loyalty points: not document totals.
            if (NOT_A_TOTAL.containsMatchIn(line)) return@forEachIndexed
            if (VAT_ID.containsMatchIn(line) && !VAT_TOTAL.containsMatchIn(line) && !SUBTOTAL.containsMatchIn(line)) return@forEachIndexed
            // Column headings of a VAT summary ("% IVA  IMPONIBILE  IMPORTO IVA"): no amounts belong to them.
            if (VAT_SUMMARY_WORD.findAll(line).count() >= 3 && lastAmountCents(line) == null) return@forEachIndexed
            // "TOTALI 209,76 21,51": taxable amount and VAT side by side at the foot of a VAT summary.
            TOTALS_ROW.find(line)?.let { m ->
                val amounts = amountsIn(line.substring(m.range.last + 1))
                if (amounts.size >= 2) {
                    consumed += i
                    found += TotalsLine(i, 1, amounts[0], line)
                    found += TotalsLine(i, 2, amounts[1], line)
                    if (amounts.size >= 3) found += TotalsLine(i, 3, amounts[2], line)
                    return@forEachIndexed
                }
            }
            val kind = when {
                SUBTOTAL.containsMatchIn(line) -> 1
                VAT_TOTAL.containsMatchIn(line) -> 2
                TOTAL_STRONG.containsMatchIn(line) -> 3
                TOTAL_WEAK.containsMatchIn(line) -> 4
                VAT_LINE.containsMatchIn(line) -> 5
                else -> 0
            }
            if (kind == 0) return@forEachIndexed
            // The amount follows the label on the same line, or is alone on the next line (two-column layouts).
            // Amounts *before* the label belong to something else on the same visual row.
            val labelEnd = when (kind) {
                1 -> SUBTOTAL; 2 -> VAT_TOTAL; 3 -> TOTAL_STRONG; 4 -> TOTAL_WEAK; else -> VAT_LINE
            }.find(line)!!.range.last + 1
            var amount = lastAmountCents(line.substring(labelEnd))
            var source = line
            if (amount == null) {
                val next = lines.getOrNull(i + 1)
                if (next != null && next.count { it.isLetter() } <= 3) {
                    amount = lastAmountCents(next)
                    if (amount != null) { consumed += i + 1; source = "$line $next" }
                }
            }
            if (amount == null && kind == 3) {
                // "TOTALE DOCUMENTO DI / CONSEGNA VALORIZZATO / CHE NON COSTITUISCE FATTURA / 231,27": a long label
                // wrapped over several lines, with the amount alone below it.
                for (j in i + 1..minOf(i + 5, lines.lastIndex)) {
                    val next = lines[j]
                    val nextAmount = lastAmountCents(next)
                    if (nextAmount != null && next.count { it.isLetter() } <= 3) {
                        amount = nextAmount; consumed += j; source = "$line … $next"; break
                    }
                    if (nextAmount != null || SUBTOTAL.containsMatchIn(next) || VAT_TOTAL.containsMatchIn(next) ||
                        TOTALS_ROW.containsMatchIn(next) || TOTAL_WEAK.containsMatchIn(next)
                    ) break
                }
            }
            if (amount == null) return@forEachIndexed
            consumed += i
            found += TotalsLine(i, kind, amount, source)
        }
        // On multi-page documents only the last page with totals holds the document totals;
        // totals on earlier pages are page subtotals.
        // The page that states the document total ("Totale documento") wins; otherwise the last page with totals.
        val totalsPage = found.filter { it.kind == 3 }.maxOfOrNull { pageOf[it.index] } ?: found.maxOfOrNull { pageOf[it.index] }
        for (t in found.filter { pageOf[it.index] == totalsPage }) {
            firstTotalsLine = minOf(firstTotalsLine, t.index)
            when (t.kind) {
                1 -> if (subtotal == null) subtotal = Extracted(t.amount, Confidence.HIGH, t.source)
                2 -> if (vat == null) vat = Extracted(t.amount, Confidence.HIGH, t.source)
                3 -> if (total == null) total = Extracted(t.amount, Confidence.HIGH, t.source)
                    else if (total!!.value != t.amount) warnings += ParseWarning.MULTIPLE_TOTALS
                4 -> weakTotals += t.amount to t.source
                5 -> vatLines += t.amount to t.source
            }
        }
        // "TOTALE DA PAGARE" alone on a later page, the VAT summary on the page before: take the taxable amount and
        // VAT from there when they add up to that total.
        val t0 = total
        if (t0 != null && (subtotal == null || vat == null)) {
            val subs = found.filter { it.kind == 1 }
            val vats = found.filter { it.kind == 2 }
            subs.firstNotNullOfOrNull { a -> vats.firstOrNull { b -> kotlin.math.abs(a.amount + b.amount - t0.value) <= 1 }?.let { a to it } }?.let { (a, b) ->
                if (subtotal == null) subtotal = Extracted(a.amount, Confidence.HIGH, a.source)
                if (vat == null) vat = Extracted(b.amount, Confidence.HIGH, b.source)
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
        if (total == null && subtotal != null && vat != null) {
            // Only the VAT summary was readable: the total is its taxable amount plus VAT (flagged for checking).
            total = Extracted(subtotal!!.value + vat!!.value, Confidence.LOW, "${subtotal!!.source} + ${vat!!.source}")
        }

        // ------------------------------------------------------------ line items
        val start = if (headerIdx >= 0 && headerIdx < firstTotalsLine) headerIdx + 1 else 0
        // A lot column: "LOTTO" in the table heading (or the heading's second row, "ID LOTTO QTA.LOT."), also when that
        // heading was not recognised as a table header: a heading row above the first amount, without amounts itself.
        val lotColumn = options.layout?.lotsUnderItems == true || (headerIdx >= 0 && (LOT_COLUMN.containsMatchIn(lines[headerIdx]) || LOT_COLUMN.containsMatchIn(lines.getOrElse(headerIdx + 1) { "" }))) ||
            run {
                val firstAmount = (start until firstTotalsLine).firstOrNull { lastAmountCents(lines[it]) != null } ?: firstTotalsLine
                (start until firstAmount).any { k -> LOT_COLUMN.containsMatchIn(lines[k]) && lastAmountCents(lines[k]) == null && LotExtractor.scan(lines[k]).lot == null }
            }
        val items = mutableListOf<ParsedLineItem>()
        var lotRowsUsed = 0
        var lotRejected = false
        var pendingQty: QtyLine? = null
        var i = start
        while (i < firstTotalsLine) {
            val idx = i
            i++
            if (idx in consumed || idx == headerIdx) continue
            val line = lines[idx]
            val scan = LotExtractor.scan(line)
            if (scan.lotRejectedAsDate) lotRejected = true
            var rest = LotExtractor.strip(line, scan.consumed)
            val isInfoOnly = (scan.lot != null || scan.expiry != null || scan.lotRejectedAsDate) &&
                (rest.isBlank() || !rest.any { it.isLetter() } || parseItemLine(rest, colliColumn, priceFirst, orderKnown) == null)
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
            // Lot column: "788058" or "B269-27519 C26-543486" alone on the row under an item.
            // "792983 57,22": the lot, with the item's amount printed on the same row (already read with the item).
            val lotRow = line.trim().let { t ->
                val prevTotal = items.lastOrNull()?.lineTotalCents?.value
                val amount = Regex("\\s+(\\d{1,3}(?:\\.\\d{3})*,\\d{2})$").find(t)
                if (amount != null && prevTotal != null && ItalianNumbers.parseCents(amount.groupValues[1]) == prevTotal) t.substring(0, amount.range.first) else t
            }
            if (lotColumn && CODE_ONLY_LINE.matches(lotRow) && lotRow.any(Char::isDigit) && ItalianDates.findDates(lotRow).isEmpty()) {
                val prev = items.lastOrNull()
                if (prev != null && prev.lotNumber == null) {
                    val code = lotRow.substringBefore(' ')
                    items[items.lastIndex] = prev.copy(lotNumber = Extracted(code, Confidence.LOW, line))
                    lotRowsUsed++
                }
                continue
            }
            if (NON_ITEM.containsMatchIn(line) || VAT_ID.containsMatchIn(line) || ADDRESS_OR_CONTACT.containsMatchIn(line)) continue
            if (seller != null && seller.source == line) continue
            if (isTableHeader(line)) continue
            // VAT summary headings ("% IVA  IMPONIBILE  IMPORTO IVA"): never an item, never merged with the rates below.
            if (VAT_SUMMARY_WORD.findAll(line).count() >= 3) continue

            // "2 x 1,25" on its own line: belongs to the item above or below.
            val qtyLine = parseQtyLine(rest)
            if (qtyLine != null) {
                val prev = items.lastOrNull()
                if (prev != null && prev.quantity == null && qtyLine.fits(prev)) {
                    items[items.lastIndex] = qtyLine.applyTo(prev)
                } else {
                    pendingQty = qtyLine
                }
                continue
            }

            var item = parseItemLine(rest, colliColumn, priceFirst, orderKnown)
            // Description and amounts split over two rows: "Mozzarella fior di latte" / "kg 2,500 8,90 22,25".
            if (item == null && rest.count { it.isLetter() } >= 3) {
                val nextIdx = idx + 1
                val next = lines.getOrNull(nextIdx)
                if (next != null && nextIdx < firstTotalsLine && nextIdx !in consumed && lettersOutsideUnits(next) <= 2) {
                    // "792983 57,22" under the item: the lot (lot column) and the item's amount on the same row.
                    val lotAndAmount = if (lotColumn) Regex("^([A-Z0-9][A-Z0-9\\-/.]{3,})\\s+(\\d{1,3}(?:\\.\\d{3})*,\\d{2})$").find(next.trim()) else null
                    val merged = if (lotAndAmount != null) {
                        parseItemLine("$rest ${lotAndAmount.groupValues[2]}", colliColumn, priceFirst, orderKnown)?.let { m ->
                            if (ItalianDates.findDates(lotAndAmount.groupValues[1]).isEmpty() && lotAndAmount.groupValues[1].any(Char::isDigit)) {
                                lotRowsUsed++
                                m.copy(lotNumber = Extracted(lotAndAmount.groupValues[1], Confidence.LOW, next))
                            } else m
                        }
                    } else {
                        parseItemLine("$rest $next", colliColumn, priceFirst, orderKnown)
                    }
                    if (merged != null) {
                        item = merged
                        rest = "$rest $next"
                        i++
                    }
                }
            }
            if (item == null) continue
            // "Merce non alimentare" took the numbers of the row below it (a tilted photo puts them between the two):
            // the product is the next line, "24195 CARTA FORNO 40CM X 50M C/ASTUCCIO".
            if (isSectionHeading(item.originalDescription)) {
                val nextIdx = idx + 1
                val next = lines.getOrNull(nextIdx)
                if (next != null && nextIdx < firstTotalsLine && nextIdx !in consumed && next.count { it.isLetter() } >= 3 &&
                    parseItemLine(next, colliColumn, priceFirst, orderKnown) == null && !isSectionHeading(next) && lastAmountCents(next) == null
                ) {
                    val stripped = stripItemCode(next.split(' ').filter { it.isNotBlank() }, colliColumn)
                    val name = cleanDescription(stripped.tokens)
                    if (name.count { it.isLetter() } >= 3) {
                        item = item.copy(
                            originalDescription = name,
                            itemCode = stripped.code ?: item.itemCode,
                            packages = stripped.packages?.let { Extracted(normalizeColli(it), Confidence.HIGH, next) } ?: item.packages,
                        )
                        i++
                    }
                } else {
                    continue // a heading with numbers and no product below it: not an item
                }
            }
            val pq = pendingQty
            if (pq != null && item.quantity == null && pq.fits(item)) item = pq.applyTo(item)
            pendingQty = null
            items += item.copy(lotNumber = scan.lot ?: item.lotNumber, expiryDate = scan.expiry ?: item.expiryDate)
        }
        var itemsReadBy = "text"
        val columnsFirst = options.layout?.readByColumns == true // this supplier's tables were read best by columns
        val tableScore = if (tableItems.isNullOrEmpty()) Int.MIN_VALUE else itemScore(tableItems, subtotal?.value, total?.value)
        val textScore = itemScore(items, subtotal?.value, total?.value)
        if (!tableItems.isNullOrEmpty() && (tableScore > textScore || (columnsFirst && tableScore == textScore))) {
            items.clear()
            items += tableItems
            itemsReadBy = "columns"
        }
        if (lotRejected) warnings += ParseWarning.LOT_LOOKS_LIKE_DATE
        return finish(
            ParsedDocument(
                sellerName = seller,
                documentDate = date,
                documentNumber = docNumber,
                currency = currency,
                subtotalCents = subtotal,
                vatCents = vat,
                totalCents = total,
                vatBasis = null,
                lineItems = items,
                warnings = warnings,
                itemsReadBy = itemsReadBy,
                layout = options.layout,
                lotsUnderItems = itemsReadBy == "text" && lotRowsUsed >= 2,
            ),
            text,
        )
    }

    private val CHECK_WARNINGS = setOf(
        ParseWarning.NO_ITEMS_FOUND, ParseWarning.LINE_TOTAL_MISMATCH, ParseWarning.TOTALS_INCONSISTENT, ParseWarning.ITEMS_SUM_MISMATCH,
        ParseWarning.VAT_GROUP_MISMATCH,
    )

    /**
     * Cross-checks a reading: subtotal + VAT = total, the lines against the totals, and whether prices
     * include VAT (printed wording first, then the arithmetic). Used for every reading (text, columns, AI).
     */
    fun finish(doc: ParsedDocument, text: String): ParsedDocument {
        // Logic first: a line that does not add up is solved from its own printed numbers where only one reading fits.
        val groups = VatSummary.parse(text)
        var items = DescriptionCleanup.apply(LineSolver.settle(doc.lineItems))
        // A line whose VAT rate was printed out of place gets the one rate that makes every VAT group add up.
        items = VatSummary.fillMissingRates(items, groups)
        // Pkgs against the total printed at the foot ("N. COLLI 10").
        items = PackagesCheck.repair(items, text)
        // Lots under a "LOTTO" heading, found under (nearly) every product: read where the document prints them.
        items = settleLots(items, text)
        val warnings = (doc.warnings - CHECK_WARNINGS).toMutableSet()
        var total = doc.totalCents
        var vat = doc.vatCents
        if (items.isEmpty()) warnings += ParseWarning.NO_ITEMS_FOUND
        if (items.any { ParseWarning.LINE_TOTAL_MISMATCH in it.warnings }) warnings += ParseWarning.LINE_TOTAL_MISMATCH

        val s = doc.subtotalCents; val v = vat; val t = total
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
        val matchesTotal = itemsSum != null && total != null && kotlin.math.abs(itemsSum - total.value) <= tolerance
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
        // Lines per VAT rate against the VAT summary: a group that does not add up points at the misread line.
        val vatChecks = VatSummary.check(items, groups)
        if (vatChecks.any { !it.ok }) warnings += ParseWarning.VAT_GROUP_MISMATCH
        val lotsPrinted = items.any { it.lotNumber != null } || LOTS_WORD.containsMatchIn(text)
        return doc.copy(
            lineItems = items, totalCents = total, vatCents = vat, vatBasis = vatBasis, warnings = warnings,
            vatChecks = vatChecks, lotsPrinted = lotsPrinted,
        )
    }

    /** Number of leading lines of [next] that repeat the last lines of [prev] (at least 2 to count). */
    private fun overlap(prev: List<String>, next: List<String>): Int {
        fun norm(l: String) = l.lowercase().replace(Regex("\\s+"), " ").trim()
        for (k in minOf(8, prev.size, next.size) downTo 2) {
            if (prev.takeLast(k).map(::norm) == next.take(k).map(::norm)) return k
        }
        return 0
    }

    /** A column-header row: several header words (one of them an item column) and no amount. */
    internal fun isTableHeader(line: String): Boolean =
        TABLE_HEADER.findAll(line).count() >= 2 && ITEM_COLUMN.containsMatchIn(line) && lastAmountCents(line) == null

    private fun lettersOutsideUnits(line: String): Int =
        line.split(' ').filter { Units.normalizeKnown(it) == null && it.lowercase() !in setOf("x", "eur", "euro") }
            .sumOf { tok -> tok.count { it.isLetter() } }

    // ---------------------------------------------------------------- quantity-only lines

    private class QtyLine(val qty: BigDecimal, val unit: String?, val price: BigDecimal, val totalCents: Long?, val raw: String) {
        fun fits(item: ParsedLineItem): Boolean {
            val t = item.lineTotalCents?.value ?: return totalCents == null
            return matches(qty, price, t) && (totalCents == null || kotlin.math.abs(totalCents - t) <= 1)
        }

        fun applyTo(item: ParsedLineItem): ParsedLineItem {
            val consistent = item.lineTotalCents?.let { matches(qty, price, it.value) } ?: false
            val c = if (consistent) Confidence.HIGH else Confidence.LOW
            return item.copy(
                quantity = Extracted(qty, c, raw),
                unitPrice = Extracted(price, c, raw),
                unit = item.unit ?: unit?.let { Extracted(it, c, raw) },
                lineTotalCents = item.lineTotalCents ?: totalCents?.let { Extracted(it, Confidence.LOW, raw) },
            )
        }
    }

    private fun parseQtyLine(line: String): QtyLine? {
        val m = QTY_ONLY_LINE.find(line) ?: return null
        val qty = ItalianNumbers.parse(m.groupValues[1]) ?: return null
        val price = ItalianNumbers.parse(m.groupValues[3]) ?: return null
        val unit = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { Units.normalizeKnown(it) }
        val total = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { ItalianNumbers.parseCents(it) }
        return QtyLine(qty, unit, price, total, line)
    }

    // ---------------------------------------------------------------- header fields

    /**
     * The document number is printed on every page of a long invoice: the reading most pages agree on wins
     * ("3SB/44240" on a blurred page 1, "38B/44240" on pages 2 and 3), and agreement makes it certain.
     */
    private fun numberAcrossPages(first: Extracted<String>?, pages: List<List<String>>): Extracted<String>? {
        if (pages.size < 2) return first
        val readings = pages.take(8).mapNotNull { findDocumentNumber(it, mutableSetOf()) }
        fun key(v: String) = v.uppercase().replace(" ", "")
        val counts = readings.groupingBy { key(it.value) }.eachCount()
        val (bestKey, n) = counts.maxByOrNull { it.value } ?: return first
        if (n < 2) return first
        val best = readings.first { key(it.value) == bestKey }
        return best.copy(confidence = Confidence.HIGH, source = best.source + " (same on $n pages)")
    }

    private val POSTCODE_TOWN = Regex("^\\d{5}\\s+[A-Za-zÀ-ú]")
    private val ADDRESS_WORD = Regex("(?i)\\b(via|viale|vicolo|piazza|corso|loc\\.?|località|strada|tel\\.?|fax)\\b")

    /** A value that can be a document number: a digit in it, and not a postcode with a town, an address, a VAT number or a date. */
    fun plausibleDocNumber(v: String): Boolean {
        val s = v.trim()
        if (s.isEmpty() || s.length > 30 || !s.any(Char::isDigit)) return false
        if (POSTCODE_TOWN.containsMatchIn(s) || ADDRESS_WORD.containsMatchIn(s)) return false
        if (SellerProfiles.isValidPartitaIva(s.filter(Char::isDigit)) && s.filter(Char::isDigit).length == 11) return false
        if (ItalianDates.findDates(s).isNotEmpty()) return false
        return true
    }

    /**
     * The supplier's numbers always look the same ("99A/99999"): a number read in that shape is certain; one in
     * another shape is replaced by the only value in the header that has the shape, if there is exactly one.
     */
    private fun numberOfShape(found: Extracted<String>?, pages: List<List<String>>, shape: String): Extracted<String>? {
        // Digits only ("99999") says too little: postcodes, codes and phone numbers look the same.
        if (shape.all { it == '9' }) return found
        if (found != null && SupplierLayouts.matchesShape(found.value, shape)) return found.copy(confidence = Confidence.HIGH)
        val head = pages.firstOrNull().orEmpty().take(40)
        val candidates = head.flatMap { line ->
            val tokens = line.split(' ').filter { it.isNotBlank() }
            // A single token, or two side by side ("B26 204177").
            (tokens.indices.map { tokens[it] } + (0 until tokens.size - 1).map { "${tokens[it]} ${tokens[it + 1]}" })
                .map { it.trim(',', ';', ':', '|') }
                .filter { it.any(Char::isDigit) && SupplierLayouts.matchesShape(it, shape) && plausibleDocNumber(it) && ItalianDates.findDates(it).isEmpty() }
                .map { it to line }
        }.distinctBy { it.first }
        val only = candidates.singleOrNull() ?: return found
        return Extracted(only.first, Confidence.HIGH, only.second)
    }

    private fun findDocumentNumber(lines: List<String>, consumed: MutableSet<Int>): Extracted<String>? {
        lines.forEachIndexed { i, line ->
            val m = DOC_NUMBER_LABELED.find(line) ?: DOC_NUMBER_BARE.find(line)
            if (m != null) {
                val value = m.groupValues[1].trimEnd('/', '-')
                if (value.any { it.isDigit() } && ItalianDates.findDates(value).isEmpty() && plausibleDocNumber(value)) {
                    consumed += i
                    return Extracted(value, Confidence.HIGH, line)
                }
            }
        }
        // Column layout: "TIPO DOCUMENTO  N.RO DOCUMENTO  DATA" with the values on the row below.
        lines.forEachIndexed { i, line ->
            // "09876543217 NUMERO | DATA": a VAT number printed on the same row does not make it a values row.
            val labelOnly = line.replace(Regex("\\b\\d{11}\\b"), " ")
            if (!NUMBER_LABEL.containsMatchIn(labelOnly) || labelOnly.any { it.isDigit() }) return@forEachIndexed
            // The values row is usually right below, but another heading line may sit in between: up to 3 rows down,
            // the first row with a date (the number is printed just before it), else the next row.
            val rowsBelow = (i + 1..minOf(i + 3, lines.lastIndex)).map { lines[it] }
            val next = rowsBelow.firstOrNull { ItalianDates.findDates(it).isNotEmpty() } ?: rowsBelow.firstOrNull() ?: return@forEachIndexed
            val toks = next.split(' ').filter { it.isNotBlank() }
            fun isNumberToken(tok: String) = tok.any(Char::isDigit) && tok.length >= 2 && ItalianDates.findDates(tok).isEmpty() &&
                !DECIMAL_AMOUNT.containsMatchIn(tok) && !Regex("^\\d{1,2}/\\d{1,2}$").matches(tok) && // not "1/5" (page)
                !SellerProfiles.isValidPartitaIva(tok) // not a VAT number on the same row
            // The number is printed just before the date: "... GG.D.F. B26 111945 15/09/2026 1/1".
            val dateIdx = toks.indexOfFirst { ItalianDates.findDates(it).isNotEmpty() }
            val value = if (dateIdx > 0) {
                val before = toks.subList(maxOf(0, dateIdx - 2), dateIdx)
                val picked = before.takeLastWhile { t -> isNumberToken(t) || (t.length <= 4 && t.any(Char::isDigit)) }
                picked.joinToString(" ").ifEmpty { null }
            } else {
                toks.firstOrNull { isNumberToken(it) }
            }?.trim(',', ';', ':')
            // "NUMERO | DATA" over "B26 204177 22/09/2026": the number right before the only date on the row is certain.
            val sure = dateIdx > 0 && Regex("(?i)\\bdata\\b").containsMatchIn(labelOnly) && ItalianDates.findDates(next).size == 1
            if (value != null && plausibleDocNumber(value)) return Extracted(value, if (sure) Confidence.HIGH else Confidence.LOW, "$line / $next")
        }
        lines.forEachIndexed { i, line ->
            val m = DOC_NUMBER_LOOSE.find(line) ?: return@forEachIndexed
            val value = m.groupValues[1].trimEnd('/', '-')
            if (value.any { it.isDigit() } && ItalianDates.findDates(value).isEmpty() && ItalianDates.findDates(line).none { it.range.first == m.groups[1]!!.range.first }) {
                return Extracted(value, Confidence.LOW, line)
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
                val d = dates.firstOrNull { it.range.first > label.range.last && it.range.first - label.range.last <= 8 }
                if (d != null && !isInsideExpiry(line, d)) {
                    consumed += i
                    return Extracted(d.date, Confidence.HIGH, line)
                }
            }
        }
        // 2. Column layout: the label row ("Numero  Data") with the values on the row below.
        lines.forEachIndexed { i, line ->
            val strongLabel = Regex("(?i)data\\s+(documento|fattura|doc\\.?|emissione)").containsMatchIn(line)
            if (!DATE_LABEL.containsMatchIn(line) || ItalianDates.findDates(line).isNotEmpty()) return@forEachIndexed
            // "MODALITA' DI PAGAMENTO  NUMERO  DATA" is a row of headings: only the word right after "data" matters.
            if (!strongLabel && Regex("(?i)\\bdata\\s+(di\\s+)?(scadenza|consegna|pagamento|nascita)").containsMatchIn(line)) return@forEachIndexed
            // The values row is usually right below, but the photo may put a line or two in between.
            for (j in i + 1..minOf(i + 3, lines.lastIndex)) {
                val next = lines[j]
                if (DATE_LABEL.containsMatchIn(next) && ItalianDates.findDates(next).isEmpty()) break // another label row
                val candidates = ItalianDates.findDates(next).filter { !isInsideExpiry(next, it) && !afterNotDocWord(next, it) }
                val d = candidates.firstOrNull() ?: continue
                // Right under a "DATA DOCUMENTO"-style heading (or "NUMERO | DATA" with a heading line in between), the only
                // date on the row: that is the document date.
                val between = (i + 1 until j).map { lines[it] }
                val sure = candidates.size == 1 && ItalianDates.findDates(next).size == 1 &&
                    (j == i + 1 || (NUMBER_LABEL.containsMatchIn(line) && between.none { ItalianDates.findDates(it).isNotEmpty() }))
                return Extracted(d.date, if (sure) Confidence.HIGH else Confidence.LOW, "$line / $next")
            }
        }
        // 3. Otherwise the first date on a line that is not about expiry, delivery or payment.
        lines.forEachIndexed { i, line ->
            val scan = LotExtractor.scan(line)
            if (scan.expiry != null || scan.lot != null || scan.lotRejectedAsDate) return@forEachIndexed
            // "RIMESSA DIRETTA ENTRO 60 GG.D.F. B26 111945 15/09/2026": only a date right after "entro",
            // "scadenza", "consegna"... is about something else.
            val d = ItalianDates.findDates(line).firstOrNull { !afterNotDocWord(line, it) }
            if (d != null) {
                consumed += i
                return Extracted(d.date, Confidence.LOW, line)
            }
        }
        // 4. Last resort (delivery notes often print only "Data consegna"/"Data trasporto"): any date that is not an expiry.
        lines.forEach { line ->
            val scan = LotExtractor.scan(line)
            if (scan.expiry != null || scan.lotRejectedAsDate) return@forEach
            val d = ItalianDates.findDates(line).firstOrNull { !isInsideExpiry(line, it) }
            if (d != null && !Regex("(?i)\\b(nascita|valuta)").containsMatchIn(line)) return Extracted(d.date, Confidence.LOW, line)
        }
        return null
    }

    /** True when a word such as "scadenza", "entro", "consegna" is printed just before the date. */
    private fun afterNotDocWord(line: String, d: DateMatch): Boolean {
        val before = line.substring(maxOf(0, d.range.first - 16), d.range.first)
        return NOT_DOC_DATE.containsMatchIn(before)
    }

    private fun isInsideExpiry(line: String, d: DateMatch): Boolean =
        LotExtractor.scan(line).consumed.any { d.range.first >= it.first && d.range.last <= it.last }

    /** "ABC S.r" at the end of a line: a legal form the photo cut short. */
    private val LOT_HEADING = Regex("(?im)^.*\\b(id\\s+lotto|lotto|lotti|n\\.?\\s*lotto)\\b.*$")
    private val LOT_VALUE = Regex("^[A-Za-z0-9][A-Za-z0-9\\-/.]{3,19}$")

    /**
     * Lots read from the lot column of a document that has one: when (nearly) every product has a lot in the same
     * place, they are where the document prints them, and need no confirmation. A lot that looks like a date or has no
     * digit stays highlighted.
     */
    private fun settleLots(items: List<ParsedLineItem>, text: String): List<ParsedLineItem> {
        if (items.size < 2 || !LOT_HEADING.containsMatchIn(text)) return items
        val withLot = items.count { it.lotNumber != null }
        if (withLot * 10 < items.size * 6) return items
        return items.map { it ->
            val lot = it.lotNumber ?: return@map it
            val ok = lot.value.any(Char::isDigit) && LOT_VALUE.matches(lot.value) && ItalianDates.findDates(lot.value).isEmpty()
            if (ok && lot.confidence == Confidence.LOW) it.copy(lotNumber = lot.copy(confidence = Confidence.HIGH)) else it
        }
    }

    private val LOTS_WORD = Regex("(?i)\\b(lott[oi]|lot\\.?|l\\.\\s?n\\.?|batch)\\b")

    internal val TRUNCATED_SUFFIX = Regex("(?i)\\bs\\.\\s?r\\.?$")

    /**
     * The seller from the letterhead. On a document of several pages the letterhead is printed on each: the
     * clearest reading wins (a complete legal form, "ABC S.r.l.", over one the photo cut short, "ABC S.r").
     */
    private fun findSellerOnPages(pages: List<List<String>>, options: ParseOptions): Extracted<String>? {
        val found = pages.take(6).mapNotNull { findSeller(it, options) }
        fun complete(e: Extracted<String>) = e.confidence == Confidence.HIGH && !TRUNCATED_SUFFIX.containsMatchIn(e.value)
        return found.firstOrNull(::complete)
            ?: found.firstOrNull { it.confidence == Confidence.HIGH }?.copy(confidence = Confidence.LOW)
            ?: found.firstOrNull()
    }

    private fun findSeller(lines: List<String>, options: ParseOptions): Extracted<String>? {
        val ownName = DuplicateDetector.normalizeSeller(options.ownBusinessName)
        val ownVat = options.ownVatNumber?.filter(Char::isDigit)?.takeIf { it.length >= 8 }
        // The operator's own business (the customer on supplier invoices) is never the seller.
        fun isOwn(line: String): Boolean {
            if (ownVat != null && line.filter(Char::isDigit).contains(ownVat)) return true
            val n = DuplicateDetector.normalizeSeller(line) ?: return false
            return ownName != null && ownName.length >= 4 && n.contains(ownName)
        }
        val head = lines.take(15)
        // The customer's block: a name followed within a few lines by the operator's own VAT number, with no other
        // VAT number in between ("FOOD ... SAS" / address / "PARTITA IVA <own>").
        fun beforeOwnVat(i: Int): Boolean {
            if (ownVat == null) return false
            for (k in i + 1..minOf(i + 5, lines.lastIndex)) {
                val digits = SellerProfiles.repairDigits(lines[k]).filter(Char::isDigit)
                if (digits.contains(ownVat)) return true
                if (Regex("\\d{11}").findAll(SellerProfiles.repairDigits(lines[k])).any { SellerProfiles.isValidPartitaIva(it.value) }) return false
            }
            return false
        }
        var skipUntil = -1
        var firstPlausible: String? = null
        var firstBeforePiva: String? = null
        var pivaSeen = false

        head.forEachIndexed { i, rawLine ->
            var line = rawLine
            val customer = CUSTOMER_LABEL.find(line)
            if (customer != null) {
                if (customer.range.first >= 4) {
                    // Seller and customer side by side on one row: keep the part before "Spett.le".
                    line = line.substring(0, customer.range.first).trim()
                    skipUntil = i + 1
                } else {
                    // "Spett.le" alone (or "DESTINAZIONE MERCE  SPETTABILE": labels only) -> the customer's name is on
                    // the next line; otherwise it is on this line.
                    val rest = CUSTOMER_LABEL.replace(line.substring(customer.range.last + 1), " ").replace(Regex("(?i)\\bmerce\\b"), " ")
                    skipUntil = if (rest.count { it.isLetter() } >= 3) i else i + 1
                    return@forEachIndexed
                }
            } else if (i <= skipUntil) {
                return@forEachIndexed
            }
            if (line.count { it.isLetter() } < 3) return@forEachIndexed
            if (isOwn(line)) return@forEachIndexed
            if (beforeOwnVat(i)) return@forEachIndexed

            val suffix = COMPANY_SUFFIX.find(line)
            if (suffix != null) {
                // Keep the name up to the legal form: "CASEIFICIO VALVERDE S.R.L. FATTURA N. 145" -> "CASEIFICIO VALVERDE S.R.L."
                val candidate = line.substring(0, suffix.range.last + 1).trim()
                val namePart = candidate.substring(0, suffix.range.first)
                if (namePart.count { it.isLetter() } >= 3 && !NOT_SELLER.containsMatchIn(namePart)) {
                    return Extracted(cleanSeller(candidate), Confidence.HIGH, rawLine)
                }
            }
            TRUNCATED_SUFFIX.find(line)?.let { t ->
                val namePart = line.substring(0, t.range.first)
                if (namePart.count { it.isLetter() } >= 3 && !NOT_SELLER.containsMatchIn(namePart)) {
                    return Extracted(cleanSeller(line), Confidence.HIGH, rawLine)
                }
            }
            if (VAT_ID.containsMatchIn(line)) pivaSeen = true
            if (isPlausibleName(line)) {
                if (firstPlausible == null) firstPlausible = line
                // The seller's name heads the block that ends with its VAT number (P.IVA).
                if (!pivaSeen && firstBeforePiva == null) firstBeforePiva = line
            }
        }
        val pick = firstBeforePiva ?: firstPlausible ?: return null
        return Extracted(cleanSeller(pick), Confidence.LOW, pick)
    }

    private fun isPlausibleName(line: String): Boolean {
        val letters = line.count { it.isLetter() }
        if (letters < 4) return false
        if (letters < line.count { !it.isWhitespace() } * 0.6) return false // mostly digits/symbols: not a name
        return !NOT_SELLER.containsMatchIn(line) && !ADDRESS_OR_CONTACT.containsMatchIn(line) &&
            !TABLE_HEADER.containsMatchIn(line) && lastAmountCents(line) == null && ItalianDates.findDates(line).isEmpty()
    }

    internal fun cleanSeller(line: String): String = line.trim().trim('*', '-', '=', '_', '|', ' ', ',', ':')
        // "ABC S.rle": the legal form misread at the end of the name.
        .replace(Regex("(?i)\\bs\\.\\s?rl[e.]?$"), "S.r.l.")

    // ---------------------------------------------------------------- amounts

    /** All monetary amounts with decimals on a line, in order, in cents. */
    private fun amountsIn(text: String): List<Long> {
        var cleaned = text
        for (d in ItalianDates.findDates(cleaned).reversed()) cleaned = cleaned.replaceRange(d.range, " ")
        cleaned = PERCENT.replace(cleaned, " ")
        return AMOUNT_IN_TEXT.findAll(cleaned).map { it.value }
            .filter { it.contains(',') || Regex("\\.\\d{2}$").containsMatchIn(it) }
            .mapNotNull { ItalianNumbers.parseCents(it) }.toList()
    }

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
        data class UnitTok(val unit: String, val raw: String) : Tok
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
        Units.normalizeKnown(t)?.let { return Tok.UnitTok(it, t) }
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
    /**
     * [colliColumn]: the table has a COLLI column, so a small number right after the article code (or at the
     * start of the line) is the number of packages. [priceFirst]: the header prints the price column before
     * the quantity column, so of two numbers the first is the price.
     */
    fun parseItemLine(line: String, colliColumn: Boolean = false, priceFirst: Boolean = false, orderKnown: Boolean = false): ParsedLineItem? {
        val stripped = stripItemCode(line.split(' ').filter { it.isNotBlank() }, colliColumn)
        val codeFree = stripped.tokens
        val itemCode = stripped.code
        var tokens = splitGluedUnit(codeFree)
        // Drop a VAT class letter after the price: "PANE 2,50 B" -> "PANE 2,50".
        while (tokens.size > 2 && VAT_CODE_TOKEN.matches(tokens.last()) && classify(tokens[tokens.size - 2]) != null) {
            tokens = tokens.dropLast(1)
        }
        // VAT code column: "... 3,450 3,45 10" -> rate 10, not an amount.
        var vatCode: BigDecimal? = null
        if (tokens.size > 2 && VAT_RATE_CODE.matches(tokens.last()) && Regex("[.,]\\d{2}$").containsMatchIn(tokens[tokens.size - 2])) {
            vatCode = BigDecimal(tokens.last())
            tokens = tokens.dropLast(1)
        }
        if (tokens.size < 2) return null
        val tail = ArrayDeque<Tok>()
        var cut = tokens.size
        for (idx in tokens.indices.reversed()) {
            val tok = classify(tokens[idx])
            if (tok == null) {
                // Conservation letter between quantity and price: "40,000 C 2,384" (C frozen, F fresh, CN canned...).
                val left = tokens.getOrNull(idx - 1)
                if (CONSERVATION.matches(tokens[idx]) && tail.firstOrNull() is Tok.Num && left != null && classify(left) is Tok.Num) {
                    cut = idx
                    continue
                }
                break
            }
            tail.addFirst(tok)
            cut = idx
        }
        // "Farina 00 sacco 2 18,50 37,00": when qty, price and total follow the unit,
        // numbers before the unit are part of the description.
        // The unit that applies is the one right before the numbers ("CF GR 0,48": CF is the packaging).
        val unitIdx = tail.indexOfLast { it is Tok.UnitTok }
        if (unitIdx > 0 && tail.drop(unitIdx + 1).count { it is Tok.Num } >= 3) {
            repeat(unitIdx) { tail.removeFirst() }
            cut += unitIdx
        }
        // Description must remain and contain letters.
        var description = cleanDescription(tokens.subList(0, cut))
        if (description.count { it.isLetter() } < 2) return null
        var nums = tail.filterIsInstance<Tok.Num>()
        // An item line must end in something that looks like money ("8,90"), not "Via Roma 12".
        if (nums.isEmpty() || !DECIMAL_AMOUNT.containsMatchIn(nums.last().raw)) return null

        var packSize: PackSizes.Size? = null
        val unitTok = tail.filterIsInstance<Tok.UnitTok>().lastOrNull()
        var unit: String? = unitTok?.unit
        // Pack size between the unit and the quantity: "GR 800 · 1 · 3,450 · 3,45", "LT 5 · 6 · 1,790 · 10,74".
        // Recognised only when quantity x price = total, so a discount column is never mistaken for it.
        if (unitTok != null && tail.none { it is Tok.QtyUnit }) {
            val after = tail.drop(tail.indexOf(unitTok) + 1).filterIsInstance<Tok.Num>()
            if (after.size >= 4) {
                val (pack, q, pr, t) = after.takeLast(4)
                if (matches(q.value, pr.value, ItalianNumbers.toCents(t.value))) {
                    description = "$description ${unitTok.raw.uppercase()} ${pack.raw}"
                    unit = "pz"
                    packSize = PackSizes.parse(listOf(unitTok.raw, pack.raw))
                    nums = listOf(q, pr, t)
                }
            }
        }
        var qtyFromUnitTok: BigDecimal? = null
        tail.filterIsInstance<Tok.QtyUnit>().firstOrNull()?.let {
            qtyFromUnitTok = it.value
            if (it.unit.isNotEmpty()) unit = it.unit
        }
        val rate = tail.filterIsInstance<Tok.Rate>().lastOrNull()?.value ?: vatCode
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
                if (priceFirst) { qty = last3[1]; price = last3[0] }
                // quantity x price = amount decides which numbers they are, wherever they sit on the line
                // (a pack size, colli or discount column may stand between them).
                findPair(values)?.let { (qi, pi) ->
                    val a = values[qi]; val b = values[pi]
                    val (q, p) = if (priceFirst) b to a else a to b
                    qty = q; price = p; totalCents = ItalianNumbers.toCents(values.last())
                }
                // Some layouts print price before quantity (no header to tell): a whole number after a price with decimals is the quantity.
                // Not when the headings say the order, nor for a weight (3 decimals on a kg/l line: "KG 3,115 1,00").
                val weight = last3[0].scale() == 3 && unit in setOf("kg", "l")
                if (!priceFirst && !orderKnown && !weight && isWholeNumber(last3[1]) && !isWholeNumber(last3[0]) && last3[0].scale() >= 2 &&
                    matches(last3[0], last3[1], ItalianNumbers.toCents(last3[2])) && qty!!.compareTo(last3[0]) == 0
                ) {
                    val tmp = qty; qty = price; price = tmp
                }
                // Invoices often print a discount column: qty, price, discount, total.
                if (!matches(qty!!, price!!, totalCents!!) && values.size >= 4 && findPair(values) == null) {
                    val q4 = values[values.size - 4]
                    val p4 = values[values.size - 3]
                    val gross = q4.multiply(p4)
                    if (gross.signum() > 0 && ItalianNumbers.toCents(gross) >= totalCents) {
                        qty = q4; price = p4
                    }
                }
            }
        }
        if (qty != null && price != null && totalCents != null) consistent = matches(qty, price, totalCents)
        // The OCR often drops a lone small number: "SK GR 800 · (1) · 3,450 · 3,45". The first number is then
        // the pack size; the quantity is worked out as total / price, and marked for the operator to check.
        var qtyWorkedOut = false
        if (!consistent && unitTok != null && unit != "pz" && values.size == 3 && tail.none { it is Tok.QtyUnit }) {
            val p = values[1]
            val t = values[2]
            if (p.signum() > 0 && t.signum() > 0) {
                val q = t.divide(p, 3, RoundingMode.HALF_UP)
                if (isWholeNumber(q) && q >= BigDecimal.ONE && q <= BigDecimal(500) && matches(q, p, ItalianNumbers.toCents(t))) {
                    description = "$description ${unitTok.raw.uppercase()} ${nums[0].raw}"
                    unit = "pz"
                    packSize = PackSizes.parse(listOf(unitTok.raw, nums[0].raw))
                    qty = q.stripTrailingZeros(); price = p; totalCents = ItalianNumbers.toCents(t)
                    qtyWorkedOut = true
                    consistent = true // the line adds up; only the quantity stays marked (worked out, not read)
                }
            }
        }
        // "GR 0,48" on a weighed item means kilograms: nobody buys 0,48 grams.
        val q0 = qty
        if (q0 != null && !isWholeNumber(q0) && q0 < BigDecimal(100)) {
            if (unit == "g") unit = "kg"
            if (unit == "ml") unit = "l"
        }

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
        val qtyConf = if (qtyWorkedOut) Confidence.LOW else conf
        val warnings = if (qty != null && price != null && totalCents != null && !consistent) {
            setOf(ParseWarning.LINE_TOTAL_MISMATCH)
        } else {
            emptySet()
        }
        val totalConf = if (consistent || (qty == null && price == null)) Confidence.HIGH else conf
        return ParsedLineItem(
            originalDescription = description,
            quantity = qty?.let { Extracted(it, qtyConf, line) },
            unit = unit?.let { Extracted(it, if (consistent) Confidence.HIGH else Confidence.LOW, line) },
            unitPrice = price?.let { Extracted(it, conf, line) },
            lineTotalCents = totalCents?.let { Extracted(it, totalConf, line) },
            vatRatePercent = rate?.let { Extracted(it, Confidence.HIGH, line) },
            lotNumber = null,
            expiryDate = null,
            warnings = warnings,
            itemCode = itemCode,
            packages = stripped.packages?.let { Extracted(normalizeColli(it), Confidence.HIGH, line) },
            packSize = packSize?.let { Extracted(it.text, conf, line) },
        )
    }

    private fun normalizeColli(raw: String): String =
        raw.map { c -> when (c) { 'l', 'I', 'L' -> '1'; 'O' -> '0'; 'X', '×', '*' -> 'x'; else -> c } }.joinToString("")

    private fun isWholeNumber(v: BigDecimal) = v.stripTrailingZeros().scale() <= 0

    /**
     * Indices (i < j) of two numbers before the last one whose product is the last one (the amount).
     * The pair closest to the amount wins; a pair with a discount percentage between price and amount
     * ("2 x 10,00 - 10% = 18,00") counts too.
     */
    private fun findPair(values: List<BigDecimal>): Pair<Int, Int>? {
        val n = values.size
        if (n < 3) return null
        val total = ItalianNumbers.toCents(values[n - 1])
        val pairs = mutableListOf<Triple<Int, Int, Int>>() // i, j, cost
        for (j in n - 2 downTo 1) for (i in j - 1 downTo 0) pairs += Triple(i, j, 2 * (n - 2 - j) + (j - i - 1))
        for ((i, j, _) in pairs.sortedBy { it.third }) {
            val between = values.subList(j + 1, n - 1)
            if (between.isEmpty() && matches(values[i], values[j], total)) return i to j
            if (between.size == 1) {
                val d = between[0]
                if (d.signum() > 0 && d < BigDecimal(100)) {
                    val net = values[i].multiply(values[j]).multiply(BigDecimal(100).subtract(d)).divide(BigDecimal(100))
                    if (kotlin.math.abs(ItalianNumbers.toCents(net) - total) <= 2) return i to j
                }
            }
        }
        return null
    }

    /** "CF GR1500 2 4,850 9,70": unit and pack size read without a space, right before the quantity. */
    private fun splitGluedUnit(tokens: List<String>): List<String> {
        val out = mutableListOf<String>()
        tokens.forEachIndexed { i, t ->
            val m = GLUED_UNIT.find(t)
            val next = tokens.getOrNull(i + 1)
            if (m != null && next != null && next.first().isDigit() && Units.normalizeKnown(m.groupValues[1]) != null) {
                out += m.groupValues[1]; out += m.groupValues[2]
            } else {
                out += t
            }
        }
        return out
    }

    private class Stripped(val tokens: List<String>, val code: String?, val packages: String?)

    /** Removes "O 2046225 1x1" (marker, article code, colli) from the start of an item line; the colli are kept apart. */
    private fun stripItemCode(tokens: List<String>, colliColumn: Boolean = false): Stripped {
        var i = 0
        // Line marker ("O" offer, "S" discount); the OCR may read the letter O as a zero.
        if (tokens.size > 3 && tokens[0].length == 1 && (tokens[0][0].isLetter() || tokens[0] == "0") &&
            (ITEM_CODE.matches(tokens[1]) || CODE_WITH_COLLI.matches(tokens[1]))
        ) i = 1
        CODE_WITH_COLLI.find(tokens.getOrElse(i) { "" })?.let { m ->
            if (tokens.size > i + 2) return Stripped(tokens.drop(i + 1), m.groupValues[1], m.groupValues[2])
        }
        var code: String? = null
        if (tokens.size > i + 2 && ITEM_CODE.matches(tokens[i])) {
            code = tokens[i]
            i++
        }
        // Colli right after the code (or first on the line): "1x6", "1 x 6", or a bare number when the table has a COLLI column.
        var packages: String? = null
        val t0 = tokens.getOrNull(i)
        val t1 = tokens.getOrNull(i + 1)
        val t2 = tokens.getOrNull(i + 2)
        if (t0 != null && tokens.size > i + 2) {
            when {
                COLLI_PATTERN.matches(t0) -> { packages = t0; i++ }
                t1 != null && t2 != null && t1 in setOf("x", "X", "×", "*") && t0.all(Char::isDigit) && t0.length <= 3 &&
                    t2.all(Char::isDigit) && t2.length <= 3 && tokens.size > i + 4 -> { packages = "${t0}x$t2"; i += 3 }
                (code != null || colliColumn) && t0.all(Char::isDigit) && t0.length <= 3 && t1 != null && t1.any(Char::isLetter) &&
                    (code == null || COLLI.matches(t0)) -> { packages = t0; i++ }
                code != null && colliColumn -> GLUED_COLLI.find(t0)?.let { m ->
                    // "2/0LIO": a zero starting the word is the letter O.
                    val word = m.groupValues[2].let { w -> if (w.startsWith('0')) "O" + w.drop(1) else w }
                    // Not a size or unit ("5KG", "6X400G"): the letters must be a word of the name.
                    if (Units.normalizeKnown(word.trimEnd('.')) == null && !word[0].equals('x', ignoreCase = true)) {
                        packages = m.groupValues[1]
                        return Stripped(listOf(word) + tokens.drop(i + 1), code, packages)
                    }
                }
            }
        }
        return Stripped(tokens.drop(i), code, packages)
    }

    /** Trims separators and a trailing packaging code ("... MULINO BIANC SK" -> "... MULINO BIANC"). */
    private fun cleanDescription(tokens: List<String>): String {
        var t = tokens
        fun dropSeparators() { while (t.isNotEmpty() && t.last().all { it in "-.,:;/" }) t = t.dropLast(1) }
        dropSeparators()
        // At most one packaging code, and only right before the numbers ("SUINO SV - . NC" keeps "SV").
        if (t.size > 1 && PACKAGING_CODE.matches(t.last()) && t.last() !in SHORT_WORDS) t = t.dropLast(1)
        dropSeparators()
        return t.joinToString(" ").trim().trimEnd(':', '-', '.', ',', ' ').trimStart('-', '.', '*', ' ').trim()
    }

    /** qty x price equals total within one cent (after rounding half-up to cents). */
    fun matches(qty: BigDecimal, price: BigDecimal, totalCents: Long): Boolean {
        val computed = qty.multiply(price).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
        return kotlin.math.abs(computed - totalCents) <= 1
    }
}
