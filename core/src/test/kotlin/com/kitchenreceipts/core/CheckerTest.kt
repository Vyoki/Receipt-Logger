package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checker's cases (invented invoice of ABC S.r.l.) and its model, against what tools/checker writes. */
class CheckerTest {

    private fun line(key: Long, desc: String, qty: String, price: String, amount: String, uncertain: Boolean = false) = LineItemDraft(
        key, DraftField(desc), quantity = DraftField(qty), unit = DraftField("kg"), unitPrice = DraftField(price),
        lineTotal = DraftField(amount, uncertain = uncertain),
    )

    private val shown = DocumentDraft(
        seller = DraftField("ABE S.r.l.", uncertain = true, source = "unproven by 1: reading=ABE S.r.l."),
        date = DraftField("03/02/2031", source = "page 1: FATTURA 77/ZZ DEL 03/02/2031"),
        number = DraftField("77/ZZ"),
        total = DraftField("49,20"),
        items = listOf(line(1, "MOZZARELLA FIOR DI LATTE", "2", "8,90", "17,80"), line(2, "PASSATA DI POMODORO", "12", "1,05", "12,60", uncertain = true)),
    )

    @Test fun eachValueIsACaseLabelledByWhatTheOperatorSaved() {
        // The supplier corrected, the date kept (written another way), the second line removed, the first kept.
        val saved = shown.copy(seller = DraftField("ABC S.r.l."), date = DraftField("3/2/2031"), items = listOf(shown.items[0]))
        val cases = CheckerCases.cases("g1", shown, saved, null, CheckerCases.Reading(textLayer = true, pages = 1))
        fun case(kind: String, i: Int = 0) = cases.filter { it.kind == kind }[i]
        assertFalse(case("seller").right)
        assertEquals(1.0, case("seller").features["uncertain"])
        assertEquals("unproven", case("seller").src)
        assertTrue(case("date").right)
        assertTrue(case("number").right)
        assertTrue(case("total").right)
        // No case for a value read empty (taxable, VAT).
        assertTrue(cases.none { it.kind == "subtotal" || it.kind == "vat" })
        assertTrue(case("amount", 0).right)
        assertEquals(1.0, case("amount", 0).features["lineOk"])
        // The removed line's values are wrong.
        assertFalse(case("amount", 1).right)
        assertFalse(case("description", 1).right)
        assertEquals(1.0, case("amount", 0).features["textLayer"])
        assertTrue(cases.all { it.group == "g1" })
    }

    @Test fun aRowHoldsNoTextOfTheDocument() {
        val cases = CheckerCases.cases("g1", shown, shown, null)
        val file = (listOf(CheckerCases.header()) + cases.map(CheckerCases::row)).joinToString("\n")
        for (word in listOf("ABE", "ABC", "MOZZARELLA", "PASSATA", "77/ZZ", "17,80", "49,20", "2031", "FATTURA")) {
            assertFalse("\"$word\" in the file", file.contains(word))
        }
        // Words of a source outside the fixed list are "other"; a printed row is never kept.
        assertEquals("other", CheckerCases.src("MOZZARELLA FIOR DI LATTE 2 8,90"))
        assertEquals("page", CheckerCases.src("page 1: ABC S.r.l."))
        assertEquals("none", CheckerCases.src(null))
        // Every row has the header's columns.
        val columns = CheckerCases.header().split(',').size
        assertTrue(cases.all { CheckerCases.row(it).split(',').size == columns })
    }

    @Test fun aHandMadeModel() {
        // One tree on "uncertain" (left when 0), one on the field's kind; base 0, rate 1.
        val json = """{"version": 1, "features": ["uncertain", "kind=amount"], "base": 0, "rate": 1,
            "trees": [{"f": [0, -2, -2], "t": [0.5, -2, -2], "l": [1, -1, -1], "r": [2, -1, -1], "v": [0, 2.0, -2.0]},
                      {"f": [1, -2, -2], "t": [0.5, -2, -2], "l": [1, -1, -1], "r": [2, -1, -1], "v": [0, 0.0, 3.0]}],
            "threshold": 0.95, "ready": true, "stats": {"invoices": 300}}"""
        val m = CheckerModel.parse(json)
        fun case(kind: String, uncertain: Double) = CheckerCases.Case("g", kind, "none", true, mapOf("uncertain" to uncertain))
        assertEquals(1 / (1 + Math.exp(-2.0)), m.chance(case("date", 0.0)), 1e-12)
        assertEquals(1 / (1 + Math.exp(2.0)), m.chance(case("date", 1.0)), 1e-12)
        assertEquals(1 / (1 + Math.exp(-5.0)), m.chance(case("amount", 0.0)), 1e-12)
        assertTrue(m.clears(case("amount", 0.0)))
        assertFalse(m.clears(case("date", 0.0)))
        assertEquals("300", m.stats["invoices"])
        // Not ready: never clears anything.
        assertFalse(CheckerModel.parse(json.replace("\"ready\": true", "\"ready\": false")).clears(case("amount", 0.0)))
    }

    @Test fun theModelGivesTheTrainersChances() {
        // A small model trained by tools/checker/train_checker.py on cases of the invented benchmark documents, with
        // the chances scikit-learn gave for some of them.
        val text = javaClass.getResource("/checker/model.json")!!.readText()
        val m = CheckerModel.parse(text)
        assertFalse(m.ready)
        val samples = (Json.parse(text) as Map<*, *>)["samples"] as List<*>
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            s as Map<*, *>
            val features = (s["features"] as Map<*, *>).entries.associate { (k, v) -> k as String to (v as String).toDouble() }
            val case = CheckerCases.Case("g", s["kind"] as String, s["src"] as String, true, features)
            assertEquals((s["chance"] as String).toDouble(), m.chance(case), 1e-9)
        }
        // Every feature the phone writes is one the trainer knows.
        assertTrue(CheckerCases.FEATURES.all { it in m.features })
    }
}
