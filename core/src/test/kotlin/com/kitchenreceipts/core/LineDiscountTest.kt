package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class LineDiscountTest {

    @Test fun printedForms() {
        assertEquals("30", LineDiscount.normalize("30,0"))
        assertEquals("10+5", LineDiscount.normalize("10+5%"))
        assertEquals("12,5", LineDiscount.normalize(" 12,50 "))
        assertNull(LineDiscount.normalize(""))
        assertNull(LineDiscount.normalize("0"))
        assertNull(LineDiscount.normalize("100"))
        assertNull(LineDiscount.normalize("abc"))
        assertEquals(0, BigDecimal("14.5").compareTo(LineDiscount.percent("10+5")))
    }

    @Test fun amountLessTheDiscount() {
        // 4,000 kg x 20,00 less 30% = 56,00
        assertEquals(5600L, LineDiscount.net(BigDecimal("4"), BigDecimal("20.00"), "30"))
        assertTrue(LineDiscount.matches(BigDecimal("4"), BigDecimal("20.00"), "30", 5600))
        assertFalse(LineDiscount.matches(BigDecimal("4"), BigDecimal("20.00"), null, 5600))
        assertTrue(LineDiscount.matches(BigDecimal("3"), BigDecimal("25.00"), null, 7500))
    }

    @Test fun draftKeepsTheDiscountAndChecksIt() {
        val line = LineItemDraft(
            key = 1, description = DraftField("ALETTA DI SPALLA"), quantity = DraftField("4,000"), unit = DraftField("kg"),
            unitPrice = DraftField("20,00"), lineTotal = DraftField("56,00"), vatRate = DraftField("10"), discount = DraftField("30"),
        )
        assertEquals(true, line.addsUp())
        assertEquals(false, line.copy(discount = DraftField("")).addsUp())
        val valid = DraftValidator.validate(DocumentDraft(seller = DraftField("ABC S.r.l."), date = DraftField("07/10/2026"), items = listOf(line)))
        assertEquals("30", (valid as ValidationResult.Valid).document.items.single().discount)
        val wrong = DraftValidator.validate(DocumentDraft(seller = DraftField("ABC S.r.l."), items = listOf(line.copy(discount = DraftField("trenta")))))
        assertTrue((wrong as ValidationResult.Invalid).errors.any { it.field == "items[0].discount" })
    }

    /** An invoice program's PDF: a LOTTO column, a price glued to its discount by a sign, totals inside the VAT summary. */
    @Test fun digitalInvoiceWithLotsAndDiscount() {
        fun put(text: String, x: Float, y: Float): List<TextLayer.Glyph> =
            text.mapIndexedNotNull { i, ch -> if (ch == ' ') null else TextLayer.Glyph(ch.toString(), x + i * 16.4f, y, x + i * 16.4f + 16.4f, y + 26) }
        val g = put("VERDE FRESCO S.p.A.", 65f, 300f) + put("P.IVA 01234567897", 65f, 340f) +
            put("NUMERO", 1500f, 900f) + put("DATA", 1700f, 900f) + put("B26 305511", 1500f, 940f) + put("07/10/26", 1700f, 940f) +
            put("ARTICOLO", 118f, 1109f) + put("DESCRIZIONE", 571f, 1109f) + put("LOTTO", 1153f, 1109f) + put("U.M.", 1364f, 1109f) +
            put("QUANTITA'", 1460f, 1109f) + put("PREZZO", 1661f, 1109f) + put("SCONTO", 1848f, 1109f) + put("IMPORTO TOTALE", 2020f, 1109f) + put("IVA", 2261f, 1109f) +
            put("AB006", 113f, 1323f) + put("CONTROFILETTO BOVINO", 276f, 1323f) + put("1111111111", 1057f, 1323f) + put("KG", 1366f, 1323f) +
            put("5,200", 1496f, 1323f) + put("30,00", 1707f, 1323f) + put("156,00", 2114f, 1323f) + put("10", 2276f, 1323f) +
            put("AB042", 113f, 1425f) + put("ALETTA DI SPALLA", 276f, 1425f) + put("2222222222", 1057f, 1425f) + put("KG", 1366f, 1425f) +
            put("4,000", 1496f, 1425f) + put("20,00§30,0", 1707f, 1425f) + put("56,00", 2130f, 1425f) + put("10", 2276f, 1425f) +
            put("CD520", 113f, 1528f) + put("TARTARE DI SALMONE", 276f, 1528f) + put("3333333333", 1057f, 1528f) + put("NR", 1366f, 1528f) +
            put("3", 1561f, 1528f) + put("25,00", 1707f, 1528f) + put("75,00", 2130f, 1528f) + put("10", 2276f, 1528f) +
            put("COD.IVA", 99f, 2159f) + put("IMPONIBILE", 300f, 2159f) + put("ALIQ.", 701f, 2159f) + put("IMPOSTA O ESENZIONE", 872f, 2159f) +
            put("SCADENZE", 1273f, 2159f) + put("TOT. IMPONIBILE", 1702f, 2159f) + put("TOT. DOCUMENTO", 2017f, 2159f) +
            put("10", 113f, 2195f) + put("287,00", 504f, 2195f) + put("10", 731f, 2195f) + put("28,70", 992f, 2195f) + put("7/10/26", 1317f, 2195f) +
            put("315,70", 1528f, 2195f) + put("287,00", 1837f, 2195f) + put("315,70", 2130f, 2195f) +
            put("TOT. IVA", 1702f, 2343f) + put("NETTO A PAGARE", 2017f, 2343f) + put("28,70", 1821f, 2400f) + put("315,70EUR", 2081f, 2400f)
        val d = ReceiptParser.parsePages(listOf(TextLayer.lines(g)), ParseOptions(today = LocalDate.of(2026, 10, 8)))
        assertEquals(listOf("1111111111", "2222222222", "3333333333"), d.lineItems.map { it.lotNumber?.value })
        val aletta = d.lineItems[1]
        assertEquals(0, BigDecimal("20.00").compareTo(aletta.unitPrice?.value))
        assertEquals(0, BigDecimal("4").compareTo(aletta.quantity?.value))
        assertEquals("30", aletta.discount?.value)
        assertEquals(5600L, aletta.lineTotalCents?.value)
        assertEquals(28700L, d.subtotalCents?.value)
        assertEquals(2870L, d.vatCents?.value)
        assertEquals(31570L, d.totalCents?.value)
        assertTrue(d.warnings.toString(), d.warnings.isEmpty())
    }
}
