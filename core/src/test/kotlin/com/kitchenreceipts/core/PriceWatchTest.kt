package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class PriceWatchTest {
    private fun p(doc: Long, day: Int, seller: String, qty: String, unit: String, cents: Long, basis: VatBasis = VatBasis.EXCLUSIVE, product: Long = 1) =
        PricePoint(product, "Mozzarella", doc, doc * 10, LocalDate.of(2026, 9, day), seller, BigDecimal(qty), unit, null, cents, basis)

    @Test fun increaseAgainstTheSameSupplier() {
        val history = listOf(p(1, 1, "Caseificio Alfa S.r.l.", "10", "kg", 8000), p(2, 5, "Beta SpA", "10", "kg", 7000))
        val new = p(3, 10, "CASEIFICIO ALFA SRL", "5", "kg", 4250)
        val c = PriceWatch.compare(new, history)!!
        assertEquals("kg", c.unit)
        assertEquals(0, BigDecimal("8.00").compareTo(c.oldPrice))
        assertEquals(0, BigDecimal("8.50").compareTo(c.newPrice))
        assertEquals(BigDecimal("6.3"), c.percent)
        assertTrue(c.sameSeller)
        assertTrue(c.isIncrease)
    }

    @Test fun gramsAndKilosAreComparedPerKilo() {
        val c = PriceWatch.compare(p(2, 10, "A", "1", "kg", 1000), listOf(p(1, 1, "A", "500", "g", 450)))!!
        assertEquals(0, BigDecimal("9").compareTo(c.oldPrice))
        assertEquals(0, BigDecimal("10").compareTo(c.newPrice))
    }

    @Test fun neverAcrossVatBasisOrIncompatibleUnits() {
        assertNull(PriceWatch.compare(p(2, 10, "A", "1", "kg", 1000), listOf(p(1, 1, "A", "1", "kg", 800, VatBasis.INCLUSIVE))))
        assertNull(PriceWatch.compare(p(2, 10, "A", "1", "kg", 1000), listOf(p(1, 1, "A", "1", "pz", 800))))
    }

    @Test fun smallDifferencesAreIgnored() {
        assertNull(PriceWatch.compare(p(2, 10, "A", "1", "kg", 1005), listOf(p(1, 1, "A", "1", "kg", 1000))))
    }

    @Test fun swappedQuantityAndPriceAreSpottedFromHistory() {
        assertTrue(PriceWatch.looksSwapped(BigDecimal("8.90"), BigDecimal("2.5"), BigDecimal("8.70")))
        assertTrue(!PriceWatch.looksSwapped(BigDecimal("2.5"), BigDecimal("8.90"), BigDecimal("8.70")))
        assertTrue(!PriceWatch.looksSwapped(BigDecimal("9"), BigDecimal("8.90"), BigDecimal("8.70")))
    }

    @Test fun historyListsEveryChange() {
        val all = listOf(p(1, 1, "A", "1", "kg", 1000), p(2, 5, "A", "1", "kg", 1100), p(3, 9, "A", "1", "kg", 1000))
        val h = PriceWatch.history(all)
        assertEquals(listOf(3L, 2L), h.map { it.newDocumentId })
        assertEquals(BigDecimal("-9.1"), h[0].percent)
    }
}
