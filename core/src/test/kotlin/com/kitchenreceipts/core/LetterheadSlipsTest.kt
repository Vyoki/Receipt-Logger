package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cash & carry invoice as the OCR reads a phone photo (content invented): the logo misread on page 1, the legal form
 * "S.r.l." read as "S.rle" on page 2, the supplier's VAT number with a letter O for the first zero, and the
 * customer's name under "DESTINAZIONE MERCE  SPETTABILE" (labels only on that row).
 */
class LetterheadSlipsTest {

    private val own = "09876543217"

    private fun page(n: Int, logo: List<String>) = (logo + listOf(
        "Società Unipersonale",
        "Sede legale 00100 ROMA (RM) - VIA ESEMPIO, 8",
        "Reg. lmp. RM, CF-PVA O1234567897-REAn 123456/RM",
        "EMITTENTE: C+C Esempio - Via delle Prove 40 Roma (RM) Op:1234 / 5678",
        "DESTINAZIONE MERCE SPETTABILE",
        "RISTORANTE PROVA SAS DI ROSSI MARIO &",
        "C.",
        "VICOLO ESEMPIO, 1",
        "00100 ROMA (RM)",
        "CODICE 975000 (123456) PARTITA IVA $own",
        "RIF.AMM. CODICE FISCALE $own",
        "TIPO DOCUMENTO N.RO DOCUMENTO DATA DOCUMENTO CONDIZIONI DI PAGAMENTO PAG.",
        "COPIA FATTURA 12A/34567 30/09/2026 RIMESSA DIRETTA $n/2",
    )).joinToString("\n")

    private val text = page(1, listOf("ABE cash and carry")) + "\n" +
        "COLLI DESCRIZIONE BENI TIPO TOT. PREZZO IMPORTO COD\n" +
        "1000001 1x1 BISCOTTI FROLLINI 800 - ESEMPIO SK GR 800 3,450 3,45 10\n" +
        "1000002 1 SALAMELLA DOLCE ESEMPIO CF GR 0,48 10,210 4,90 10\n" +
        "1000003 1 SALAMELLA DOLCE ESEMPIO CF GR 0,49 10,210 5,00 10\n" +
        "SEGUE >>>\n" + ReceiptParser.PAGE_BREAK + "\n" +
        page(2, listOf("cash and carry", "ABC", "ABC S.rle")) + "\n" +
        "IMPONIBILI IVA IMPORTO IVA DESCRIZIONE COD IVA\n" +
        "13,35 10 1,34 ALIQUOTA 10%\n" +
        "TOTALE IMPONIBILI 13,35\nTOTALE IVA 1,34\nTOTALE DOCUMENTO 14,69\n"

    @Test fun sellerIsTheLetterheadNotTheCustomer() {
        val d = ReceiptParser.parse(text, ParseOptions(null, own))
        assertEquals("ABC S.r.l.", d.sellerName?.value)
        assertEquals(Confidence.HIGH, d.sellerName?.confidence)
    }

    @Test fun vatNumberWithLetterO() {
        assertEquals("01234567897", SellerProfiles.supplierVatNumber(text, own))
    }

    @Test fun productLinesAreNotVatSummaryRows() {
        val d = ReceiptParser.parse(text, ParseOptions(null, own))
        assertEquals(1, d.vatChecks.size)
        assertEquals(1335L, d.vatChecks.single().printedCents)
        assertEquals(true, d.vatChecks.single().ok)
        // "SK GR 800 · 3,450 · 3,45": the quantity 1 the OCR dropped is worked out; the line adds up.
        val biscotti = d.lineItems.first()
        assertEquals(0, java.math.BigDecimal.ONE.compareTo(biscotti.quantity!!.value))
        assertEquals(emptySet<ParseWarning>(), biscotti.warnings)
    }
}
