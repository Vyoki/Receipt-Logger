package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrCleanupTest {
    @Test fun lettersInsideNumbers() {
        assertEquals("PANE 2,50", OcrCleanup.cleanLine("PANE 2,5O"))
        assertEquals("14/03/2025", OcrCleanup.cleanLine("l4/03/2025"))
        assertEquals("IVA 10%", OcrCleanup.cleanLine("IVA 1O%"))
        assertEquals("10,50", OcrCleanup.cleanLine("l0,50"))
    }

    @Test fun wordsAndUnitsUntouched() {
        assertEquals("Olio 1l 4,50", OcrCleanup.cleanLine("Olio 1l 4,50"))
        assertEquals("Il BURRO", OcrCleanup.cleanLine("Il BURRO"))
        assertEquals("Lotto L24-118", OcrCleanup.cleanLine("Lotto L24-118"))
    }

    @Test fun splitDecimalsAndEuro() {
        assertEquals("3,90", OcrCleanup.cleanLine("3 ,90"))
        assertEquals("3,90", OcrCleanup.cleanLine("3, 90"))
        assertEquals("Totale € 12,00", OcrCleanup.cleanLine("Totale €12,00"))
        assertEquals("12,00 €", OcrCleanup.cleanLine("12,00€"))
        assertEquals("Via Roma 1, 20100 Milano", OcrCleanup.cleanLine("Via Roma 1, 20100 Milano"))
    }
}
