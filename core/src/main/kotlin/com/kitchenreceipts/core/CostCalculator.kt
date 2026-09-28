package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/** One purchase of a product, taken from a saved line item. */
data class PurchaseRecord(
    val lineItemId: Long,
    val documentId: Long,
    val date: LocalDate?,
    val sellerName: String?,
    val quantity: BigDecimal?,
    val unit: String?,
    val unitPrice: BigDecimal? = null,
    val lineTotalCents: Long?,
    val vatBasis: VatBasis,
    val lotNumber: String? = null,
)

/** User-defined conversion: 1 [fromUnit] = [factor] [toUnit] (e.g. 1 conf = 6 pz). */
data class UnitConversion(val fromUnit: String, val toUnit: String, val factor: BigDecimal) {
    init {
        require(factor.signum() > 0) { "Conversion factor must be positive" }
        require(fromUnit != toUnit) { "Conversion must be between two different units" }
    }
}

data class AverageCost(
    val unit: String,
    val vatBasis: VatBasis,
    val totalCents: Long,
    val totalQuantity: BigDecimal,
    /** totalCents / 100 / totalQuantity, 4 decimals, half-even. */
    val averageUnitCost: BigDecimal,
    /** How many purchases contribute to this average. */
    val purchaseCount: Int,
    /** Units that were converted into [unit] to build this average. */
    val sourceUnits: Set<String>,
)

enum class ExclusionReason { MISSING_QUANTITY, MISSING_TOTAL, MISSING_UNIT, ZERO_QUANTITY }

data class CostSummary(
    val averages: List<AverageCost>,
    val excluded: List<Pair<PurchaseRecord, ExclusionReason>>,
)

/**
 * Weighted average unit cost = total cost / total quantity.
 *
 * - Money is summed in integer cents; quantities in BigDecimal. No floating point.
 * - Purchases are grouped by (unit family, VAT basis). VAT-inclusive, VAT-exclusive and
 *   unknown-basis purchases are always separate averages.
 * - Units are combined only when they are the same physical dimension (g -> kg, ml -> l)
 *   or connected by a user-defined [UnitConversion]. kg and pz, or pz and conf, are never
 *   combined otherwise.
 * - Purchases without quantity, unit or line total are excluded and reported, never guessed.
 */
object CostCalculator {

    private val MC = MathContext.DECIMAL128

    fun summarize(purchases: List<PurchaseRecord>, conversions: List<UnitConversion> = emptyList()): CostSummary {
        val excluded = mutableListOf<Pair<PurchaseRecord, ExclusionReason>>()
        val usable = mutableListOf<PurchaseRecord>()
        for (p in purchases) {
            val reason = when {
                p.lineTotalCents == null -> ExclusionReason.MISSING_TOTAL
                p.quantity == null -> ExclusionReason.MISSING_QUANTITY
                p.quantity.signum() == 0 -> ExclusionReason.ZERO_QUANTITY
                p.unit.isNullOrBlank() -> ExclusionReason.MISSING_UNIT
                else -> null
            }
            if (reason != null) excluded += p to reason else usable += p
        }

        val units = usable.map { Units.normalize(it.unit)!! }.toSet()
        val graph = buildGraph(units, conversions)
        val targetOf = chooseTargets(units, graph, usable, conversions.map { Units.normalize(it.toUnit)!! }.toSet())

        data class Acc(var cents: Long = 0, var qty: BigDecimal = BigDecimal.ZERO, var count: Int = 0, val src: MutableSet<String> = mutableSetOf())
        val groups = linkedMapOf<Pair<String, VatBasis>, Acc>()
        for (p in usable) {
            val unit = Units.normalize(p.unit)!!
            val target = targetOf.getValue(unit)
            val factor = factorBetween(unit, target, graph)
                ?: error("No conversion path from $unit to $target") // cannot happen: same component
            val acc = groups.getOrPut(target to p.vatBasis) { Acc() }
            acc.cents += p.lineTotalCents!!
            acc.qty = acc.qty.add(p.quantity!!.multiply(factor, MC), MC)
            acc.count += 1
            acc.src += unit
        }

        val averages = groups.mapNotNull { (key, acc) ->
            if (acc.qty.signum() == 0) return@mapNotNull null
            AverageCost(
                unit = key.first,
                vatBasis = key.second,
                totalCents = acc.cents,
                totalQuantity = acc.qty.stripTrailingZeros(),
                averageUnitCost = ItalianNumbers.centsToDecimal(acc.cents).divide(acc.qty, 4, RoundingMode.HALF_EVEN),
                purchaseCount = acc.count,
                sourceUnits = acc.src,
            )
        }.sortedWith(compareByDescending<AverageCost> { it.purchaseCount }.thenBy { it.unit }.thenBy { it.vatBasis })
        return CostSummary(averages, excluded)
    }

    // unit -> list of (neighbour, factor) where qty_in_neighbour = qty_in_unit * factor
    private fun buildGraph(units: Set<String>, conversions: List<UnitConversion>): Map<String, List<Pair<String, BigDecimal>>> {
        val g = mutableMapOf<String, MutableList<Pair<String, BigDecimal>>>()
        fun edge(a: String, b: String, f: BigDecimal) {
            g.getOrPut(a) { mutableListOf() } += b to f
            g.getOrPut(b) { mutableListOf() } += a to BigDecimal.ONE.divide(f, MC)
        }
        val all = units + conversions.flatMap { listOf(Units.normalize(it.fromUnit)!!, Units.normalize(it.toUnit)!!) }
        for (u in all) {
            g.getOrPut(u) { mutableListOf() }
            val dim = Units.dimension(u) ?: continue
            val base = Units.baseUnit(dim)
            if (u != base) edge(u, base, Units.factorToBase(u)!!)
        }
        for (c in conversions) edge(Units.normalize(c.fromUnit)!!, Units.normalize(c.toUnit)!!, c.factor)
        return g
    }

    private fun factorBetween(from: String, to: String, g: Map<String, List<Pair<String, BigDecimal>>>): BigDecimal? {
        if (from == to) return BigDecimal.ONE
        val seen = mutableSetOf(from)
        val queue = ArrayDeque(listOf(from to BigDecimal.ONE))
        while (queue.isNotEmpty()) {
            val (u, f) = queue.removeFirst()
            for ((v, k) in g[u].orEmpty()) {
                if (!seen.add(v)) continue
                val nf = f.multiply(k, MC)
                if (v == to) return nf
                queue.addLast(v to nf)
            }
        }
        return null
    }

    /** For each connected group of units, report in the base unit if present, else the most used unit. */
    private fun chooseTargets(
        units: Set<String>,
        g: Map<String, List<Pair<String, BigDecimal>>>,
        purchases: List<PurchaseRecord>,
        conversionTargets: Set<String>,
    ): Map<String, String> {
        val usage = purchases.groupingBy { Units.normalize(it.unit)!! }.eachCount()
        val result = mutableMapOf<String, String>()
        for (u in units) {
            if (u in result) continue
            val component = mutableSetOf(u)
            val stack = ArrayDeque(listOf(u))
            while (stack.isNotEmpty()) {
                val x = stack.removeLast()
                for ((y, _) in g[x].orEmpty()) if (component.add(y)) stack.addLast(y)
            }
            val used = component.filter { it in units }
            val target = used.firstOrNull { it == "kg" || it == "l" }
                ?: component.firstOrNull { it == "kg" || it == "l" }?.takeIf { used.any { u2 -> Units.dimension(u2) != null } }
                ?: used.sortedWith(
                    compareByDescending<String> { usage[it] ?: 0 }
                        .thenByDescending { u2 -> conversionTargets.contains(u2) } // "1 conf = 6 pz" -> report per pz
                        .thenBy { it },
                ).first()
            for (c in used) result[c] = target
        }
        return result
    }
}
