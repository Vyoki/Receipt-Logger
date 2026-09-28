package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Layout of a real cash & carry invoice (content invented). */
class CashAndCarryTest {

    private val text = javaClass.classLoader!!.getResource("fixtures/fattura_cash_and_carry.txt")!!.readText()
    private val d = ReceiptParser.parse(text)

    private fun assertDec(expected: String, actual: BigDecimal?) =
        assertTrue("expected $expected but was $actual", actual != null && actual.compareTo(BigDecimal(expected)) == 0)

    private fun item(prefix: String) = d.lineItems.first { it.originalDescription.startsWith(prefix) }

    @Test fun header() {
        assertEquals("ABC S.r.l.", d.sellerName?.value)
        assertEquals("12A/34567", d.documentNumber?.value)
        assertEquals(LocalDate.of(2026, 9, 23), d.documentDate?.value)
        assertEquals("EUR", d.currency?.value)
        assertNull("page 1 of 2: the totals are on the last page", d.totalCents)
    }

    @Test fun allLinesRead() {
        assertEquals(13, d.lineItems.size)
        assertEquals(
            listOf(345L, 109L, 1074L, 1954L, 12678L, 252L, 8180L, 6220L, 490L, 1132L, 1190L, 1490L, 970L),
            d.lineItems.map { it.lineTotalCents?.value },
        )
        // No item code, colli or packaging code left in the descriptions.
        assertTrue(d.lineItems.none { it.originalDescription.first().isDigit() || it.originalDescription.startsWith("O ") })
        assertFalse(item("BISCOTTI").originalDescription.contains(" SK"))
        assertTrue(d.lineItems.none { it.originalDescription.contains("Prov SU", ignoreCase = true) })
    }

    @Test fun vatCodeColumnIsTheRateNotThePrice() {
        val b = item("BISCOTTI FROLLINI")
        assertDec("10", b.vatRatePercent?.value)
        assertDec("3.450", b.unitPrice?.value)
        assertDec("4", item("FETTE").vatRatePercent?.value)
        assertDec("22", item("CANDEGGINA").vatRatePercent?.value)
    }

    @Test fun packSizeIsNotTheQuantity() {
        val b = item("BISCOTTI FROLLINI") // SK GR 800 · 1 · 3,450 · 3,45
        assertDec("1", b.quantity?.value)
        assertEquals("pz", b.unit?.value)
        assertTrue(b.originalDescription.endsWith("GR 800"))
        assertEquals(Confidence.HIGH, b.quantity?.confidence)

        val c = item("CANDEGGINA") // FL LT 5 · 6 · 1,790 · 10,74
        assertDec("6", c.quantity?.value)
        assertEquals("pz", c.unit?.value)
        assertDec("1.790", c.unitPrice?.value)

        val p = item("PARMIGIANO") // KG 0.8 · 4 · 15,550 · 62,20
        assertDec("4", p.quantity?.value)
        assertDec("15.550", p.unitPrice?.value)
    }

    @Test fun weighedItemsKeepKilograms() {
        val f = item("FILONE SUINO") // NC KG 4,45 · 4,390 · 19,54
        assertDec("4.45", f.quantity?.value)
        assertEquals("kg", f.unit?.value)
        val s = item("SALAMELLA") // CF GR 0,48 · 10,210 · 4,90: 0,48 is kilograms
        assertDec("0.48", s.quantity?.value)
        assertEquals("kg", s.unit?.value)
        assertEquals(Confidence.HIGH, s.lineTotalCents?.confidence)
    }

    @Test fun descriptionAndAmountsOnTwoRows() {
        val c = item("CIPOLLA DORATA")
        assertDec("1", c.quantity?.value)
        assertEquals(1190L, c.lineTotalCents?.value)
    }

    @Test fun customerVatNumberIsNotTheSuppliers() {
        assertEquals("01234567897", SellerProfiles.supplierVatNumber(text, null))
        assertEquals("01234567897", SellerProfiles.supplierVatNumber(text, "09876543217"))
    }

    @Test fun articleCodeKeptForRememberingProducts() {
        assertEquals("1000001", item("BISCOTTI FROLLINI").itemCode)
        assertEquals("1000003", item("CANDEGGINA").itemCode) // "O 1000003" offer marker
        assertEquals("#1000001", ProductMatching.codeKey("1000001"))
        val draft = DocumentDraft.fromParsed(d)
        assertEquals("1000001", draft.items.first().itemCode)
        val valid = (DraftValidator.validate(draft.copy(seller = DraftField("ABC"))) as ValidationResult.Valid).document
        assertEquals("1000001", valid.items.first().itemCode)
    }
}
