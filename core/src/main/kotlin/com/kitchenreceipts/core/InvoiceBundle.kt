package com.kitchenreceipts.core

import java.math.BigDecimal
import java.time.LocalDate

/**
 * One PDF that holds many invoices (an accountant's or the tax portal's printout of the purchase e-invoices, one after
 * another): where each invoice starts and ends, from the PDF's own text.
 *
 * The signals are the labels of the two published e-invoice stylesheets, the same for every supplier:
 * - AssoSoftware: an invoice opens with the parties' boxes ("Cedente/prestatore", "Cessionario/committente") and the
 *   document's identity row ("Tipologia documento"), and it ends with the "Totale documento" heading. A file with
 *   several bodies prints the parties once and an identity row for each body.
 * - SdI: a file opens with "FATTURA ELETTRONICA" / "Dati relativi alla trasmissione"; each body of a batch with
 *   "Numero documento nel lotto". Its "Importo totale documento" is printed in the header, so it is no end; a body
 *   is complete when its "Dati di riepilogo per aliquota IVA e natura" is printed.
 *
 * Nothing is guessed: an invoice whose start, end, supplier or total is not sure is flagged for the operator to look
 * at. Pages before the first invoice are kept apart as pages the app does not recognise.
 */
object InvoiceBundle {

    /** An invoice, or pages before the first invoice (a cover page): not recognised. */
    enum class Kind { INVOICE, UNKNOWN }

    /** What opened a segment. */
    enum class Start { NONE, ASSO, ASSO_BODY, SDI_FILE, SDI_BODY, BY_HAND }

    /** Why an invoice needs a look before it is read. */
    enum class Flag { NO_TOTAL, SEVERAL_IN_ONE, OVER_PAGE_LIMIT, NO_SUPPLIER, OTHER_BUYER, START_UNSURE, NO_TEXT }

    /** The app reads at most this many pages as one document. */
    const val PAGE_LIMIT = 20

    data class VatGroup(val ratePercent: BigDecimal, val taxableCents: Long, val vatCents: Long)

    /** What one page shows. Small: a long file's signals are kept while it is looked through, without [rowTexts]. */
    data class PageSignals(
        val index: Int,
        val hasText: Boolean,
        /** The supplier's role label in the top 40% of the page. */
        val supplierRoleTop: Boolean = false,
        val customerRole: Boolean = false,
        /** The VAT number in the customer's box. */
        val customerVat: String? = null,
        /** "Tipologia documento" rows: one per document. */
        val identityRows: Int = 0,
        /** An identity row in the top 40% of the page. */
        val identityTop: Boolean = false,
        /** Rows labelled exactly "Totale documento" (the AssoSoftware end). */
        val assoEnds: Int = 0,
        val sdiFileStart: Boolean = false,
        val sdiBatchStart: Boolean = false,
        val sdiSummary: Boolean = false,
        /** The inside of an invoice: the item heading again, totals or payment, and no start. */
        val continuation: Boolean = false,
        val supplierVat: String? = null,
        val supplierName: String? = null,
        val docType: String? = null,
        val number: String? = null,
        val date: LocalDate? = null,
        /** The total as printed under "Totale documento" or after "Importo totale documento". */
        val printedTotalCents: Long? = null,
        val vatGroups: List<VatGroup> = emptyList(),
        val creditNote: Boolean = false,
        /** Signs of an electricity, gas or water bill, and of a fuel invoice (bit sets, see [SupplierCategories]). */
        val utilityHits: Int = 0,
        val fuelHits: Int = 0,
        /** How many product rows look like each kind of product. */
        val kinds: Map<Category, Int> = emptyMap(),
        /** Some product rows as read (at most [MAX_ROWS]); not kept when the signals are saved. */
        val rowTexts: List<String> = emptyList(),
    ) {
        val assoEnd: Boolean get() = assoEnds > 0
        /** Rule (a): the parties' boxes and the identity row on one page. */
        val assoStart: Boolean get() = supplierRoleTop && customerRole && identityRows > 0
        val hints: SupplierCategories.Hints get() = SupplierCategories.Hints(utilityHits, fuelHits, kinds)
    }

    data class Segment(
        val first: Int,
        val last: Int,
        val kind: Kind,
        val start: Start,
        val supplierVat: String?,
        val supplierName: String?,
        val customerVat: String?,
        val docType: String?,
        val number: String?,
        val date: LocalDate?,
        val printedTotalCents: Long?,
        val vatGroups: List<VatGroup>,
        /** Σ(taxable + VAT) of the printed VAT summary equals the printed total, to the cent. */
        val totalProven: Boolean,
        val creditNote: Boolean,
        val flags: Set<Flag>,
        /** Its end was printed ("Totale documento", or the SdI summary). */
        val ended: Boolean,
    ) {
        val pageCount: Int get() = last - first + 1
        val sure: Boolean get() = flags.isEmpty()
    }

    const val MAX_ROWS = 40

    private val SUPPLIER_ROLE = rx("(?i)^\\s*(dati\\s+del\\s+)?cedente\\s*/\\s*prestatore\\b")
    private val CUSTOMER_ROLE = rx("(?i)^\\s*(dati\\s+del\\s+)?cessionario\\s*/\\s*committente\\b")
    private val IDENTITY = rx("(?i)\\btipologia\\s+documento\\b")
    private val SDI_TITLE = rx("(?i)^\\s*(fattura\\s+elettronica|dati\\s+relativi\\s+alla\\s+trasmissione)\\s*$")
    /** Headings only the SdI layout prints: a courtesy copy that only says "FATTURA ELETTRONICA" is no SdI file. */
    private val SDI_HEADING = rx("(?i)^\\s*(dati\\s+relativi\\s+alla\\s+trasmissione|dati\\s+del\\s+cedente\\s*/\\s*prestatore)")
    private val SDI_SUBHEADING = rx("(?i)^\\s*dati\\s+(anagrafici|della\\s+sede|della\\s+stabile\\s+organizzazione|di\\s+iscrizione.*|contatti.*)\\s*$")
    private val SDI_BATCH = rx("(?i)numero\\s+documento\\s+nel\\s+lotto")
    private val SDI_SUMMARY = rx("(?i)dati\\s+di\\s+riepilogo\\s+per\\s+aliquota\\s+iva\\s+e\\s+natura")
    private val SDI_NUMBER = rx("(?i)^\\s*numero\\s+documento\\s*:\\s*(\\S.{0,19}?)\\s*$")
    private val SDI_DATE = rx("(?i)^\\s*data\\s+documento\\s*:\\s*(\\d{4})-(\\d{2})-(\\d{2})")
    private val SDI_TOTAL = rx("(?i)^\\s*importo\\s+totale\\s+documento\\s*:\\s*(-?\\d{1,11}\\.\\d{2})\\s*$")
    private val DOC_TYPE = rx("\\b(TD\\d{2})\\b")
    private val CREDIT_TITLE = rx("(?i)^\\s*nota\\s+di\\s+credito\\b")
    /** The end of the customer's box: the next block of the page. */
    private val BOX_END = rx(
        "(?i)^\\s*(terzo\\s+intermediario|soggetto\\s+emittente|tipologia\\s+documento|rappresentante\\s+fiscale|stabile\\s+organizzazione|" +
            "dati\\s+(del\\s+terzo|del\\s+rappresentante|della\\s+stabile|generali|relativi)|numero\\s+documento|versione\\s+prodotta)",
    )
    private val CONTINUES = rx(
        "(?i)^\\s*(descrizione|riepiloghi\\s+iva|totale\\s+documento|totale\\s+pagina|a\\s+riportare|riporto|modalit\\S*\\s+pagamento|" +
            "dati\\s+relativi\\s+alle\\s+linee|dati\\s+di\\s+riepilogo|dati\\s+relativi\\s+al\\s+pagamento)",
    )
    private val MONEY = rx("^(?:€|EUR)?\\s*(-?(?:\\d{1,3}(?:\\.\\d{3})+|\\d+),\\d{2})\\s*(?:€|EUR)?$")
    private val PRODUCT_ROW = rx("\\p{L}{3}.*\\d,\\d{2}\\s*$")

    private fun words(t: String) = t.trim().split(rx("\\s+")).size
    private fun norm(t: String) = t.trim().trimEnd(':').trim().replace(rx("\\s+"), " ").lowercase()

    /**
     * The signals of one page. [pageHeight]: the page's height in the lines' units, when known; otherwise the bottom
     * of the lowest text stands for it.
     */
    fun signals(index: Int, lines: List<OcrLine>, ownVat: String?, pageHeight: Int? = null): PageSignals {
        val clean = lines.filter { it.text.isNotBlank() }
        val hasText = clean.sumOf { l -> l.text.count(Char::isLetterOrDigit) } >= 20
        if (!hasText) return PageSignals(index, hasText = false)
        val height = (pageHeight ?: clean.maxOf { it.bottom }).coerceAtLeast(1)
        fun top(l: OcrLine, share: Double) = l.top < height * share

        val supplierRoleTop = clean.any { top(it, 0.40) && SUPPLIER_ROLE.containsMatchIn(it.text) && words(it.text) <= 6 }
        val customerLabel = clean.firstOrNull { CUSTOMER_ROLE.containsMatchIn(it.text) && words(it.text) <= 6 }
        val identity = clean.filter { IDENTITY.containsMatchIn(it.text) }
        val sdiFileStart = clean.any { top(it, 0.25) && SDI_TITLE.containsMatchIn(it.text) } && clean.any { SDI_HEADING.containsMatchIn(it.text) }
        val sdiBatchStart = clean.any { SDI_BATCH.containsMatchIn(it.text) }
        val sdiSummary = clean.any { SDI_SUMMARY.containsMatchIn(it.text) }
        // The AssoSoftware end: the heading cell "Totale documento" itself (never "Importo totale documento", never
        // an "Arrotondamento su ..." row, never "Totale pagina").
        val ends = clean.filter { norm(it.text) == "totale documento" }

        val layout = runCatching { LayoutRows.layout(clean) }.getOrNull()
        val rows = layout?.rows?.map { r -> r.sortedBy { it.left } }.orEmpty()

        var supplierVat: String? = null
        var supplierName: String? = null
        if (supplierRoleTop || sdiFileStart) {
            // The SdI layout prints sub-headings inside a party's list ("Dati anagrafici"): no part of the name.
            val s = runCatching {
                val partyLayout = if (sdiFileStart) LayoutRows.layout(clean.filterNot { SDI_SUBHEADING.matches(it.text.trim()) }) else layout
                partyLayout?.let { Parties.supplier(listOf(it), ownVat) }
            }.getOrNull()
            supplierVat = s?.vatNumber
            supplierName = s?.name?.value
        }
        val customerVat = customerLabel?.let { customerVat(rows, it, supplierVat) }

        // The document's number, date and type: only on a page that prints its identity row.
        var number: String? = null
        var date: LocalDate? = null
        var docType: String? = null
        if (identity.isNotEmpty()) {
            val grid = layout?.let { runCatching { HeaderGrid.read(listOf(it)) }.getOrNull() }
            number = grid?.number ?: clean.firstNotNullOfOrNull { l -> SDI_NUMBER.find(l.text)?.groupValues?.get(1)?.takeIf { v -> v.any(Char::isDigit) } }
            date = grid?.date ?: clean.firstNotNullOfOrNull { l ->
                SDI_DATE.find(l.text)?.let { m -> runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() }
            }
            val from = identity.minOf { it.top } - 2
            docType = clean.filter { it.top >= from }.sortedWith(compareBy({ it.top }, { it.left }))
                .firstNotNullOfOrNull { DOC_TYPE.find(it.text)?.groupValues?.get(1) }
        }
        // TD04 / TD08; without a TD code, a "Nota di credito" title near the top.
        val creditNote = if (docType != null) docType == "TD04" || docType == "TD08"
            else clean.any { top(it, 0.40) && CREDIT_TITLE.containsMatchIn(it.text) && words(it.text) <= 8 }

        val printed = ends.firstNotNullOfOrNull { valueUnder(rows, it) }
            ?: clean.firstNotNullOfOrNull { l -> SDI_TOTAL.find(l.text)?.groupValues?.get(1)?.let { BigDecimal(it).movePointRight(2).longValueExact() } }
        val groups = layout?.let { l -> runCatching { VatSummary.parse(l.text) }.getOrNull() }.orEmpty()
            .map { VatGroup(it.ratePercent, it.taxableCents, it.vatCents) }

        val rowTexts = rows.map { r -> r.joinToString(" ") { it.text.trim() } }
        val hints = SupplierCategories.hints(rowTexts)
        val start = (supplierRoleTop && customerLabel != null && identity.isNotEmpty()) || sdiFileStart || sdiBatchStart
        return PageSignals(
            index = index,
            hasText = true,
            supplierRoleTop = supplierRoleTop,
            customerRole = customerLabel != null,
            customerVat = customerVat,
            identityRows = identity.size,
            identityTop = identity.any { top(it, 0.40) },
            assoEnds = ends.size,
            sdiFileStart = sdiFileStart,
            sdiBatchStart = sdiBatchStart,
            sdiSummary = sdiSummary,
            continuation = !start && clean.any { CONTINUES.containsMatchIn(it.text) },
            supplierVat = supplierVat,
            supplierName = supplierName,
            docType = docType,
            number = number,
            date = date,
            printedTotalCents = printed,
            vatGroups = groups,
            creditNote = creditNote,
            utilityHits = hints.utility,
            fuelHits = hints.fuel,
            kinds = hints.kinds,
            rowTexts = rowTexts.filter { PRODUCT_ROW.containsMatchIn(it) }.take(MAX_ROWS),
        )
    }

    /** The amount printed under a heading cell: in the next rows, in the heading's column (up to the next heading). */
    private fun valueUnder(rows: List<List<OcrLine>>, label: OcrLine): Long? {
        val r = rows.indexOfFirst { row -> row.any { it === label } }
        if (r < 0) return null
        val nextLeft = rows[r].filter { it.left > label.right }.minOfOrNull { it.left } ?: Int.MAX_VALUE
        for (k in r + 1 until minOf(rows.size, r + 3)) {
            for (c in rows[k]) {
                if (c.centerX < label.left - 8 || c.centerX >= nextLeft) continue
                MONEY.matchEntire(c.text.trim())?.let { m -> return ItalianNumbers.parseCents(m.groupValues[1]) }
            }
        }
        return null
    }

    /** The first valid VAT number in the customer's box: the rows under its label, in its column, up to the next block. */
    private fun customerVat(rows: List<List<OcrLine>>, label: OcrLine, supplierVat: String?): String? {
        val r = rows.indexOfFirst { row -> row.any { it === label } }
        if (r < 0) return null
        val digits = StringBuilder()
        for (k in r + 1 until minOf(rows.size, r + 15)) {
            if (rows[k].any { BOX_END.containsMatchIn(it.text) || SUPPLIER_ROLE.containsMatchIn(it.text) }) break
            rows[k].filter { it.centerX >= label.left - 40 }.forEach { digits.append(SellerProfiles.repairDigits(it.text)).append(' ') }
        }
        return rx("\\d{11}").findAll(digits).map { it.value }.firstOrNull { SellerProfiles.isValidPartitaIva(it) && it != supplierVat }
    }

    /** Where invoices start: rules (a), (b) and (c). */
    private fun starts(pages: List<PageSignals>): List<Pair<Int, Start>> {
        val out = mutableListOf<Pair<Int, Start>>()
        var kind = Start.NONE
        var vat: String? = null
        var number: String? = null
        var ended = false
        var identity = false
        for ((i, p) in pages.withIndex()) {
            val open = kind != Start.NONE
            val start = when {
                // (c) An SdI file, or the next body of an SdI batch once the current one holds a document.
                p.sdiFileStart -> Start.SDI_FILE
                p.sdiBatchStart && (!open || identity) -> Start.SDI_BODY
                // (a) The parties and the identity: the first invoice, after an end, or another supplier or number.
                p.assoStart && (!open || ended || differ(vat, p.supplierVat) || differ(number, p.number)) -> Start.ASSO
                // (b) The next body of an AssoSoftware file: its identity row at the top, after the end of the one before.
                !p.assoStart && p.identityTop && ended && (kind == Start.ASSO || kind == Start.ASSO_BODY) -> Start.ASSO_BODY
                else -> null
            }
            if (start != null) {
                out += i to start
                kind = start; vat = null; number = null; ended = false; identity = false
            } else if (i == 0) {
                out += 0 to Start.NONE
            }
            if (vat == null) vat = p.supplierVat
            if (number == null && p.identityRows > 0) number = p.number
            if (p.assoEnd || p.sdiSummary) ended = true
            if (p.identityRows > 0) identity = true
        }
        return out
    }

    fun segments(pages: List<PageSignals>, ownVat: String? = null): List<Segment> = assemble(pages, starts(pages), ownVat)

    /** The segments for the given starts (position in [pages], what opened it). */
    private fun assemble(pages: List<PageSignals>, starts: List<Pair<Int, Start>>, ownVat: String?): List<Segment> {
        val own = ownVat?.filter(Char::isDigit)?.takeIf { it.length == 11 }
        val out = mutableListOf<Segment>()
        val sorted = starts.sortedBy { it.first }.distinctBy { it.first }
        for ((k, s) in sorted.withIndex()) {
            val end = sorted.getOrNull(k + 1)?.first ?: pages.size
            if (s.first >= end) continue
            out += build(pages.subList(s.first, end), s.second, out.lastOrNull(), own)
        }
        return out
    }

    private fun build(pages: List<PageSignals>, start: Start, prev: Segment?, own: String?): Segment {
        val kind = if (start == Start.NONE) Kind.UNKNOWN else Kind.INVOICE
        var vat = pages.firstNotNullOfOrNull { it.supplierVat }
        var name = pages.firstNotNullOfOrNull { it.supplierName }
        var customer = pages.firstNotNullOfOrNull { it.customerVat }
        // A body of a batch: the file prints the parties once, before its first body.
        if ((start == Start.ASSO_BODY || start == Start.SDI_BODY) && vat == null && name == null && prev?.kind == Kind.INVOICE) {
            vat = prev.supplierVat; name = prev.supplierName; customer = customer ?: prev.customerVat
        }
        val head = pages.firstOrNull { it.identityRows > 0 }
        val number = head?.number ?: pages.firstNotNullOfOrNull { it.number }
        val date = head?.date ?: pages.firstNotNullOfOrNull { it.date }
        val sdi = start == Start.SDI_FILE || start == Start.SDI_BODY || pages.any { it.sdiFileStart || it.sdiBatchStart || it.sdiSummary }
        val ended = if (sdi) pages.any { it.sdiSummary } else pages.any { it.assoEnd }
        val printedAll = pages.mapNotNull { it.printedTotalCents }.distinct()
        val printed = printedAll.singleOrNull()
        val groups = pages.flatMap { it.vatGroups }
        val proven = printed != null && groups.isNotEmpty() && groups.sumOf { it.taxableCents + it.vatCents } == printed
        val numbers = pages.filter { it.identityRows > 0 }.mapNotNull { it.number }.distinct()
        val flags = buildSet {
            if (pages.any { !it.hasText }) add(Flag.NO_TEXT)
            if (kind == Kind.INVOICE) {
                if (!ended) add(Flag.NO_TOTAL)
                // Two documents: two ends, two identity rows on one page, or two different numbers. A header printed
                // again on every page (same number) is one document.
                if (pages.sumOf { it.assoEnds } >= 2 || pages.count { it.sdiSummary } >= 2 || pages.any { it.identityRows >= 2 } || numbers.size >= 2) {
                    add(Flag.SEVERAL_IN_ONE)
                }
                if (pages.size > PAGE_LIMIT) add(Flag.OVER_PAGE_LIMIT)
                if (vat == null && name == null) add(Flag.NO_SUPPLIER)
                if (own != null && customer != null && customer != own) add(Flag.OTHER_BUYER)
                if (start == Start.SDI_FILE || start == Start.SDI_BODY || (start != Start.BY_HAND && prev?.kind == Kind.INVOICE && !prev.ended)) {
                    add(Flag.START_UNSURE)
                }
            }
        }
        return Segment(
            first = pages.first().index, last = pages.last().index, kind = kind, start = start,
            supplierVat = vat, supplierName = name, customerVat = customer,
            docType = head?.docType ?: pages.firstNotNullOfOrNull { it.docType },
            number = number, date = date, printedTotalCents = printed, vatGroups = groups, totalProven = proven,
            creditNote = pages.any { it.creditNote }, flags = flags, ended = ended,
        )
    }

    /** The segments for given starts (a page index and what opened it), every field worked out from the signals. */
    fun rebuild(pages: List<PageSignals>, starts: List<Pair<Int, Start>>, ownVat: String? = null): List<Segment> {
        val at = starts.mapNotNull { (page, s) -> pages.indexOfFirst { it.index == page }.takeIf { it >= 0 }?.let { it to s } }
        // The first page always belongs to a segment.
        return assemble(pages, if (at.none { it.first == 0 }) listOf(0 to Start.NONE) + at else at, ownVat)
    }

    /** The pages of segment [index] belong to the one before it. Every field is worked out again from the signals. */
    fun join(segments: List<Segment>, pages: List<PageSignals>, index: Int, ownVat: String? = null): List<Segment> {
        if (index <= 0 || index >= segments.size) return segments
        return rebuild(pages, segments.filterIndexed { i, _ -> i != index }.map { it.first to it.start }, ownVat)
    }

    /** A new invoice starts at [page] (a page index), as the operator says. Every field is worked out again. */
    fun splitAt(segments: List<Segment>, pages: List<PageSignals>, page: Int, ownVat: String? = null): List<Segment> {
        if (segments.any { it.first == page } || segments.none { page in it.first..it.last }) return segments
        return rebuild(pages, segments.map { it.first to it.start } + (page to Start.BY_HAND), ownVat)
    }

    /** The file has text to work with: at least half of its pages. */
    fun usable(pages: List<PageSignals>): Boolean = pages.isNotEmpty() && pages.count { it.hasText } * 2 >= pages.size

    /** Two or more invoices: worth splitting. */
    fun isBundle(segments: List<Segment>): Boolean = segments.count { it.kind == Kind.INVOICE } >= 2

    fun find(pages: List<List<OcrLine>>, ownVat: String?): List<Segment> =
        segments(pages.mapIndexed { i, p -> signals(i, p, ownVat) }, ownVat)

    private fun differ(a: String?, b: String?) = a != null && b != null && a != b

    // ------------------------------------------------------------------ saved form (a scan resumes from it)

    /** One page's signals as one line of text, without [PageSignals.rowTexts]. */
    fun encode(p: PageSignals): String = BundleText.record(
        "i" to p.index.toString(),
        "t" to bit(p.hasText),
        "sr" to bit(p.supplierRoleTop), "cr" to bit(p.customerRole), "cv" to p.customerVat,
        "ir" to p.identityRows.takeIf { it > 0 }?.toString(), "it" to bit(p.identityTop),
        "ae" to p.assoEnds.takeIf { it > 0 }?.toString(),
        "sf" to bit(p.sdiFileStart), "sb" to bit(p.sdiBatchStart), "ss" to bit(p.sdiSummary), "co" to bit(p.continuation),
        "sv" to p.supplierVat, "sn" to p.supplierName, "dt" to p.docType, "n" to p.number, "d" to p.date?.toString(),
        "pt" to p.printedTotalCents?.toString(),
        "g" to p.vatGroups.takeIf { it.isNotEmpty() }?.joinToString(";") { "${it.ratePercent.toPlainString()}/${it.taxableCents}/${it.vatCents}" },
        "cn" to bit(p.creditNote),
        "u" to p.utilityHits.takeIf { it != 0 }?.toString(), "f" to p.fuelHits.takeIf { it != 0 }?.toString(),
        "k" to p.kinds.takeIf { it.isNotEmpty() }?.entries?.joinToString(",") { "${it.key.key}=${it.value}" },
    )

    fun decode(line: String): PageSignals? = runCatching {
        val f = BundleText.fields(line)
        fun b(k: String) = f[k] == "1"
        PageSignals(
            index = f.getValue("i")!!.toInt(),
            hasText = b("t"),
            supplierRoleTop = b("sr"), customerRole = b("cr"), customerVat = f["cv"],
            identityRows = f["ir"]?.toInt() ?: 0, identityTop = b("it"), assoEnds = f["ae"]?.toInt() ?: 0,
            sdiFileStart = b("sf"), sdiBatchStart = b("sb"), sdiSummary = b("ss"), continuation = b("co"),
            supplierVat = f["sv"], supplierName = f["sn"], docType = f["dt"], number = f["n"], date = f["d"]?.let(LocalDate::parse),
            printedTotalCents = f["pt"]?.toLong(),
            vatGroups = f["g"].orEmpty().split(';').filter { it.isNotBlank() }.map { g ->
                val (r, t, v) = g.split('/')
                VatGroup(BigDecimal(r), t.toLong(), v.toLong())
            },
            creditNote = b("cn"), utilityHits = f["u"]?.toInt() ?: 0, fuelHits = f["f"]?.toInt() ?: 0,
            kinds = f["k"].orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { e ->
                Category.fromKey(e.substringBefore('='))?.let { it to e.substringAfter('=').toInt() }
            }.toMap(),
        )
    }.getOrNull()

    private fun bit(b: Boolean): String? = if (b) "1" else null
}

/**
 * Records as one line of text: "key:value" fields separated by '|', with '\', '|' and line breaks escaped. A field that
 * is null is left out, and a reader skips the keys it does not know: a newer app's records stay readable.
 */
internal object BundleText {

    fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\"); '|' -> append("\\p"); '\n' -> append("\\n"); '\r' -> append("\\r"); else -> append(c)
        }
    }

    fun unesc(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 'p' -> append('|'); 'n' -> append('\n'); 'r' -> append('\r'); else -> append(s[i + 1]) }
                i += 2
            } else { append(c); i++ }
        }
    }

    fun record(vararg fields: Pair<String, String?>): String =
        fields.filter { it.second != null }.joinToString("|") { (k, v) -> k + ":" + esc(v!!) }

    fun fields(line: String): Map<String, String?> {
        val out = linkedMapOf<String, String?>()
        val cur = StringBuilder()
        fun close() {
            val raw = cur.toString()
            cur.clear()
            val colon = raw.indexOf(':')
            if (colon > 0) out[raw.substring(0, colon)] = unesc(raw.substring(colon + 1))
        }
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) { cur.append(c).append(line[i + 1]); i += 2; continue }
            if (c == '|') close() else cur.append(c)
            i++
        }
        close()
        return out
    }
}
