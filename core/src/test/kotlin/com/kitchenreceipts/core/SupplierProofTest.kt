package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented supplier "ABC S.r.l." (VAT 01234567897); the restaurant is "RISTORANTE PROVA SAS" (09876543217). */
class SupplierProofTest {

    private val own = ParseOptions(ownBusinessName = "RISTORANTE PROVA SAS", ownVatNumber = "09876543217")
    private fun reading(name: String, line: String = name) = Extracted(name, Confidence.HIGH, "page 1: $line")
    private fun judge(lines: List<String>, read: String?, opts: ParseOptions = own, box: String? = null) =
        SupplierProof.judge(SupplierProof.collect(listOf(lines), read?.let { reading(it) }, box, opts))

    /** The customer's box, then the body of the document (the footer comes after it). */
    private val customerBox = listOf("Spett.le", "RISTORANTE PROVA SAS", "Via Verdi 2 00100 Roma", "P.IVA 09876543217",
        "DESCRIZIONE QUANTITA PREZZO IMPORTO", "MOZZARELLA FIOR DI LATTE 2 8,90 17,80", "TOTALE DOCUMENTO 17,80")

    @Test fun aLogoMisreadByOneLetterLosesToTheLetterheadAndTheWebAddress() {
        // The logo is read "ABE S.R.L."; the legal footer and the email address both say ABC.
        val page = listOf("ABE S.R.L.", "FATTURA N. 12A/34567 DEL 23/09/2026") + customerBox +
            listOf("ABC S.r.l. - Cap. Soc. 10.000 i.v. - Reg. Imp. Roma - P.IVA 01234567897", "info@abcsrl.it")
        val r = judge(page, "ABE S.R.L.")
        assertEquals(SupplierProof.State.PROVEN, r.state)
        assertEquals("ABC S.r.l.", r.name)
        assertTrue(r.alternatives.any { it.startsWith("ABE") })
        assertEquals(2, r.support) // the footer line and the email address; nothing from the customer's box
    }

    @Test fun oneReadingAloneIsNotProofHoweverClear() {
        val r = judge(listOf("ABE S.R.L.", "Via Roma 1 00100 Roma", "P.IVA 01234567897") + customerBox, "ABE S.R.L.")
        assertEquals(SupplierProof.State.UNPROVEN, r.state)
        // Applied to a reading: no longer sure, so the AI is asked who issued the document.
        val doc = SupplierProof.applyResult(ParsedDocument.EMPTY.copy(sellerName = reading("ABE S.R.L.")), r)
        assertEquals(Confidence.LOW, doc.sellerName?.confidence)
    }

    @Test fun twoPrintedPlacesThatAgreeProveTheName() {
        val r = judge(listOf("ABC S.r.l.", "Via Roma 1 00100 Roma") + customerBox + listOf("ABC S.r.l. - Reg. Imp. Roma 123456"), "ABC S.r.l.")
        assertEquals(SupplierProof.State.PROVEN, r.state)
        assertEquals(2, r.support)
    }

    @Test fun theSameLineReadTwiceIsOnePlace() {
        // The reading and the "company line" are the same printed line: still one place, not proof.
        val r = judge(listOf("ABC S.r.l.", "Via Roma 1 00100 Roma") + customerBox, "ABC S.r.l.")
        assertEquals(SupplierProof.State.UNPROVEN, r.state)
    }

    @Test fun equalSupportForTwoSpellingsIsAConflictThatTheAiSettles() {
        val page = listOf("ABE S.R.L.", "Fornitore: ABC S.r.l.") + customerBox
        val r = judge(page, "ABE S.R.L.")
        assertEquals(SupplierProof.State.CONFLICT, r.state)
        // The AI looked at those places and read ABC: two readings agree.
        val settled = SupplierProof.withAi(r, "ABC S.R.L.")!!
        assertEquals(SupplierProof.State.PROVEN, settled.state)
        assertTrue(settled.name!!.startsWith("ABC"))
        // An answer that matches no place read is not taken.
        assertNull(SupplierProof.withAi(r, "VERDE FRESCO S.p.A."))
        // A part of a name read adds nothing.
        val rossi = judge(listOf("ROSSI DI ROSSI MARIO S.r.l.") + customerBox, "ROSSI DI ROSSI MARIO S.r.l.")
        assertEquals(rossi, SupplierProof.withAi(rossi, "MARIO"))
    }

    @Test fun aSupplierKnownByItsVatNumberIsProvenWhateverTheLogoSays() {
        val opts = own.copy(supplierByVat = { vat -> if (vat == "01234567897") "ABC S.r.l." else null })
        val r = judge(listOf("ABE S.R.L.", "Via Roma 1", "P.IVA 01234567897") + customerBox, "ABE S.R.L.", opts)
        assertEquals(SupplierProof.State.PROVEN, r.state)
        assertEquals("ABC S.r.l.", r.name)
        // Two printed places clearly naming another company: that VAT number was learned by mistake, no proof.
        val other = judge(listOf("VERDE FRESCO S.p.A.", "P.IVA 01234567897") + customerBox + listOf("VERDE FRESCO S.p.A. - Reg. Imp. Roma"), "VERDE FRESCO S.p.A.", opts)
        assertEquals("VERDE FRESCO S.p.A.", other.name)
    }

    @Test fun carriersBanksAndTheCustomerAreNotTheSupplier() {
        val page = listOf("ABC S.r.l.", "Vettore: VERDE FRESCO S.p.A.", "Banca: BANCA ESEMPIO S.p.A. IBAN IT00X0000000000000000000000") + customerBox
        val sources = SupplierProof.collect(listOf(page), reading("ABC S.r.l."), null, own)
        assertTrue(sources.none { it.name?.contains("VERDE") == true || it.name?.contains("BANCA") == true || it.name?.contains("PROVA") == true })
    }

    @Test fun aPecMailboxNamesTheCompanyWhenTheDomainIsTheProvidersOwn() {
        val r = judge(listOf("ABC S.r.l.", "PEC: abcsrl@pec.it") + customerBox, "ABC S.r.l.")
        assertEquals(SupplierProof.State.PROVEN, r.state)
        val generic = judge(listOf("ABC S.r.l.", "info@gmail.com") + customerBox, "ABC S.r.l.")
        assertEquals(SupplierProof.State.UNPROVEN, generic.state)
    }

    @Test fun theOtherSpellingIsOfferedWithOneTap() {
        val r = judge(listOf("ABE S.R.L.", "Fornitore: ABC S.r.l.") + customerBox, "ABE S.R.L.")
        val parsed = SupplierProof.applyResult(ParsedDocument.EMPTY.copy(sellerName = reading("ABE S.R.L.")), r)
        val draft = DocumentDraft.fromParsed(parsed)
        assertTrue(draft.seller.uncertain)
        val offered = Replacements.compute(draft)[Replacements.header("seller")].orEmpty()
        assertTrue(offered.joinToString().contains("AB"))
        assertTrue(offered.none { it == draft.seller.text })
    }

    @Test fun theWholeReadingUsesIt() {
        // Through the parser: the logo misread, the footer and the email correct it, and the name is sure.
        val lines = listOf("ABE S.R.L.", "FATTURA N. 45 DEL 12/09/2026") + customerBox + listOf(
            "DESCRIZIONE QUANTITA PREZZO IMPORTO",
            "MOZZARELLA FIOR DI LATTE 2 8,90 17,80",
            "TOTALE DOCUMENTO 17,80",
            "ABC S.r.l. - Reg. Imp. Roma - P.IVA 01234567897 - info@abcsrl.it",
        )
        val doc = ReceiptParser.parsePages(listOf(lines.mapIndexed { i, t -> OcrLine(t, 40, 100 + i * 32, 40 + t.length * 9, 116 + i * 32) }), own)
        assertNotNull(doc.supplierProof)
        assertTrue(doc.sellerName!!.value.startsWith("ABC"))
        assertEquals(Confidence.HIGH, doc.sellerName!!.confidence)
    }
}
