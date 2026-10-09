package com.kitchenreceipts.core

import java.time.LocalDate

/**
 * Values printed under their headings, the way many invoicing programs lay out the foot and the head of a document:
 *
 *     TOTALE IMPONIBILE   TOTALE I.V.A.   TOTALE NON SOGGETTO   TOTALE DOCUMENTO
 *          231,89            13,77                                   € 245,66
 *
 * Read as text, the row of amounts has no labels, and the plain "Totale" rules pick the wrong one (a bank charge
 * of 15,00 as the total). Here each value goes to the heading whose column it sits in: a column runs from its
 * heading's left edge to the next heading's. Totals are used only when they add up (taxable + VAT = total).
 */
object HeaderGrid {

    data class Grid(
        val subtotal: Long? = null,
        val vat: Long? = null,
        val total: Long? = null,
        /** "Totale importi / merce": the lines' sum before charges. */
        val goods: Long? = null,
        /** Charges printed in the foot, not as lines ("Spese bancarie 15,00"). */
        val fees: List<Pair<String, Long>> = emptyList(),
        val date: LocalDate? = null,
        /** The document number under "Numero documento" / "N. fattura". */
        val number: String? = null,
    ) {
        /** Taxable + VAT = total (to the cent or two). */
        val consistent: Boolean get() = subtotal != null && vat != null && total != null && kotlin.math.abs(subtotal + vat - total) <= 2
    }

    private enum class K { SUB, VAT, TOTAL, GOODS, FEE, DATE, NUMBER, OTHER }

    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(rx("\\p{M}+"), "").uppercase().replace(rx("(?<=\\b[A-Z])\\.(?=[A-Z]\\b)"), "").replace(".", "").replace(rx("\\s+"), " ").trim()

    private val SUB = rx("^(TOTALE )?(IMPONIBILE|NETTO|TOTALE NETTO)( TOTALE| EURO| €)?$")
    private val VAT = rx("^(TOTALE )?(IVA|LVA|1VA|IMPOSTA|IMPOSTE)( TOTALE| EURO| €)?$")
    private val TOTAL = rx("^(TOTALE( (DOCUMENTO|FATTURA|DA PAGARE|A PAGARE|GENERALE|EURO|€))?|NETTO A PAGARE|TOTALE DOC[A-Z]*)$")
    private val GOODS = rx("^(TOTALE |TOT )?(IMPORTI|IMPORTO MERCE|MERCE|MERCI|RIGHE|PRODOTTI)$")
    private val FEE = rx("^(SPESE( [A-Z]+){0,2}|TRASPORTO|SPESE TRASPORTO|BOLLI?|IMBALLO|SPESE INCASSO)$")
    private val DATE = rx("^(DATA( (DOCUMENTO|DOC|FATTURA|EMISSIONE|DEL DOCUMENTO))?)$")
    private val NUMBER = rx("^(NUMERO|NUM|N|NR|NO)( (DOCUMENTO|DOC|FATTURA|FATT|DDT))$|^NUMERO$")
    private val VAT_SUMMARY = rx("\\b(ALIQ|ALIQUOTA|COD IVA|CODICE IVA)\\b")
    private val MONEY = rx("^(?:€|E|EUR|EURO)?\\s*(-?\\d{1,3}(?:\\.\\d{3})*,\\s?\\d{2})\\s*(?:€|EUR)?$")

    private fun kind(text: String): K? {
        val t = norm(text).replace(",", " ").replace(":", " ").replace(rx("\\s+"), " ").trim().replace(rx("^TOT "), "TOTALE ")
            // "TOTALELVA": the space lost after TOTALE.
            .replace(rx("^TOTALE(?=[A-Z])"), "TOTALE ")
        if (t.isEmpty()) return null
        // A label with digits ("Art. 73", a misread "%IVA" as "6IVA") is still a label, of nothing the grid uses.
        if (t.any(Char::isDigit)) return if (money(text) == null && ItalianDates.parse(text.trim()) == null && t.count(Char::isLetter) >= 2) K.OTHER else null
        // A short word ("ar", a misread "Arr.") is a label of nothing the grid uses; a lone sign is nothing.
        if (t.count(Char::isLetter) < 3) return if (t.count(Char::isLetter) >= 1 && money(text) == null) K.OTHER else null
        return when {
            NUMBER.matches(t) -> K.NUMBER
            TOTAL.matches(t) -> K.TOTAL
            SUB.matches(t) -> K.SUB
            VAT.matches(t) -> K.VAT
            GOODS.matches(t) -> K.GOODS
            DATE.matches(t) -> K.DATE
            FEE.matches(t) -> K.FEE
            else -> K.OTHER
        }
    }

    private fun money(text: String): Long? {
        // A currency sign the OCR could not read ("? 245,66") is no part of the amount.
        val cleaned = text.trim().trim('|', '¦').trim().replace(rx("^[^\\p{L}\\p{N}€-]+\\s*(?=\\d)"), "")
        val m = MONEY.matchEntire(cleaned.uppercase()) ?: return null
        return ItalianNumbers.parseCents(m.groupValues[1].replace(" ", ""))
    }

    private class Label(val left: Int, val right: Int, val text: String, val row: Int)

    /** A row that is only labels: no amount, no date. */
    private fun labelsOnly(row: List<OcrLine>) = row.isNotEmpty() && row.all { c -> kind(c.text) != null }

    /**
     * Labels printed on two or three lines ("Numero / documento", "Prezzo / unitario"): words one above the other
     * are one label. Words side by side on one row stay apart.
     */
    private fun stack(rows: List<List<OcrLine>>): List<Label> {
        val labels = mutableListOf<Label>()
        for ((r, row) in rows.withIndex()) for (c in row) {
            val i = labels.indexOfFirst { l -> l.row < r && c.right > l.left - 8 && c.left < l.right + 8 }
            if (i >= 0) {
                val l = labels[i]
                labels[i] = Label(minOf(l.left, c.left), maxOf(l.right, c.right), l.text + " " + c.text.trim(), r)
            } else {
                labels += Label(c.left, c.right, c.text.trim(), r)
            }
        }
        return labels.sortedBy { it.left }
    }

    fun read(pages: List<LayoutRows.Layout>): Grid {
        var g = Grid()
        for (layout in pages) {
            val rows = layout.rows.map { r -> r.filter { it.text.isNotBlank() }.sortedBy { it.left } }
            for (v in 1 until rows.size) {
                val values = rows[v]
                if (values.none { money(it.text) != null || ItalianDates.parse(it.text.trim()) != null }) continue
                if (values.any { it.text.contains('%') && money(it.text.replace("%", "")) != null }) continue
                // The labels right above (one row), or printed over two or three rows ("Numero / documento"):
                // the closest reading first, so a value is taken under its own label.
                for (n in 1..3) {
                    val from = v - n
                    if (from < 0 || !labelsOnly(rows[from])) break
                    g = readValues(g, stack(rows.subList(from, v)), values)
                }
            }
        }
        return g
    }

    private fun readValues(start: Grid, labels: List<Label>, values: List<OcrLine>): Grid {
        var g = start
        val kinds = labels.map { kind(it.text) }
        if (kinds.size < 2 || kinds.none { it != K.OTHER && it != null }) return g
        val summary = labels.any { VAT_SUMMARY.containsMatchIn(norm(it.text)) }
        // A VAT summary row (COD.IVA IMPONIBILE ALIQ. IMPOSTA) may end with the document's totals
        // (TOT. IMPONIBILE, TOT. DOCUMENTO): only those, the rest is per VAT rate.
        fun kindAt(j: Int) = kinds[j]?.let { k -> if (summary && k != K.OTHER && !norm(labels[j].text).startsWith("TOT")) K.OTHER else k }
        val placed = values.mapNotNull { v ->
            val col = labels.indices.lastOrNull { j -> labels[j].left - 8 <= v.centerX } ?: return@mapNotNull null
            col to v
        }
        // A row of values: amounts, dates, codes; not a row of descriptions (an item, an address) under the labels used.
        if (placed.any { (col, v) -> kindAt(col).let { it != null && it != K.OTHER } && v.text.split(' ').count { w -> w.count(Char::isLetter) >= 3 } >= 2 }) return g
        val taken = HashSet<Int>()
        for ((col, v) in placed) {
            if (!taken.add(col)) continue
            val k = kindAt(col) ?: continue
            val cents = money(v.text)
            when (k) {
                K.SUB -> if (g.subtotal == null && cents != null) g = g.copy(subtotal = cents)
                K.VAT -> if (g.vat == null && cents != null) g = g.copy(vat = cents)
                K.TOTAL -> if (g.total == null && cents != null) g = g.copy(total = cents)
                K.GOODS -> if (g.goods == null && cents != null) g = g.copy(goods = cents)
                K.FEE -> if (cents != null && cents != 0L && g.fees.none { it.first == labels[col].text.trim() }) g = g.copy(fees = g.fees + (labels[col].text.trim() to cents))
                K.DATE -> if (g.date == null) ItalianDates.parse(v.text.trim())?.let { g = g.copy(date = it) }
                K.NUMBER -> {
                    val t = v.text.trim().trim('|', ':', '.')
                    if (g.number == null && t.any(Char::isDigit) && t.length <= 20 && cents == null && ItalianDates.parse(t) == null) g = g.copy(number = t)
                }
                K.OTHER -> {}
            }
        }
        return g
    }

    /**
     * Uses what the grid proves: totals that add up replace the text reading's; charges printed in the foot become
     * charge lines when, with them, the lines add up to the taxable amount; the heading's document date wins over a
     * date found elsewhere (a law's date in the small print).
     */
    fun apply(doc: ParsedDocument, g: Grid, today: LocalDate?): ParsedDocument {
        var d = doc
        if (g.consistent) {
            val src = "under the headings"
            d = d.copy(
                subtotalCents = Extracted(g.subtotal!!, Confidence.HIGH, src),
                vatCents = Extracted(g.vat!!, Confidence.HIGH, src),
                totalCents = Extracted(g.total!!, Confidence.HIGH, src),
                warnings = d.warnings - ParseWarning.TOTALS_INCONSISTENT - ParseWarning.MULTIPLE_TOTALS,
            )
        }
        val sub = d.subtotalCents?.value
        val items = d.lineItems
        val sums = items.mapNotNull { it.lineTotalCents?.value }
        if (sub != null && g.fees.isNotEmpty() && sums.size == items.size && items.isNotEmpty()) {
            val lines = sums.sum()
            val feeSum = g.fees.sumOf { it.second }
            val tolerance = maxOf(2L, items.size.toLong())
            val already = items.any { it.adjustment && g.fees.any { f -> f.second == it.lineTotalCents?.value } }
            if (!already && kotlin.math.abs(lines + feeSum - sub) <= tolerance && kotlin.math.abs(lines - sub) > tolerance) {
                val added = g.fees.map { (label, cents) ->
                    ParsedLineItem(
                        originalDescription = java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFD).replace(rx("\\p{M}+"), "")
                            .lowercase().replaceFirstChar { it.titlecase() },
                        quantity = null, unit = null, unitPrice = null,
                        lineTotalCents = Extracted(cents, Confidence.HIGH, "$label (foot of the document)"),
                        vatRatePercent = null, lotNumber = null, expiryDate = null, adjustment = true,
                    )
                }
                d = d.copy(lineItems = items + added)
            }
        }
        val newSums = d.lineItems.mapNotNull { it.lineTotalCents?.value }
        if (ParseWarning.ITEMS_SUM_MISMATCH in d.warnings && newSums.size == d.lineItems.size) {
            val s = newSums.sum()
            val tolerance = maxOf(2L, d.lineItems.size.toLong())
            val ok = listOfNotNull(d.subtotalCents?.value, d.totalCents?.value, g.goods).any { kotlin.math.abs(s - it) <= tolerance } ||
                (g.goods != null && kotlin.math.abs(newSums.filterIndexed { i, _ -> !(d.lineItems[i].adjustment && d.lineItems[i].lineTotalCents?.source?.contains("foot") == true) }.sum() - g.goods) <= tolerance)
            if (ok) d = d.copy(warnings = d.warnings - ParseWarning.ITEMS_SUM_MISMATCH)
        }
        // The number under its heading wins over a number found next to other words ("... lett. 177").
        g.number?.let { n ->
            val cur = d.documentNumber
            d = d.copy(documentNumber = if (cur != null && cur.value == n && cur.confidence == Confidence.HIGH) cur.copy(source = cur.source + " (number under its heading)")
                else Extracted(n, Confidence.HIGH, "number under its heading"))
        }
        val gd = g.date
        if (gd != null && (today == null || (!gd.isAfter(today.plusDays(1)) && !gd.isBefore(today.minusYears(2))))) {
            val cur = d.documentDate
            d = d.copy(documentDate = if (cur != null && cur.value == gd && cur.confidence == Confidence.HIGH) cur.copy(source = cur.source + " (DATA under its heading)")
                else Extracted(gd, Confidence.HIGH, "DATA under its heading"))
        }
        return d
    }
}
