package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app learns about a supplier's documents, and how it is used next time (content invented). */
class SupplierLayoutTest {

    @Test fun numberShapes() {
        assertEquals("99A/99999", SupplierLayouts.shape("12A/34567"))
        assertEquals("A99 999999", SupplierLayouts.shape("B26  204177"))
        assertTrue(SupplierLayouts.matchesShape("12A/34568", "99A/99999"))
        assertTrue(SupplierLayouts.matchesShape("12A/134567", "99A/99999")) // the counter grew a digit
        assertFalse(SupplierLayouts.matchesShape("B26 204177", "99A/99999"))
        assertFalse(SupplierLayouts.matchesShape("00100 ROMA", "A99 999999"))
    }

    @Test fun storedAndReadBack() {
        val l = SupplierLayout(mapOf("colli/descrizione" to TableReader.Kind.DESCRIPTION), "99A/99999", true, false, 3)
        assertEquals(l, SupplierLayout.decode(l.encode()))
        assertNull(SupplierLayout.decode("garbage"))
        val merged = SupplierLayouts.merge(l, SupplierLayout(numberShape = "99A/999999", documents = 1))
        assertEquals("99A/999999", merged.numberShape)
        assertEquals(4, merged.documents)
        assertEquals(TableReader.Kind.DESCRIPTION, merged.headings["colli/descrizione"])
    }

    @Test fun learnedNumberShapeFindsTheNumber() {
        val text = "ABC S.r.l.\nReg. Imp. RM, C.F. - P.IVA 01234567897\nCOPIA FATTURA\n12A/34567 30/09/2026 RIMESSA DIRETTA\n" +
            "DESCRIZIONE QUANTITA PREZZO IMPORTO\nPASSATA DI POMODORO 700G 12 1,05 12,60\nTOTALE DOCUMENTO 12,60\n"
        val plain = ReceiptParser.parse(text)
        val learned = ReceiptParser.parse(text, ParseOptions(layout = SupplierLayout(numberShape = "99A/99999", documents = 2)))
        assertEquals("12A/34567", learned.documentNumber?.value)
        assertEquals(Confidence.HIGH, learned.documentNumber?.confidence)
        // Without what was learned the number is not certain (or not found).
        assertTrue(plain.documentNumber == null || plain.documentNumber.confidence == Confidence.LOW || plain.documentNumber.value == "12A/34567")
    }

    @Test fun digitsOnlyShapeDecidesNothing() {
        val text = "ABC S.r.l.\nVia Esempio 1 00100 ROMA\nDESCRIZIONE QUANTITA PREZZO IMPORTO\nPANE 2 1,50 3,00\nTOTALE 3,00\n"
        val d = ReceiptParser.parse(text, ParseOptions(layout = SupplierLayout(numberShape = "99999")))
        assertNull(d.documentNumber?.takeIf { it.value == "00100" && it.confidence == Confidence.HIGH })
    }

    private fun ddtPages(): List<List<OcrLine>> {
        val text = javaClass.classLoader!!.getResource("fixtures/ddt_surgelati_boxes.txt")!!.readText()
        val pages = mutableListOf<MutableList<OcrLine>>()
        val word = Regex("(-?\\d+)-(-?\\d+):(\\S+)")
        for (l in text.lines()) {
            if (l.startsWith("=== Raw lines")) { pages += mutableListOf<OcrLine>(); continue }
            if (l.startsWith("#") || !l.contains(" | ")) continue
            val (g, rest) = l.split(" | ", limit = 2)
            val n = g.split(",")
            val bracket = rest.lastIndexOf("  [")
            val boxed = bracket >= 0 && rest.endsWith("]")
            val words = if (boxed) rest.substring(bracket + 3, rest.length - 1).split(' ').mapNotNull { w ->
                word.matchEntire(w)?.let { m -> OcrLine(m.groupValues[3], m.groupValues[1].toInt(), n[1].toInt(), m.groupValues[2].toInt(), n[3].toInt()) }
            } else emptyList()
            pages.last() += OcrLine(if (boxed) rest.substring(0, bracket) else rest, n[0].toInt(), n[1].toInt(), n[2].toInt(), n[3].toInt(), n[4].toFloat(), words)
        }
        return pages
    }

    @Test fun learnFromAConfirmedDocumentAndUseItNextTime() {
        val pages = ddtPages()
        val options = ParseOptions(null, "09876543217")
        val read = ReceiptParser.parsePages(pages, options)
        val learned = SupplierLayouts.learn(read, DocumentDraft.fromParsed(read))
        assertEquals("A99 999999", learned.numberShape)
        assertTrue(learned.lotsUnderItems)
        assertEquals(false, learned.readByColumns)

        // Next document of the same supplier (found by its VAT number): read with what was learned.
        var asked: String? = null
        val next = ReceiptParser.parsePages(pages, options.copy(layoutLookup = { key -> asked = key; learned }))
        assertEquals("vat:01234567897", asked)
        assertNotNull(next.layout)
        assertEquals("B26 204177", next.documentNumber?.value)
        assertEquals(Confidence.HIGH, next.documentNumber?.confidence)
        assertEquals(7, next.lineItems.size)
    }

    @Test fun aiAnswerAboutHeadings() {
        val pages = ddtPages()
        val read = ReceiptParser.parsePages(pages, ParseOptions(null, "09876543217"))
        // The question is only asked when the reading does not check out; here: pretend the date was not found.
        val q = AiTargets.layoutQuestion(pages, read.copy(documentDate = null))
        assertNotNull(q)
        assertEquals(null, AiTargets.layoutQuestion(pages, read)) // checks out: nothing to ask
        val headings = q!!.headings
        assertTrue(headings.toString(), headings.any { it.startsWith("COLLI/DESCRIZIONE") })
        assertTrue(headings.toString(), "U.M." in headings && headings.any { it.startsWith("QUANTITA") })
        val answer = headings.joinToString(",") { h ->
            when {
                h.contains("DESCR") -> "C"; h.startsWith("COD") -> "A"; h.startsWith("U.M") -> "D"; h.startsWith("QUANT") -> "E"
                h.startsWith("PREZZO") -> "F"; h.startsWith("IMPORTO") -> "H"; h.contains("IVA") -> "I"; else -> "K"
            }
        }
        val layout = AiReader.decodeLayout(answer, q)
        assertEquals(TableReader.Kind.DESCRIPTION, layout?.headings?.get("colli/descrizione"))
        // Only words the app did not know are learned.
        assertNull(layout?.headings?.get("importo"))
        // A wrong number of letters, or no description column: nothing is learned.
        assertNull(AiReader.decodeLayout("A,C", q))
        assertNull(AiReader.decodeLayout(headings.joinToString(",") { "K" }, q))
        assertEquals("root ::= l \",\" l \",\" l\nl ::= [A-K]\n", AiReader.layoutGrammar(3))
    }
}
