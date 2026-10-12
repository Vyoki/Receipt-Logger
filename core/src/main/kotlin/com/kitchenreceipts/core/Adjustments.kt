package com.kitchenreceipts.core

import java.math.BigDecimal

/**
 * Lines that change what is paid but are not goods: discounts ("Sconto inc.", "Sconto del 4%", "Abbuono"),
 * rounding, returns of empties, and charges (transport, bank or collection fees, deposits). They stay on the
 * document so the lines still add up to its total, but they are never a product: no quantity, no price per unit,
 * no product link, no price history.
 */
object Adjustments {

    /** Words that reduce what is paid: the amount is always negative. */
    private val DISCOUNT = rx(
        "(?i)^\\W*(?:\\d{3,}\\s+)?(sconto|sconti|sc\\.(?:\\s|$)|abbuono|arrotondamento|storno|omaggio|premio|bonus|promozione|" +
            "discount|rabatt|remise|descuento|rebate|reso\\s+vuoti|resi\\s+vuoti|vuoti\\s+resi)\\b",
    )

    /** Charges that are not goods. */
    private val CHARGE = rx(
        "(?i)^\\W*(?:\\d{3,}\\s+)?(spese(?:\\s+(?:di|per))?\\s+(?:trasporto|spedizione|incasso|bancarie|banca|varie|accessorie|bollo|consegna)|" +
            "trasporto|spedizione|contributo\\s+(?:trasporto|consegna|spedizione)|bollo|imposta\\s+di\\s+bollo|cauzione|deposito\\s+cauzionale|" +
            "delivery\\s+(?:charge|fee)|shipping|transport|frais\\s+de\\s+port|versandkosten|gastos\\s+de\\s+env[ií]o)\\b",
    )

    /**
     * True for a discount or charge line. A line with a quantity of goods is a product (a "reso" of 2 kg is goods
     * coming back), unless its words say discount and its amount is negative. Goods given free ("OMAGGIO MOZZARELLA
     * 2 pz 0,00", "SCONTO MERCE PASSATA 6 pz") are goods at price 0: they come in, with their lot and expiry.
     */
    fun isAdjustment(description: String, amountCents: Long?, quantity: BigDecimal?): Boolean {
        val d = description.trim()
        if (d.isEmpty()) return false
        if (DISCOUNT.containsMatchIn(d)) {
            if (isPercentOnly(d)) return true
            if (isFreeGoods(d, amountCents, quantity)) return false
            return quantity == null || amountCents == null || amountCents <= 0
        }
        if (CHARGE.containsMatchIn(d)) return true
        return false
    }

    /** "Sconto del 4%", "SC. 10%": the number on the line is a percentage, not a quantity. */
    private fun isPercentOnly(d: String) = d.contains('%')

    /** A quantity of something named after the discount word, at no charge. */
    private fun isFreeGoods(d: String, amountCents: Long?, quantity: BigDecimal?): Boolean {
        if (quantity == null || quantity.signum() <= 0) return false
        if (amountCents != null && amountCents != 0L) return false
        val rest = DISCOUNT.replace(d, " ")
        return rest.split(rx("[^\\p{L}]+")).count { w -> w.length >= 3 && w.lowercase() !in NOT_GOODS } >= 1
    }

    private val NOT_GOODS = setOf("merce", "merci", "del", "per", "sul", "sulla", "fattura", "incondizionato", "inc", "cassa", "pagamento", "the", "for", "goods")

    fun isDiscount(description: String): Boolean = DISCOUNT.containsMatchIn(description.trim())

    /**
     * Marks the adjustment lines of a reading: quantity, unit and unit price are dropped (they are a percentage or
     * a repeated amount, not goods), and a discount's amount is negative even when the minus sign was not read.
     */
    fun mark(items: List<ParsedLineItem>): List<ParsedLineItem> = items.map { it ->
        if (it.adjustment || !isAdjustment(it.originalDescription, it.lineTotalCents?.value, it.quantity?.value)) return@map it
        val total = it.lineTotalCents?.let { t ->
            if (isDiscount(it.originalDescription) && t.value > 0) t.copy(value = -t.value, confidence = Confidence.LOW) else t
        }
        it.copy(
            quantity = null, unit = null, unitPrice = null, lineTotalCents = total, packSize = null, packages = null, discount = null,
            // A lot or expiry printed on it is kept as read (nothing is thrown away; the operator sees it).
            choices = emptyList(), adjustment = true,
            warnings = it.warnings - ParseWarning.LINE_TOTAL_MISMATCH,
        )
    }
}
