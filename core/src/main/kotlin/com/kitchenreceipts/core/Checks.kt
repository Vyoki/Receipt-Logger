package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A price agreed with a supplier for a product (from its price list), per [unit], with or without VAT. */
data class AgreedPrice(
    val id: Long,
    val productId: Long,
    /** null = any supplier. */
    val sellerId: Long?,
    val unit: String,
    val price: BigDecimal,
    val vatBasis: VatBasis,
)

/** A purchase line with what is needed to check it against the agreed price. */
data class CheckedLine(
    val lineItemId: Long,
    val documentId: Long,
    val sellerId: Long,
    val productId: Long?,
    val productName: String?,
    val description: String,
    val date: LocalDate?,
    val quantity: BigDecimal?,
    val unit: String?,
    val unitPrice: BigDecimal?,
    val lineTotalCents: Long?,
    val vatBasis: VatBasis,
)

data class OverCharge(
    val line: CheckedLine,
    val agreed: AgreedPrice,
    /** In the agreed price's unit (kg and l stay kg and l). */
    val paid: BigDecimal,
    val percent: BigDecimal,
    /** (paid - agreed) x quantity, cents; null when the quantity is not in a comparable unit. */
    val extraCents: Long?,
) {
    fun key() = "agreed:${line.lineItemId}"
}

/** Purchases charged above the price agreed with the supplier. */
object AgreedPrices {
    private val MC = MathContext.DECIMAL64
    /** Below this, a difference is rounding (half a percent). */
    val TOLERANCE: BigDecimal = BigDecimal("0.5")

    /** The agreed price that applies to [line]: the supplier's own first, then one for any supplier. */
    fun applicable(line: CheckedLine, prices: List<AgreedPrice>): AgreedPrice? {
        val pid = line.productId ?: return null
        val forProduct = prices.filter { it.productId == pid && it.vatBasis == line.vatBasis }
        return forProduct.firstOrNull { it.sellerId == line.sellerId } ?: forProduct.firstOrNull { it.sellerId == null }
    }

    /** [q] of [from] expressed in [to], or null when they don't convert (kg/g, l/ml, or a conversion the operator set). */
    fun convert(q: BigDecimal, from: String?, to: String, productId: Long?, conversions: (Long) -> List<UnitConversion>): BigDecimal? {
        val u = Units.normalize(from) ?: return null
        val t = Units.normalize(to) ?: return null
        if (u == t) return q
        val fu = Units.factorToBase(u)
        val ft = Units.factorToBase(t)
        if (fu != null && ft != null && Units.dimension(u) == Units.dimension(t)) return q.multiply(fu).divide(ft, MC)
        val conv = productId?.let(conversions).orEmpty()
        conv.firstOrNull { Units.normalize(it.fromUnit) == u && Units.normalize(it.toUnit) == t }?.let { return q.multiply(it.factor, MC) }
        conv.firstOrNull { Units.normalize(it.fromUnit) == t && Units.normalize(it.toUnit) == u }?.let { if (it.factor.signum() > 0) return q.divide(it.factor, MC) }
        return null
    }

    fun overCharges(lines: List<CheckedLine>, prices: List<AgreedPrice>, conversions: (Long) -> List<UnitConversion> = { emptyList() }): List<OverCharge> {
        if (prices.isEmpty()) return emptyList()
        return lines.mapNotNull { l ->
            val a = applicable(l, prices) ?: return@mapNotNull null
            if (a.price.signum() <= 0) return@mapNotNull null
            val q = l.quantity?.takeIf { it.signum() > 0 }
            val qIn = q?.let { convert(it, l.unit, a.unit, l.productId, conversions) }
            // What was paid per agreed unit: the line total over the quantity (discounts count), else the printed price.
            val paid = when {
                qIn != null && l.lineTotalCents != null -> ItalianNumbers.centsToDecimal(l.lineTotalCents).divide(qIn, MC)
                l.unitPrice != null -> convert(BigDecimal.ONE, a.unit, l.unit ?: return@mapNotNull null, l.productId, conversions)?.let { perAgreed -> l.unitPrice.multiply(perAgreed, MC) }
                else -> null
            } ?: return@mapNotNull null
            if (paid.signum() <= 0) return@mapNotNull null
            val pct = paid.subtract(a.price).divide(a.price, MC).movePointRight(2).setScale(1, RoundingMode.HALF_UP)
            if (pct <= TOLERANCE) return@mapNotNull null
            val extra = qIn?.let { paid.subtract(a.price).multiply(it).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong() }
            OverCharge(l, a, paid.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros(), pct, extra)
        }.sortedByDescending { it.line.date ?: LocalDate.MIN }
    }
}

/** What arrived with an expiry date, as needed for the expiry list. */
data class ExpiringLine(
    val lineItemId: Long,
    val documentId: Long,
    val productName: String?,
    val description: String,
    val sellerName: String,
    val lot: String?,
    val expiry: LocalDate,
    val quantity: BigDecimal?,
    val unit: String?,
    val received: LocalDate?,
)

object Expiry {
    /** Shown ahead of the date (days). */
    const val AHEAD = 7L
    /** Still shown after the date, until marked used or thrown away (days). */
    const val AFTER = 14L

    /**
     * What expires soon or expired recently and was not marked as used, soonest first. Only expiry dates printed on
     * the documents are known: the app does not know what is still in the fridge, so the operator marks it.
     */
    fun soon(lines: List<ExpiringLine>, today: LocalDate, handled: Set<Long>, ahead: Long = AHEAD): List<ExpiringLine> =
        lines.filter { it.lineItemId !in handled }
            .filter { val d = ChronoUnit.DAYS.between(today, it.expiry); d in -AFTER..ahead }
            .sortedWith(compareBy({ it.expiry }, { it.productName ?: it.description }))

    fun daysLeft(line: ExpiringLine, today: LocalDate): Long = ChronoUnit.DAYS.between(today, line.expiry)
}

/** Lot numbers as people type them and as OCR reads them: case, spaces, dashes and dots do not matter. */
object Lots {
    fun key(lot: String): String = lot.uppercase().filter { it.isLetterOrDigit() }

    /**
     * Whether a saved lot matches what was typed: the same characters, or the typed text inside it
     * (a label may print "L.24/0187" where the invoice has "240187").
     */
    fun matches(saved: String?, typed: String): Boolean {
        if (saved.isNullOrBlank()) return false
        val t = key(typed)
        if (t.length < 2) return false
        val s = key(saved)
        return s == t || (t.length >= 4 && s.contains(t))
    }
}

/**
 * "It's fine" and "Done" marks name the lines they are about by id; saving an edited document writes its lines
 * again with new ids. These keep the marks on the same lines.
 */
object DismissedKeys {

    /** One saved line, to find it again after the document is saved again. */
    data class Line(val id: Long, val description: String, val amountCents: Long?)

    /** Old line id -> new line id, for the lines that are still there (same description and amount, in order). */
    fun match(before: List<Line>, after: List<Line>): Map<Long, Long> {
        val free = after.toMutableList()
        val out = HashMap<Long, Long>()
        for (o in before) {
            val n = free.firstOrNull { it.description == o.description && it.amountCents == o.amountCents } ?: continue
            free.remove(n)
            out[o.id] = n.id
        }
        return out
    }

    /** The key with its line ids moved to the new ones; null when it names none of [ids]. */
    fun remap(key: String, ids: Map<Long, Long>): String? {
        val p = key.split(':').toMutableList()
        val lineSlots = when (p.firstOrNull()) {
            "exp", "agreed" -> listOf(1)
            "ddt" -> listOf(3, 4) // ddt:<invoice>:<kind>:<invoice line>:<delivery line>
            else -> return null
        }
        var changed = false
        for (i in lineSlots) {
            val id = p.getOrNull(i)?.toLongOrNull() ?: continue
            val n = ids[id] ?: continue
            if (n != id) { p[i] = n.toString(); changed = true }
        }
        return if (changed) p.joinToString(":") else null
    }
}
