package com.kitchenreceipts.app.data

import androidx.room.withTransaction
import com.kitchenreceipts.core.Categories
import com.kitchenreceipts.core.Category
import com.kitchenreceipts.core.FoodCost
import com.kitchenreceipts.core.IngredientPrice
import com.kitchenreceipts.core.OrderSuggestion
import com.kitchenreceipts.core.PricedPurchase
import com.kitchenreceipts.core.Recipe
import com.kitchenreceipts.core.RecipeCost
import com.kitchenreceipts.core.RecipeIngredient
import com.kitchenreceipts.core.Reorder
import com.kitchenreceipts.core.UnitConversion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** A dish as edited: the recipe and its ingredients. */
data class RecipeDraft(val recipe: RecipeEntity, val items: List<RecipeItemEntity>)

/** Food cost of dishes, month by month against revenue, and the usual order to each supplier. */
class FoodRepository(private val db: AppDatabase) {

    private val dao = db.foodDao()
    private val products = db.productDao()

    private fun conversions(rows: List<UnitConversionEntity>): Map<Long, List<UnitConversion>> =
        rows.groupBy { it.productId }.mapValues { (_, l) -> l.mapNotNull { c -> runCatching { UnitConversion(c.fromUnit, c.toUnit, c.factor) }.getOrNull() } }

    private fun PricedRow.toPurchase() = PricedPurchase(
        productId, documentDate, sellerName, quantity, unit, unitPrice, lineTotalCents, vatBasis, vatRate,
        Category.fromKey(productCategory) ?: Categories.guess(productName ?: originalDescription), lineItemId,
    )

    /** The price of each product: its last purchase, VAT taken out. */
    fun prices(): Flow<Map<Long, IngredientPrice>> = combine(dao.pricedPurchases(), products.allConversions()) { rows, _ ->
        rows.filter { it.productId != null }.groupBy { it.productId!! }.mapNotNull { (pid, list) -> FoodCost.lastPrice(list.map { it.toPurchase() })?.let { pid to it } }.toMap()
    }.flowOn(Dispatchers.Default)

    fun recipeCosts(overheadPercent: BigDecimal): Flow<List<RecipeCost>> =
        combine(dao.recipes(), dao.allItems(), prices(), products.allConversions()) { recipes, items, prices, conv ->
            val byRecipe = items.groupBy { it.recipeId }
            val c = conversions(conv)
            recipes.map { r -> FoodCost.cost(r.toCore(byRecipe[r.id].orEmpty()), { prices[it] }, { c[it].orEmpty() }, overheadPercent) }
        }.flowOn(Dispatchers.Default)

    suspend fun draft(id: Long): RecipeDraft? = withContext(Dispatchers.IO) {
        val r = dao.recipe(id) ?: return@withContext null
        RecipeDraft(r, dao.items(id))
    }

    /** Saves a dish and its ingredients in one go; returns its id. */
    suspend fun save(d: RecipeDraft): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val id = if (d.recipe.id == 0L) dao.insertRecipe(d.recipe.copy(createdAt = now, updatedAt = now))
        else { dao.updateRecipe(d.recipe.copy(updatedAt = now)); d.recipe.id }
        dao.deleteItems(id)
        dao.insertItems(d.items.mapIndexed { i, it -> it.copy(id = 0, recipeId = id, position = i) })
        id
    }

    suspend fun delete(id: Long) = dao.deleteRecipe(id)

    // ------------------------------------------------------------ menu from a spreadsheet

    /** What importing [menu] would change: nothing is written yet. */
    suspend fun planImport(menu: com.kitchenreceipts.core.MenuImport): com.kitchenreceipts.core.MenuPlan = withContext(Dispatchers.IO) {
        val names = products.allOnce()
        val items = dao.allItemsOnce().groupBy { it.recipeId }
        val existing = dao.recipesOnce().map { r ->
            com.kitchenreceipts.core.ExistingDish(r.id, r.name, items[r.id].orEmpty().map { it.name to it.productId })
        }
        com.kitchenreceipts.core.MenuImporter.plan(menu, existing, names.map { com.kitchenreceipts.core.ProductRef(it.id, it.name) })
    }

    /** Writes the plan in one go; dishes not in the file are deleted only when [removeMissing]. */
    suspend fun applyImport(plan: com.kitchenreceipts.core.MenuPlan, removeMissing: Boolean) = db.withTransaction {
        val now = System.currentTimeMillis()
        for (d in plan.dishes) {
            val old = d.existingId?.let { dao.recipe(it) }
            val entity = RecipeEntity(
                id = old?.id ?: 0, name = d.dish.name, category = d.dish.course ?: old?.category, salePriceCents = d.dish.salePriceCents,
                priceIncludesVat = d.dish.priceIncludesVat, vatRate = d.dish.vatRatePercent, portions = d.dish.portions,
                createdAt = old?.createdAt ?: now, updatedAt = now,
            )
            val id = if (old == null) dao.insertRecipe(entity) else { dao.updateRecipe(entity); old.id }
            dao.deleteItems(id)
            dao.insertItems(
                d.ingredients.mapIndexed { i, p ->
                    RecipeItemEntity(
                        recipeId = id, position = i, productId = p.productId, name = p.ingredient.name, quantity = p.ingredient.quantity,
                        unit = p.ingredient.unit, wastePercent = p.ingredient.wastePercent, manualPrice = p.ingredient.price, manualUnit = p.ingredient.priceUnit,
                    )
                },
            )
        }
        if (removeMissing) plan.notInFile.forEach { dao.deleteRecipe(it.id) }
    }

    // ------------------------------------------------------------ months

    fun revenue(): Flow<List<RevenueEntity>> = dao.revenue()

    suspend fun setRevenue(month: YearMonth, cents: Long?, includesVat: Boolean, vatRate: BigDecimal) {
        if (cents == null) dao.deleteRevenue(month.toString())
        else dao.upsertRevenue(RevenueEntity(month.toString(), cents, includesVat, vatRate))
    }

    /** The last [count] months, newest first. */
    fun months(count: Int = 6, today: LocalDate = LocalDate.now()): Flow<List<FoodCost.Month>> =
        combine(dao.pricedPurchases(), dao.revenue()) { rows, revenue ->
            val months = (0 until count).map { YearMonth.from(today).minusMonths(it.toLong()) }
            val rev = revenue.mapNotNull { r ->
                runCatching { FoodCost.MonthRevenue(YearMonth.parse(r.month), r.cents, r.includesVat, r.vatRate) }.getOrNull()
            }
            FoodCost.months(rows.map { it.toPurchase() }, rev, months)
        }.flowOn(Dispatchers.Default)

    // ------------------------------------------------------------ orders

    fun suppliers(): Flow<List<SellerLastRow>> = dao.sellersByLastDelivery()

    suspend fun usualOrder(sellerId: Long, today: LocalDate = LocalDate.now()): List<OrderSuggestion> = withContext(Dispatchers.Default) {
        Reorder.suggestions(dao.boughtFrom(sellerId).map { Reorder.Bought(it.productId, it.name, it.documentId, it.documentDate, it.quantity, it.unit) }, today)
    }
}

fun RecipeEntity.toCore(items: List<RecipeItemEntity>) = Recipe(
    id, name, category, salePriceCents, priceIncludesVat, vatRate, portions,
    items.sortedBy { it.position }.map { RecipeIngredient(it.productId, it.name, it.quantity, it.unit, it.wastePercent, it.manualPrice, it.manualUnit) },
)
