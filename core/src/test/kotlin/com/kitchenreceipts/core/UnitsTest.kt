package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun unitsKeptAsPrinted() {
        // No name of the app's own for these: kept as printed, in capitals.
        for (u in listOf("ORE", "CAD", "MQ", "GG", "KWH", "KW*MESE")) assertEquals(u, Units.asPrinted(u.lowercase()))
        assertNull(Units.normalizeKnown("ore"))
        // Never a number, a single letter or a long word.
        for (w in listOf("12", "B", "STAGIONATO", "1,50")) assertNull(Units.asPrinted(w))
        assertTrue(Units.isPrintedOnly("kWh") && Units.isPrintedOnly("KW*MESE") && Units.isPrintedOnly("Smc"))
        assertTrue(!Units.isPrintedOnly("kg"))
    }
}
