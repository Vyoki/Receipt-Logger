package com.kitchenreceipts.core

import com.kitchenreceipts.core.EInvoicePrintoutFixture.CUSTOMER_VAT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Reverse-charge forms (TD16-TD19, invented): logged net of the VAT they print, their lines as amounts only, and one
 * purchase with the supplier's own invoice they complete.
 */
class ReverseChargeTest {

    private val options = ParseOptions(ownVatNumber = CUSTOMER_VAT, today = LocalDate.of(2031, 5, 2))

    private fun read(inv: EInvoicePrintoutFixture.Invoice): ParsedDocument {
        val pages = EInvoicePrintoutFixture.pages(inv)
        return ReceiptParser.parsePages(pages, options.copy(textLayerPages = pages.indices.toSet()))
    }

    @Test fun theFormIsFoundByItsPrintedType() {
        val f = ReverseCharge.fromText("TD18 integrazione per acquisto di beni\nintracomunitari\nFatt.Coll. 4711 del 21-02-2031")!!
        assertEquals("TD18", f.type)
        assertEquals("4711", f.refNumber)
        assertEquals(LocalDate.of(2031, 2, 21), f.refDate)
        assertEquals("TD17", ReverseCharge.fromText("TD17 integrazione/autofattura per acquisto servizi dall'estero")?.type)
        assertEquals("TD16", ReverseCharge.fromText("TD16 integrazione fattura reverse charge interno")?.type)
        assertEquals("A-77/2031", ReverseCharge.fromText("TD19 integrazione\nFattura collegata n. A-77/2031 del 03/01/2031")?.refNumber)
        // Other documents and other codes: no form.
        assertNull(ReverseCharge.fromText("TD01 fattura\nFatt.Coll. 4711 del 21-02-2031"))
        assertNull(ReverseCharge.fromText("Articolo TD18 cornice 2 x 4,00"))
    }

    @Test fun aTd18FormIsLoggedNetOfTheVatItPrints() {
        val d = read(EInvoicePrintoutFixture.SELF_BILLED)
        // The VAT printed (51,48) is the buyer's own: none in the purchase; what it cost is the taxable amount.
        assertEquals(23400L, d.subtotalCents?.value)
        assertEquals(0L, d.vatCents?.value)
        assertEquals(Confidence.HIGH, d.vatCents?.confidence)
        assertEquals(23400L, d.totalCents?.value)
        assertEquals(Confidence.HIGH, d.totalCents?.confidence)
        assertEquals(VatBasis.EXCLUSIVE, d.vatBasis?.value)
        // One line, its amount only: no quantity was printed and none is made up.
        val line = d.lineItems.single()
        assertEquals(23400L, line.lineTotalCents?.value)
        assertNull(line.quantity)
        assertNull(line.unitPrice)
        assertTrue(line.adjustment)
        assertTrue(ParseWarning.REVERSE_CHARGE in d.warnings)
        // Everything else is as printed: the supplier (proven by its EU VAT number), the number and date.
        assertEquals("MUSTER HANDEL GMBH", d.sellerName?.value)
        assertEquals("00000003/2031/A", d.documentNumber?.value)
        assertEquals(LocalDate.of(2031, 2, 28), d.documentDate?.value)
        // The draft adds up, and the operator sees the form once (it never saves by itself).
        val draft = AutoAccept.settleProven(DocumentDraft.fromParsed(d))
        assertTrue(AutoAccept.sumMatches(draft))
        assertEquals(listOf(ReviewReason.REVERSE_CHARGE), AutoAccept.reasons(draft).filter { it != ReviewReason.UNCERTAIN_VALUES })
    }

    @Test fun theAiDoubleCheckLeavesTheFormNet() {
        // Read as a photo: the AI's double-check of the totals runs.
        val pages = EInvoicePrintoutFixture.pages(EInvoicePrintoutFixture.SELF_BILLED)
        val d = ReceiptParser.parsePages(pages, options)
        val text = pages.joinToString("\n") { LayoutRows.layout(it).text }
        // The AI reads the VAT printed (51,48, the self-assessed VAT): the form stays logged net, nothing flagged.
        val answer = """{"subtotal":"234,00","vat":"51,48","total":"285,48"}"""
        val after = AiReader.applyTargets(d, listOf(AiTarget.Totals(0, emptyList(), verify = true) to answer), text)
        assertEquals(0L, after.vatCents?.value)
        assertEquals(Confidence.HIGH, after.vatCents?.confidence)
        assertEquals(23400L, after.totalCents?.value)
        assertEquals(VatBasis.EXCLUSIVE, after.vatBasis?.value)
        assertNull(after.aiCheck?.header?.get("vat"))
        // Any later pass of the checks keeps it net too.
        val again = ReceiptParser.finish(after, text)
        assertEquals(0L, again.vatCents?.value)
        assertEquals(VatBasis.EXCLUSIVE, again.vatBasis?.value)
        assertNull(again.lineItems.single().quantity)
    }

    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<p:FatturaElettronica versione="FPR12" xmlns:p="http://ivaservizi.agenziaentrate.gov.it/docs/xsd/fatture/v1.2">
  <FatturaElettronicaHeader>
    <CedentePrestatore><DatiAnagrafici><IdFiscaleIVA><IdPaese>DE</IdPaese><IdCodice>123456788</IdCodice></IdFiscaleIVA>
      <Anagrafica><Denominazione>MUSTER HANDEL GMBH</Denominazione></Anagrafica></DatiAnagrafici></CedentePrestatore>
    <CessionarioCommittente><DatiAnagrafici><IdFiscaleIVA><IdPaese>IT</IdPaese><IdCodice>09876543217</IdCodice></IdFiscaleIVA>
      <Anagrafica><Denominazione>RISTORANTE PROVA SAS</Denominazione></Anagrafica></DatiAnagrafici></CessionarioCommittente>
  </FatturaElettronicaHeader>
  <FatturaElettronicaBody>
    <DatiGenerali>
      <DatiGeneraliDocumento><TipoDocumento>TD18</TipoDocumento><Divisa>EUR</Divisa><Data>2031-02-28</Data>
        <Numero>00000003/2031/A</Numero><ImportoTotaleDocumento>285.48</ImportoTotaleDocumento></DatiGeneraliDocumento>
      <DatiFattureCollegate><IdDocumento>4711</IdDocumento><Data>2031-02-21</Data></DatiFattureCollegate>
    </DatiGenerali>
    <DatiBeniServizi>
      <DettaglioLinee><NumeroLinea>1</NumeroLinea><Descrizione>BENI</Descrizione><Quantita>1.00</Quantita><PrezzoUnitario>234.00</PrezzoUnitario>
        <PrezzoTotale>234.00</PrezzoTotale><AliquotaIVA>22.00</AliquotaIVA></DettaglioLinee>
      <DatiRiepilogo><AliquotaIVA>22.00</AliquotaIVA><ImponibileImporto>234.00</ImponibileImporto><Imposta>51.48</Imposta></DatiRiepilogo>
    </DatiBeniServizi>
  </FatturaElettronicaBody>
</p:FatturaElettronica>""".toByteArray()

    @Test fun theSameFormAsAnEInvoiceFile() {
        val r = EInvoice.read(xml, ownVat = "09876543217")
        val d = r.parsed
        // The file's total (285,48) holds the self-assessed VAT: the purchase is 234,00.
        assertEquals(23400L, d.subtotalCents?.value)
        assertEquals(0L, d.vatCents?.value)
        assertEquals(23400L, d.totalCents?.value)
        assertNull(d.lineItems.single().quantity)
        assertTrue(ParseWarning.REVERSE_CHARGE in d.warnings)
        // The text kept for the document names the invoice it completes, for the duplicate check.
        val f = ReverseCharge.fromText(r.text)!!
        assertEquals("4711", f.refNumber)
        assertEquals(LocalDate.of(2031, 2, 21), f.refDate)
    }

    @Test fun theFormAndTheInvoiceItCompletesAreOnePurchase() {
        val invoice = DocumentFingerprint(1, "Muster Handel GmbH", "4711", LocalDate.of(2031, 2, 21), 23400, "sha-1")
        val form = DocumentFingerprint(2, "MUSTER HANDEL GMBH", "00000003/2031/A", LocalDate.of(2031, 2, 28), 23400, "sha-2",
            refNumber = "4711", refDate = LocalDate.of(2031, 2, 21))
        // Either may be saved first.
        assertEquals(setOf(DuplicateReason.SAME_PURCHASE), DuplicateDetector.findDuplicates(form, listOf(invoice)).single().reasons)
        assertEquals(setOf(DuplicateReason.SAME_PURCHASE), DuplicateDetector.findDuplicates(invoice, listOf(form)).single().reasons)
        assertTrue(DuplicateDetector.findDuplicates(form, listOf(invoice)).single().isStrong)
        // Another supplier's invoice 4711, or the same supplier's 4711 of another date, is another purchase.
        assertTrue(DuplicateDetector.findDuplicates(form, listOf(invoice.copy(sellerName = "VERDE FRESCO S.p.A."))).isEmpty())
        assertTrue(DuplicateDetector.findDuplicates(form, listOf(invoice.copy(date = LocalDate.of(2031, 1, 5)))).isEmpty())
        // A form with no date for the invoice: the same number in the same year.
        val undated = form.copy(refDate = null)
        assertEquals(setOf(DuplicateReason.SAME_PURCHASE), DuplicateDetector.findDuplicates(undated, listOf(invoice)).single().reasons)
        assertTrue(DuplicateDetector.findDuplicates(undated, listOf(invoice.copy(date = LocalDate.of(2030, 2, 21)))).isEmpty())
    }
}
