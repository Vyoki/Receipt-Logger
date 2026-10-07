package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ChecksTest {

    private fun line(id: Long, pid: Long?, q: String?, unit: String?, cents: Long?, price: String? = null, seller: Long = 1, basis: VatBasis = VatBasis.EXCLUSIVE) =
        CheckedLine(id, 1, seller, pid, "P$pid", "desc", LocalDate.of(2026, 10, 1), q?.let(::BigDecimal), unit, price?.let(::BigDecimal), cents, basis)

    private val mozz = AgreedPrice(1, 10, 1, "kg", BigDecimal("8.00"), VatBasis.EXCLUSIVE)

    @Test fun chargedAboveTheAgreedPrice() {
        val over = AgreedPrices.overCharges(listOf(line(1, 10, "3", "kg", 2550)), listOf(mozz)) // 8,50/kg
        assertEquals(1, over.size)
        assertEquals(0, BigDecimal("8.5").compareTo(over[0].paid))
        assertEquals(0, BigDecimal("6.3").compareTo(over[0].percent))
        assertEquals(150L, over[0].extraCents)
    }

    @Test fun gramsAndDiscountsAreComparedPerKg() {
        // 2.500 g for 19,50 = 7,80/kg: below the agreed price.
        assertTrue(AgreedPrices.overCharges(listOf(line(1, 10, "2500", "g", 1950)), listOf(mozz)).isEmpty())
        // Printed price only: 0,0085 per g = 8,50 per kg.
        assertEquals(1, AgreedPrices.overCharges(listOf(line(1, 10, null, "g", null, price = "0.0085")), listOf(mozz)).size)
    }

    @Test fun roundingIsNotOvercharging() {
        assertTrue(AgreedPrices.overCharges(listOf(line(1, 10, "3", "kg", 2410)), listOf(mozz)).isEmpty()) // +0,4%
    }

    @Test fun neverAcrossVatBasesOrUnrelatedUnits() {
        assertTrue(AgreedPrices.overCharges(listOf(line(1, 10, "3", "kg", 3000, basis = VatBasis.INCLUSIVE)), listOf(mozz)).isEmpty())
        assertTrue(AgreedPrices.overCharges(listOf(line(1, 10, "3", "pz", 3000)), listOf(mozz)).isEmpty())
    }

    @Test fun piecesWithTheOperatorsConversion() {
        // 1 pz = 0,125 kg; 4 pz for 4,40 = 8,80 per kg.
        val conv = { _: Long -> listOf(UnitConversion("pz", "kg", BigDecimal("0.125"))) }
        val over = AgreedPrices.overCharges(listOf(line(1, 10, "4", "pz", 440)), listOf(mozz), conv)
        assertEquals(1, over.size)
        assertEquals(0, BigDecimal("8.8").compareTo(over[0].paid))
        assertEquals(40L, over[0].extraCents) // 0,5 kg x 0,80
    }

    @Test fun suppliersOwnPriceFirst() {
        val any = AgreedPrice(2, 10, null, "kg", BigDecimal("9.00"), VatBasis.EXCLUSIVE)
        assertEquals(1L, AgreedPrices.applicable(line(1, 10, "1", "kg", 900), listOf(any, mozz))?.id)
        assertEquals(2L, AgreedPrices.applicable(line(1, 10, "1", "kg", 900, seller = 5), listOf(any, mozz))?.id)
        assertNull(AgreedPrices.applicable(line(1, null, "1", "kg", 900), listOf(any, mozz)))
    }

    @Test fun expiryList() {
        val today = LocalDate.of(2026, 10, 7)
        fun e(id: Long, d: LocalDate) = ExpiringLine(id, 1, "Mozzarella", "MOZZ", "ABC S.r.l.", "L1", d, BigDecimal.ONE, "kg", today.minusDays(3))
        val all = listOf(e(1, today.plusDays(2)), e(2, today.minusDays(1)), e(3, today.plusDays(30)), e(4, today.minusDays(40)), e(5, today.plusDays(7)))
        assertEquals(listOf(2L, 1L, 5L), Expiry.soon(all, today, handled = emptySet()).map { it.lineItemId })
        assertEquals(listOf(2L, 5L), Expiry.soon(all, today, handled = setOf(1L)).map { it.lineItemId })
        assertEquals(-1L, Expiry.daysLeft(all[1], today))
    }

    @Test fun lotsAsTyped() {
        assertTrue(Lots.matches("L.24/0187", "240187"))
        assertTrue(Lots.matches("ab-123 45", "AB12345"))
        assertTrue(Lots.matches("788815", "788815"))
        assertFalse(Lots.matches("788815", "788816"))
        assertFalse(Lots.matches("12", "1")) // too short to mean anything
        assertFalse(Lots.matches(null, "123"))
    }
}
