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
