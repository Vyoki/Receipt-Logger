package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class FoodCostTest {

    private fun buy(pid: Long, date: String, q: String, unit: String, cents: Long, basis: VatBasis = VatBasis.EXCLUSIVE, rate: String? = "10", id: Long = 0, cat: Category? = null) =
        PricedPurchase(pid, LocalDate.parse(date), "ABC S.r.l.", BigDecimal(q), unit, null, cents, basis, rate?.let(::BigDecimal), cat, id)

    private fun eq(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(actual!!))

    @Test fun lastPurchaseWithoutVat() {
        val p = FoodCost.lastPrice(
            listOf(
                buy(1, "2026-09-01", "10", "kg", 2000, id = 1), // 2,00
                buy(1, "2026-10-01", "5", "kg", 1100, basis = VatBasis.INCLUSIVE, id = 2), // 2,20 with 10% VAT = 2,00
                buy(1, "2026-10-02", "2000", "g", 500, basis = VatBasis.UNKNOWN, id = 3), // newer but VAT unknown: not first
            ),
        )!!
        eq("2.00", p.price)
        assertEquals("kg", p.unit)
        assertEquals(LocalDate.parse("2026-10-01"), p.date)
        assertFalse(p.vatUnknown)
        // Only an unknown basis: used, and said.
        assertTrue(FoodCost.lastPrice(listOf(buy(1, "2026-10-02", "2", "kg", 500, basis = VatBasis.UNKNOWN)))!!.vatUnknown)
        // An inclusive price without its rate cannot lose its VAT: not used.
        assertNull(FoodCost.lastPrice(listOf(buy(1, "2026-10-02", "2", "kg", 500, basis = VatBasis.INCLUSIVE, rate = null))))
    }

    /** A dish built like a kitchen costing sheet: grams of each ingredient, waste, sale price with VAT. */
    @Test fun dishCost() {
        val prices = mapOf(
            1L to IngredientPrice(BigDecimal("13"), "kg", null, null, false), // beef 13/kg
            2L to IngredientPrice(BigDecimal("12"), "l", null, null, false), // oil 12/l
            3L to IngredientPrice(BigDecimal("0.12"), "pz", null, null, false), // egg 0,12 each
        )
        val r = Recipe(
            1, "Tartare", "antipasti", 1300, true, BigDecimal.TEN, BigDecimal.ONE,
            listOf(
                RecipeIngredient(1, "Manzo", BigDecimal("100"), "g", BigDecimal("10")), // 0,1 x 13 x 1,10 = 1,43
                RecipeIngredient(2, "Olio", BigDecimal("10"), "ml", BigDecimal("5")), // 0,01 x 12 x 1,05 = 0,126
                RecipeIngredient(3, "Uova", BigDecimal("1"), "pz"), // 0,12
                RecipeIngredient(null, "Tartufo", BigDecimal("8"), "g", manualPrice = BigDecimal("70"), manualUnit = "kg"), // 0,56
            ),
        )
        val c = FoodCost.cost(r, { prices[it] })
        assertTrue(c.complete)
        eq("2.236", c.foodCost)
        eq("11.8182", c.netPrice) // 13,00 with 10% VAT
        eq("18.9", c.foodCostPercent)
        // 30% food cost: 2,236 / 0,30 x 1,10 = 8,20 -> 8,50
        eq("8.50", FoodCost.priceForTarget(c))
        // 30% for staff and utilities, as the owner's sheet adds.
        eq("2.9068", FoodCost.cost(r, { prices[it] }, overheadPercent = BigDecimal(30)).fullCost)
        assertTrue(!c.vatUncertain)
        // The beef's only price comes from a document that did not say whether prices include VAT: the dish says so.
        val unsure = prices + (1L to IngredientPrice(BigDecimal("13"), "kg", null, null, false, vatUnknown = true))
        assertTrue(FoodCost.cost(r, { unsure[it] }).vatUncertain)
    }

    @Test fun missingPriceIsSaidNotZero() {
        val r = Recipe(2, "Bruschetta", null, 1000, true, BigDecimal.TEN, BigDecimal.ONE, listOf(RecipeIngredient(9, "Lardo", BigDecimal("13"), "g")))
        val c = FoodCost.cost(r, { null })
        assertFalse(c.complete)
        assertEquals(CostProblem.NO_PRICE, c.ingredients.single().problem)
    }

    @Test fun piecesAndWeightsNeedTheProductsConversion() {
        val egg = IngredientPrice(BigDecimal("0.12"), "pz", null, null, false)
        val r = Recipe(3, "Crema", null, null, true, BigDecimal.TEN, BigDecimal(4), listOf(RecipeIngredient(3, "Uova", BigDecimal("300"), "g")))
        assertEquals(CostProblem.UNITS_DONT_CONVERT, FoodCost.cost(r, { egg }).ingredients.single().problem)
        // 1 egg = 60 g: 300 g = 5 eggs = 0,60, for 4 portions = 0,15.
        val c = FoodCost.cost(r, { egg }, { listOf(UnitConversion("pz", "g", BigDecimal(60))) })
        eq("0.15", c.foodCost)
    }

    @Test fun monthsAgainstRevenue() {
        val m = YearMonth.of(2026, 9)
        val rows = listOf(
            buy(1, "2026-09-03", "10", "kg", 10000, cat = Category.MEAT),
            buy(2, "2026-09-10", "1", "pz", 1100, basis = VatBasis.INCLUSIVE, rate = "10", cat = Category.DAIRY_EGGS), // 10,00 net
            buy(3, "2026-09-12", "1", "pz", 2440, basis = VatBasis.INCLUSIVE, rate = "22", cat = Category.CLEANING), // 20,00, not food
            buy(4, "2026-09-20", "1", "pz", 500, basis = VatBasis.UNKNOWN, cat = Category.DRY_GOODS),
            buy(1, "2026-10-01", "10", "kg", 99999, cat = Category.MEAT),
        )
        val out = FoodCost.months(rows, listOf(FoodCost.MonthRevenue(m, 55000, includesVat = true)), listOf(m)).single()
        assertEquals(10000L, out.byCategory[Category.MEAT])
        assertEquals(1000L, out.byCategory[Category.DAIRY_EGGS])
        assertEquals(2000L, out.byCategory[Category.CLEANING])
        assertEquals(11500L, out.foodAndDrinkCents)
        assertEquals(500L, out.uncertainCents)
        assertEquals(50000L, out.revenueNetCents)
        eq("23.0", out.percent)
    }

    @Test fun usualOrder() {
        val today = LocalDate.of(2026, 10, 7)
        fun b(pid: Long, doc: Long, date: String, q: String, unit: String) = Reorder.Bought(pid, "P$pid", doc, LocalDate.parse(date), BigDecimal(q), unit)
        val s = Reorder.suggestions(
            listOf(
                b(1, 1, "2026-09-16", "10", "kg"), b(1, 2, "2026-09-23", "12", "kg"), b(1, 3, "2026-09-30", "11000", "g"),
                b(2, 2, "2026-09-23", "2", "conf"), b(2, 2, "2026-09-23", "1", "conf"), // two lines of one delivery
                b(3, 9, "2026-06-01", "5", "kg"), // too old
            ),
            today,
        )
        assertEquals(listOf(1L, 2L), s.map { it.productId })
        eq("11", s[0].usualQuantity)
        assertEquals(7L, s[0].everyDays)
        assertEquals(3, s[0].times)
        eq("3", s[1].usualQuantity)
        assertEquals(null, s[1].everyDays)
        eq("2.5", Reorder.round(BigDecimal("2.3"), "kg"))
        eq("1", Reorder.round(BigDecimal("0.2"), "pz"))
        assertEquals("Order\n- Pomodori: 10 kg", Reorder.message("Order", listOf("Pomodori" to "10 kg"), null))
    }

    @Test fun dishPushedOverTarget() {
        fun price(v: String) = IngredientPrice(BigDecimal(v), "kg", null, null, manual = false)
        val dish = Recipe(1, "Tagliata", null, 2200, ingredients = listOf(RecipeIngredient(7, "Manzo", BigDecimal("0.25"), "kg")))
        val other = Recipe(2, "Insalata", null, 900, ingredients = listOf(RecipeIngredient(8, "Lattuga", BigDecimal("0.2"), "kg")))
        // 20,00 €/kg x 0,25 = 5,00 on 20,00 net = 25%; at 28,00 €/kg = 35%.
        val alerts = FoodCost.dishAlerts(listOf(dish, other), mapOf(7L to price("20"), 8L to price("2")), mapOf(7L to price("28"), 8L to price("2")), targetPercent = BigDecimal(30))
        assertEquals(listOf("Tagliata"), alerts.map { it.cost.recipe.name })
        eq("25.0", alerts[0].beforePercent); eq("35.0", alerts[0].nowPercent)
        assertEquals(listOf("Manzo"), alerts[0].changed)
        // Already over and barely moved: not repeated.
        assertTrue(FoodCost.dishAlerts(listOf(dish), mapOf(7L to price("28")), mapOf(7L to price("28.5")), targetPercent = BigDecimal(30)).isEmpty())
        // A cheaper price never alerts.
        assertTrue(FoodCost.dishAlerts(listOf(dish), mapOf(7L to price("28")), mapOf(7L to price("20")), targetPercent = BigDecimal(30)).isEmpty())
    }

    /** What the owner reads: what one plate earns, where the customer's money goes, and per kilo of the main ingredient. */
    @Test fun whatAPlateEarns() {
        val prices = mapOf(
            1L to IngredientPrice(BigDecimal("17"), "kg", LocalDate.parse("2026-10-09"), "ABC S.r.l.", false,
                previous = IngredientPrice(BigDecimal("16"), "kg", LocalDate.parse("2026-09-26"), "ABC S.r.l.", false)),
            2L to IngredientPrice(BigDecimal("8"), "l", null, null, false),
        )
        val r = Recipe(
            1, "Tagliata", "secondi", 1700, true, BigDecimal.TEN, BigDecimal.ONE,
            listOf(
                RecipeIngredient(1, "Manzo", BigDecimal("250"), "g"), // 0,25 kg x 17 = 4,25
                RecipeIngredient(2, "Olio", BigDecimal("10"), "ml"), // 0,08
            ),
        )
        val c = FoodCost.cost(r, { prices[it] })
        eq("17.00", c.customerPays)
        eq("1.5455", c.vatOfPrice) // 17,00 - 15,4545
        eq("4.33", c.foodCost)
        eq("11.1245", c.earns) // 15,4545 - 4,33
        // A kilo of beef makes 4 plates, which earn 4 x 11,1245.
        val k = c.perKilo!!
        assertEquals("Manzo", k.ingredient)
        assertEquals("kg", k.unit)
        eq("0.25", k.perPlate)
        eq("4.0", k.plates)
        eq("44.50", k.earns)
        // The beef went from 16 to 17 €/kg: 0,25 € more per plate.
        eq("0.25", c.ingredients[0].changePerPortion)
        assertNull(c.ingredients[1].changePerPortion)
        eq("0.25", c.changePerPortion)
        // VAT + ingredients + earnings = what the customer pays.
        eq("17.00", c.vatOfPrice!!.add(c.foodCost).add(c.overheads).add(c.earns!!))
    }

    @Test fun staffAndUtilitiesComeOutOfTheEarnings() {
        val r = Recipe(1, "Pasta", "primi", 1100, false, BigDecimal.TEN, BigDecimal("2"), listOf(RecipeIngredient(1, "Pasta", BigDecimal("200"), "g")))
        val c = FoodCost.cost(r, { IngredientPrice(BigDecimal("2"), "kg", null, null, false) }, overheadPercent = BigDecimal(30))
        eq("0.20", c.foodCost) // 0,2 kg x 2 / 2 portions
        eq("0.06", c.overheads)
        eq("12.10", c.customerPays) // 11,00 without VAT + 10%
        eq("1.10", c.vatOfPrice)
        eq("10.74", c.earns)
        eq("0.1", c.perKilo!!.perPlate)
        eq("10.0", c.perKilo!!.plates)
        // A kilo earns its plates' earnings, staff and utilities included: 10 x 10,74.
        eq("107.40", c.perKilo!!.earns)
    }

    @Test fun thePriceBeforeIsAnEarlierDelivery() {
        val p = FoodCost.lastPrice(
            listOf(
                buy(1, "2026-09-26", "1", "kg", 1890, id = 1),
                buy(1, "2026-10-09", "1", "kg", 1869, id = 2),
                buy(1, "2026-10-09", "2", "kg", 3738, id = 3), // same delivery, second line: no change
            ),
        )!!
        eq("18.69", p.price)
        eq("18.90", p.previous!!.price)
        assertEquals(LocalDate.parse("2026-09-26"), p.previous!!.date)
        assertNull(FoodCost.lastPrice(listOf(buy(1, "2026-10-09", "1", "kg", 1869)))!!.previous)
    }

    @Test fun noPerKiloForDishesOfPieces() {
        val r = Recipe(1, "Uovo", null, 500, true, BigDecimal.TEN, BigDecimal.ONE, listOf(RecipeIngredient(1, "Uova", BigDecimal("2"), "pz")))
        assertNull(FoodCost.cost(r, { IngredientPrice(BigDecimal("0.20"), "pz", null, null, false) }).perKilo)
    }

    @Test fun theSplitAddsUpToWhatTheCustomerPays() {
        fun check(cents: Long, withVat: Boolean, price: String, overhead: Int) {
            val r = Recipe(1, "Piatto", null, cents, withVat, BigDecimal.TEN, BigDecimal.ONE, listOf(RecipeIngredient(1, "Manzo", BigDecimal("150"), "g")))
            val c = FoodCost.cost(r, { IngredientPrice(BigDecimal(price), "kg", null, null, false) }, overheadPercent = BigDecimal(overhead))
            val s = FoodCost.split(c)!!
            assertEquals(s.paysCents, s.vatCents + s.foodCents + s.overheadsCents + s.earnsCents)
        }
        check(1200, true, "22.23", 0)
        check(1200, true, "22.23", 30)
        check(1100, false, "22.233", 0)
    }
}
