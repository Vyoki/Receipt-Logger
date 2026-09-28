package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SellerProfilesTest {
    // Synthetic numbers that pass the Partita IVA checksum.
    private val supplierVat = "01234567897"
    private val ownVat = "09876543217"

    private val invoice = """
        CASEIFICIO VALVERDE S.R.L.
        Latticini freschi - Produzione propria
        Via dei Pascoli 14 - 00100 Roma
        P.IVA IT$supplierVat
        Spett.le RISTORANTE ESEMPIO S.R.L. P.IVA $ownVat
        FATTURA N. 0145/2025 del 14/03/2025
    """.trimIndent()

    @Test fun partitaIvaChecksum() {
        assertTrue(SellerProfiles.isValidPartitaIva(supplierVat))
        assertFalse(SellerProfiles.isValidPartitaIva("01234567890")) // one digit misread
        assertFalse(SellerProfiles.isValidPartitaIva("1234"))
    }

    @Test fun findsVatNumbersAndIgnoresOwn() {
        assertEquals(listOf(supplierVat, ownVat), SellerProfiles.vatNumbers(invoice))
        assertEquals(supplierVat, SellerProfiles.supplierVatNumber(invoice, ownVat))
        assertEquals(supplierVat, SellerProfiles.vatNumbers("P. IVA: IT 01234 567897").single())
    }

    private val candidates = listOf(
        SellerCandidate(1, "Caseificio Valverde S.r.l.", supplierVat, emptyMap(), emptySet()),
        SellerCandidate(
            2, "Ortofrutta Collina Verde", null,
            SellerProfiles.parseProfile(SellerProfiles.mergeProfile(SellerProfiles.mergeProfile(null,
                setOf("ortofrutta", "collina", "verde", "frutta", "stagione")), setOf("ortofrutta", "collina", "verde", "frutta"))),
            setOf("0rtofrutta c0llina"),
        ),
    )

    @Test fun recognisedByVatNumberEvenWithGarbledName() {
        val m = SellerProfiles.identify(candidates, invoice.replace("CASEIFICIO", "CASE1F1C10"), "CASE1F1C10 VALVERDE", ownVat)!!
        assertEquals(1L, m.sellerId)
        assertEquals(SellerMatchReason.VAT_NUMBER, m.reason)
    }

    @Test fun recognisedByLearnedMisreading() {
        val m = SellerProfiles.identify(candidates, "0RTOFRUTTA C0LLINA\nDDT n. 88", "0RTOFRUTTA C0LLINA")!!
        assertEquals(2L, m.sellerId)
        assertEquals(SellerMatchReason.NAME_ALIAS, m.reason)
    }

    @Test fun recognisedByHeaderLayout() {
        val text = "Az. Agr. ORTOFRUTTA COLLINA VERDE\nFrutta e verdura di stagione\nDDT n. 90 del 02/04/2025"
        val m = SellerProfiles.identify(candidates, text, "Az. Agr.")!!
        assertEquals(2L, m.sellerId)
        assertEquals(SellerMatchReason.LAYOUT, m.reason)
    }

    @Test fun unknownSupplierIsNotForced() {
        assertNull(SellerProfiles.identify(candidates, "MACELLERIA BIANCHI\nVia Roma 1\nFattura n. 3", "MACELLERIA BIANCHI"))
    }

    @Test fun profileKeepsMostFrequentWords() {
        val p = SellerProfiles.parseProfile(SellerProfiles.mergeProfile("a1b:1;ortofrutta:2", setOf("ortofrutta", "verde")))
        assertEquals(3, p["ortofrutta"])
        assertEquals(1, p["verde"])
    }
}
