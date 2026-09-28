package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitsTest {
    @Test fun aliases() {
        assertEquals("kg", Units.normalize("KG"))
        assertEquals("kg", Units.normalize("Kg."))
        assertEquals("pz", Units.normalize("pezzi"))
        assertEquals("conf", Units.normalize("CF"))
        assertEquals("l", Units.normalize("Lt"))
        assertEquals("vaso", Units.normalize("Vaso"))  // unknown units are kept, not dropped
        assertNull(Units.normalize("  "))
        assertNull(Units.dimension("pz"))
        assertEquals(Units.Dimension.MASS, Units.dimension("g"))
    }
}
