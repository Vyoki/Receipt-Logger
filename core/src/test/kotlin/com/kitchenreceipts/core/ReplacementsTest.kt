package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReplacementsTest {

    private fun sure(t: String) = DraftField(t)
    private fun doubt(t: String) = DraftField(t, uncertain = true)

    @Test fun lineAmountFromSureQuantityAndPrice() {
        // 4,18 x 18,900 = 79,00: a doubtful amount read as 79,90 gets 79,00 offered; one read right gets nothing.
        val line = LineItemDraft(1, quantity = sure("4,18"), unitPrice = sure("18,900"), lineTotal = doubt("79,90"))
        assertEquals(listOf("79,00"), Replacements.compute(DocumentDraft(items = listOf(line)))[Replacements.item(1, "lineTotal")])
        val right = line.copy(lineTotal = doubt("79,00"))
        assertNull(Replacements.compute(DocumentDraft(items = listOf(right)))[Replacements.item(1, "lineTotal")])
    }

    @Test fun priceFromAmountAndQuantity() {
        // "S,390" misread: the amount and the quantity give the price.
        val d = DocumentDraft(items = listOf(LineItemDraft(1, quantity = sure("2,56"), unitPrice = DraftField(""), lineTotal = sure("13,80"))))
        assertEquals("5,39", Replacements.compute(d)[Replacements.item(1, "unitPrice")]?.first())
    }

    @Test fun quantityFromAmountAndPrice() {
        val d = DocumentDraft(items = listOf(LineItemDraft(1, quantity = doubt("1"), unitPrice = sure("7,990"), lineTotal = sure("39,95"))))
        assertEquals("5", Replacements.compute(d)[Replacements.item(1, "quantity")]?.first())
    }

    @Test fun totalsFromEachOther() {
        val d = DocumentDraft(subtotal = sure("753,71"), vat = sure("67,07"), total = doubt("826,78"))
        assertEquals(listOf("820,78"), Replacements.compute(d)[Replacements.header("total")])
        val v = DocumentDraft(subtotal = sure("753,71"), vat = DraftField(""), total = sure("820,78"))
        assertEquals(listOf("67,07"), Replacements.compute(v)[Replacements.header("vat")])
    }

    @Test fun aiReadingsAreOffered() {
        val d = DocumentDraft(
            seller = doubt("GME"), number = sure("12A/34567"),
            aiCheck = AiCheck(3, emptyList(), mapOf("seller" to "ABC S.r.l.", "number" to "12A/34567")),
            items = listOf(LineItemDraft(7, description = doubt("PASTA RIG500 GR 150"), aiRead = mapOf("description" to "PASTA RIGATONI 500"))),
        )
        val r = Replacements.compute(d)
        assertEquals(listOf("ABC S.r.l."), r[Replacements.header("seller")])
        assertNull(r[Replacements.header("number")]) // sure: nothing to replace
        assertEquals(listOf("PASTA RIGATONI 500"), r[Replacements.item(7, "description")])
    }

    @Test fun misreadSupplierNameFindsTheSavedOne() {
        val known = listOf("ABC S.r.l.", "VERDE FRESCO S.p.A.", "RISTORANTE PROVA SAS")
        assertEquals(listOf("ABC S.r.l."), Replacements.similarSellers("ABG", known))
        assertEquals(listOf("VERDE FRESCO S.p.A."), Replacements.similarSellers("VERDE FRESC0 SPA", known))
        assertTrue(Replacements.similarSellers("XYZ", known).isEmpty())
        val d = DocumentDraft(seller = doubt("ABG S.R.L."))
        assertEquals("ABC S.r.l.", Replacements.compute(d, known)[Replacements.header("seller")]?.first())
    }

    @Test fun dateInAnImpossibleYear() {
        val today = LocalDate.of(2026, 10, 6)
        assertEquals("26/09/2026", Replacements.plausibleYear("26/09/2078", today))
        assertEquals("12/12/2025", Replacements.plausibleYear("12/12/2026", today)) // in the future this year: last year
        assertNull(Replacements.plausibleYear("26/09/2026", today))
        assertEquals(listOf("26/09/2026"), Replacements.compute(DocumentDraft(date = doubt("26/09/2078")), today = today)[Replacements.header("date")])
    }

    @Test fun vatRateOfEveryOtherLine() {
        val d = DocumentDraft(items = listOf(
            LineItemDraft(1, vatRate = sure("22")), LineItemDraft(2, vatRate = sure("22")), LineItemDraft(3, vatRate = DraftField("")),
        ))
        assertEquals(listOf("22"), Replacements.compute(d)[Replacements.item(3, "vatRate")])
    }

    @Test fun sureFieldsGetNothing() {
        val d = DocumentDraft(seller = sure("ABC S.r.l."), total = sure("10,00"), items = listOf(LineItemDraft(1, description = sure("PANE"), lineTotal = sure("10,00"))))
        assertTrue(Replacements.compute(d, listOf("ABD S.r.l.")).isEmpty())
    }

    @Test fun theAiHeaderReadingIsKeptAsAReplacement() {
        // The supplier's name misread by the OCR; the AI reads it right but the OCR never saw those letters, so it is
        // not taken: it must still reach the operator as the replacement.
        val text = "ABG S.R.L.\nVia Roma 1 Roma\nP.IVA 01234567897\nFATTURA N. 12A/34567 del 26/09/2026\nPANE KG 2,000 3,00 6,00\nTOTALE 6,00"
        val doc = ReceiptParser.parse(text)
        val answer = "{\"seller\": \"ABC S.r.l.\", \"number\": \"12A/34567\", \"date\": \"26/09/2026\"}"
        val checked = AiReader.applyTargets(doc, listOf(AiTarget.Header(0, emptyList()) to answer), text)
        assertEquals("ABC S.r.l.", checked.aiCheck?.header?.get("seller"))
        assertNull(checked.aiCheck?.header?.get("number")) // same as read
        val draft = DocumentDraft.fromParsed(checked)
        assertTrue("a supplier the AI spells differently is looked at", draft.seller.uncertain)
        assertEquals("ABC S.r.l.", Replacements.compute(draft)[Replacements.header("seller")]?.first())
    }
}
