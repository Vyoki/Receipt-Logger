package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class AiReaderTest {
    private val ocr = javaClass.classLoader!!.getResource("fixtures/ddt_surgelati.txt")!!.readText()

    // What a vision model might answer for the delivery note, including two mistakes: an invented lot and an amount it misread.
    private val answer = """
        {"seller":"VERDE FRESCO S.p.A.","seller_vat":"01234567897","number":"B26 111945","date":"15/09/2026",
         "subtotal":"209,76","vat":"21,51","total":"231,27","items":[
          {"code":"19965","colli":"5","description":"TORTA AL TESTO SPICCHI (PANI) GR.500","unit":"NR","quantity":"40,000","price":"2,384","discount":null,"amount":"95,36","vat_rate":"10","lot":"788058"},
          {"code":"25023","colli":"1","description":"PANNA COTTA (MARCA X) GR. 520","unit":"NR","quantity":"3,000","price":"8,820","discount":null,"amount":"26,46","vat_rate":"10","lot":"L999999"},
          {"code":"21051","colli":"1","description":"SEMOLA G.DURO RIMACINATA (5)","unit":"KG","quantity":"5,000","price":"1,054","discount":null,"amount":"5,27","vat_rate":"04","lot":"15/10/2026"},
          {"code":"68033","colli":"1","description":"ACETO DI VINO BIANCO LT. 1","unit":"NR","quantity":"12,000","price":"0,851","discount":null,"amount":"10,21","vat_rate":"10","lot":null},
          {"code":"23741","colli":"2","description":"SALE MARINO GROSSO GR.1000","unit":"NR","quantity":"20,000","price":"0,422","discount":null,"amount":"8,44","vat_rate":"22","lot":null},
          {"code":"23740","colli":"1","description":"SALE MARINO FINO GR.1000","unit":"NR","quantity":"10,000","price":"0,422","discount":null,"amount":"4,22","vat_rate":"22","lot":null},
          {"code":"25368","colli":"2","description":"POMODORI PELATI GR.2550(1650)X6","unit":"CT","quantity":"2,000","price":"21,780","discount":null,"amount":"43,56","vat_rate":"04","lot":null},
          {"code":"24195","colli":null,"description":"CARTA FORNO 40CM X 50M C/ASTUCCIO","unit":"NR","quantity":"3,000","price":"5,412","discount":null,"amount":"16,94","vat_rate":"22","lot":null}
        ]}
    """.trimIndent()

    @Test fun decodesAndChecksAgainstTheOcr() {
        val a = AiReader.decode(answer)!!
        val d = AiReader.toParsed(a, ocr, ParseOptions(ownVatNumber = "09876543217"))
        assertEquals("VERDE FRESCO S.p.A.", d.sellerName?.value)
        assertEquals(Confidence.HIGH, d.sellerName?.confidence)
        assertEquals(LocalDate.of(2026, 9, 15), d.documentDate?.value)
        assertEquals(Confidence.HIGH, d.documentDate?.confidence)
        assertEquals(23127L, d.totalCents?.value)
        assertEquals(8, d.lineItems.size)
        val torta = d.lineItems[0]
        assertEquals("5", torta.packages?.value)
        assertEquals("788058", torta.lotNumber?.value)       // printed: kept
        assertEquals(Confidence.HIGH, torta.quantity?.confidence)
        assertEquals("pz", torta.unit?.value)
        assertNull(d.lineItems[1].lotNumber)                   // "L999999" is not on the page: dropped
        assertNull(d.lineItems[2].lotNumber)                   // a date is never a lot
        val carta = d.lineItems[7]                             // 3 x 5,412 = 16,24, not 16,94
        assertTrue(ParseWarning.LINE_TOTAL_MISMATCH in carta.warnings)
        assertEquals(Confidence.LOW, carta.lineTotalCents?.confidence)
        assertEquals(Confidence.LOW, carta.quantity?.confidence)
        assertTrue(ParseWarning.ITEMS_SUM_MISMATCH in d.warnings)
    }

    @Test fun mergeKeepsTheReadingThatAddsUp() {
        val regular = ReceiptParser.parse(ocr, ParseOptions(ownVatNumber = "09876543217"))
        val ai = AiReader.toParsed(AiReader.decode(answer)!!, ocr)
        val merged = AiReader.merge(regular, ai, ocr)
        // The regular reading has all 8 lines right; the AI has one wrong amount: the regular items stay.
        assertEquals("text", merged.itemsReadBy)
        assertEquals(1624L, merged.lineItems.last().lineTotalCents?.value)
        // With the AI's mistake fixed, it explains the document just as well plus colli and lots: still no worse.
        val fixed = AiReader.toParsed(AiReader.decode(answer.replace("\"16,94\"", "\"16,24\""))!!, ocr)
        assertTrue(ParseWarning.ITEMS_SUM_MISMATCH !in fixed.warnings)
    }

    @Test fun badAnswersAreRejected() {
        assertNull(AiReader.decode("not json"))
        assertNull(AiReader.decode("[1,2]"))
        assertEquals(0, AiReader.decode("{\"items\":[]}")!!.items.size)
    }

    @Test fun ownBusinessIsNeverTheSeller() {
        val a = AiReader.decode(answer.replace("VERDE FRESCO S.p.A.", "RISTORANTE PROVA SAS"))!!
        assertNull(AiReader.toParsed(a, ocr, ParseOptions(ownBusinessName = "Ristorante Prova")).sellerName)
    }

    @Test fun grammarShape() {
        val g = AiReader.GRAMMAR
        assertTrue(g, g.startsWith("root ::= \"{\" \"\\\"seller\\\":\" value"))
        assertTrue(g.contains("item ::= \"{\" \"\\\"code\\\":\" value"))
        assertTrue(!g.contains("ws"))
        assertTrue(g.contains("value ::= \"null\" | \"\\\"\" char{0,100} \"\\\"\""))
        assertEquals(BigDecimal("40"), BigDecimal("40.000").stripTrailingZeros().let { BigDecimal(it.toPlainString()) })
    }

    /** A real answer of the 2B model (from CI) for the synthetic cash & carry photo. */
    @Test fun realModelAnswer() {
        val raw = javaClass.classLoader!!.getResource("fixtures/ai_answer_qwen3vl_2b.json")!!.readText()
        val ocrText = javaClass.classLoader!!.getResource("fixtures/ocr_mlkit_cash_and_carry.txt")!!.readText()
        val d = AiReader.toParsed(AiReader.decode(raw)!!, ocrText)
        assertEquals(listOf(345L, 109L, 1074L, 1954L, 12678L, 252L, 8180L, 6220L, 490L, 1132L, 1490L, 970L), d.lineItems.map { it.lineTotalCents?.value })
        assertTrue(d.lineItems.all { it.quantity?.confidence == Confidence.HIGH })
        val biscotti = d.lineItems[0]
        assertEquals("pz", biscotti.unit?.value)                          // "SK GR 800": one 800 g pack
        assertTrue(biscotti.originalDescription.endsWith("GR 800"))
        assertEquals("kg", d.lineItems[3].unit?.value)                    // "NC KG" + 4,45
        assertEquals("kg", d.lineItems[8].unit?.value)                    // "CF GR" + 0,48 = kilograms
        val candeggina = d.lineItems[2]                                   // "O 10000032x3"
        assertEquals("1000003", candeggina.itemCode)
        assertEquals("2x3", candeggina.packages?.value)
        assertEquals(38152L, d.totalCents?.value)
        assertTrue(ParseWarning.ITEMS_SUM_MISMATCH !in d.warnings)
        assertEquals(VatBasis.EXCLUSIVE, d.vatBasis?.value)
    }

    @Test fun unitColumnVariants() {
        assertEquals("kg" to null, AiReader.splitUnit("KG"))
        assertEquals("pz" to "LT 5", AiReader.splitUnit("FL LT 5"))
        assertEquals("g" to null, AiReader.splitUnit("CF GR"))
        assertEquals("pz" to null, AiReader.splitUnit("NR"))
        assertEquals(null to null, AiReader.splitUnit("SK"))
    }
}
