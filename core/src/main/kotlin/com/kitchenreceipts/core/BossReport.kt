package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.RoundingMode

/** Spend with one supplier in the report period, compared with the previous period of the same length. */
data class SupplierSpend(
    val sellerName: String,
    /** Sum of document totals as printed ("totale documento", normally VAT included). */
    val totalCents: Long,
    val documentCount: Int,
    val documentsMissingTotal: Int,
    val previousTotalCents: Long,
) {
    /** Change against the previous period in percent, one decimal; null when there was nothing before. */
    val changePercent: BigDecimal? get() = percentChange(previousTotalCents, totalCents)
}

data class CategorySpend(val category: Category, val spend: List<SpendTotal>, val previous: List<SpendTotal>, val productCount: Int)

/** The report sent to the owner / manager: a period's purchases at a glance. */
data class BossReport(
    val period: Period,
    val previousPeriod: Period,
    val totalCents: Long,
    val previousTotalCents: Long,
    val documentCount: Int,
    val documentsMissingTotal: Int,
    val documentsWithoutDate: Int,
    val suppliers: List<SupplierSpend>,
    val categories: List<CategorySpend>,
    /** Price changes of purchases made in the period: increases first, largest first. */
    val priceChanges: List<PriceChange>,
    /** What was bought, by category, with the usual amount per period. */
    val inventory: List<InventoryLine>,
) {
    val changePercent: BigDecimal? get() = percentChange(previousTotalCents, totalCents)
    val increases: Int get() = priceChanges.count { it.isIncrease }
    val decreases: Int get() = priceChanges.count { !it.isIncrease }
}

internal fun percentChange(before: Long, now: Long): BigDecimal? =
    if (before <= 0) null
    else BigDecimal(now - before).multiply(BigDecimal(100)).divide(BigDecimal(before), 1, RoundingMode.HALF_UP)

object BossReports {

    /**
     * Builds the report for [period]. Document totals are compared like for like (both periods, printed totals);
     * category spend keeps VAT-inclusive and VAT-exclusive amounts apart, as everywhere in the app.
     */
    fun build(
        period: Period,
        documents: List<ReportDocument>,
        purchases: List<InventoryPurchase>,
        pricePoints: List<PricePoint>,
        conversions: Map<Long, List<UnitConversion>> = emptyMap(),
        maxPriceChanges: Int = 20,
    ): BossReport {
        val previous = period.previous()
        val inPeriod = documents.filter { d -> d.date?.let(period::contains) == true }
        val inPrevious = documents.filter { d -> d.date?.let(previous::contains) == true }
        val prevBySeller = inPrevious.groupBy { DuplicateDetector.normalizeSeller(it.sellerName) ?: it.sellerName }
        val suppliers = inPeriod.groupBy { DuplicateDetector.normalizeSeller(it.sellerName) ?: it.sellerName }.map { (key, docs) ->
            SupplierSpend(
                sellerName = docs.first().sellerName,
                totalCents = docs.sumOf { it.totalCents ?: 0L },
                documentCount = docs.size,
                documentsMissingTotal = docs.count { it.totalCents == null },
                previousTotalCents = prevBySeller[key].orEmpty().sumOf { it.totalCents ?: 0L },
            )
        }.sortedWith(compareByDescending<SupplierSpend> { it.totalCents }.thenBy { it.sellerName.lowercase() })

        val inv = Inventory.report(purchases, period, conversions)
        val invPrev = Inventory.report(purchases, previous, conversions)
        val prevCats = invPrev.categories.associateBy { it.category }
        val categories = inv.categories.map { c ->
            CategorySpend(c.category, c.spend, prevCats[c.category]?.spend.orEmpty(), c.productCount)
        }.sortedByDescending { c -> c.spend.sumOf { it.cents } }

        val changes = PriceWatch.history(pricePoints)
            .filter { it.newDate?.let(period::contains) == true }
            .sortedWith(compareByDescending<PriceChange> { it.isIncrease }.thenByDescending { it.percent.abs() })
            .take(maxPriceChanges)

        return BossReport(
            period = period,
            previousPeriod = previous,
            totalCents = inPeriod.sumOf { it.totalCents ?: 0L },
            previousTotalCents = inPrevious.sumOf { it.totalCents ?: 0L },
            documentCount = inPeriod.size,
            documentsMissingTotal = inPeriod.count { it.totalCents == null },
            documentsWithoutDate = documents.count { it.date == null },
            suppliers = suppliers,
            categories = categories,
            priceChanges = changes,
            inventory = inv.lines,
        )
    }
}
