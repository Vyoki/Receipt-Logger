package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/** One purchase of a product, as needed to follow its price. */
data class PricePoint(
    val productId: Long,
    val productName: String,
    val documentId: Long,
    val lineItemId: Long,
    val date: LocalDate?,
    val sellerName: String,
    val quantity: BigDecimal?,
    val unit: String?,
    val unitPrice: BigDecimal?,
    val lineTotalCents: Long?,
    val vatBasis: VatBasis,
)

data class PriceChange(
    val productId: Long,
    val productName: String,
    /** The unit both prices refer to (kg and l for weights and volumes, otherwise the printed unit). */
    val unit: String,
    val vatBasis: VatBasis,
    val oldPrice: BigDecimal,
    val newPrice: BigDecimal,
    /** (new - old) / old x 100, one decimal. */
    val percent: BigDecimal,
    val oldDate: LocalDate?,
    val oldSeller: String,
    val oldDocumentId: Long,
    val newDate: LocalDate?,
    val newSeller: String,
    val newDocumentId: Long,
) {
    val sameSeller: Boolean get() = DuplicateDetector.normalizeSeller(oldSeller) == DuplicateDetector.normalizeSeller(newSeller)
    val isIncrease: Boolean get() = newPrice > oldPrice
}

/**
 * Detects price changes: each purchase is compared with the previous purchase of the same product
 * in a comparable unit and with the same VAT basis (VAT-inclusive and VAT-exclusive prices are never
 * compared). The previous purchase from the same supplier is preferred; otherwise the most recent
 * from anyone, and the change says so.
 *
 * The price paid per unit is the line total divided by the quantity (so line discounts count);
 * if either is missing, the printed unit price. 1.000 g at 9,00 and 1 kg at 10,00 are compared per kg.
 */
object PriceWatch {

    private val MC = MathContext.DECIMAL64

    /** Minimum change reported, in percent (smaller differences are rounding or scale noise). */
    val DEFAULT_THRESHOLD: BigDecimal = BigDecimal("1.0")

    /** (unit, price per that unit) or null when the purchase has no usable price. */
    fun unitCost(p: PricePoint): Pair<String, BigDecimal>? {
        val unit = Units.normalize(p.unit) ?: return null
        val paid = if (p.lineTotalCents != null && p.quantity != null && p.quantity.signum() > 0) {
            ItalianNumbers.centsToDecimal(p.lineTotalCents).divide(p.quantity, MC)
        } else {
            p.unitPrice
        } ?: return null
        if (paid.signum() <= 0) return null
        val toBase = Units.factorToBase(unit)
        return if (toBase != null) {
            val dim = Units.dimension(unit)!!
            Units.baseUnit(dim) to paid.divide(toBase, MC)
        } else {
            unit to paid
        }
    }

    /** The change of [new] against [history] (which may include [new] itself; it is skipped), or null. */
    fun compare(new: PricePoint, history: List<PricePoint>, threshold: BigDecimal = DEFAULT_THRESHOLD): PriceChange? {
        val (unit, newPrice) = unitCost(new) ?: return null
        val earlier = history.filter { h ->
            h.productId == new.productId && h.documentId != new.documentId && h.vatBasis == new.vatBasis &&
                isBefore(h, new) &&
                // An unknown VAT basis is only comparable with the same supplier's documents.
                (new.vatBasis != VatBasis.UNKNOWN || sameSeller(h, new)) &&
                unitCost(h)?.first == unit
        }
        if (earlier.isEmpty()) return null
        val latest = compareBy<PricePoint>({ it.date ?: LocalDate.MIN }, { it.documentId }, { it.lineItemId })
        val previous = earlier.filter { sameSeller(it, new) }.maxWithOrNull(latest) ?: earlier.maxWith(latest)
        val oldPrice = unitCost(previous)!!.second
        val percent = newPrice.subtract(oldPrice).multiply(BigDecimal(100)).divide(oldPrice, 1, RoundingMode.HALF_UP)
        if (percent.abs() < threshold) return null
        if (newPrice.subtract(oldPrice).abs() < BigDecimal("0.005")) return null
        return PriceChange(
            productId = new.productId, productName = new.productName, unit = unit, vatBasis = new.vatBasis,
            oldPrice = oldPrice.setScale(4, RoundingMode.HALF_EVEN).stripTrailingZerosKeep2(),
            newPrice = newPrice.setScale(4, RoundingMode.HALF_EVEN).stripTrailingZerosKeep2(),
            percent = percent,
            oldDate = previous.date, oldSeller = previous.sellerName, oldDocumentId = previous.documentId,
            newDate = new.date, newSeller = new.sellerName, newDocumentId = new.documentId,
        )
    }

    /** Every price change in a purchase history, most recent first. */
    fun history(points: List<PricePoint>, threshold: BigDecimal = DEFAULT_THRESHOLD): List<PriceChange> {
        val byProduct = points.groupBy { it.productId }
        return points.mapNotNull { compare(it, byProduct.getValue(it.productId), threshold) }
            .sortedWith(compareByDescending<PriceChange> { it.newDate ?: LocalDate.MIN }.thenByDescending { it.newDocumentId })
    }

    private fun isBefore(h: PricePoint, new: PricePoint): Boolean {
        val a = h.date
        val b = new.date
        return when {
            a != null && b != null && a != b -> a < b
            else -> h.documentId < new.documentId // same day or undated: order of saving
        }
    }

    private fun sameSeller(a: PricePoint, b: PricePoint) =
        DuplicateDetector.normalizeSeller(a.sellerName) == DuplicateDetector.normalizeSeller(b.sellerName)

    private fun BigDecimal.stripTrailingZerosKeep2(): BigDecimal {
        val s = stripTrailingZeros()
        return if (s.scale() < 2) s.setScale(2) else s
    }
}
