package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Product lines as a cash & carry prints them, and the names, brands and sizes the app proposes. */
class ProductNamesTest {

    private fun c(printed: String, pack: String = "") = ProductNames.clean(printed, PackSizes.parse(pack.split(' ')))

    @Test fun brandSizeAndAbbreviations() {
        val pasta = c("PASTA BARI.PIPETTE RIG500 N.86 - BARILLA", "GR 500")
        assertEquals("Pasta Barilla Pipette Rigate n.86", pasta.name)
        assertEquals("Barilla", pasta.brand)
        assertEquals("500 g", pasta.size?.text)
        assertTrue(pasta.unknown.isEmpty())

        assertEquals("Pasta Barilla Mezze Penne Rigate n.70", c("PASTA BARI.M/PENNE RIG500 N.70 - BARILLA", "GR 500").name)
        assertEquals("Pasta Barilla Fusilli n.98", c("PASTA BARI.FUSILLI 500 N.98 - BARILLA", "GR 500").name) // "500" is the pack
        assertEquals("Lombo Bovino Adulto con Osso 8C 'd' 6685", c("LOMBO B/A C/OSSO 8C 'D' 6685 - .").name)
        assertEquals("Coppa Suino Senza Osso Sottovuoto Kometa", c("COPPA SUINO S/OSSO SV KOMETA - .").name)
        assertEquals("Acqua Minerale Frasassi Frizzante", c("ACQUA MINERA.FRASASSI FRIZ.LT1", "PZ 1").name)
        assertEquals("1 l", c("ACQUA MINERA.FRASASSI FRIZ.LT1", "PZ 1").size?.text) // the name's litre, not "1 piece"

        val cheese = c("FORM.STAG.ITA KG4 DALLABONA - DALLA BONA")
        assertEquals("Formaggio Stagionato Italiano Dalla Bona", cheese.name)
        assertEquals("4 kg", cheese.size?.text)

        val milk = c("LATTE GRIFO ROSSO P.S 500 SLIM - GMF", "ML 500")
        assertEquals("Latte Grifo Rosso Parzialmente Scremato Slim", milk.name)
        assertEquals("GMF", milk.brand)

        assertEquals("Uova Allevate a Terra per Pasta x6 Orlan", c("UOVA A/TERRA P/PASTA X6 ORLAN.").name)
        assertEquals("Sambuca Molinari", c("SAMBUCA MOLINARI CC.700 - MOLINARI").name)
        assertEquals("700 ml", c("SAMBUCA MOLINARI CC.700 - MOLINARI").size?.text)
    }

    @Test fun unknownAbbreviationsAreShownNotGuessed() {
        val beef = c("ROASTBEEF B.AD.TR.S/V FR500168")
        assertEquals("Roastbeef Bovino Adulto Tr Sottovuoto FR500168", beef.name)
        assertEquals(listOf("TR."), beef.unknown)
    }

    @Test fun learnedFromTheOperatorsName() {
        val taught = ProductNames.learnFromRename("ROASTBEEF B.AD.TR.S/V FR500168", "Roastbeef bovino adulto tenerissimo sottovuoto")
        assertEquals("tenerissimo", taught["tr"])
        try {
            ProductNames.learned = taught
            val beef = c("ROASTBEEF B.AD.TR.S/V FR500170")
            assertEquals("Roastbeef Bovino Adulto Tenerissimo Sottovuoto FR500170", beef.name)
            assertTrue(beef.unknown.isEmpty())
        } finally {
            ProductNames.learned = emptyMap()
        }
    }
}
