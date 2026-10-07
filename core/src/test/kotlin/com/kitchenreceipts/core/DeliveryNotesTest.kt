package com.kitchenreceipts.core

import com.kitchenreceipts.core.bench.DocGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DeliveryNotesTest {

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    @Test fun kindsOfTheFixtures() {
        assertEquals(DocKind.DELIVERY_NOTE, DocumentKinds.detect(fixture("ddt_ortofrutta.txt")))
        assertEquals(DocKind.DELIVERY_NOTE, DocumentKinds.detect(fixture("ddt_surgelati.txt")))
        assertEquals(DocKind.DELIVERY_NOTE, DocumentKinds.detect(fixture("ddt_surgelati_glued.txt")))
        assertEquals(DocKind.INVOICE, DocumentKinds.detect(fixture("fattura_macelleria.txt")))
        assertEquals(DocKind.INVOICE, DocumentKinds.detect(fixture("fattura_caseificio.txt")))
        assertEquals(DocKind.INVOICE, DocumentKinds.detect(fixture("fattura_cash_and_carry.txt")))
        assertEquals(DocKind.INVOICE, DocumentKinds.detect(fixture("fattura_cash_and_carry_5_pagine.txt")))
        assertEquals(DocKind.RECEIPT, DocumentKinds.detect(fixture("scontrino_mercato.txt")))
    }

    @Test fun kindsFromTitles() {
        fun k(vararg lines: String) = DocumentKinds.detect(lines.toList())
        assertEquals(DocKind.INVOICE, k("ABC S.r.l.", "FATTURA ACCOMPAGNATORIA N. 12", "Causale del trasporto: vendita"))
        assertEquals(DocKind.INVOICE, k("FATTURA DIFFERITA n. 45 del 31/10/2026", "Rif. DDT n. 101 del 03/10/2026", "Rif. DDT n. 102 del 10/10/2026"))
        assertEquals(DocKind.INVOICE, k("FATTURA ELETTRONICA (TD24)", "ABC S.r.l.", "DDT: 101 del 03/10/2026, 102 del 10/10/2026"))
        assertEquals(DocKind.DELIVERY_NOTE, k("VERDE FRESCO S.p.A.", "D.D.T. N. 88 DEL 03/04/2025", "La fattura seguirà a fine mese"))
        assertEquals(DocKind.DELIVERY_NOTE, k("DOCUMENTO DI TRASPORI0 N. 7", "PRIVACY: dati trattati per fattura e contabilita"))
        assertEquals(DocKind.DELIVERY_NOTE, k("BOLLA DI CONSEGNA 33"))
        assertEquals(DocKind.CREDIT_NOTE, k("NOTA DI CREDITO N. 5", "a storno della fattura n. 44 del 02/10/2026"))
        assertEquals(DocKind.CREDIT_NOTE, k("NOTA DI CREDITO ELETTRONICA (TD04)"))
        assertEquals(DocKind.RECEIPT, k("DOCUMENTO COMMERCIALE di vendita o prestazione"))
        assertEquals(DocKind.INVOICE, k("Rechnung Nr. 2026-117", "Lieferschein 5521 vom 01.10.2026"))
        assertEquals(DocKind.DELIVERY_NOTE, k("Lieferschein Nr. 5521"))
        assertEquals(DocKind.INVOICE, k("INVOICE 2026/88"))
        assertEquals(null, k("Pomodori 2 kg 3,00", "Totale 6,00"))
        // "Trasporto a cura del destinatario" alone does not make a delivery note.
        assertEquals(null, k("Trasporto a cura del destinatario"))
    }

    /** The synthetic bench prints a title on every document (with OCR noise): the kind must come out right. */
    @Test fun kindsOnTheSyntheticBench() {
        var right = 0
        var wrong = 0
        val errors = mutableListOf<String>()
        val docs = (1..120).map { DocGen.generate(it, DocGen.Noise.CLEAN) } + (1001..1200).map { DocGen.generate(it, DocGen.Noise.LIGHT) } +
            (2001..2120).map { DocGen.generate(it, DocGen.Noise.HEAVY) }
        for (d in docs) {
            val text = d.pages.flatMap { p -> p.sortedWith(compareBy({ it.top }, { it.left })).map { it.text } }
            val want = when (d.truth.kind) { "ddt" -> DocKind.DELIVERY_NOTE; "receipt" -> DocKind.RECEIPT; else -> DocKind.INVOICE }
            val got = DocumentKinds.detect(text)
            if (got == want) right++ else if (got != null) { wrong++; errors += "${d.name}: $got" }
        }
        println("Document kinds: right $right, wrong $wrong, unknown ${docs.size - right - wrong}  ${errors.take(10)}")
        assertTrue("wrong kinds: $errors", wrong <= docs.size / 100)
        assertTrue("too few kinds read: $right of ${docs.size}", right >= docs.size * 9 / 10)
    }

    @Test fun referencesOnInvoices() {
        val r = DocumentKinds.references(
            """
            FATTURA DIFFERITA N. 45 DEL 31/10/2026
            Rif. DDT n. 101 del 03/10/2026
            Vs. D.D.T. N° 000102 DEL 10/10/26
            DDT: 103 del 17/10/2026, 104 del 24/10/2026
            Documento di trasporto nr. 105/A del 30 ottobre 2026
            Trasporto a cura del vettore
            """.trimIndent(),
        )
        assertEquals(listOf("101", "000102", "103", "104", "105/A"), r.map { it.number })
        assertEquals(LocalDate.of(2026, 10, 3), r[0].date)
        assertEquals(LocalDate.of(2026, 10, 10), r[1].date)
        assertEquals(LocalDate.of(2026, 10, 24), r[3].date)
        assertEquals(LocalDate.of(2026, 10, 30), r[4].date)
        // A delivery note's own number is not a reference to another one.
        assertTrue(DocumentKinds.references("D.D.T. N. 88 DEL 03/04/2025", ownNumber = "88").isEmpty())
        // Words are not numbers.
        assertTrue(DocumentKinds.references("Documento di trasporto del cedente").isEmpty())
        assertEquals("101|2026-10-03;102|", DeliveryRef.encodeAll(listOf(DeliveryRef("101", LocalDate.of(2026, 10, 3)), DeliveryRef("102", null))))
        assertEquals(2, DeliveryRef.decodeAll("101|2026-10-03;102|").size)
    }

    @Test fun coversByNumberSupplierAndDate() {
        val d = { id: Long, seller: Long, n: String, date: LocalDate -> MatchDoc(id, seller, DocKind.DELIVERY_NOTE, n, date, emptyList()) }
        val docs = listOf(
            d(1, 7, "000101", LocalDate.of(2026, 10, 3)),
            d(2, 7, "102/2026", LocalDate.of(2026, 10, 10)),
            d(3, 8, "101", LocalDate.of(2026, 10, 3)), // another supplier, same number
            d(4, 7, "101", LocalDate.of(2025, 10, 3)), // last year, same number
            MatchDoc(10, 7, DocKind.INVOICE, "45", LocalDate.of(2026, 10, 31), listOf(DeliveryRef("101", LocalDate.of(2026, 10, 3)), DeliveryRef("102", null), DeliveryRef("999", null))),
        )
        assertEquals(mapOf(1L to 10L, 2L to 10L), DeliveryMatching.covers(docs))
        assertEquals(listOf("999"), DeliveryMatching.missing(docs.last(), docs).map { it.number })
    }

    private fun line(doc: Long, id: Long, pid: Long?, desc: String, q: String?, unit: String?, cents: Long?) =
        CompareLine(doc, id, pid, desc, q?.let(::BigDecimal), unit, cents, null)

    @Test fun differencesBetweenNotesAndInvoice() {
        val notes = listOf(
            line(1, 11, 100, "POMODORI SAN MARZANO", "12.5", "kg", 2625),
            line(1, 12, 101, "ZUCCHINE", "8", "kg", 1440),
            line(2, 21, 100, "POMODORI SAN MARZANO", "5000", "g", 1050), // same product on a second note, in grams
            line(2, 22, 102, "BASILICO", "5", "mazzo", null),
            line(2, 23, 103, "PATATE", "2", "cassa", 2200),
        )
        val invoice = listOf(
            line(9, 91, 100, "POMODORI S.MARZANO", "17.5", "kg", 3675), // 12,5 + 5 kg: fine
            line(9, 92, 101, "ZUCCHINE", "10", "kg", 2000), // 2 kg more, and dearer
            line(9, 93, 104, "MELANZANE", "3", "kg", 750), // never delivered
            line(9, 94, null, "TRASPORTO", null, null, 1500), // fee: not compared
            line(9, 95, 102, "BASILICO", "5", "mazzo", 450),
        )
        val diffs = DeliveryMatching.compare(invoice, notes)
        val kinds = diffs.map { it.kind to it.description }.toSet()
        assertEquals(
            setOf(
                DifferenceKind.MORE_INVOICED to "ZUCCHINE",
                DifferenceKind.HIGHER_PRICE to "ZUCCHINE",
                DifferenceKind.NOT_DELIVERED to "MELANZANE",
                DifferenceKind.NOT_INVOICED to "PATATE",
            ),
            kinds,
        )
        val more = diffs.first { it.kind == DifferenceKind.MORE_INVOICED }
        assertEquals(0, BigDecimal("8").compareTo(more.delivered))
        assertEquals(0, BigDecimal("10").compareTo(more.invoiced))
        assertEquals(400L, more.amountCents) // 2 kg at 2,00
        val dearer = diffs.first { it.kind == DifferenceKind.HIGHER_PRICE }
        assertEquals(160L, dearer.amountCents) // 0,20 more on 8 kg
    }

    @Test fun unlinkedLinesPairByDescription() {
        val notes = listOf(line(1, 11, null, "MOZZARELLA FIOR DI LATTE", "3", "kg", null))
        val invoice = listOf(line(9, 91, 55, "MOZZARELLA FIOR DI LATTE", "3", "kg", 2400))
        assertTrue(DeliveryMatching.compare(invoice, notes).isEmpty())
    }

    @Test fun roundingIsNotADifference() {
        val notes = listOf(line(1, 11, 1, "SALSICCIA", "4.995", "kg", null))
        val invoice = listOf(line(9, 91, 1, "SALSICCIA", "5", "kg", 3500))
        assertTrue(DeliveryMatching.compare(invoice, notes).isEmpty())
    }
}
