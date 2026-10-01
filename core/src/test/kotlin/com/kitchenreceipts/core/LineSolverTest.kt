package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class LineSolverTest {
    private fun dec(s: String) = BigDecimal(s)
    private fun eq(e: String, a: BigDecimal?) = assertTrue("expected $e but was $a", a != null && a.compareTo(dec(e)) == 0)

    @Test fun oneReadingAddsUp() {
        val r = LineSolver.solve("07752 3FOCACCIA ROMANA SPICCHI GR.450 NR 12,000c 2,150 25,80 10").single()
        eq("12", r.quantity)
        eq("2.15", r.unitPrice)
        assertEquals(2580L, r.lineTotalCents)
        assertEquals("pz", r.unit)
    }

    @Test fun discountColumn() {
        val r = LineSolver.solve("PROSCIUTTO KG 2,000 10,000 10 18,00").single() // 2 x 10,00 less 10% = 18,00
        eq("10", r.discountPercent)
        eq("2", r.quantity)
        eq("10", r.unitPrice)
    }

    @Test fun severalReadingsAreChoices() {
        val r = LineSolver.solve("ARTICOLO VARIO 2 4 1,00 2,00 4,00")
        assertEquals(2, r.size)
    }

    @Test fun nothingFits() {
        assertTrue(LineSolver.solve("PANE 2 3,10 7,00").isEmpty())
    }

    @Test fun settlesADoubtfulLine() {
        val src = "07752 FOCACCIA GR.450 NR 12,000 2,150 25,80 10"
        val doubtful = ParsedLineItem(
            "FOCACCIA GR.450 NR 12,000", Extracted(dec("2.15"), Confidence.LOW, src), null, null,
            Extracted(2580L, Confidence.LOW, src), null, null, null, setOf(ParseWarning.LINE_TOTAL_MISMATCH),
        )
        val fixed = LineSolver.settle(listOf(doubtful)).single()
        eq("12", fixed.quantity?.value)
        assertEquals(Confidence.HIGH, fixed.quantity?.confidence)
        assertEquals("FOCACCIA GR.450", fixed.originalDescription)
        assertEquals("pz", fixed.unit?.value)
        assertTrue(fixed.warnings.isEmpty())
    }

    @Test fun rememberedChoiceSettlesTheSameDoubtNextTime() {
        val choices = LineSolver.solve("ARTICOLO VARIO 2 4 1,00 2,00 4,00")
        assertEquals(2, choices.size)
        val draft = LineItemDraft(1, quantity = DraftField("2", uncertain = true), choices = choices)
        // The operator picked the second reading once: that is remembered as a rule for the supplier.
        val rule = choices[1].rule
        assertEquals(rule, ChoiceRule.decode(rule.encode()))
        val settled = LineSolver.applyRules(listOf(draft), setOf(rule)).single()
        assertTrue(settled.choices.isEmpty())
        assertEquals(ItalianNumbers.toEditText(choices[1].quantity), settled.quantity.text)
        assertTrue(!settled.quantity.uncertain)
        // No rule for this supplier: the operator is asked.
        assertEquals(2, LineSolver.applyRules(listOf(draft), emptySet()).single().choices.size)
    }

    @Test fun aiAnswersWithOneLetter() {
        val choices = LineSolver.solve("ARTICOLO VARIO 2 4 1,00 2,00 4,00")
        assertEquals(choices[1], AiReader.decodeChoice("B", choices))
        assertNull(AiReader.decodeChoice("X", choices))
        assertNull(AiReader.decodeChoice("D", choices))
        val it = AiReader.choiceInstruction("QUANTITA PREZZO IMPORTO", "ARTICOLO VARIO 2 4 1,00 2,00 4,00", choices, AiReader.Lang.IT)
        assertTrue(it.contains("A: QUANTITA'") && it.contains("B: QUANTITA'"))
        assertTrue(AiReader.instruction("", AiReader.Lang.IT).startsWith("La foto mostra"))
        assertTrue(AiReader.instruction("", AiReader.Lang.EN).startsWith("This photo shows"))
    }

    @Test fun supplierExamplesInTheLineQuestion() {
        val ex = AiReader.RowExample("04411 1/SPALLA KG 4,000 9,250 37,00 10", "04411", "1", "SPALLA", "KG", "4,000", "9,250", "37,00", "10")
        val q = AiReader.rowInstruction("COD COLLI DESCRIZIONE", "07752 3FOCACCIA NR 12,000c 2,150 25,80 10", AiReader.Lang.EN, listOf(ex))
        assertTrue(q.contains("\"quantity\":\"4,000\""))
        assertTrue(AiReader.decodeItem(ex.answerJson())?.price == "9,250")
    }

    @Test fun ocrRepairs() {
        assertEquals("B26 204177 22/09/2026 1/1", OcrCleanup.cleanLine("B26 20417722/09/2026 1/1"))
        assertEquals("NR 24,000 C 2,384", OcrCleanup.cleanLine("NR 24,000c 2,384"))
        assertEquals("SECCHIELLO GR.1200", OcrCleanup.cleanLine("SECCHIELLO GR.12c0"))
        assertEquals("GIALLO 12,50", OcrCleanup.cleanLine("GIALLO 12,50")) // words untouched
    }
}

/** A noisier photo of the frozen/fresh wholesaler's delivery note (content invented). */
class DdtGluedTest {
    private val text = javaClass.classLoader!!.getResource("fixtures/ddt_surgelati_glued.txt")!!.readText()
    private val d = ReceiptParser.parse(text, ParseOptions(ownVatNumber = "09876543217"))
    private fun item(prefix: String) = d.lineItems.first { it.originalDescription.startsWith(prefix) }

    @Test fun header() {
        assertEquals("VERDE FRESCO S.p.A.", d.sellerName?.value)
        assertEquals(LocalDate.of(2026, 9, 15), d.documentDate?.value) // "20417715/09/2026": number and date glued
        assertEquals("B26 204177", d.documentNumber?.value)
        assertEquals(13432L, d.totalCents?.value)
    }

    @Test fun allSevenLinesAddUp() {
        assertEquals(listOf(3700L, 2580L, 1023L, 1780L, 746L, 1182L, 1440L), d.lineItems.map { it.lineTotalCents?.value })
        assertTrue(d.lineItems.all { it.quantity?.confidence == Confidence.HIGH && it.warnings.isEmpty() })
        assertEquals(12451L, d.lineItems.sumOf { it.lineTotalCents!!.value })
    }

    @Test fun pkgsGluedToTheName() {
        assertEquals("1", item("SPALLA").packages?.value) // "1/SPALLA"
        assertEquals("3", item("FOCACCIA").packages?.value) // "3FOCACCIA"
        assertEquals("1", item("PASSATA").packages?.value) // "1PASSATA"
        assertNull(item("ORIGANO").packages) // none printed
        assertEquals("2", item("OLIO").packages?.value)
    }

    @Test fun storageLetterGluedToTheQuantity() {
        val f = item("FOCACCIA") // "NR 12,000c 2,150 25,80"
        assertTrue(f.quantity!!.value.compareTo(BigDecimal(12)) == 0)
        assertEquals("pz", f.unit?.value)
        assertEquals("FOCACCIA ROMANA SPICCHI GR.450", f.originalDescription)
        assertEquals("NOCI SGUSC. SECCHIELLO GR.1000", item("NOCI").originalDescription)
    }
}

class FieldLocatorTest {
    private fun w(t: String, l: Int, r: Int, top: Int = 100) = OcrLine(t, l, top, r, top + 20)
    private val page = listOf(
        OcrLine("DOCUMENTO DI TRASPORTO", 10, 10, 400, 30),
        OcrLine("07752 3FOCACCIA ROMANA NR 1 1,000 2,150 25,80", 10, 100, 600, 120, words = listOf(
            w("07752", 10, 60), w("3FOCACCIA", 70, 160), w("ROMANA", 170, 240), w("NR", 250, 270), w("1", 280, 290),
            w("1,000", 300, 350), w("2,150", 360, 410), w("25,80", 420, 470),
        )),
        OcrLine("12253 PEPE NERO CT 1,000 14,400 14,40", 10, 200, 600, 220),
    )

    @Test fun findsTheValueInItsRow() {
        val s = FieldLocator.locate(listOf(page), "07752 3FOCACCIA ROMANA NR 1 1,000 2,150 25,80", "2,15")!!
        assertEquals(0, s.page)
        assertTrue(s.exact)
        assertEquals(360, s.mark.left)
        // A quantity "1" is the printed 1,000, not the bare 1 of the Pkgs column.
        assertEquals(300, FieldLocator.locate(listOf(page), "07752 3FOCACCIA ROMANA NR 1 1,000 2,150 25,80", "1")!!.mark.left)
    }

    @Test fun wholeRowWhenNoWordBoxes() {
        val s = FieldLocator.locate(listOf(page), "12253 PEPE NERO CT 1,000 14,400 14,40", "14,4")!!
        assertEquals(200, s.mark.top)
        assertTrue(!s.exact || s.mark.right - s.mark.left <= 590)
    }

    @Test fun nothingWhenTheRowIsNotThere() {
        assertNull(FieldLocator.locate(listOf(page), "SALMONE AFFUMICATO 3,50", "3,5"))
    }
}
