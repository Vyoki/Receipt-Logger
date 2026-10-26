package com.kitchenreceipts.core

import com.kitchenreceipts.core.EInvoicePrintoutFixture.CUSTOMER_VAT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Invoices printed with the standard e-invoice stylesheet (invented, [EInvoicePrintoutFixture]), read from the PDF's
 * own text: the rules that read this layout for any supplier.
 */
class EInvoicePrintoutLayoutTest {

    private val options = ParseOptions(ownVatNumber = CUSTOMER_VAT, today = LocalDate.of(2031, 5, 2))

    /** Read as the app reads a PDF with a text layer: every page's words are the PDF's own text. */
    private fun read(inv: EInvoicePrintoutFixture.Invoice) = readPages(EInvoicePrintoutFixture.pages(inv))

    private fun readPages(pages: List<List<OcrLine>>, textLayer: Boolean = true) =
        ReceiptParser.parsePages(pages, options.copy(textLayerPages = if (textLayer) pages.indices.toSet() else emptySet()))

    private fun text(inv: EInvoicePrintoutFixture.Invoice) =
        EInvoicePrintoutFixture.pages(inv).joinToString("\n") { p -> p.joinToString("\n") { it.text } }

    @Test fun headerAndTotalsUnderTheirHeadings() {
        val d = read(EInvoicePrintoutFixture.MEAT)
        // The supplier's box ends before the intermediary's block: its name is not the supplier's.
        assertEquals("CARNI ESEMPIO S.R.L.", d.sellerName?.value)
        // Number and date under labels stacked with "ART. / 73" (a short number on a label line).
        assertEquals("318", d.documentNumber?.value)
        assertEquals(Confidence.HIGH, d.documentNumber?.confidence)
        assertEquals(LocalDate.of(2031, 3, 14), d.documentDate?.value)
        // Two VAT rates: taxable and VAT are the sums of the summary rows, proven by the total under its heading.
        assertEquals(22300L, d.subtotalCents?.value)
        assertEquals(1942L, d.vatCents?.value)
        assertEquals(24242L, d.totalCents?.value)
        assertEquals(Confidence.HIGH, d.totalCents?.confidence)
    }

    @Test fun supplierVatPrintedTwiceInItsBox() {
        val inv = EInvoicePrintoutFixture.MEAT
        assertEquals(inv.supplierVat, SellerProfiles.supplierVatNumber(text(inv), CUSTOMER_VAT))
    }

    @Test fun linesReadByColumnsUnderABandOfHeadings() {
        val d = read(EInvoicePrintoutFixture.MEAT)
        assertEquals(listOf(11500L, 6000L, 4800L), d.lineItems.map { it.lineTotalCents?.value })
        assertEquals(listOf("10", "10", "4"), d.lineItems.map { it.vatRatePercent?.value?.stripTrailingZeros()?.toPlainString() })
        // The code's type printed after it is no part of the name.
        assertEquals(listOf("Pancetta tesa", "Salsiccia fresca", "Pecorino stagionato"), d.lineItems.map { it.originalDescription })
        assertTrue(d.lineItems.none { ParseWarning.LINE_TOTAL_MISMATCH in it.warnings })
    }

    @Test fun lotNotesUnderTheLineAreItsLots() {
        val d = read(EInvoicePrintoutFixture.MEAT)
        assertEquals(listOf("AB123", "CD456", "EF789"), d.lineItems.map { it.lotNumber?.value })
        assertTrue(d.lineItems.all { it.lotNumber?.confidence == Confidence.HIGH })
    }

    @Test fun otherNotesAreNeverItemsOrLots() {
        val d = read(EInvoicePrintoutFixture.WHOLESALER)
        assertEquals(listOf(1920L, 1240L, 1900L, 2100L, 120L, 1560L), d.lineItems.map { it.lineTotalCents?.value })
        assertTrue(d.lineItems.none { it.lotNumber?.value == "COPPA-INTERA" })
        // Three rates in the summary, each proven, and their sum the printed total.
        assertEquals(9761L, d.totalCents?.value)
        assertEquals(Confidence.HIGH, d.totalCents?.confidence)
        // A pack size in a name is no lot.
        assertTrue(d.lineItems.none { it.lotNumber?.value?.contains("40X40") == true })
    }

    @Test fun lotQuantityAndNumberNotesAreNoItems() {
        // The standard layout's lot note ("Lotto:", "Scadenza:", "Quantità del suddetto lotto:") and a numeric note
        // ("Rif. numero:", printed like money) under a line: notes of that line, never items or amounts.
        val meat = EInvoicePrintoutFixture.MEAT
        val first = meat.lines[0].copy(notes = emptyList(), more = listOf("Lotto: GH321", "Scadenza: 20-04-2031", "Quantità del suddetto lotto: 12,50", "Rif. numero: 12,50"))
        val d = read(meat.copy(lines = listOf(first) + meat.lines.drop(1)))
        assertEquals(listOf(11500L, 6000L, 4800L), d.lineItems.map { it.lineTotalCents?.value })
        assertEquals("GH321", d.lineItems[0].lotNumber?.value)
        assertEquals(24242L, d.totalCents?.value)
    }

    @Test fun utilityUnitsAreKeptAsPrinted() {
        val d = read(EInvoicePrintoutFixture.ENERGY)
        assertEquals(3, d.lineItems.size)
        // Units the app has no name for are kept as printed, in capitals; a "pz" is never made up.
        assertEquals(listOf("KWH", "MESE", "CAD"), d.lineItems.map { it.unit?.value })
        assertTrue(d.lineItems.none { it.unit?.value == "pz" })
    }

    @Test fun negativeLineAndNatureCodeLeaveTheBillInReview() {
        val d = read(EInvoicePrintoutFixture.ENERGY)
        val rounding = d.lineItems.last()
        // The amount as printed; the price is read without its sign, so quantity x price does not prove the line.
        assertEquals(-30L, rounding.lineTotalCents?.value)
        assertTrue(rounding.unitPrice?.value?.signum() != -1)
        assertTrue(rounding.unitPrice?.confidence != Confidence.HIGH || ParseWarning.LINE_TOTAL_MISMATCH in rounding.warnings)
        // "N2.2" in the VAT column is no rate: the line has none, and nothing is made up for it.
        assertEquals(null, rounding.vatRatePercent?.value)
        // Without the nature row the summary does not add up to the total: the bill is not proven, so it goes to review.
        assertTrue(d.totalCents?.confidence != Confidence.HIGH)
        assertTrue(AutoAccept.reasons(DocumentDraft.fromParsed(d)).isNotEmpty())
    }

    @Test fun soleTraderAndLetterSpacedNames() {
        val d = read(EInvoicePrintoutFixture.HOUSEHOLD)
        assertEquals("BIANCHI LUCA", d.sellerName?.value)
        assertEquals("55/A", d.documentNumber?.value)
        assertEquals(listOf(300L, 240L), d.lineItems.map { it.lineTotalCents?.value })
        // Letters printed one by one are one word: never a unit "g" or "l", never a code.
        assertTrue(d.lineItems.none { it.unit?.value == "g" || it.unit?.value == "l" })
        assertTrue(d.lineItems.first().originalDescription.startsWith("SAPONE"))
    }

    @Test fun nameOverTwoLinesIsOneSupplier() {
        val d = read(EInvoicePrintoutFixture.TWO_LINE_NAME)
        assertEquals("VERDI CARNI DI VERDI ANNA & C. SNC", d.sellerName?.value)
        assertNotEquals(SupplierProof.State.CONFLICT, d.supplierProof?.state)
    }

    @Test fun linesRunningOverTwoPages() {
        val pages = EInvoicePrintoutFixture.pages(EInvoicePrintoutFixture.LONG)
        assertTrue(pages.size >= 2)
        val d = ReceiptParser.parsePages(pages, options)
        assertEquals(60, d.lineItems.size)
        assertEquals(18720L, d.totalCents?.value)
        assertEquals(18000L, d.lineItems.sumOf { it.lineTotalCents?.value ?: 0L })
    }

    @Test fun deliveryNoteReferencesAreNoLots() {
        val d = read(EInvoicePrintoutFixture.MEAT.copy(lines = EInvoicePrintoutFixture.MEAT.lines.map { it.copy(notes = emptyList()) }))
        assertTrue(d.lineItems.none { it.lotNumber?.value?.startsWith("61") == true || it.lotNumber?.value?.startsWith("74") == true })
    }

    @Test fun packSizes() {
        assertTrue(LotExtractor.packSizeLike("KG9X7"))
        assertTrue(LotExtractor.packSizeLike("ML.333"))
        assertTrue(LotExtractor.packSizeLike("ROT.MT45X3PZ"))
        assertTrue(LotExtractor.packSizeLike("BIA3V40X40X30"))
        assertTrue(!LotExtractor.packSizeLike("L.0042"))
        assertTrue(!LotExtractor.packSizeLike("AB731942"))
    }

    // ------------------------------------------------------------------ line notes (F10)

    private val meat = EInvoicePrintoutFixture.MEAT
    private fun meatWith(first: EInvoicePrintoutFixture.Line) = meat.copy(lines = listOf(first) + meat.lines.drop(1))

    @Test fun sdiValueNotesGiveTheLot() {
        val first = meat.lines[0].copy(notes = listOf(EInvoicePrintoutFixture.Note("LOTTO", "QX481", valueLabel = "Valore testo")))
        val d = read(meatWith(first))
        assertEquals("QX481", d.lineItems[0].lotNumber?.value)
        assertEquals(Confidence.HIGH, d.lineItems[0].lotNumber?.confidence)
    }

    @Test fun numericAndDateNotesAreNeverItemsOrLots() {
        val first = meat.lines[0].copy(notes = emptyList(), more = listOf("Tipo dato: LOTTO", "Valore numerico: 7,00", "Tipo dato: CONSEGNA", "Valore data: 03-02-2031"))
        val d = read(meatWith(first))
        assertEquals(listOf(11500L, 6000L, 4800L), d.lineItems.map { it.lineTotalCents?.value })
        assertEquals(null, d.lineItems[0].lotNumber)
        assertTrue(d.lineItems.none { it.quantity?.value?.compareTo(BigDecimal("7")) == 0 })
    }

    @Test fun quantityOfTheLotIsNeverAQuantityOrALine() {
        for (label in listOf("Quantità del suddetto lotto: 12,00", "Quantita' del suddetto lotto: 12,00")) {
            val first = meat.lines[0].copy(notes = emptyList(), more = listOf("Lotto: KQ702", "Scadenza: 20-04-2031", label))
            val d = read(meatWith(first))
            assertEquals(listOf(11500L, 6000L, 4800L), d.lineItems.map { it.lineTotalCents?.value })
            assertTrue(d.lineItems.none { it.quantity?.value?.compareTo(BigDecimal("12")) == 0 })
            assertEquals("KQ702", d.lineItems[0].lotNumber?.value)
            assertEquals(LocalDate.of(2031, 4, 20), d.lineItems[0].expiryDate?.value)
        }
    }

    @Test fun aLotNoteAfterAPricedRowThatIsNoItemIsUncertain() {
        // Two priced rows above the note: the item, then a row of numbers with no name. The note may be either's.
        // Read by columns (the reading this rule belongs to), with a lot that has no pack-size shape.
        fun lotRead(lines: List<EInvoicePrintoutFixture.Line>): Extracted<String>? {
            val pages = EInvoicePrintoutFixture.pages(meat.copy(lines = lines)).map { LayoutRows.layout(it) }
            return TableReader.read(pages, textLayerPages = pages.indices.toSet())!!.firstNotNullOfOrNull { it.lotNumber?.takeIf { l -> l.value == "QZ771" } }
        }
        val note = listOf(EInvoicePrintoutFixture.Note("LOTTO", "QZ771"))
        val numbersOnly = EInvoicePrintoutFixture.Line("", "2,00", "5,00", "kg", "10,00", "10,00", code = null, codeType = null, notes = note)
        val doubted = lotRead(listOf(meat.lines[0].copy(notes = emptyList()), numbersOnly) + meat.lines.drop(1))
        assertEquals("QZ771", doubted?.value)
        assertEquals(Confidence.LOW, doubted?.confidence)
        // The same note right under the item, with no priced row in between: the item's lot, sure.
        val sure = lotRead(listOf(meat.lines[0].copy(notes = note)) + meat.lines.drop(1))
        assertEquals(Confidence.HIGH, sure?.confidence)
    }

    @Test fun aLotNoteOutsideTheDescriptionCellIsUncertain() {
        val first = meat.lines[0].copy(notes = listOf(EInvoicePrintoutFixture.Note("LOTTO", "MN204", x = 1290)))
        val d = read(meatWith(first))
        assertEquals("MN204", d.lineItems[0].lotNumber?.value)
        assertEquals(Confidence.LOW, d.lineItems[0].lotNumber?.confidence)
    }

    @Test fun twoLotNotesUnderOneLineAreShownForACheck() {
        val notes = listOf(EInvoicePrintoutFixture.Note("LOTTO", "AB123"), EInvoicePrintoutFixture.Note("LOTTO", "AB124"))
        val d = read(meatWith(meat.lines[0].copy(notes = notes)))
        val lot = d.lineItems[0].lotNumber!!
        assertEquals("AB123", lot.value)
        assertEquals(Confidence.LOW, lot.confidence)
        assertTrue(lot.source.contains("AB124"))
    }

    // ------------------------------------------------------------------ letters one by one (F12), code kinds (F13)

    @Test fun lettersOneByOneAreJoinedOnlyOnThePdfsOwnText() {
        val pages = EInvoicePrintoutFixture.pages(EInvoicePrintoutFixture.HOUSEHOLD)
        assertTrue(readPages(pages).lineItems.first().originalDescription.startsWith("SAPONE"))
        // On a photo the same row is not guessed at.
        assertTrue(readPages(pages, textLayer = false).lineItems.none { it.originalDescription.startsWith("SAPONE") })
    }

    @Test fun aLongNamePrintedLetterByLetterStaysOneName() {
        // It starts a little left of its column and runs past its heading; a lone "G" after a digit is part of it.
        val inv = EInvoicePrintoutFixture.HOUSEHOLD
        val d = read(inv.copy(lines = listOf(inv.lines[0].copy(desc = "LAVAPIATTI LIMONE 3 G", descX = 628), inv.lines[1])))
        val first = d.lineItems.first()
        assertTrue(first.originalDescription, first.originalDescription.startsWith("LAVAPIATTILIMONE"))
        assertTrue(first.originalDescription, first.originalDescription.endsWith("3 G"))
        assertEquals("pz", first.unit?.value)
        assertEquals(300L, first.lineTotalCents?.value)
        assertTrue(ParseWarning.LINE_TOTAL_MISMATCH !in first.warnings)
    }

    @Test fun spacedDigitsAreNeverJoined() {
        val inv = EInvoicePrintoutFixture.HOUSEHOLD
        val d = read(inv.copy(lines = listOf(inv.lines[0].copy(desc = "SALE 1 2 , 5 0"), inv.lines[1])))
        val name = d.lineItems.first().originalDescription
        assertTrue(name, name.startsWith("SALE"))
        assertTrue(name, !name.contains("12,50") && !name.contains("1250"))
    }

    @Test fun packTextWithASingleLetterIsKept() {
        val inv = EInvoicePrintoutFixture.WHOLESALER
        val d = read(inv.copy(lines = listOf(inv.lines[0].copy(desc = "SPUGNE x 2")) + inv.lines.drop(1)))
        assertEquals("SPUGNE X 2", d.lineItems.first().originalDescription.uppercase())
    }

    @Test fun qualityMarksInBracketsStayInTheName() {
        val inv = EInvoicePrintoutFixture.WHOLESALER
        val d = read(inv.copy(lines = listOf(inv.lines[0].copy(desc = "MOZZARELLA (DOP) 125G", codeType = "Cod.Forn.")) + inv.lines.drop(1)))
        assertEquals("MOZZARELLA (DOP) 125G", d.lineItems.first().originalDescription)
    }

    @Test fun codeKindOnlyRightAfterACodeWithADigit() {
        // On a photo nothing is dropped: the kind printed after the code stays in what was read.
        val photo = readPages(EInvoicePrintoutFixture.pages(meat), textLayer = false)
        assertTrue(photo.lineItems.any { it.originalDescription.contains("FORNITORE") })
        // A "code" without a digit is no article code: what follows it in brackets is not dropped.
        val inv = EInvoicePrintoutFixture.WHOLESALER
        val d = read(inv.copy(lines = listOf(inv.lines[0].copy(code = "ZZ", codeType = "PROVA")) + inv.lines.drop(1)))
        assertTrue(d.lineItems.first().originalDescription.contains("PROVA"))
    }

    // ------------------------------------------------------------------ rows that are no items (F19, F20)

    @Test fun deliveryNoteRowIsNeverALotOrAnItem() {
        val d = read(meat.copy(ddtRefs = listOf("DDT 77/ZZ del 03-02-2031", "Documento di trasporto n. 78 del 04-02-2031"),
            lines = meat.lines.map { it.copy(notes = emptyList()) }))
        assertEquals(listOf(11500L, 6000L, 4800L), d.lineItems.map { it.lineTotalCents?.value })
        assertTrue(d.lineItems.none { l -> l.lotNumber?.value?.let { it.contains("77") || it.contains("78") } == true })
        assertTrue(d.lineItems.none { it.originalDescription.contains("DDT") || it.originalDescription.contains("trasporto") })
        assertTrue(LotExtractor.isDeliveryNoteRef("D.D.T. 77/ZZ del 03-02-2031"))
        assertTrue(!LotExtractor.isDeliveryNoteRef("DDT del 03-02-2031"))
    }

    @Test fun packSizesAndMonthsAreNeverLots() {
        for (t in listOf("KG9X7", "ML.333", "MT45X8PZ", "03/2031")) assertTrue(t, LotExtractor.neverALot(t))
        assertTrue(!LotExtractor.neverALot("AB731942"))
        val first = meat.lines[0].copy(notes = listOf(EInvoicePrintoutFixture.Note("LOTTO", "03/2031")))
        assertEquals(null, read(meatWith(first)).lineItems[0].lotNumber)
    }

    @Test fun thePaymentBlockEndsTheTable() {
        // No totals between the lines and the payment block: the payment row ("MP05 Bonifico 242,42") is no line.
        val pages = EInvoicePrintoutFixture.pages(meat.copy(summary = false, payment = "MP05 Bonifico"))
        val layouts = pages.map { LayoutRows.layout(OcrCleanup.repairLabels(OcrCleanup.stripRules(it))) }
        assertEquals(listOf(11500L, 6000L, 4800L), TableReader.read(layouts, textLayerPages = pages.indices.toSet())?.map { it.lineTotalCents?.value })
    }

    @Test fun summaryRowsAreNeverItems() {
        val groups = meat.groups.map { it.copy(label = "Esigibilità immediata") }
        val d = read(meat.copy(groups = groups, summaryHeading = false))
        assertEquals(listOf(11500L, 6000L, 4800L), d.lineItems.map { it.lineTotalCents?.value })
    }

    @Test fun aWeightIsNeverAnItem() {
        val d = read(meat.copy(footRows = listOf("PESO NETTO KG" to "23,50")))
        assertEquals(listOf(11500L, 6000L, 4800L), d.lineItems.map { it.lineTotalCents?.value })
    }

    // ------------------------------------------------------------------ totals not proven by the summary (F5)

    @Test fun stampDutyRoundingOrDiscountLeaveTaxableAndVatUnproven() {
        val src = "VAT summary rows add up to the total under its heading"
        for (inv in listOf(
            meat.copy(bollo = "2,00", total = "244,42"),
            meat.copy(rounding = "0,01", total = "242,43"),
            meat.copy(docDiscount = "-2,42", total = "240,00"),
        )) {
            val d = read(inv)
            assertTrue(d.subtotalCents?.source != src && d.vatCents?.source != src)
            assertTrue(d.totalCents?.confidence != Confidence.HIGH || d.totalCents?.source != src)
            assertTrue(AutoAccept.reasons(DocumentDraft.fromParsed(d)).isNotEmpty())
        }
    }

    // ------------------------------------------------------------------ the supplier's VAT number (F7)

    @Test fun supplierVatFromItsRoleBox() {
        assertEquals(SupplierVatSource.ROLE_BOX, read(meat).supplierVatSource)
        val letterhead = listOf(
            EInvoicePrintoutFixture.t(100, 100, "ABC S.r.l."),
            EInvoicePrintoutFixture.t(100, 150, "P.IVA 01234567897 - Reg. Imp. Roma"),
            EInvoicePrintoutFixture.t(100, 250, "FATTURA N. 12 DEL 03-02-2031"),
            EInvoicePrintoutFixture.t(100, 300, "PANE 1 2,00 2,00"),
            EInvoicePrintoutFixture.t(100, 350, "TOTALE DOCUMENTO 2,00"),
        )
        assertEquals(SupplierVatSource.OTHER, readPages(listOf(letterhead)).supplierVatSource)
    }

    // ------------------------------------------------------------------ a name printed over two lines (C2)

    @Test fun aNameRunningOnHoldsTheSave() {
        val first = meat.lines[0].copy(more = listOf("STAGIONATA DI PROVA"))
        val d = read(meatWith(first))
        assertEquals(listOf(11500L, 6000L, 4800L), d.lineItems.map { it.lineTotalCents?.value })
        assertEquals("the name continues on the next printed line", d.lineItems[0].nameNote)
        assertTrue(d.lineItems.drop(1).all { it.nameNote == null })
        val draft = DocumentDraft.fromParsed(d)
        assertTrue(draft.items[0].description.uncertain)
        assertTrue(ReviewReason.UNCERTAIN_VALUES in AutoAccept.reasons(draft))
        // On a photo the row is dropped as before, with no new hold.
        assertTrue(readPages(EInvoicePrintoutFixture.pages(meatWith(first)), textLayer = false).lineItems.all { it.nameNote == null })
    }

    // ------------------------------------------------------------------ a name wrapped onto the next line

    // Two names as long as the column allows (they end at its edge), as a program that wraps long names prints them.
    private val longNames = meat.copy(lines = listOf(
        meat.lines[0].copy(desc = "PROSCIUTTO CRUDO STAGIONATO DI"),
        meat.lines[1].copy(desc = "SALSICCIA FRESCA DI SUINO AL FI"),
        meat.lines[2],
    ))

    @Test fun aNameWrappedAtTheColumnsEdgeIsJoined() {
        val first = longNames.lines[0].copy(more = listOf("PROVA AFFETTATO"))
        val d = read(longNames.copy(lines = listOf(first) + longNames.lines.drop(1)))
        assertEquals("PROSCIUTTO CRUDO STAGIONATO DI PROVA AFFETTATO", d.lineItems[0].originalDescription)
        assertNull(d.lineItems[0].nameNote)
        assertEquals("PROSCIUTTO CRUDO STAGIONATO DI", d.lineItems[0].nameFirstLine)
        assertEquals(listOf(11500L, 6000L, 4800L), d.lineItems.map { it.lineTotalCents?.value })
        assertTrue(!DocumentDraft.fromParsed(d).items[0].description.uncertain)
        // A short name with a line under it stays a name to look at (aNameRunningOnHoldsTheSave); on a photo the row
        // is dropped as before.
        assertTrue(readPages(EInvoicePrintoutFixture.pages(longNames.copy(lines = listOf(first) + longNames.lines.drop(1))), textLayer = false)
            .lineItems.all { it.nameNote == null && it.nameFirstLine == null })
    }

    @Test fun aNameOverThreeLinesIsHeldNotJoined() {
        // Two lines under a name that wrapped: where it ends is not sure, so it is looked at, as before.
        val first = longNames.lines[0].copy(more = listOf("PROVA AFFETTATO SOTTILE DI", "MONTAGNA"))
        val d = read(longNames.copy(lines = listOf(first) + longNames.lines.drop(1)))
        assertEquals("the name continues on the next printed line", d.lineItems[0].nameNote)
        assertEquals("PROSCIUTTO CRUDO STAGIONATO DI", d.lineItems[0].originalDescription)
        assertEquals(listOf(11500L, 6000L, 4800L), d.lineItems.map { it.lineTotalCents?.value })
    }

    @Test fun aNameCutAtAHyphenIsJoined() {
        val first = meat.lines[0].copy(desc = "Pancetta tesa e salsic-", more = listOf("cia fresca"))
        val d = read(meatWith(first))
        assertEquals("Pancetta tesa e salsic cia fresca", d.lineItems[0].originalDescription)
        assertNull(d.lineItems[0].nameNote)
    }

    @Test fun aLetterCodeWithTheKindTheEInvoiceNamesIsNoPartOfTheName() {
        val first = meat.lines[0].copy(code = "X", codeType = "Codice articolo")
        val d = read(meatWith(first))
        assertEquals("Pancetta tesa", d.lineItems[0].originalDescription)
    }

    @Test fun aDeliveryNoteReferenceUnderALineIsNoPartOfItsName() {
        val first = meat.lines[0].copy(notes = emptyList(), more = listOf("Ns. doc. (DDT09) n. 77/ZZ del 03/02/2031"))
        val d = read(meatWith(first))
        assertEquals("Pancetta tesa", d.lineItems[0].originalDescription)
        assertNull(d.lineItems[0].nameNote)
        assertTrue(LotExtractor.isDeliveryNoteRef("Ns. doc. (DDT09) n. 77/ZZ del 03/02/2031"))
        assertTrue(LotExtractor.isDeliveryNoteRef("DDT 77/ZZ del 03-02-2031"))
    }
    @Test fun noTotalPrinted() {
        // An invoice whose "TOTALE DOCUMENTO" is left empty (the form an Italian buyer issues for a foreign supplier).
        val d = read(EInvoicePrintoutFixture.SELF_BILLED.copy(type = "TD01 fattura"))
        // Taxable and VAT under their headings: sure, as the summary row (VAT = 22% of the taxable) and the line agree.
        assertEquals(23400L, d.subtotalCents?.value)
        assertEquals(Confidence.HIGH, d.subtotalCents?.confidence)
        assertEquals(5148L, d.vatCents?.value)
        assertEquals(Confidence.HIGH, d.vatCents?.confidence)
        // The total is not printed: none is made up.
        assertNull(d.totalCents)
        // The line under a heading band of three printed lines ("PREZZO", "UM %IVA PREZZO TOTALE", "UNITARIO"), read by
        // its columns; the summary row "Esigib. non dich." is no line.
        assertEquals(listOf(23400L), d.lineItems.map { it.lineTotalCents?.value })
        assertEquals(listOf("22"), d.lineItems.map { it.vatRatePercent?.value?.stripTrailingZeros()?.toPlainString() })
        assertTrue(d.lineItems.none { it.originalDescription.contains("Esigib") })
    }

    @Test fun theDateOfTheInvoiceNamedOverTheLinesIsNotTheDocumentsDate() {
        // "Fatt.Coll. 4711 del 21-02-2031" names another document; the date is the one under "DATA / DOCUMENTO",
        // printed next to "ART. / 73".
        val d = read(EInvoicePrintoutFixture.SELF_BILLED.copy(type = "TD01 fattura"))
        assertEquals(LocalDate.of(2031, 2, 28), d.documentDate?.value)
        assertEquals(Confidence.HIGH, d.documentDate?.confidence)
        assertEquals("00000003/2031/A", d.documentNumber?.value)
    }

    @Test fun aForeignSuppliersBoxProvesItsName() {
        // Read from the PDF's own text, the name in the supplier's box with a VAT number whose (German) check digits
        // are right: the supplier is sure.
        val d = read(EInvoicePrintoutFixture.SELF_BILLED.copy(type = "TD01 fattura"))
        assertEquals("MUSTER HANDEL GMBH", d.sellerName?.value)
        assertEquals(SupplierProof.State.PROVEN, d.supplierProof?.state)
        // The same box with one digit misread proves nothing.
        val misread = read(EInvoicePrintoutFixture.SELF_BILLED.copy(type = "TD01 fattura", supplierVat = "DE123456789"))
        assertNotEquals(SupplierProof.State.PROVEN, misread.supplierProof?.state)
    }
}
