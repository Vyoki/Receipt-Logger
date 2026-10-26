package com.kitchenreceipts.core

import java.time.LocalDate

/**
 * The forms an Italian business issues itself for a purchase on which it owes the VAT (reverse charge,
 * "integrazione" / "autofattura"): TD16 (from an Italian supplier, e.g. cleaning of buildings), TD17 (services from
 * abroad), TD18 (goods from another EU country), TD19 (goods under art. 17 c.2). The VAT printed on them is never paid
 * to the supplier: the buyer self-assesses it and deducts it again. What was bought costs the taxable amount, so the
 * form is logged net of VAT: VAT 0, total = taxable. Its lines are amounts only (the products are on the supplier's
 * own invoice, the one the form names: "Fatt. coll. 4711 del 21-02-2031"). Saving both is one purchase twice, so each
 * is a duplicate of the other (see [DuplicateDetector]).
 */
object ReverseCharge {

    val TYPES = setOf("TD16", "TD17", "TD18", "TD19")

    /** [printedVatCents]: the VAT printed (self-assessed); [refNumber] / [refDate]: the supplier's invoice it completes. */
    data class Form(val type: String, val printedVatCents: Long? = null, val refNumber: String? = null, val refDate: LocalDate? = null)

    /** "TD18 integrazione per acquisto di beni intracomunitari", "TD17 integrazione/autofattura per acquisto servizi". */
    private val TYPE = rx("(?i)(?<![A-Z0-9])(TD1[6-9])(?![0-9])\\s*[-:)]?\\s*(?:integraz|autofatt|reverse\\s+charge)")

    /**
     * The invoice the form completes: "Fatt.Coll. 4711 del 21-02-2031" (the e-invoice stylesheet), "Fattura collegata
     * n. 4711 del 21/02/2031".
     */
    private val LINKED = rx(
        "(?i)\\b(?:fatt(?:ura)?\\.?\\s*coll(?:egata)?\\.?|fatture\\s+collegate)\\s*:?\\s*(?:n(?:r|um)?\\.?\\s*)?" +
            "([A-Za-z0-9][A-Za-z0-9/_.\\-]{0,29}?)\\s*(?:del|data)?\\s*:?\\s*(\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4})?(?:\\s|$)",
    )

    /** The form printed in [text], or null when the text is no reverse-charge form. */
    fun fromText(text: String): Form? {
        val type = TYPE.find(text)?.groupValues?.get(1)?.uppercase() ?: return null
        val linked = LINKED.find(text)
        val number = linked?.groupValues?.get(1)?.trimEnd('.', '-', '/')?.takeIf { n -> n.any(Char::isDigit) }
        val date = linked?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.let { ItalianDates.parse(it) }
        return Form(type, refNumber = number, refDate = date?.takeIf { number != null })
    }

    /**
     * Logs the form net of VAT: VAT 0 (sure: the printed type says it is self-assessed), the total the taxable amount
     * (as sure as that was read), the lines amounts only, and a warning that tells the operator why.
     */
    fun apply(d: ParsedDocument, form: Form): ParsedDocument {
        val printedVat = form.printedVatCents ?: d.vatCents?.value
        val vat = printedVat?.let { " " + ItalianNumbers.formatCents(it) } ?: ""
        return net(d, "$PREFIX (${form.type}): VAT$vat self-assessed, not paid to the supplier")
    }

    /**
     * A form already logged net and read again (the AI's double-check, a second pass): it stays net, whatever that
     * pass read for the VAT, the total or the lines.
     */
    fun keepNet(d: ParsedDocument): ParsedDocument {
        if (ParseWarning.REVERSE_CHARGE !in d.warnings) return d
        val src = listOfNotNull(d.vatCents?.source, d.vatBasis?.source).firstOrNull { it.startsWith(PREFIX) } ?: PREFIX
        return net(d, src)
    }

    private const val PREFIX = "reverse charge"

    private fun net(d: ParsedDocument, src: String): ParsedDocument {
        val sub = d.subtotalCents
        val lines = d.lineItems.map { l ->
            // What was bought is on the supplier's own invoice: here only what it cost.
            l.copy(quantity = null, unit = null, unitPrice = null, discount = null, adjustment = true, lotNumber = null, expiryDate = null)
        }
        val sums = lines.mapNotNull { it.lineTotalCents?.value }
        val linesAddUp = sub != null && lines.isNotEmpty() && sums.size == lines.size && sums.sum() == sub.value
        return d.copy(
            vatCents = Extracted(0L, Confidence.HIGH, src),
            totalCents = sub?.let { Extracted(it.value, it.confidence, "$src; the total is the taxable amount") },
            vatBasis = Extracted(VatBasis.EXCLUSIVE, Confidence.HIGH, src),
            lineItems = lines,
            lotsPrinted = false,
            warnings = d.warnings + ParseWarning.REVERSE_CHARGE - ParseWarning.TOTALS_INCONSISTENT - ParseWarning.MULTIPLE_TOTALS -
                (if (linesAddUp) setOf(ParseWarning.ITEMS_SUM_MISMATCH) else emptySet()),
        )
    }
}
