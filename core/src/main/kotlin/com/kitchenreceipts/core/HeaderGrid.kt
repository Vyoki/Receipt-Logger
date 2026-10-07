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
    ) {
        /** Taxable + VAT = total (to the cent or two). */
        val consistent: Boolean get() = subtotal != null && vat != null && total != null && kotlin.math.abs(subtotal + vat - total) <= 2
    }

    private enum class K { SUB, VAT, TOTAL, GOODS, FEE, DATE, OTHER }

    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(rx("\\p{M}+"), "").uppercase().replace(rx("(?<=\\b[A-Z])\\.(?=[A-Z]\\b)"), "").replace(".", "").replace(rx("\\s+"), " ").trim()

    private val SUB = rx("^(TOTALE )?(IMPONIBILE|NETTO|TOTALE NETTO)( TOTALE| EURO| €)?$")
    private val VAT = rx("^(TOTALE )?(IVA|LVA|1VA|IMPOSTA|IMPOSTE)( TOTALE| EURO| €)?$")
    private val TOTAL = rx("^(TOTALE( (DOCUMENTO|FATTURA|DA PAGARE|A PAGARE|GENERALE|EURO|€))?|NETTO A PAGARE|TOTALE DOC)$")
    private val GOODS = rx("^(TOTALE |TOT )?(IMPORTI|IMPORTO MERCE|MERCE|MERCI|RIGHE|PRODOTTI)$")
    private val FEE = rx("^(SPESE( [A-Z]+){0,2}|TRASPORTO|SPESE TRASPORTO|BOLLI?|IMBALLO|SPESE INCASSO)$")
    private val DATE = rx("^(DATA( (DOCUMENTO|DOC|FATTURA|EMISSIONE|DEL DOCUMENTO))?)$")
    private val VAT_SUMMARY = rx("\\b(ALIQ|ALIQUOTA|COD IVA|CODICE IVA)\\b")
    private val MONEY = rx("^(?:€|E|EUR|EURO)?\\s*(-?\\d{1,3}(?:\\.\\d{3})*,\\s?\\d{2})\\s*(?:€|EUR)?$")

    private fun kind(text: String): K? {
        val t = norm(text)
        if (t.isEmpty() || t.any(Char::isDigit)) return null
        if (t.count(Char::isLetter) < 3) return null
        return when {
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
        val m = MONEY.matchEntire(text.trim().uppercase()) ?: return null
        return ItalianNumbers.parseCents(m.groupValues[1].replace(" ", ""))
    }

    fun read(pages: List<LayoutRows.Layout>): Grid {
        var g = Grid()
        for (layout in pages) {
            val rows = layout.rows.map { r -> r.filter { it.text.isNotBlank() }.sortedBy { it.left } }
            for (i in 0 until rows.size - 1) {
                val labels = rows[i]
                val kinds = labels.map { kind(it.text) }
                if (kinds.any { it == null } || kinds.size < 2) continue
                if (kinds.none { it != K.OTHER } || labels.any { VAT_SUMMARY.containsMatchIn(norm(it.text)) }) continue
                val values = rows[i + 1]
                // A row of values: amounts, dates, codes, a word ("FATTURA"); not a row of descriptions (an item, an address).
                if (values.isEmpty() || values.any { v -> v.text.split(' ').count { w -> w.count(Char::isLetter) >= 3 } >= 2 }) continue
                if (values.none { money(it.text) != null || ItalianDates.parse(it.text.trim()) != null }) continue
                if (values.any { it.text.contains('%') }) continue
                val taken = HashSet<Int>()
                for (v in values) {
                    val x = v.centerX
                    val col = labels.indices.lastOrNull { j -> labels[j].left - 8 <= x } ?: continue
                    if (!taken.add(col)) continue
                    val k = kinds[col] ?: continue
                    val cents = money(v.text)
                    when (k) {
                        K.SUB -> if (g.subtotal == null && cents != null) g = g.copy(subtotal = cents)
                        K.VAT -> if (g.vat == null && cents != null) g = g.copy(vat = cents)
                        K.TOTAL -> if (g.total == null && cents != null) g = g.copy(total = cents)
                        K.GOODS -> if (g.goods == null && cents != null) g = g.copy(goods = cents)
                        K.FEE -> if (cents != null && cents != 0L) g = g.copy(fees = g.fees + (labels[col].text.trim() to cents))
                        K.DATE -> if (g.date == null) ItalianDates.parse(v.text.trim())?.let { g = g.copy(date = it) }
                        K.OTHER -> {}
                    }
                }
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
        val gd = g.date
        if (gd != null && (today == null || (!gd.isAfter(today.plusDays(1)) && !gd.isBefore(today.minusYears(2))))) {
            val cur = d.documentDate
            if (cur == null || cur.value != gd) d = d.copy(documentDate = Extracted(gd, Confidence.HIGH, "DATA under its heading"))
        }
        return d
    }
}
