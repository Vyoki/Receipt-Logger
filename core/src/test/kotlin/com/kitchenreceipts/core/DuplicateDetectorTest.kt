package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DuplicateDetectorTest {

    private val d = LocalDate.of(2025, 3, 14)
    private val existing = listOf(
        DocumentFingerprint(1, "Caseificio Valverde S.r.l.", "0145/2025", d, 7106, "aaa"),
        DocumentFingerprint(2, "Mercato Fresco", null, d, 1640, "bbb"),
    )

    @Test fun sameSellerAndNumberWithFormattingDifferences() {
        val c = DocumentFingerprint(0, "CASEIFICIO VALVERDE SRL", "145/2025", null, null, null)
        val m = DuplicateDetector.findDuplicates(c, existing).single()
        assertEquals(1L, m.existingId)
        assertTrue(DuplicateReason.SAME_SELLER_AND_NUMBER in m.reasons)
        assertTrue(m.isStrong)
    }

    @Test fun theSameNumberNextYearIsAnotherDocument() {
        val old = DocumentFingerprint(1, "Caseificio Valverde S.r.l.", "12", java.time.LocalDate.of(2025, 2, 3), 7106, "aaa")
        val next = DocumentFingerprint(0, "Caseificio Valverde S.r.l.", "12", java.time.LocalDate.of(2026, 1, 20), 5000, null)
        assertTrue(DuplicateDetector.findDuplicates(next, listOf(old)).isEmpty())
        val again = DocumentFingerprint(0, "Caseificio Valverde S.r.l.", "12", java.time.LocalDate.of(2025, 2, 3), 7106, null)
        assertTrue(DuplicateDetector.findDuplicates(again, listOf(old)).isNotEmpty())
    }

    @Test fun sameFile() {
        val c = DocumentFingerprint(0, null, null, null, null, "bbb")
        assertEquals(setOf(DuplicateReason.SAME_FILE), DuplicateDetector.findDuplicates(c, existing).single().reasons)
    }

    @Test fun sameSellerDateTotal() {
        val c = DocumentFingerprint(0, "Mercato  Fresco", null, d, 1640, null)
        assertEquals(setOf(DuplicateReason.SAME_SELLER_DATE_TOTAL), DuplicateDetector.findDuplicates(c, existing).single().reasons)
    }

    @Test fun weakMatchOnlyDateAndTotal() {
        val c = DocumentFingerprint(0, "Altro fornitore", null, d, 1640, null)
        val m = DuplicateDetector.findDuplicates(c, existing).single()
        assertEquals(setOf(DuplicateReason.SAME_DATE_AND_TOTAL), m.reasons)
        assertFalse(m.isStrong)
    }

    @Test fun differentDocumentsDoNotMatch() {
        val c = DocumentFingerprint(0, "Caseificio Valverde S.r.l.", "0146/2025", d, 5000, "ccc")
        assertTrue(DuplicateDetector.findDuplicates(c, existing).isEmpty())
    }

    @Test fun sameVatNumberIsTheSameSupplierHoweverTheNameReads() {
        val old = DocumentFingerprint(1, "ABC S.r.l.", "77/2031", LocalDate.of(2031, 2, 3), 7106, "aaa", sellerVat = "01234567897")
        // The name read with extra words: by name alone this is another supplier.
        val again = DocumentFingerprint(0, "ABC Distribuzione Alimentare", "77/2031", null, null, null, sellerVat = "IT 01234567897")
        val m = DuplicateDetector.findDuplicates(again, listOf(old)).single()
        assertEquals(setOf(DuplicateReason.SAME_SELLER_AND_NUMBER), m.reasons)
        assertTrue(m.isStrong)
        // Same date and total under the same VAT number: the same supplier's document, not the weak hint.
        val sameDay = DocumentFingerprint(0, "ABC Distribuzione Alimentare", null, LocalDate.of(2031, 2, 3), 7106, null, sellerVat = "01234567897")
        assertEquals(setOf(DuplicateReason.SAME_SELLER_DATE_TOTAL), DuplicateDetector.findDuplicates(sameDay, listOf(old)).single().reasons)
    }

    @Test fun vatNumberDoesNotJoinOtherDocuments() {
        val old = DocumentFingerprint(1, "ABC S.r.l.", "77/2031", LocalDate.of(2031, 2, 3), 7106, "aaa", sellerVat = "01234567897")
        // Another supplier's invoice with the same number.
        val other = DocumentFingerprint(0, "VERDE FRESCO S.p.A.", "77/2031", LocalDate.of(2031, 2, 5), 900, null, sellerVat = "09876543217")
        assertTrue(DuplicateDetector.findDuplicates(other, listOf(old)).isEmpty())
        // The same supplier's next year's invoice with the same number.
        val nextYear = DocumentFingerprint(0, "ABC", "77/2031", LocalDate.of(2032, 1, 9), 900, null, sellerVat = "01234567897")
        assertTrue(DuplicateDetector.findDuplicates(nextYear, listOf(old)).isEmpty())
        // A number that is not a VAT number (too short, all zeros) is never used.
        val short = DocumentFingerprint(0, "XYZ", "77/2031", null, null, null, sellerVat = "123")
        assertTrue(DuplicateDetector.findDuplicates(short, listOf(old.copy(sellerVat = "123"))).isEmpty())
        val zeros = DocumentFingerprint(0, "XYZ", "77/2031", null, null, null, sellerVat = "00000000000")
        assertTrue(DuplicateDetector.findDuplicates(zeros, listOf(old.copy(sellerVat = "00000000000"))).isEmpty())
    }

    @Test fun editingASavedDocumentDoesNotMatchItself() {
        val c = existing[0].copy()
        assertTrue(DuplicateDetector.findDuplicates(c, existing).none { it.existingId == 1L })
    }

    @Test fun normalisation() {
        assertEquals("caseificio valverde", DuplicateDetector.normalizeSeller("Caseificio Valverde S.R.L."))
        assertEquals("macelleria f lli bianchi", DuplicateDetector.normalizeSeller("MACELLERIA F.LLI BIANCHI S.A.S."))
        assertEquals("FT/145/2025", DuplicateDetector.normalizeNumber("ft 0145-2025"))
    }
}
