package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartMatchTest {
    private val products = listOf(
        ProductCandidate(1, "Mozzarella fior di latte 125g"),
        ProductCandidate(2, "Sale marino grosso 1kg", listOf("SALE MARINO GROSSO GR.1000")),
        ProductCandidate(3, "Sale marino fino 1kg"),
        ProductCandidate(4, "Pollo intero"),
        ProductCandidate(5, "Pomodori pelati 2,5kg"),
        ProductCandidate(6, "Pane"),
        ProductCandidate(7, "Petto di pollo"),
    )

    private fun match(d: String) = SmartMatcher.bestMatch(d, products)

    @Test fun sameWordsDifferentCaseAndPlural() {
        val m = match("MOZZARELLE FIOR DI LATTE GR 125")!!
        assertEquals(1L, m.productId)
        assertTrue(m.automatic)
    }

    @Test fun typoIsNotANewProduct() {
        val m = match("SALE MARINO GROSOS KG 1")!!
        assertEquals(2L, m.productId)
        assertEquals(MatchStrength.TYPO, m.strength)
        assertTrue(m.automatic)
        val ocr = match("MOZARELLA FIOR DI LATTE 125 G")!!
        assertEquals(1L, ocr.productId)
        assertTrue(ocr.automatic)
    }

    @Test fun abbreviation() {
        val m = match("POM. PELATI KG 2,5")!!
        assertEquals(5L, m.productId)
        assertTrue(m.automatic)
    }

    @Test fun differentVariantIsOnlySuggested() {
        val m = match("SALE MARINO INTEGRALE 1KG")
        assertTrue(m == null || !m.automatic)
    }

    @Test fun differentPackSizeIsADifferentProduct() {
        val m = match("MOZZARELLA FIOR DI LATTE 1KG")
        assertTrue(m == null || m.productId != 1L)
    }

    @Test fun similarFoodWordsAreNeverTypos() {
        assertEquals(0.0, SmartMatcher.wordSimilarity("pollo", "polpo"), 0.0)
        assertEquals(0.0, SmartMatcher.wordSimilarity("bovina", "ovina"), 0.0)
        assertEquals(0.0, SmartMatcher.wordSimilarity("pane", "panettone"), 0.0)
        val m = match("POLPO INTERO")
        assertTrue(m == null || !m.automatic)
    }

    @Test fun shortProductNameDoesNotSwallowLongerOnes() {
        val m = match("PETTO DI POLLO A FETTE")
        assertTrue(m == null || !m.automatic)
        assertNull(match("PANETTONE ARTIGIANALE"))
    }

    @Test fun twoEquallyCloseProductsAreNotChosen() {
        val m = SmartMatcher.bestMatch("SALE MARINO 1KG", products)
        assertTrue(m == null || !m.automatic)
    }

    @Test fun duplicatesAmongExistingProducts() {
        val dups = SmartMatcher.possibleDuplicates(
            listOf(ProductCandidate(1, "Mozzarella"), ProductCandidate(2, "Mozarella"), ProductCandidate(3, "Burrata")),
        )
        assertEquals(1, dups.size)
        assertEquals(setOf(1L, 2L), setOf(dups[0].first.id, dups[0].second.id))
        assertFalse(dups.any { it.first.id == 3L || it.second.id == 3L })
    }
}
