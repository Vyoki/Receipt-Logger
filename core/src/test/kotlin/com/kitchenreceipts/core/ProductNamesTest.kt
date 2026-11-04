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

        val milk = c("LATTE GRIFO ROSSO P.S 500 SLIM - ABC", "ML 500")
        assertEquals("Latte Grifo Rosso Parzialmente Scremato Slim", milk.name)
        assertEquals("ABC", milk.brand)

        assertEquals("Uova Allevate a Terra per Pasta x6 Orlan.", c("UOVA A/TERRA P/PASTA X6 ORLAN.").name)
        assertEquals("Sambuca Molinari", c("SAMBUCA MOLINARI CC.700 - MOLINARI").name)
        assertEquals("700 ml", c("SAMBUCA MOLINARI CC.700 - MOLINARI").size?.text)
    }

    @Test fun unknownAbbreviationsAreShownNotGuessed() {
        val beef = c("ROASTBEEF B.AD.TR.S/V AB500168")
        assertEquals("Roastbeef Bovino Adulto Tr. Sottovuoto AB500168", beef.name)
        assertEquals(listOf("TR."), beef.unknown)
    }

    @Test fun learnedFromTheOperatorsName() {
        val taught = ProductNames.learnFromRename("ROASTBEEF B.AD.TR.S/V AB500168", "Roastbeef bovino adulto tenerissimo sottovuoto")
        assertEquals("tenerissimo", taught["tr"])
        try {
            ProductNames.learned = taught
            val beef = c("ROASTBEEF B.AD.TR.S/V AB500170")
            assertEquals("Roastbeef Bovino Adulto Tenerissimo Sottovuoto AB500170", beef.name)
            assertTrue(beef.unknown.isEmpty())
            // What was taught is written out like the list's own words: the joined pieces around it keep their rules.
            ProductNames.learned = ProductNames.learnFromRename("BIBITA M.B.GIROT.PESCA", "Bibita M.B. Girotondo pesca")
            assertEquals("girotondo", ProductNames.learned["girot"])
            assertEquals("Bibita M.B Girotondo Pesca", c("BIBITA M.B.GIROT.PESCA").name)
        } finally {
            ProductNames.learned = emptyMap()
        }
    }

    @Test fun anAbbreviationNotWrittenOutKeepsItsDotsAndStaysJoinedAsPrinted() {
        val biscuits = c("BISCOTTI M.B.GIROTONDI GR.800")
        assertEquals("Biscotti M.B.Girotondi", biscuits.name)
        assertEquals(listOf("M.", "B."), biscuits.unknown)
        assertEquals("Legna da Ardere P.lle", c("LEGNA DA ARDERE P.LLE").name)
        // A piece written out still becomes its word, apart from the pieces kept as printed.
        assertEquals("Ace Pavimen. Igienizzante Talco", c("ACE PAVIMEN.IGIENIZ.TALCO LT.1").name)
        assertEquals("Acqua Minerale S.Bene Naturale", c("ACQUA MINERA.S.BENE.NAT. LT.2").name)
        assertEquals("Biscotti Pandistelle M/BIANCO 700", c("BISC.PANDISTELLE M/BIANCO 700").name)
        // At the end of the printed word the last dot stays.
        assertEquals("Biscotti M.B. Molinetti", c("BISC.M.B. MOLINETTI G.800").name)
    }

    @Test fun frequentInvoiceAbbreviationsAreWrittenOut() {
        val wine = c("SPUM.BABULLE EX.DRY MILL.75").name
        assertTrue(wine, wine.startsWith("Spumante Babulle Extra Dry"))
        assertTrue(c("GUA.NITR.VANTO NERI X100 PIC").name.startsWith("Guanti Nitrile Vanto Neri"))
        assertTrue(c("PECOR.ROMANO DOP 1/16 PETRUC").name.startsWith("Pecorino Romano DOP"))
        assertTrue(c("SUS.NERE IT.CRT").name.startsWith("Susine Nere"))
    }

    @Test fun aNumberOrASizeNeverJoinsTheAbbreviationBeforeIt() {
        val cups = c("50 COPPE COMP.450ML MP")
        assertEquals("50 Coppe Comp. 450ML Mp", cups.name)
        assertEquals("450 ml", ProductName.split(cups.name).size) // the size is still found
        val beer = c("BIRRA BIONDA COT.21 M/B/UMB.50").name
        assertEquals("Birra Bionda Cot. 21 M/B/UMB. 50", beer)
        // A joined abbreviation keeps its own dot before a number or a size, like a single one ("Comp.").
        val dispenser = c("6DISPENSER PLAST.TR.490ML MP")
        assertEquals("6DISPENSER Plast.Tr. 490ML Mp", dispenser.name)
        assertEquals("490 ml", ProductName.split(dispenser.name).size)
        assertTrue(c("CIP.CANN.TREC.D.G500CR").name.contains("Trec.D. "))
        // A decimal number is not cut at its dot.
        assertEquals("0.75 l", ProductName.split(c("Bott. da 0.75 l di Sangiovese IGP Umbria").name).size)
    }
}
