package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CostCalculatorTest {

    private var nextId = 1L
    private fun buy(qty: String?, unit: String?, totalCents: Long?, vat: VatBasis = VatBasis.EXCLUSIVE) =
        PurchaseRecord(nextId++, 1, null, "Fornitore", qty?.let(::BigDecimal), unit, null, totalCents, vat)

    @Test fun weightedAverageIsTotalCostOverTotalQuantity() {
        // 2 kg for 20,00 and 8 kg for 60,00 -> 80,00 / 10 kg = 8,00 (simple mean of prices would be 8,75)
        val s = CostCalculator.summarize(listOf(buy("2", "kg", 2000), buy("8", "kg", 6000)))
        val a = s.averages.single()
        assertEquals("kg", a.unit)
        assertEquals(0, BigDecimal("8.0000").compareTo(a.averageUnitCost))
        assertEquals(8000L, a.totalCents)
        assertEquals(2, a.purchaseCount)
    }

    @Test fun exactDecimalNoFloatingPointDrift() {
        val purchases = (1..3).map { buy("0.1", "kg", 1) } // 3 x (0,1 kg for 0,01 €)
        val a = CostCalculator.summarize(purchases).averages.single()
        assertEquals(0, BigDecimal("0.3").compareTo(a.totalQuantity))
        assertEquals(0, BigDecimal("0.1000").compareTo(a.averageUnitCost))
    }

    @Test fun gramsAreConvertedToKilograms() {
        val s = CostCalculator.summarize(listOf(buy("500", "g", 450), buy("1,5".replace(',', '.'), "kg", 1350)))
        val a = s.averages.single()
        assertEquals("kg", a.unit)
        assertEquals(0, BigDecimal("2").compareTo(a.totalQuantity))
        assertEquals(0, BigDecimal("9.0000").compareTo(a.averageUnitCost))
        assertEquals(setOf("g", "kg"), a.sourceUnits)
    }

    @Test fun incompatibleUnitsAreNeverAveragedTogether() {
        val s = CostCalculator.summarize(listOf(buy("2", "kg", 2000), buy("10", "pz", 500), buy("3", "conf", 900)))
        assertEquals(3, s.averages.size)
        assertEquals(setOf("kg", "pz", "conf"), s.averages.map { it.unit }.toSet())
        assertTrue(s.averages.all { it.purchaseCount == 1 })
    }

    @Test fun userConversionAllowsCombining() {
        // user says: 1 conf = 6 pz
        val conv = listOf(UnitConversion("conf", "pz", BigDecimal(6)))
        val s = CostCalculator.summarize(listOf(buy("12", "pz", 600), buy("2", "conf", 540)), conv)
        val a = s.averages.single()
        assertEquals("pz", a.unit)
        assertEquals(0, BigDecimal("24").compareTo(a.totalQuantity))
        assertEquals(0, BigDecimal("0.4750").compareTo(a.averageUnitCost)) // 11,40 / 24
        assertEquals(2, a.purchaseCount)
    }

    @Test fun vatInclusiveAndExclusiveAreSeparate() {
        val s = CostCalculator.summarize(
            listOf(
                buy("1", "kg", 1000, VatBasis.EXCLUSIVE),
                buy("1", "kg", 1100, VatBasis.INCLUSIVE),
                buy("1", "kg", 1050, VatBasis.UNKNOWN),
            ),
        )
        assertEquals(3, s.averages.size)
        assertEquals(setOf(VatBasis.EXCLUSIVE, VatBasis.INCLUSIVE, VatBasis.UNKNOWN), s.averages.map { it.vatBasis }.toSet())
    }

    @Test fun incompletePurchasesAreExcludedAndReported() {
        val s = CostCalculator.summarize(
            listOf(buy("2", "kg", 2000), buy(null, "kg", 500), buy("1", null, 500), buy("1", "kg", null), buy("0", "kg", 100)),
        )
        assertEquals(1, s.averages.single().purchaseCount)
        assertEquals(
            listOf(ExclusionReason.MISSING_QUANTITY, ExclusionReason.MISSING_UNIT, ExclusionReason.MISSING_TOTAL, ExclusionReason.ZERO_QUANTITY),
            s.excluded.map { it.second },
        )
    }

    @Test fun roundingIsHalfEvenToFourDecimals() {
        val a = CostCalculator.summarize(listOf(buy("3", "kg", 1000))).averages.single()
        assertEquals(BigDecimal("3.3333"), a.averageUnitCost)
    }

    @Test fun emptyInput() {
        val s = CostCalculator.summarize(emptyList())
        assertTrue(s.averages.isEmpty())
        assertTrue(s.excluded.isEmpty())
    }
}
