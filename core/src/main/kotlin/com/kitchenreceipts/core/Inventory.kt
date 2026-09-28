package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

enum class PeriodKind { WEEK, MONTH, QUARTER, YEAR }

/** A calendar period: ISO week (Monday to Sunday), month, quarter or year. */
data class Period(val kind: PeriodKind, val start: LocalDate) {
    val end: LocalDate
        get() = when (kind) {
            PeriodKind.WEEK -> start.plusDays(6)
            PeriodKind.MONTH -> start.with(TemporalAdjusters.lastDayOfMonth())
            PeriodKind.QUARTER -> start.plusMonths(3).minusDays(1)
            PeriodKind.YEAR -> start.withDayOfYear(start.lengthOfYear())
        }

    fun contains(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)

    fun next(): Period = of(end.plusDays(1), kind)
    fun previous(): Period = of(start.minusDays(1), kind)

    /** "Sett. 38 2026", "Settembre 2026", "T3 2026", "2026" (Italian short labels; the app localises its own). */
    fun label(): String = when (kind) {
        PeriodKind.WEEK -> "W${start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} ${start.get(IsoFields.WEEK_BASED_YEAR)}"
        PeriodKind.MONTH -> ItalianDates.formatMonth(java.time.YearMonth.from(start))
        PeriodKind.QUARTER -> "Q${start.get(IsoFields.QUARTER_OF_YEAR)} ${start.year}"
        PeriodKind.YEAR -> "${start.year}"
    }

    companion object {
        fun of(date: LocalDate, kind: PeriodKind): Period = Period(
            kind,
            when (kind) {
                PeriodKind.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                PeriodKind.MONTH -> date.withDayOfMonth(1)
                PeriodKind.QUARTER -> date.withMonth(((date.monthValue - 1) / 3) * 3 + 1).withDayOfMonth(1)
                PeriodKind.YEAR -> date.withDayOfYear(1)
            },
        )
    }
}

/** One purchased line for the inventory. [productId] null = not linked to a product yet. */
data class InventoryPurchase(
    val productId: Long?,
    val name: String,
    val category: Category,
    val date: LocalDate?,
    val quantity: BigDecimal?,
    val unit: String?,
    val lineTotalCents: Long?,
    val vatBasis: VatBasis,
)

data class QuantityTotal(val unit: String, val amount: BigDecimal)
data class SpendTotal(val vatBasis: VatBasis, val cents: Long)

data class InventoryLine(
    val productId: Long?,
    val name: String,
    val category: Category,
    /** Bought in the period, one entry per unit that cannot be converted into another (kg, pz, ct...). */
    val quantities: List<QuantityTotal>,
    /** Spent in the period, kept apart by VAT basis (never added across VAT-inclusive and -exclusive). */
    val spend: List<SpendTotal>,
    val purchaseCount: Int,
    /** Purchases in the period without a quantity or unit (counted, not guessed). */
    val withoutQuantity: Int,
    /** Usual amount per period: average of the previous periods since this product was first bought (max 6). */
    val usual: List<QuantityTotal>,
)

data class CategoryTotal(val category: Category, val spend: List<SpendTotal>, val productCount: Int)

data class InventoryReport(val period: Period, val lines: List<InventoryLine>, val categories: List<CategoryTotal>)

/**
 * The restaurant's inventory of purchases: every product bought, by category, with how much was bought
 * in a week, month, quarter or year, and the usual amount per period.
 * Weights and volumes are added in kg and l; other units are kept apart unless the operator defined a
 * conversion for that product (the same rules as the average cost).
 */
object Inventory {

    private const val USUAL_PERIODS = 6

    fun report(
        purchases: List<InventoryPurchase>,
        period: Period,
        conversions: Map<Long, List<UnitConversion>> = emptyMap(),
    ): InventoryReport {
        val dated = purchases.filter { it.date != null }
        val groups = dated.groupBy { it.productId?.let { id -> "p$id" } ?: ("d" + ProductMatching.aliasKey(it.name)) }
        val lines = groups.mapNotNull { (_, all) ->
            val inPeriod = all.filter { period.contains(it.date!!) }
            if (inPeriod.isEmpty()) return@mapNotNull null
            val first = all.first()
            val conv = first.productId?.let { conversions[it] }.orEmpty()
            val firstDate = all.minOf { it.date!! }
            val previous = generateSequence(period.previous()) { it.previous() }
                .takeWhile { !it.end.isBefore(firstDate) }.take(USUAL_PERIODS).toList()
            val usual = if (previous.isEmpty()) {
                emptyList()
            } else {
                quantities(all.filter { p -> previous.any { it.contains(p.date!!) } }, conv).map {
                    QuantityTotal(it.unit, it.amount.divide(BigDecimal(previous.size), 3, RoundingMode.HALF_EVEN).stripTrailingZeros())
                }
            }
            InventoryLine(
                productId = first.productId,
                name = first.name,
                category = first.category,
                quantities = quantities(inPeriod, conv),
                spend = spend(inPeriod),
                purchaseCount = inPeriod.size,
                withoutQuantity = inPeriod.count { it.quantity == null || it.quantity.signum() == 0 || it.unit.isNullOrBlank() },
                usual = usual,
            )
        }.sortedWith(compareBy<InventoryLine> { it.category.ordinal }.thenBy { it.name.lowercase() })
        val categories = lines.groupBy { it.category }.map { (c, l) ->
            CategoryTotal(c, l.flatMap { it.spend }.groupBy { it.vatBasis }.map { (b, s) -> SpendTotal(b, s.sumOf { it.cents }) }, l.size)
        }.sortedBy { it.category.ordinal }
        return InventoryReport(period, lines, categories)
    }

    private fun quantities(rows: List<InventoryPurchase>, conversions: List<UnitConversion>): List<QuantityTotal> {
        // Reuse the average-cost unit logic (same conversions, same "never mix kg and pz" rule),
        // with every row on one basis and a zero amount where the total is missing: only quantities matter here.
        val records = rows.mapIndexed { i, p ->
            PurchaseRecord(i.toLong(), 0, p.date, null, p.quantity, p.unit, null, p.lineTotalCents ?: 0, VatBasis.UNKNOWN)
        }
        return CostCalculator.summarize(records, conversions).averages
            .map { QuantityTotal(it.unit, it.totalQuantity.round(MathContext(12)).stripTrailingZeros()) }
            .sortedWith(compareByDescending<QuantityTotal> { it.amount })
    }

    private fun spend(rows: List<InventoryPurchase>): List<SpendTotal> =
        rows.filter { it.lineTotalCents != null }.groupBy { it.vatBasis }
            .map { (b, r) -> SpendTotal(b, r.sumOf { it.lineTotalCents!! }) }
            .sortedBy { it.vatBasis.ordinal }
}
