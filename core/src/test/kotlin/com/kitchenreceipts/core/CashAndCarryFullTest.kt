package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** All 5 pages of a cash & carry invoice, as scanned with "Add another page" (content invented). */
class CashAndCarryFullTest {

    private val text = javaClass.classLoader!!.getResource("fixtures/fattura_cash_and_carry_5_pagine.txt")!!.readText()
    private val d = ReceiptParser.parse(text, ParseOptions(ownVatNumber = "09876543217"))

    @Test fun totalsFromTheTotalsPageNotFromThePointsPage() {
        assertEquals(41332L, d.subtotalCents?.value) // "TOTALE IMPONIBILI", not "TOTALE IMPONIBILE SCONTO € 13,23"
        assertEquals(3551L, d.vatCents?.value)
        assertEquals(44883L, d.totalCents?.value)
        assertEquals(Confidence.HIGH, d.totalCents?.confidence)
        assertEquals(VatBasis.EXCLUSIVE, d.vatBasis?.value) // lines add up to the taxable amount
    }

    @Test fun everyLineOfBothItemPagesCountedOnce() {
        assertEquals(20, d.lineItems.size)
        assertEquals(41332L, d.lineItems.sumOf { it.lineTotalCents!!.value })
        assertTrue(ParseWarning.ITEMS_SUM_MISMATCH !in d.warnings)
        // The same product bought twice, printed at the end of page 1 and the start of page 2, is two purchases.
        assertEquals(2, d.lineItems.count { it.originalDescription.startsWith("RICOTTA") })
    }

    @Test fun pageTwoDetails() {
        val sedano = d.lineItems.first { it.originalDescription.startsWith("SEDANO") } // "-SEDANO ..."
        assertTrue(sedano.quantity!!.value.compareTo(BigDecimal(3)) == 0)
        val zucche = d.lineItems.first { it.originalDescription.startsWith("ZUCCHE") } // "S 1000017" discount marker
        assertEquals("kg", zucche.unit?.value)
        assertEquals(734L, zucche.lineTotalCents?.value)
        val salvia = d.lineItems.first { it.originalDescription.startsWith("SALVIA") }
        assertTrue(salvia.vatRatePercent!!.value.compareTo(BigDecimal(5)) == 0)
    }

    @Test fun header() {
        assertEquals("ABC S.r.l.", d.sellerName?.value)
        assertEquals("12A/34567", d.documentNumber?.value)
        assertEquals(LocalDate.of(2026, 9, 23), d.documentDate?.value)
        assertTrue(d.lineItems.none { it.originalDescription.contains("ALIQUOTA") || it.originalDescription.contains("carta", true) })
    }
}
