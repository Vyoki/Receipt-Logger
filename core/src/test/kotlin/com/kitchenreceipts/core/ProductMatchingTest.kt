package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductMatchingTest {

    private val products = listOf(ProductRef(1, "Mozzarella fior di latte"), ProductRef(2, "Mozzarella di bufala"), ProductRef(3, "Burro"))

    @Test fun aliasKeyIsExactButTolerant() {
        assertEquals(ProductMatching.aliasKey("MOZZARELLA  Fior di Latte"), ProductMatching.aliasKey("mozzarella fior di latte"))
        assertEquals("caffe", ProductMatching.aliasKey("Caffè"))
        assertTrue(ProductMatching.aliasKey("Mozzarella bufala") != ProductMatching.aliasKey("Mozzarella fior di latte"))
    }

    @Test fun suggestionsAreRankedNotMerged() {
        val s = ProductMatching.suggest("MZ01 Mozzarella fior di latte", products)
        assertEquals(1L, s.first().product.id)
        assertTrue(s.any { it.product.id == 2L }) // similar name shown as an option, not merged
        assertTrue(s.none { it.product.id == 3L })
    }

    @Test fun proposedNameDropsLeadingCode() {
        assertEquals("Mozzarella fior di latte", ProductMatching.proposeName("MZ01 Mozzarella fior di latte"))
        assertEquals("Pomodori san marzano", ProductMatching.proposeName("Pomodori San Marzano"))
    }
}
