package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Invented names: each size and code form, and the names left as printed. */
class ProductNameTest {

    private fun parts(raw: String) = ProductName.split(raw)
    private fun check(raw: String, name: String, size: String?, code: String? = null) =
        assertEquals(raw, ProductName.Parts(name, size, code), parts(raw))

    @Test fun theUnitThenItsNumber() {
        check("SALE FINO DI PROVA GR.1000", "SALE FINO DI PROVA", "1000 g")
        check("ZUCCHERO PROVA KG.1", "ZUCCHERO PROVA", "1 kg")
        check("ZUCCHERO PROVA SU' KG.1", "ZUCCHERO PROVA SU'", "1 kg")
        check("LIQUORE PROVA CC.700", "LIQUORE PROVA", "700 cc")
        check("CREMA PROVA ML.150", "CREMA PROVA", "150 ml")
        check("FARINA PROVA KG.1,5", "FARINA PROVA", "1,5 kg")
    }

    @Test fun theUnitAndItsNumberAsTwoWords() {
        check("BIBITA PROVA LT 0,4", "BIBITA PROVA", "0,4 l")
        check("ACQUA PROVA CL 100", "ACQUA PROVA", "100 cl")
        check("RISO PROVA 1000 G", "RISO PROVA", "1000 g")
        check("PANINI PROVA 6 PZ", "PANINI PROVA", "6 pz")
    }

    @Test fun theNumberThenItsUnit() {
        check("TEGLIA PROVA 21CM", "TEGLIA PROVA", "21 cm")
        check("PASTA PROVA 500G", "PASTA PROVA", "500 g")
    }

    @Test fun packs() {
        check("FRUTTI DI BOSCO PROVA G250X6 CRT", "FRUTTI DI BOSCO PROVA CRT", "250 g x 6")
        check("PATATE PROVA KG.5X2", "PATATE PROVA", "5 kg x 2")
        check("UOVA PROVA X16", "UOVA PROVA", "x 16")
        check("TOVAGLIOLI PROVA MX10", "TOVAGLIOLI PROVA", "x 10")
        check("PATATE PROVA KG.5 X2", "PATATE PROVA", "5 kg x 2")
    }

    @Test fun unmistakableLeadingCodes() {
        check("100234 FORMAGGIO PROVA", "FORMAGGIO PROVA", null, "100234")
        check("AB12C OLIO PROVA LT 1", "OLIO PROVA", "1 l", "AB12C")
        // Too short, letters only or digits only under four: part of the name.
        check("AB1 OLIO PROVA", "AB1 OLIO PROVA", null)
        check("PROVA OLIO", "PROVA OLIO", null)
        check("123 OLIO PROVA", "123 OLIO PROVA", null)
    }

    @Test fun whatIsNotSureStaysInTheName() {
        // A bare trailing number is neither a code nor a size.
        check("SPAGH.INI500 11", "SPAGH.INI500 11", null)
        check("FARFALLE 500 78", "FARFALLE 500 78", null)
        check("VINO PROVA Grad. Alc. 12,5", "VINO PROVA Grad. Alc. 12,5", null)
        check("POD/PDR: IT001E00000009", "POD/PDR: IT001E00000009", null)
        check("CASSETTA PROVA C/6", "CASSETTA PROVA C/6", null)
        check("PRESA PROVA 2X10/16A", "PRESA PROVA 2X10/16A", null)
        // Two measures: which is the size is a guess, so neither is taken.
        check("BIBITA PROVA CL 33 LT 1", "BIBITA PROVA CL 33 LT 1", null)
        // A unit letter with one digit and no dot ("L1") is no size.
        check("GUANTI PROVA L1", "GUANTI PROVA L1", null)
        // A number printed in two words, or a number on both sides of a unit word: which is the size is a guess.
        check("ACQUA PROVA 1 5L", "ACQUA PROVA 1 5L", null)
        check("CHIAVE PROVA ( 1 2 MM", "CHIAVE PROVA ( 1 2 MM", null)
        check("FARINA PROVA 0 KG 1", "FARINA PROVA 0 KG 1", null)
        // A count glued to a word is no article code.
        check("12TAPPI PROVA", "12TAPPI PROVA", null)
        check("G200AGAR PROVA", "G200AGAR PROVA", null)
    }

    @Test fun spacingCleanedCaseKept() {
        check("  Sale  marino   grosso - GR.1000 ", "Sale marino grosso", "1000 g")
        check("CC.700", "CC.700", null)
        check("", "", null)
    }
}
