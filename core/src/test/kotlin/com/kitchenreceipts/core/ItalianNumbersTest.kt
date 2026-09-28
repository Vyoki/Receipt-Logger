package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class ItalianNumbersTest {

    private fun p(s: String) = ItalianNumbers.parse(s)

    @Test fun decimalComma() {
        assertEquals(BigDecimal("12.50"), p("12,50"))
        assertEquals(BigDecimal("0.9"), p("0,9"))
        assertEquals(BigDecimal("2.500"), p("2,500"))
    }

    @Test fun thousandsDotWithDecimalComma() {
        assertEquals(BigDecimal("1234.56"), p("1.234,56"))
        assertEquals(BigDecimal("1234567.89"), p("1.234.567,89"))
    }

    @Test fun dotOnly() {
        assertEquals(BigDecimal("1250"), p("1.250"))      // Italian thousands
        assertEquals(BigDecimal("12.50"), p("12.50"))     // decimal point
        assertEquals(BigDecimal("0.250"), p("0.250"))     // leading zero -> decimal
        assertEquals(BigDecimal("1234.56"), p("1,234.56")) // English style
    }

    @Test fun currencyAndSigns() {
        assertEquals(BigDecimal("3.90"), p("€ 3,90"))
        assertEquals(BigDecimal("3.90"), p("3,90€"))
        assertEquals(BigDecimal("71.06"), p("EUR 71,06"))
        assertEquals(BigDecimal("-2.00"), p("-2,00"))
        assertEquals(BigDecimal("-12.00"), p("12,00-"))
    }

    @Test fun rejectsNonNumbers() {
        assertNull(p(""))
        assertNull(p("abc"))
        assertNull(p("12a"))
        assertNull(p("1,2,3.4.5x"))
        assertNull(ItalianNumbers.parse(null))
    }

    @Test fun centsAreExact() {
        assertEquals(7106L, ItalianNumbers.parseCents("71,06"))
        assertEquals(10L, ItalianNumbers.parseCents("0,1"))
        // 0.1 + 0.2 must be exactly 30 cents (would fail with floating point)
        assertEquals(30L, ItalianNumbers.parseCents("0,1")!! + ItalianNumbers.parseCents("0,2")!!)
        assertEquals(2655L, ItalianNumbers.toCents(BigDecimal("1.235").multiply(BigDecimal("21.50"))))
    }

    @Test fun formatting() {
        assertEquals("1.234,56", ItalianNumbers.formatCents(123456))
        assertEquals("-0,05", ItalianNumbers.formatCents(-5))
        assertEquals("1.234,56 €", ItalianNumbers.formatMoney(123456))
        assertEquals("8,9", ItalianNumbers.formatDecimal(BigDecimal("8.9000")))
        assertEquals("21,5263", ItalianNumbers.formatDecimal(BigDecimal("21.52631"), maxScale = 4))
        assertEquals("2,5", ItalianNumbers.toEditText(BigDecimal("2.500")))
        assertEquals("100", ItalianNumbers.toEditText(BigDecimal("1E+2")))
        assertEquals("71,06", ItalianNumbers.centsToEditText(7106))
    }
}
