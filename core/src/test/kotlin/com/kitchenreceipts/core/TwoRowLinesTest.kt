package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lines printed on two rows (code and name, then the numbers), with produce notes under fresh goods. On a tilted photo
 * the numbers row is read before its name. Content invented.
 */
class TwoRowLinesTest {

    private val head = "ABC S.r.l.\nP.IVA 01234567897\nFATTURA N. 12A/34567 DEL 23/09/2026\nCODICE COLLI DESCRIZIONE UM QTA PREZZO IMPORTO IVA\n"
    private val foot = "\nIMPONIBILE ALIQUOTA IMPOSTA\n27,76 04 1,11\n9,36 22 2,06\nTOTALE IMPONIBILE 37,12\nTOTALE IVA 3,17\nTOTALE FATTURA 40,29\n"

    private fun read(body: String) = ReceiptParser.parse(head + body + foot, ParseOptions(ownVatNumber = "09876543217"))

    @Test fun numbersReadBeforeTheirName() {
        val d = read(
            """
            Prov ITALIA Cat II Cal 140 -150
            VA GR 150 3 0,780 2,34 22
            0 1000011 1x3 CROCCHETTE CANE GR.150 POLLO - MARCA
            0 1000012 1x5 CROCCHETTE CANE GR.150 MANZO - MARCA VA GR 150 5 0,780 3,90 22
            VA GR 150 3 0,780 2,34 22
            0 1000013 1x3 CROCCHETTE CANE GR.150 VERDURE - MARCA
            KG 7,82 3,550 27,76 04
            1000014 2 RADICCHIO ROSSO LUNGO
            Prov ITALIA Cat II Cal
            PZ 1 0,780 0,78 22
            1000015 1 CROCCHETTE CANE GR.150 TACCHINO - MARCA
            """.trimIndent(),
        )
        assertEquals(
            listOf("POLLO" to 234L, "MANZO" to 390L, "VERDURE" to 234L, "RADICCHIO" to 2776L, "TACCHINO" to 78L),
            d.lineItems.map { i -> listOf("POLLO", "MANZO", "VERDURE", "RADICCHIO", "TACCHINO").first { it in i.originalDescription } to i.lineTotalCents!!.value },
        )
        assertEquals(true, d.vatChecks.all { it.ok })
    }

    @Test fun normalOrderStillWorks() {
        val d = read(
            """
            1000014 2 RADICCHIO ROSSO LUNGO
            KG 7,82 3,550 27,76 04
            Prov ITALIA Cat II Cal 40 -45
            1000011 1x3 CROCCHETTE CANE GR.150 POLLO
            PZ 12 0,780 9,36 22
            """.trimIndent(),
        )
        assertEquals(listOf(2776L, 936L), d.lineItems.map { it.lineTotalCents!!.value })
        assertEquals(listOf("RADICCHIO ROSSO LUNGO", "CROCCHETTE CANE GR.150 POLLO"), d.lineItems.map { it.originalDescription })
    }
}
