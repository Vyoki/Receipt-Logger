package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DraftValidatorTest {

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    @Test fun parsedFixtureBecomesValidDocument() {
        val draft = DocumentDraft.fromParsed(ReceiptParser.parse(fixture("fattura_caseificio.txt")))
        assertEquals("14/03/2025", draft.date.text)
        assertEquals("71,06", draft.total.text)
        assertEquals("2,5", draft.items[0].quantity.text)
        assertEquals("", draft.items[2].lot.text) // missing, shown as missing
        val r = DraftValidator.validate(draft) as ValidationResult.Valid
        assertEquals(LocalDate.of(2025, 3, 14), r.document.date)
        assertEquals(7106L, r.document.totalCents)
        assertEquals(4, r.document.items.size)
    }

    @Test fun lowConfidenceFieldsAreMarkedUncertain() {
        val draft = DocumentDraft.fromParsed(ReceiptParser.parse(fixture("scontrino_mercato.txt")))
        assertTrue(draft.seller.uncertain)
        assertTrue(draft.uncertainCount > 0)
        val confirmed = draft.copy(seller = draft.seller.confirmed("Mercato Fresco"))
        assertEquals(draft.uncertainCount - 1, confirmed.uncertainCount)
    }

    @Test fun reportsFieldErrors() {
        val draft = DocumentDraft(
            seller = DraftField(""),
            date = DraftField("31/02/2025"),
            total = DraftField("12,345"),
            currency = DraftField("euro"),
            items = listOf(
                LineItemDraft(1, description = DraftField(""), quantity = DraftField("0"), unitPrice = DraftField("abc"), vatRate = DraftField("150")),
            ),
        )
        val errors = (DraftValidator.validate(draft) as ValidationResult.Invalid).errors
        assertTrue(FieldError("seller", ErrorCode.REQUIRED) in errors)
        assertTrue(FieldError("date", ErrorCode.INVALID_DATE) in errors)
        assertTrue(FieldError("total", ErrorCode.INVALID_NUMBER) in errors)
        assertTrue(FieldError("currency", ErrorCode.INVALID_CURRENCY) in errors)
        assertTrue(FieldError("items[0].description", ErrorCode.REQUIRED) in errors)
        assertTrue(FieldError("items[0].quantity", ErrorCode.MUST_NOT_BE_ZERO) in errors)
        assertTrue(FieldError("items[0].unitPrice", ErrorCode.INVALID_NUMBER) in errors)
        assertTrue(FieldError("items[0].vatRate", ErrorCode.OUT_OF_RANGE) in errors)
    }

    @Test fun missingOptionalFieldsStayNull() {
        val r = DraftValidator.validate(DocumentDraft(seller = DraftField("X"), items = listOf(LineItemDraft(1, DraftField("Pane")))))
        val doc = (r as ValidationResult.Valid).document
        assertEquals(null, doc.date)
        assertEquals(null, doc.totalCents)
        assertEquals(null, doc.items[0].quantity)
        assertEquals(null, doc.items[0].lotNumber)
    }

    @Test fun computedTotalIsOnlyASuggestion() {
        val item = LineItemDraft(1, DraftField("Limoni"), quantity = DraftField("2"), unitPrice = DraftField("1,20"))
        assertEquals(240L, item.computedTotalCents())
        assertEquals("", item.lineTotal.text)
        assertEquals(BigDecimal("2"), ItalianNumbers.parse(item.quantity.text))
    }
}
