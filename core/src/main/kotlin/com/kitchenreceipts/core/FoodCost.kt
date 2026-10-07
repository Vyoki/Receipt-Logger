package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/** A purchase with what food cost needs: its VAT rate (to take VAT out) and its category. */
data class PricedPurchase(
    val productId: Long?,
    val date: LocalDate?,
    val sellerName: String?,
    val quantity: BigDecimal?,
    val unit: String?,
    val unitPrice: BigDecimal?,
    val lineTotalCents: Long?,
    val vatBasis: VatBasis,
    val vatRate: BigDecimal?,
    val category: Category? = null,
    val lineItemId: Long = 0,
)

/** What one unit of a product costs, without VAT, and where that comes from. */
data class IngredientPrice(
    /** Per [unit], VAT excluded (or as printed when the basis is unknown: [vatUnknown]). */
    val price: BigDecimal,
    val unit: String,
    val date: LocalDate?,
    val sellerName: String?,
    val manual: Boolean,
    val vatUnknown: Boolean = false,
)

data class RecipeIngredient(
    val productId: Long?,
    val name: String,
    val quantity: BigDecimal,
    val unit: String,
    /** Part of what is bought that is thrown away (peel, bones, trimming), in percent. */
    val wastePercent: BigDecimal = BigDecimal.ZERO,
    /** A price typed by the operator, used when the product has no purchase (per [manualUnit], VAT excluded). */
    val manualPrice: BigDecimal? = null,
    val manualUnit: String? = null,
)

data class Recipe(
    val id: Long,
    val name: String,
    val category: String?,
    /** The menu price of one portion. */
    val salePriceCents: Long?,
    val priceIncludesVat: Boolean = true,
    val vatRatePercent: BigDecimal = BigDecimal.TEN,
    /** How many portions the ingredients make. */
    val portions: BigDecimal = BigDecimal.ONE,
    val ingredients: List<RecipeIngredient>,
)

enum class CostProblem { NO_PRICE, UNITS_DONT_CONVERT }

data class IngredientCost(
    val ingredient: RecipeIngredient,
    /** For one portion, VAT excluded; null when it cannot be worked out ([problem]). */
    val cost: BigDecimal?,
    val price: IngredientPrice?,
    val problem: CostProblem?,
)

data class RecipeCost(
    val recipe: Recipe,
    val ingredients: List<IngredientCost>,
    /** One portion, VAT excluded, of the ingredients that have a price. */
    val foodCost: BigDecimal,
    /** Every ingredient has a price: otherwise [foodCost] is too low and says so. */
    val complete: Boolean,
    /** The menu price without VAT. */
    val netPrice: BigDecimal?,
    /** foodCost / netPrice x 100, one decimal. */
    val foodCostPercent: BigDecimal?,
    /** netPrice - foodCost. */
    val margin: BigDecimal?,
    /** [foodCost] plus the operator's share for staff and utilities. */
    val fullCost: BigDecimal,
)

/**
 * Food cost of dishes from the prices actually paid. Every number comes from saved purchases or from what the
 * operator typed; when a price is missing the dish says so instead of counting it as zero.
 *
 * Waste follows the usual kitchen sheet: the cost of an ingredient is quantity x price, plus the waste percentage
 * of that cost (10% waste on 1,00 € = 1,10 €).
 */
object FoodCost {
    private val MC = MathContext.DECIMAL64
    private val HUNDRED = BigDecimal(100)

    /** Food cost the operator aims at, in percent of the price without VAT (a common kitchen target). */
    val DEFAULT_TARGET: BigDecimal = BigDecimal(30)

    /** Price of one unit without VAT, or null if the VAT cannot be taken out (inclusive price, rate unknown). */
    fun netUnitPrice(p: PricedPurchase): Pair<String, BigDecimal>? {
        val unit = Units.normalize(p.unit) ?: return null
        val q = p.quantity
        val paid = if (p.lineTotalCents != null && q != null && q.signum() > 0) ItalianNumbers.centsToDecimal(p.lineTotalCents).divide(q, MC) else p.unitPrice
        if (paid == null || paid.signum() <= 0) return null
        val net = when (p.vatBasis) {
            VatBasis.INCLUSIVE -> p.vatRate?.let { r -> paid.divide(BigDecimal.ONE.add(r.divide(HUNDRED, MC)), MC) } ?: return null
            else -> paid
        }
        return unit to net
    }

    /**
     * The price of a product: its last purchase with a usable price (VAT taken out). Purchases with a known VAT
     * basis come first; one with an unknown basis is used only when there is nothing else, and says so.
     */
    fun lastPrice(purchases: List<PricedPurchase>): IngredientPrice? {
        val ordered = purchases.sortedWith(compareByDescending<PricedPurchase> { it.date ?: LocalDate.MIN }.thenByDescending { it.lineItemId })
        val known = ordered.filter { it.vatBasis != VatBasis.UNKNOWN }.firstNotNullOfOrNull { p -> netUnitPrice(p)?.let { p to it } }
        val any = known ?: ordered.firstNotNullOfOrNull { p -> netUnitPrice(p)?.let { p to it } }
        val (p, up) = any ?: return null
        return IngredientPrice(up.second, up.first, p.date, p.sellerName, manual = false, vatUnknown = p.vatBasis == VatBasis.UNKNOWN)
    }

    /** [q] of [from] in [to]: kg/g, l/ml, or the product's own conversions (in either direction). */
    fun convert(q: BigDecimal, from: String, to: String, conversions: List<UnitConversion>): BigDecimal? {
        val f = Units.normalize(from) ?: return null
        val t = Units.normalize(to) ?: return null
        if (f == t) return q
        val ff = Units.factorToBase(f)
        val ft = Units.factorToBase(t)
        if (ff != null && ft != null && Units.dimension(f) == Units.dimension(t)) return q.multiply(ff).divide(ft, MC)
        // One hop through a conversion, with kg/g and l/ml on either side ("1 pz = 0,5 kg" also gives pz -> g).
        for (c in conversions) {
            val cf = Units.normalize(c.fromUnit) ?: continue
            val ct = Units.normalize(c.toUnit) ?: continue
            val a = convertPhysical(q, f, cf)
            if (a != null) convertPhysical(a.multiply(c.factor, MC), ct, t)?.let { return it }
            val b = convertPhysical(q, f, ct)
            if (b != null) convertPhysical(b.divide(c.factor, MC), cf, t)?.let { return it }
        }
        return null
    }

    private fun convertPhysical(q: BigDecimal, f: String, t: String): BigDecimal? {
        if (f == t) return q
        val ff = Units.factorToBase(f) ?: return null
        val ft = Units.factorToBase(t) ?: return null
        return if (Units.dimension(f) == Units.dimension(t)) q.multiply(ff).divide(ft, MC) else null
    }

    fun cost(
        recipe: Recipe,
        priceOf: (Long) -> IngredientPrice?,
        conversionsOf: (Long) -> List<UnitConversion> = { emptyList() },
        overheadPercent: BigDecimal = BigDecimal.ZERO,
    ): RecipeCost {
        val portions = recipe.portions.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
        val lines = recipe.ingredients.map { ing ->
            val price = ing.productId?.let(priceOf)
                ?: ing.manualPrice?.takeIf { it.signum() > 0 }?.let { IngredientPrice(it, Units.normalize(ing.manualUnit ?: ing.unit) ?: ing.unit, null, null, manual = true) }
            if (price == null) return@map IngredientCost(ing, null, null, CostProblem.NO_PRICE)
            val q = convert(ing.quantity, ing.unit, price.unit, ing.productId?.let(conversionsOf).orEmpty())
                ?: return@map IngredientCost(ing, null, price, CostProblem.UNITS_DONT_CONVERT)
            val waste = BigDecimal.ONE.add(ing.wastePercent.divide(HUNDRED, MC))
            IngredientCost(ing, q.multiply(price.price, MC).multiply(waste, MC).divide(portions, MC), price, null)
        }
        val food = lines.mapNotNull { it.cost }.fold(BigDecimal.ZERO, BigDecimal::add)
        val net = recipe.salePriceCents?.let { c ->
            val gross = ItalianNumbers.centsToDecimal(c)
            if (recipe.priceIncludesVat) gross.divide(BigDecimal.ONE.add(recipe.vatRatePercent.divide(HUNDRED, MC)), MC) else gross
        }?.takeIf { it.signum() > 0 }
        return RecipeCost(
            recipe = recipe,
            ingredients = lines,
            foodCost = food.setScale(4, RoundingMode.HALF_UP),
            complete = lines.all { it.problem == null },
            netPrice = net?.setScale(4, RoundingMode.HALF_UP),
            foodCostPercent = net?.let { food.divide(it, MC).multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP) },
            margin = net?.subtract(food)?.setScale(4, RoundingMode.HALF_UP),
            fullCost = food.multiply(BigDecimal.ONE.add(overheadPercent.divide(HUNDRED, MC)), MC).setScale(4, RoundingMode.HALF_UP),
        )
    }

    /** The menu price (with VAT when the recipe's price includes it) that gives [targetPercent] food cost, to 0,50 €. */
    fun priceForTarget(c: RecipeCost, targetPercent: BigDecimal = DEFAULT_TARGET): BigDecimal? {
        if (c.foodCost.signum() <= 0 || targetPercent.signum() <= 0) return null
        var p = c.foodCost.divide(targetPercent.divide(HUNDRED, MC), MC)
        if (c.recipe.priceIncludesVat) p = p.multiply(BigDecimal.ONE.add(c.recipe.vatRatePercent.divide(HUNDRED, MC)), MC)
        // Up to the next 0,50.
        return p.multiply(BigDecimal(2)).setScale(0, RoundingMode.CEILING).divide(BigDecimal(2)).setScale(2)
    }

    /** A dish whose food cost went over the target because of new prices. */
    data class DishAlert(
        val cost: RecipeCost,
        /** Food cost % with the prices before the document; null when the dish had no price before. */
        val beforePercent: BigDecimal?,
        val nowPercent: BigDecimal,
        /** The ingredients whose price changed. */
        val changed: List<String>,
    )

    /**
     * Dishes pushed over [targetPercent] by a change of prices ([before] → [after], e.g. without and with a new
     * invoice): a dish is named when it uses a product whose price changed, its food cost is now over the target,
     * and it either was not over before or rose by a point or more. A dish already over that barely moved is not
     * repeated each delivery.
     */
    fun dishAlerts(
        recipes: List<Recipe>,
        before: Map<Long, IngredientPrice>,
        after: Map<Long, IngredientPrice>,
        conversionsOf: (Long) -> List<UnitConversion> = { emptyList() },
        targetPercent: BigDecimal = DEFAULT_TARGET,
    ): List<DishAlert> {
        val changed = after.filter { (pid, p) -> before[pid].let { b -> b == null || b.unit != p.unit || b.price.compareTo(p.price) != 0 } }.keys
        if (changed.isEmpty()) return emptyList()
        return recipes.mapNotNull { r ->
            val touched = r.ingredients.filter { it.productId != null && it.productId in changed }
            if (touched.isEmpty()) return@mapNotNull null
            val now = cost(r, { after[it] }, conversionsOf)
            val nowP = now.foodCostPercent ?: return@mapNotNull null
            if (nowP <= targetPercent) return@mapNotNull null
            val beforeP = cost(r, { before[it] }, conversionsOf).foodCostPercent?.takeIf { it.signum() > 0 }
            val rose = beforeP == null || beforeP <= targetPercent || nowP.subtract(beforeP) >= BigDecimal.ONE
            if (!rose) return@mapNotNull null
            DishAlert(now, beforeP, nowP, touched.map { it.name })
        }.sortedByDescending { it.nowPercent }
    }

    // ------------------------------------------------------------------ month by month

    data class MonthRevenue(val month: YearMonth, val cents: Long, val includesVat: Boolean, val vatRatePercent: BigDecimal = BigDecimal.TEN)

    data class Month(
        val month: YearMonth,
        /** Bought, VAT excluded, per category. */
        val byCategory: Map<Category, Long>,
        /** Food and drink (everything except cleaning and disposables), VAT excluded. */
        val foodAndDrinkCents: Long,
        /** Lines whose VAT could not be taken out (inclusive price without a rate, or unknown basis): counted as printed. */
        val uncertainCents: Long,
        val revenueNetCents: Long?,
        /** foodAndDrink / revenue x 100. */
        val percent: BigDecimal?,
    )

    val NOT_FOOD = setOf(Category.CLEANING, Category.DISPOSABLES)

    /**
     * Spending per month and category without VAT, against the revenue the operator typed. Purchases follow
     * deliveries, not what was used: without stock counts a month that stocks up looks dearer than one that uses
     * up the stock.
     */
    fun months(purchases: List<PricedPurchase>, revenue: List<MonthRevenue>, months: List<YearMonth>): List<Month> {
        val byMonth = purchases.filter { it.date != null && it.lineTotalCents != null }.groupBy { YearMonth.from(it.date) }
        return months.map { m ->
            val lines = byMonth[m].orEmpty()
            val cats = LinkedHashMap<Category, Long>()
            var uncertain = 0L
            for (p in lines) {
                val total = ItalianNumbers.centsToDecimal(p.lineTotalCents!!)
                val net = when {
                    p.vatBasis == VatBasis.INCLUSIVE && p.vatRate != null -> total.divide(BigDecimal.ONE.add(p.vatRate.divide(HUNDRED, MC)), MC)
                    p.vatBasis == VatBasis.EXCLUSIVE -> total
                    else -> { uncertain += p.lineTotalCents; total }
                }
                val cents = net.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
                val c = p.category ?: Category.OTHER
                cats[c] = (cats[c] ?: 0L) + cents
            }
            val food = cats.filterKeys { it !in NOT_FOOD }.values.sum()
            val rev = revenue.firstOrNull { it.month == m }?.let { r ->
                if (r.includesVat) ItalianNumbers.centsToDecimal(r.cents).divide(BigDecimal.ONE.add(r.vatRatePercent.divide(HUNDRED, MC)), MC).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
                else r.cents
            }
            Month(
                m, cats, food, uncertain, rev,
                rev?.takeIf { it > 0 }?.let { BigDecimal(food).divide(BigDecimal(it), MC).multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP) },
            )
        }
    }
}
