package com.kitchenreceipts.core

import java.time.LocalDate

/**
 * One PDF that holds many invoices (an accountant's or the tax portal's printout of the purchase e-invoices, one after
 * another): where each invoice starts and ends, from the PDF's own text.
 *
 * The signals are those of the two published e-invoice stylesheets, the same for every supplier: an invoice starts
 * on a page that prints the supplier's role box at the top ("Cedente/prestatore", or the SdI layout's "Dati del
 * cedente" under "FATTURA ELETTRONICA") together with the document's identity ("Tipologia documento", a TDnn code,
 * "Numero documento"); the pages after it continue it (the item heading printed again, or only totals and payment).
 * A page that reprints the same supplier and number belongs to the same invoice. "Totale documento" closes one.
 *
 * Nothing is guessed: an invoice whose supplier, number or total could not be found is listed with that problem, for
 * the operator to look at.
 */
object InvoiceBundle {

    enum class Problem { NO_TOTAL, SEVERAL_DOCUMENTS, NO_SUPPLIER, NO_NUMBER, NO_TEXT }

    data class PageSignals(
        val index: Int,
        val hasText: Boolean,
        /** The page opens an invoice: the supplier's role box at the top, with the document's identity. */
        val start: Boolean,
        val supplierVat: String?,
        val supplierName: String?,
        val customerVat: String?,
        val docType: String?,
        val number: String?,
        val date: LocalDate?,
        val totalCents: Long?,
        /** "Totale documento" labels on the page: each closes one invoice. */
        val totals: Int,
        /** The lines of the page's text (for the supplier's kind of goods). */
        val lines: List<String>,
    )

    data class Segment(
        val first: Int,
        val last: Int,
        val supplierVat: String?,
        val supplierName: String?,
        val customerVat: String?,
        val docType: String?,
        val number: String?,
        val date: LocalDate?,
        val totalCents: Long?,
        val problems: Set<Problem>,
        val lines: List<String> = emptyList(),
        /** Opened by a page with the supplier's box (not pages before the first invoice). */
        val opened: Boolean = true,
    ) {
        val pageCount: Int get() = last - first + 1
        val sure: Boolean get() = problems.isEmpty()
        /** TD04 / TD08: a credit note. */
        val creditNote: Boolean get() = docType == "TD04" || docType == "TD08"
    }

    private val SUPPLIER_ROLE = rx("(?i)(cedente\\s*/\\s*prestatore|dati\\s+del\\s+cedente)")
    private val SDI_HEADER = rx("(?i)(fattura\\s+elettronica|dati\\s+relativi\\s+alla\\s+trasmissione)")
    private val IDENTITY = rx("(?i)(tipologia\\s+documento|numero\\s+documento|dati\\s+generali\\s+del\\s+documento|\\bTD\\d{2}\\b)")
    private val DOC_TYPE = rx("\\b(TD\\d{2})\\b")
    private val TOTAL_LABEL = rx("(?i)(totale\\s+documento|importo\\s+totale\\s+documento)")
    /** "Arrotondamento su totale documento": a rounding row, not the total. */
    private val ROUNDING = rx("(?i)arrotond")
    private val CUSTOMER_ROLE = rx("(?i)(cessionario\\s*/\\s*committente|dati\\s+del\\s+cessionario)")

    fun signals(index: Int, lines: List<OcrLine>, ownVat: String?): PageSignals {
        val texts = lines.map { it.text }
        val hasText = texts.sumOf { it.count(Char::isLetterOrDigit) } >= 20
        if (!hasText) return PageSignals(index, false, false, null, null, null, null, null, null, null, 0, texts)
        val height = lines.maxOf { it.bottom }.coerceAtLeast(1)
        // The top of the page: the role box opens an invoice only there (a page that quotes "cedente" lower down
        // does not).
        val top = lines.filter { it.top < height * 0.45 }
        val roleAtTop = top.any { SUPPLIER_ROLE.containsMatchIn(it.text) } || top.any { SDI_HEADER.containsMatchIn(it.text) }
        val identity = texts.any { IDENTITY.containsMatchIn(it) }
        val layout = runCatching { LayoutRows.layout(lines) }.getOrNull()
        val supplier = if (roleAtTop && layout != null) runCatching { Parties.supplier(listOf(layout), ownVat) }.getOrNull() else null
        val text = texts.joinToString("\n")
        val supplierVat = supplier?.vatNumber ?: if (roleAtTop) SellerProfiles.supplierVatNumber(text, ownVat) else null
        // The customer's VAT number: in the parties' area, the one that is not the supplier's.
        val customerVat = if (roleAtTop && texts.any { CUSTOMER_ROLE.containsMatchIn(it) })
            SellerProfiles.vatNumbers(top.joinToString("\n") { it.text }).firstOrNull { it != supplierVat } else null
        val grid = layout?.let { runCatching { HeaderGrid.read(listOf(it)) }.getOrNull() }
        val totals = texts.count { TOTAL_LABEL.containsMatchIn(it) && !ROUNDING.containsMatchIn(it) }
        return PageSignals(
            index = index,
            hasText = true,
            start = roleAtTop && identity,
            supplierVat = supplierVat,
            supplierName = supplier?.name?.value,
            customerVat = customerVat,
            docType = texts.firstNotNullOfOrNull { DOC_TYPE.find(it)?.groupValues?.get(1) },
            number = grid?.number,
            date = grid?.date,
            totalCents = if (totals > 0) grid?.total else null,
            totals = totals,
            // The page's rows (a product's words and numbers on one row), for the kind of goods it sells.
            lines = layout?.rows?.map { row -> row.joinToString(" ") { it.text } } ?: texts,
        )
    }

    fun segments(pages: List<PageSignals>): List<Segment> {
        class Open(val first: Int, val start: PageSignals?) {
            var last = first
            val seen = mutableListOf<PageSignals>()
        }
        val out = mutableListOf<Segment>()
        var cur: Open? = null
        fun close() {
            val c = cur ?: return
            val pagesIn = c.seen
            val s = c.start
            val vat = s?.supplierVat ?: pagesIn.firstNotNullOfOrNull { it.supplierVat }
            val name = s?.supplierName ?: pagesIn.firstNotNullOfOrNull { it.supplierName }
            val number = s?.number ?: pagesIn.firstNotNullOfOrNull { it.number }
            val totals = pagesIn.sumOf { it.totals }
            val numbers = pagesIn.mapNotNull { it.number }.distinct()
            val problems = buildSet {
                if (totals == 0) add(Problem.NO_TOTAL)
                if (totals >= 2 || numbers.size >= 2) add(Problem.SEVERAL_DOCUMENTS)
                if (vat == null && name == null) add(Problem.NO_SUPPLIER)
                if (number == null) add(Problem.NO_NUMBER)
                if (pagesIn.any { !it.hasText }) add(Problem.NO_TEXT)
            }
            out += Segment(
                first = c.first, last = c.last, supplierVat = vat, supplierName = name,
                customerVat = s?.customerVat ?: pagesIn.firstNotNullOfOrNull { it.customerVat },
                docType = s?.docType ?: pagesIn.firstNotNullOfOrNull { it.docType },
                number = number, date = s?.date ?: pagesIn.firstNotNullOfOrNull { it.date },
                totalCents = pagesIn.lastOrNull { it.totalCents != null }?.totalCents,
                problems = problems, lines = pagesIn.flatMap { it.lines }, opened = s != null,
            )
            cur = null
        }
        for (p in pages) {
            val c = cur
            val newInvoice = p.start && (
                c == null || c.start == null ||
                    differ(c.start.supplierVat, p.supplierVat) ||
                    differ(c.start.number, p.number) ||
                    // The invoice before was already closed by its total, and this is not the same one printed again.
                    (c.seen.any { it.totals > 0 } && !(same(c.start.supplierVat, p.supplierVat) && same(c.start.number, p.number)))
                )
            if (newInvoice || c == null) {
                close()
                cur = Open(p.index, if (p.start) p else null)
            }
            cur!!.last = p.index
            cur!!.seen += p
        }
        close()
        return out
    }

    /** Two or more invoices that each start with the supplier's box. */
    fun isBundle(segments: List<Segment>): Boolean = segments.count { it.opened } >= 2

    fun find(pages: List<List<OcrLine>>, ownVat: String?): List<Segment> = segments(pages.mapIndexed { i, p -> signals(i, p, ownVat) })

    private fun differ(a: String?, b: String?) = a != null && b != null && a != b
    private fun same(a: String?, b: String?) = a != null && a == b
}
