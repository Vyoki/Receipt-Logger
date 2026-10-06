package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReadingCheckTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("fixtures/$name")) { "missing fixture $name" }.readText()

    private val doc by lazy { ReceiptParser.parse(fixture("fattura_caseificio.txt")) }
    private val saved = ReadingCheck.Confirmed(
        LocalDate.of(2025, 3, 14), "0145/2025", 7106, 6460, 646, listOf(2225, 640, 2655, 940),
    )

    @Test fun sameValuesScoreFull() {
        val s = ReadingCheck.score(saved, doc)
        assertEquals(9, s.of)
        assertEquals(9, s.right)
        assertTrue(s.differences.isEmpty())
    }

    @Test fun numberComparedWithoutSeparators() {
        val s = ReadingCheck.score(saved.copy(number = "0145 2025"), doc)
        assertEquals(s.of, s.right)
    }

    @Test fun differencesAreListed() {
        val s = ReadingCheck.score(saved.copy(totalCents = 7107, lineAmounts = listOf(2225, 640, 2655, 940, 100)), doc)
        assertEquals(10, s.of)
        assertEquals(8, s.right)
        assertTrue(s.differences.any { it.startsWith("total: saved 71,07, read 71,06") })
        assertTrue(s.differences.any { it == "lines not read: 1,00" })
    }

    @Test fun missingSavedValuesAreNotCounted() {
        val s = ReadingCheck.score(ReadingCheck.Confirmed(null, null, 7106, null, null, emptyList()), doc)
        assertEquals(1, s.of)
        assertEquals(1, s.right)
    }

    @Test fun worseAndBetterOnlyAgainstAnEarlierVersion() {
        val now = ReadingCheck.Score(7, 9, emptyList())
        assertTrue(ReadingCheck.Compared(1, "x", now, 9).worse)
        assertTrue(ReadingCheck.Compared(1, "x", now, 5).better)
        assertFalse(ReadingCheck.Compared(1, "x", now, null).worse)
        assertFalse(ReadingCheck.Compared(1, "x", now, null).better)
    }

    // ------------------------------------------------------------ knowledge pack

    private val pack = KnowledgePack(
        "2026-10-01",
        listOf(KnowledgePack.Supplier("IT", "01234567897", "ABC S.r.l.")),
    )

    @Test fun packNamesASupplierByItsVatNumber() {
        val text = "ABG S.R.L.\nVia Roma 1\nP.IVA 01234567897\nSpett.le RISTORANTE PROVA SAS\nP.IVA 09876543217\nDDT N. 12 del 01/10/2026"
        val f = pack.supplierOf(text, "09876543217", "ABG S.R.L.")
        assertEquals("ABC S.r.l.", f?.supplier?.name)
        // The operator's own number is never taken as the supplier's.
        assertNull(pack.supplierOf("Spett.le\nP.IVA 01234567897", "01234567897", null))
        assertTrue(pack.supplierOf(text, "09876543217", "ABC srl")!!.agreesWithReading)
    }

    @Test fun packEntriesAreValidated() {
        assertTrue(KnowledgePack.valid(KnowledgePack.Supplier("IT", "01234567897", "ABC S.r.l.")))
        assertFalse(KnowledgePack.valid(KnowledgePack.Supplier("IT", "01234567890", "Wrong checksum")))
        assertFalse(KnowledgePack.valid(KnowledgePack.Supplier("IT", "01234567897", " ")))
        assertEquals("2026-10-01", KnowledgePack.newer(pack, KnowledgePack.EMPTY)?.version)
    }

    // ------------------------------------------------------------ online product lookup

    @Test fun onlyProductWordsAreSearched() {
        assertEquals("mozzarella fior di latte", ProductLookup.query("MZ01 Mozzarella fior di latte kg 2,500"))
        assertEquals("passata pomodoro", ProductLookup.query("PASSATA POMODORO 6X700G"))
        assertEquals("olio extra vergine", ProductLookup.query("Olio extra vergine 5 lt ABC S.r.l."))
        assertNull(ProductLookup.query("12345 6,40"))
        assertNull(ProductLookup.query(null))
    }

    @Test fun barcodesNeedAValidCheckDigit() {
        assertEquals("8001234567897", ProductLookup.barcode("8001234567897"))
        assertNull(ProductLookup.barcode("8001234567890"))
        assertNull(ProductLookup.barcode("MZ01"))
    }

    @Test fun categoryTags() {
        assertEquals(Category.DAIRY_EGGS, ProductLookup.category(listOf("en:dairies", "en:cheeses")))
        assertEquals(Category.FROZEN, ProductLookup.category(listOf("en:frozen-foods", "en:fishes")))
        assertNull(ProductLookup.category(listOf("en:unknown")))
    }
}
