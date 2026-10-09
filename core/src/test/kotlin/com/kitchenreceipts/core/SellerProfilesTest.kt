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

    @Test fun wronglyLearnedOperatorVatDoesNotHijackAnotherSupplier() {
        // What happened on the phone: "ABC S.r.l." was saved with the operator's own VAT number
        // (the only one readable on that invoice). A delivery note from another company arrives,
        // with its own name clearly printed and the operator's VAT number in the customer box.
        val learnedWrong = listOf(SellerCandidate(1, "ABC S.r.l.", ownVat, emptyMap(), emptySet()))
        val ddt = "VERDE FRESCO S.p.A.\nN.Iscr.Reg.Impr.RM, C.F. e P.IVA $supplierVat\nPARTITA IVA CODICE FISCALE\n$ownVat $ownVat RIMESSA DIRETTA"
        val r = SellerProfiles.identifyDetailed(learnedWrong, ddt, "VERDE FRESCO S.p.A.", null, ocrSellerReliable = true)
        assertNull(r.match)
        assertEquals(ownVat, r.suspectVatNumber)
        // Without a clearly printed name the VAT match still works.
        assertEquals(1L, SellerProfiles.identifyDetailed(learnedWrong, ddt, null, null).match?.sellerId)
    }

    @Test fun aMisreadLogoLetterDoesNotOutvoteTheVatNumber() {
        // The supplier was saved correctly. On the next photo the logo's last letter is read as another one: the VAT
        // number on the letterhead still names the supplier, and it must not be forgotten as "shared".
        val known = listOf(SellerCandidate(1, "ABC S.r.l.", supplierVat, emptyMap(), emptySet()))
        val doc = "ABE S.r.l.\nVia Roma 1 - C.F. e P.IVA $supplierVat\nFATTURA N. 12A/34567"
        val r = SellerProfiles.identifyDetailed(known, doc, "ABE S.r.l.", null, ocrSellerReliable = true)
        assertEquals(1L, r.match?.sellerId)
        assertEquals("ABC S.r.l.", r.match?.name)
        assertNull(r.suspectVatNumber)
        // A clearly different company with the same number is still caught.
        val other = SellerProfiles.identifyDetailed(known, doc.replace("ABE S.r.l.", "VERDE FRESCO S.p.A."), "VERDE FRESCO S.p.A.", null, ocrSellerReliable = true)
        assertNull(other.match)
        assertEquals(supplierVat, other.suspectVatNumber)
    }

    @Test fun misreadNamesAreCloseButDifferentCompaniesAreNot() {
        assertTrue(SellerProfiles.sameCompanyOrMisread("ABC S.r.l.", "ABE SRL"))
        assertTrue(SellerProfiles.sameCompanyOrMisread("Caseificio Valverde S.r.l.", "CASEIFIC1O VALVERDE"))
        assertFalse(SellerProfiles.sameCompanyOrMisread("ABC S.r.l.", "VERDE FRESCO S.p.A."))
        assertFalse(SellerProfiles.sameCompanyOrMisread("Caseificio Valverde", "Macelleria Rossi"))
    }

    @Test fun supplierVatComesFromTheLetterheadNotTheCustomerBox() {
        val ddt = "VERDE FRESCO S.p.A.\nN.Iscr.Reg.Impr.RM, C.F. e P.IVA $supplierVat\nPARTITA IVA CODICE FISCALE\n$ownVat $ownVat RIMESSA DIRETTA"
        assertEquals(supplierVat, SellerProfiles.supplierVatNumber(ddt, null))
        // Letterhead unreadable: only the customer's number (printed twice) is left -> nothing is learned.
        val unreadable = "ABC S.r.l.\nSPETTABILE\nRISTORANTE PROVA SAS\nCODICE 975137 PARTITA IVA $ownVat\nRIF.AMM. CODICE FISCALE $ownVat"
        assertNull(SellerProfiles.supplierVatNumber(unreadable, null))
    }

    @Test fun sameCompanyComparison() {
        assertTrue(SellerProfiles.sameCompany("Caseificio Valverde S.r.l.", "CASEIFICIO VALVERDE SRL"))
        assertFalse(SellerProfiles.sameCompany("ABC S.r.l.", "VERDE FRESCO S.p.A."))
    }
}
