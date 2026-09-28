package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Text exactly as the on-device OCR (ML Kit) returned it for a rendered cash & carry page. */
class MlKitOutputTest {
    private val d = ReceiptParser.parse(javaClass.classLoader!!.getResource("fixtures/ocr_mlkit_cash_and_carry.txt")!!.readText())
    private fun item(prefix: String) = d.lineItems.first { it.originalDescription.startsWith(prefix) }
    private fun assertDec(expected: String, actual: BigDecimal?) =
        assertTrue("expected $expected but was $actual", actual != null && actual.compareTo(BigDecimal(expected)) == 0)

    @Test fun allLinesWithRightAmounts() {
        assertEquals(listOf(345L, 109L, 1074L, 1954L, 12678L, 252L, 8180L, 6220L, 490L, 1132L, 1490L, 970L), d.lineItems.map { it.lineTotalCents?.value })
    }

    @Test fun headerDespiteZeroForO() {
        assertEquals("12A/34567", d.documentNumber?.value) // heading read as "D0CUMENTO"
        assertEquals("ABC S.r.l.", d.sellerName?.value)
    }

    @Test fun codesRemovedEvenWhenGlued() {
        assertTrue(item("CANDEGGINA").originalDescription.startsWith("CANDEGGINA")) // "O 10000032x3 CANDEGGINA"
        assertTrue(item("FILETTO").originalDescription.startsWith("FILETTO"))       // "0 10000051 FILETTO"
        assertEquals("1000003", item("CANDEGGINA").itemCode)
    }

    @Test fun quantityTheOcrDroppedIsWorkedOutButMarkedForChecking() {
        val b = item("BISCOTTI") // "SK GR 800 3,450 3,45": the "1" was not read
        assertDec("1", b.quantity?.value)
        assertEquals("pz", b.unit?.value)
        assertEquals(Confidence.LOW, b.quantity?.confidence)
        val a = item("ACQUA") // "PT CL 150 0,420 2,52": the "6" was not read
        assertDec("6", a.quantity?.value)
        assertEquals(Confidence.LOW, a.quantity?.confidence)
        assertDec("0.420", a.unitPrice?.value)
    }
}
