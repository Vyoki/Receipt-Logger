package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class BossReportTest {
    private val sep = LocalDate.of(2026, 9, 10)
    private val aug = LocalDate.of(2026, 8, 10)
    private val docs = listOf(
        ReportDocument(1, "Caseificio Alfa S.r.l.", aug, "1", 10000, 1),
        ReportDocument(2, "CASEIFICIO ALFA SRL", sep, "2", 12500, 1),
        ReportDocument(3, "Ortofrutta Beta", sep, "7", 4000, 1),
        ReportDocument(4, "Ortofrutta Beta", sep, "8", null, 1),
        ReportDocument(5, "Ignoto", null, null, 999, 0),
    )
    private val purchases = listOf(
        InventoryPurchase(1, "Mozzarella", Category.DAIRY_EGGS, aug, BigDecimal("10"), "kg", 9000, VatBasis.EXCLUSIVE),
        InventoryPurchase(1, "Mozzarella", Category.DAIRY_EGGS, sep, BigDecimal("10"), "kg", 11000, VatBasis.EXCLUSIVE),
        InventoryPurchase(2, "Patate", Category.FRUIT_VEG, sep, BigDecimal("40"), "kg", 3600, VatBasis.INCLUSIVE),
    )
    private val points = listOf(
        PricePoint(1, "Mozzarella", 1, 10, aug, "Caseificio Alfa S.r.l.", BigDecimal("10"), "kg", null, 9000, VatBasis.EXCLUSIVE),
        PricePoint(1, "Mozzarella", 2, 20, sep, "CASEIFICIO ALFA SRL", BigDecimal("10"), "kg", null, 11000, VatBasis.EXCLUSIVE),
    )

    @Test fun september() {
        val r = BossReports.build(Period.of(sep, PeriodKind.MONTH), docs, purchases, points)
        assertEquals(16500L, r.totalCents)
        assertEquals(10000L, r.previousTotalCents)
        assertEquals(BigDecimal("65.0"), r.changePercent)
        assertEquals(3, r.documentCount)
        assertEquals(1, r.documentsMissingTotal)
        assertEquals(1, r.documentsWithoutDate)
        val alfa = r.suppliers.first()
        assertEquals(12500L, alfa.totalCents)            // same supplier despite different spelling
        assertEquals(BigDecimal("25.0"), alfa.changePercent)
        assertNull(r.suppliers[1].changePercent)          // not bought from in August
        assertEquals(Category.DAIRY_EGGS, r.categories.first().category)
        assertEquals(listOf(SpendTotal(VatBasis.EXCLUSIVE, 9000)), r.categories.first().previous)
        assertEquals(1, r.increases)
        assertEquals(BigDecimal("22.2"), r.priceChanges.single().percent)
        assertTrue(r.inventory.any { it.name == "Patate" })
    }
}
