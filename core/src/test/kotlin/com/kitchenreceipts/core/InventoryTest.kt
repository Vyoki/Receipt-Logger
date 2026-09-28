package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class InventoryTest {
    private fun buy(product: Long?, name: String, date: LocalDate, qty: String?, unit: String?, cents: Long?, basis: VatBasis = VatBasis.EXCLUSIVE) =
        InventoryPurchase(product, name, Categories.guess(name), date, qty?.let(::BigDecimal), unit, cents, basis)

    @Test fun periods() {
        val d = LocalDate.of(2026, 9, 16) // Wednesday
        assertEquals(LocalDate.of(2026, 9, 14), Period.of(d, PeriodKind.WEEK).start)
        assertEquals(LocalDate.of(2026, 9, 20), Period.of(d, PeriodKind.WEEK).end)
        assertEquals(LocalDate.of(2026, 7, 1), Period.of(d, PeriodKind.QUARTER).start)
        assertEquals(LocalDate.of(2026, 9, 30), Period.of(d, PeriodKind.QUARTER).end)
        assertEquals(LocalDate.of(2026, 8, 1), Period.of(d, PeriodKind.MONTH).previous().start)
        assertEquals("Q3 2026", Period.of(d, PeriodKind.QUARTER).label())
    }

    @Test fun monthTotalsWithUnitsAndUsualAmount() {
        val purchases = listOf(
            buy(1, "Mozzarella", LocalDate.of(2026, 7, 3), "10", "kg", 8000),
            buy(1, "Mozzarella", LocalDate.of(2026, 8, 3), "6", "kg", 4800),
            buy(1, "Mozzarella", LocalDate.of(2026, 9, 3), "4", "kg", 3400),
            buy(1, "Mozzarella", LocalDate.of(2026, 9, 20), "500", "g", 450),
            buy(1, "Mozzarella", LocalDate.of(2026, 9, 21), "2", "pz", 300),
            buy(1, "Mozzarella", LocalDate.of(2026, 9, 22), null, null, 1000, VatBasis.INCLUSIVE),
            buy(2, "Patate", LocalDate.of(2026, 8, 3), "20", "kg", 1800),
        )
        val r = Inventory.report(purchases, Period.of(LocalDate.of(2026, 9, 1), PeriodKind.MONTH))
        assertEquals(1, r.lines.size) // potatoes not bought in September
        val m = r.lines.single()
        assertEquals(Category.DAIRY_EGGS, m.category)
        assertEquals(4, m.purchaseCount)
        assertEquals(1, m.withoutQuantity)
        assertEquals(0, BigDecimal("4.5").compareTo(m.quantities.first { it.unit == "kg" }.amount))
        assertEquals(0, BigDecimal("2").compareTo(m.quantities.first { it.unit == "pz" }.amount))
        assertEquals(listOf(SpendTotal(VatBasis.INCLUSIVE, 1000), SpendTotal(VatBasis.EXCLUSIVE, 4150)).toSet(), m.spend.toSet())
        // July and August: (10 + 6) / 2
        assertEquals(0, BigDecimal("8").compareTo(m.usual.single { it.unit == "kg" }.amount))
        assertTrue(r.categories.single().category == Category.DAIRY_EGGS)
    }
}
