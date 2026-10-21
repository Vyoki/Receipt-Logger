package com.kitchenreceipts.core

import com.kitchenreceipts.core.EInvoicePrintoutFixture.CUSTOMER_VAT
import com.kitchenreceipts.core.EInvoicePrintoutFixture.ENERGY
import com.kitchenreceipts.core.EInvoicePrintoutFixture.HOUSEHOLD
import com.kitchenreceipts.core.EInvoicePrintoutFixture.LONG
import com.kitchenreceipts.core.EInvoicePrintoutFixture.Line
import com.kitchenreceipts.core.EInvoicePrintoutFixture.MEAT
import com.kitchenreceipts.core.EInvoicePrintoutFixture.TWO_LINE_NAME
import com.kitchenreceipts.core.EInvoicePrintoutFixture.WHOLESALER
import com.kitchenreceipts.core.EInvoicePrintoutFixture.t
import com.kitchenreceipts.core.InvoiceBundle.Flag
import com.kitchenreceipts.core.InvoiceBundle.Kind
import com.kitchenreceipts.core.InvoiceBundle.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Where each invoice starts in one PDF of many (invented invoices in both standard e-invoice printouts). */
class InvoiceBundleTest {

    private fun longOf(lines: Int, number: String) =
        LONG.copy(number = number, lines = (1..lines).map { i -> Line("ARTICOLO DI PROVA N$i", "2,00", "1,50", "KG", "4,00", "3,00", code = (2000000 + i).toString()) },
            groups = listOf(EInvoicePrintoutFixture.Group("4,00", ItalianNumbers.formatCents(300L * lines), ItalianNumbers.formatCents(12L * lines))),
            total = ItalianNumbers.formatCents(312L * lines))

    private val eleven = listOf(
        MEAT, WHOLESALER, longOf(100, "4411"), ENERGY, LONG,
        MEAT.copy(number = "319", date = "28-03-2031"), // the same supplier again, the next invoice
        HOUSEHOLD, TWO_LINE_NAME, longOf(150, "4412"),
        WHOLESALER.copy(number = "7C 000012400", type = "TD04 nota di credito"),
        longOf(140, "4413"),
    )

    private fun ranges(invoices: List<EInvoicePrintoutFixture.Invoice>): List<IntRange> {
        var at = 0
        return invoices.map { inv -> val n = EInvoicePrintoutFixture.pages(inv).size; (at until at + n).also { at += n } }
    }

    @Test fun elevenInvoicesOverTwentyPages() {
        val pages = EInvoicePrintoutFixture.bundle(eleven)
        assertEquals(20, pages.size)
        val found = InvoiceBundle.find(pages, CUSTOMER_VAT)
        assertEquals(ranges(eleven), found.map { it.first..it.last })
        assertTrue(InvoiceBundle.isBundle(found))
        assertTrue(found.all { it.kind == Kind.INVOICE && it.start == Start.ASSO && it.ended })
        assertEquals(eleven.map { it.supplierVat }, found.map { it.supplierVat })
        assertEquals(eleven.map { it.number }, found.map { it.number })
        assertEquals(LocalDate.of(2031, 3, 28), found[5].date)
        assertEquals(eleven.map { ItalianNumbers.parseCents(it.total) }, found.map { it.printedTotalCents })
        assertTrue(found.all { it.customerVat == CUSTOMER_VAT })
        assertEquals(listOf(9), found.indices.filter { found[it].creditNote })
        // Every invoice is sure, and every total is proven by its VAT summary except the electricity bill's (its
        // "N2.2" group is not read).
        assertTrue(found.map { it.flags }.toString(), found.all { it.sure })
        assertEquals(listOf(3), found.indices.filter { !found[it].totalProven })
    }

    @Test fun oneInvoiceIsNoBundle() {
        for (inv in listOf(MEAT, LONG, ENERGY, longOf(150, "1"))) {
            val found = InvoiceBundle.find(EInvoicePrintoutFixture.pages(inv), CUSTOMER_VAT)
            assertEquals(1, found.size)
            assertFalse(InvoiceBundle.isBundle(found))
        }
    }

    @Test fun theSameSupplierAgainStartsAfterTheEnd() {
        // The same invoice printed twice: same supplier and number, but the first one ended.
        val twice = InvoiceBundle.find(EInvoicePrintoutFixture.bundle(listOf(MEAT, MEAT)), CUSTOMER_VAT)
        assertEquals(listOf(0..0, 1..1), twice.map { it.first..it.last })
        assertTrue(twice.all { it.sure })
        val next = InvoiceBundle.find(EInvoicePrintoutFixture.bundle(listOf(MEAT, MEAT.copy(number = "319"))), CUSTOMER_VAT)
        assertEquals(listOf("318", "319"), next.map { it.number })
    }

    @Test fun headerPrintedAgainOnEveryPageIsTheSameInvoice() {
        // A three-page invoice that prints its parties and number at the top of each page and running totals
        // ("Totale pagina", "A riportare") at the foot; the document's total is only on the last page.
        val one = EInvoicePrintoutFixture.pages(MEAT).single()
        val running = listOf(t(1748, 3000, "TOTALE PAGINA"), t(2246, 3046, "175,00"), t(1748, 3100, "A riportare"), t(2246, 3146, "175,00"))
        val withoutTotals = one.filterNot { it.text.contains("TOTALE DOCUMENTO") || it.text == "242,42" } + running
        val found = InvoiceBundle.find(listOf(withoutTotals, withoutTotals, one), CUSTOMER_VAT)
        assertEquals(listOf(0..2), found.map { it.first..it.last })
        assertTrue(found.single().flags.toString(), found.single().sure)
        assertEquals(24242L, found.single().printedTotalCents)
    }

    @Test fun onlyTheExactHeadingEndsAnInvoice() {
        val lines = listOf(
            t(100, 100, "Arrotondamento su Importo totale documento: 0.01"),
            t(100, 150, "Importo totale documento: 242.42"),
            t(100, 200, "TOTALE PAGINA"),
            t(100, 250, "Totale documento complessivo della fornitura"),
        )
        assertEquals(0, InvoiceBundle.signals(0, lines, null).assoEnds)
        assertEquals(1, InvoiceBundle.signals(0, lines + t(1748, 300, "TOTALE DOCUMENTO"), null).assoEnds)
        assertEquals(1, InvoiceBundle.signals(0, lines + t(1748, 300, "Totale documento:"), null).assoEnds)
    }

    @Test fun sdiFileAndBatchStarts() {
        val batch = Sdi.file(Sdi.ABC, listOf(Sdi.Body("A-1", "2031-03-02", "61.00"), Sdi.Body("A-2", "2031-03-09", "122.00")), bodyOnNewPage = true)
        val single = Sdi.file(Sdi.VERDE, listOf(Sdi.Body("77", "2031-03-10", "61.00")))
        val pages = batch + single + EInvoicePrintoutFixture.pages(MEAT)
        val found = InvoiceBundle.find(pages, CUSTOMER_VAT)
        assertEquals(listOf(Start.SDI_FILE, Start.SDI_BODY, Start.SDI_FILE, Start.ASSO), found.map { it.start })
        assertEquals(listOf(0..0, 1..1, 2..2, 3..3), found.map { it.first..it.last })
        // The batch prints the parties once: its second body is from the same supplier.
        assertEquals(listOf(Sdi.ABC.first, Sdi.ABC.first, Sdi.VERDE.first, MEAT.supplierVat), found.map { it.supplierVat })
        assertEquals("ABC S.r.l.", found[1].supplierName)
        assertEquals(listOf("A-1", "A-2", "77", "318"), found.map { it.number })
        assertEquals(LocalDate.of(2031, 3, 9), found[1].date)
        assertEquals(listOf(6100L, 12200L, 6100L, 24242L), found.map { it.printedTotalCents })
        assertTrue(found.all { it.customerVat == CUSTOMER_VAT })
        // SdI starts are never trusted silently; the AssoSoftware invoice is sure.
        assertTrue(found.take(3).all { it.flags == setOf(Flag.START_UNSURE) })
        assertTrue(found[3].sure)
    }

    @Test fun anSdiHeaderPageAloneBelongsToItsFirstBody() {
        val pages = Sdi.file(Sdi.ABC, listOf(Sdi.Body("A-1", "2031-03-02", "61.00"), Sdi.Body("A-2", "2031-03-09", "61.00")), bodyOnNewPage = true, headerAlone = true)
        assertEquals(3, pages.size)
        val found = InvoiceBundle.find(pages, CUSTOMER_VAT)
        assertEquals(listOf(0..1, 2..2), found.map { it.first..it.last })
        assertEquals(listOf("A-1", "A-2"), found.map { it.number })
    }

    @Test fun anSdiBodyIsCompleteWithItsSummary() {
        // "Importo totale documento" is optional and printed in the header: never the end.
        val noTotal = InvoiceBundle.find(Sdi.file(Sdi.ABC, listOf(Sdi.Body("5", "2031-03-02", null))), CUSTOMER_VAT).single()
        assertFalse(Flag.NO_TOTAL in noTotal.flags)
        assertNull(noTotal.printedTotalCents)
        assertTrue(noTotal.ended)
        val noSummary = InvoiceBundle.find(Sdi.file(Sdi.ABC, listOf(Sdi.Body("5", "2031-03-02", "61.00", summary = false))), CUSTOMER_VAT).single()
        assertTrue(Flag.NO_TOTAL in noSummary.flags)
    }

    @Test fun aCourtesyCopyTitledFatturaElettronicaIsNoSdiFile() {
        val page = EInvoicePrintoutFixture.pages(MEAT).single() + t(1000, 20, "FATTURA ELETTRONICA")
        val s = InvoiceBundle.signals(0, page, CUSTOMER_VAT)
        assertFalse(s.sdiFileStart)
        assertTrue(s.assoStart)
    }

    @Test fun creditNotes() {
        val found = InvoiceBundle.find(
            EInvoicePrintoutFixture.bundle(listOf(WHOLESALER.copy(type = "TD04 nota di credito"), WHOLESALER.copy(number = "9"), WHOLESALER.copy(number = "10", type = "TD08 nota di credito semplificata"))),
            CUSTOMER_VAT,
        )
        assertEquals(listOf(true, false, true), found.map { it.creditNote })
        assertEquals(listOf("TD04", "TD01", "TD08"), found.map { it.docType })
        val sdi = InvoiceBundle.find(Sdi.file(Sdi.ABC, listOf(Sdi.Body("5", "2031-03-02", "61.00", type = "TD04 (nota di credito)"))), CUSTOMER_VAT)
        assertTrue(sdi.single().creditNote)
    }

    @Test fun aCoverPageIsNotRecognised() {
        val cover = listOf(t(300, 400, "STUDIO CONTABILE ESEMPIO"), t(300, 500, "Fatture di acquisto marzo 2031"), t(300, 600, "Cliente: RISTORANTE PROVA SAS"))
        val found = InvoiceBundle.find(listOf(cover) + EInvoicePrintoutFixture.bundle(listOf(MEAT, HOUSEHOLD)), CUSTOMER_VAT)
        assertEquals(listOf(Kind.UNKNOWN, Kind.INVOICE, Kind.INVOICE), found.map { it.kind })
        assertEquals(listOf(0..0, 1..1, 2..2), found.map { it.first..it.last })
        assertTrue(InvoiceBundle.isBundle(found))
        // The invoice after a cover page has a sure start.
        assertTrue(found[1].sure)
    }

    @Test fun anInvoiceOverTwentyPages() {
        val long = longOf(1260, "4500")
        val pages = EInvoicePrintoutFixture.pages(long)
        assertEquals(25, pages.size)
        val found = InvoiceBundle.find(pages, CUSTOMER_VAT).single()
        assertEquals(setOf(Flag.OVER_PAGE_LIMIT), found.flags)
        assertTrue(found.totalProven)
    }

    @Test fun addressedToAnotherBusiness() {
        val otherVat = EInvoicePrintoutFixture.pIva("0555555000")
        val other = EInvoicePrintoutFixture.pages(MEAT, customerVat = otherVat)
        val found = InvoiceBundle.find(other + EInvoicePrintoutFixture.pages(HOUSEHOLD), CUSTOMER_VAT)
        assertEquals(listOf(setOf(Flag.OTHER_BUYER), emptySet()), found.map { it.flags })
        assertEquals(otherVat, found[0].customerVat)
        // Without the restaurant's own VAT number nothing is compared.
        assertTrue(InvoiceBundle.find(other, null).single().sure)
    }

    @Test fun multiRateTotalProvenByTheVatSummary() {
        val w = InvoiceBundle.find(EInvoicePrintoutFixture.pages(WHOLESALER), CUSTOMER_VAT).single()
        assertEquals(9761L, w.printedTotalCents)
        assertEquals(3, w.vatGroups.size)
        assertTrue(w.totalProven)
        // The electricity bill's summary has a group the app does not read (N2.2): the total stays as printed, unproven.
        val e = InvoiceBundle.find(EInvoicePrintoutFixture.pages(ENERGY), CUSTOMER_VAT).single()
        assertEquals(25590L, e.printedTotalCents)
        assertFalse(e.totalProven)
    }

    @Test fun joinAndSplitRoundTrip() {
        val pages = EInvoicePrintoutFixture.bundle(listOf(MEAT, WHOLESALER, LONG, HOUSEHOLD)).mapIndexed { i, p -> InvoiceBundle.signals(i, p, CUSTOMER_VAT) }
        val found = InvoiceBundle.segments(pages, CUSTOMER_VAT)
        assertEquals(4, found.size)
        val joined = InvoiceBundle.join(found, pages, 1, CUSTOMER_VAT)
        assertEquals(listOf(0..1, 2..3, 4..4), joined.map { it.first..it.last })
        assertTrue(Flag.SEVERAL_IN_ONE in joined[0].flags)
        assertNull(joined[0].printedTotalCents) // two different totals: none is the invoice's
        val split = InvoiceBundle.splitAt(joined, pages, 1, CUSTOMER_VAT)
        assertEquals(Start.BY_HAND, split[1].start)
        assertEquals(found, split.map { it.copy(start = Start.ASSO) })
        // Splitting where an invoice already starts, or past the end, changes nothing.
        assertEquals(found, InvoiceBundle.splitAt(found, pages, 2, CUSTOMER_VAT))
        assertEquals(found, InvoiceBundle.splitAt(found, pages, 99, CUSTOMER_VAT))
        assertEquals(found, InvoiceBundle.join(found, pages, 0, CUSTOMER_VAT))
        // The operator marks a start inside the pages nobody recognised.
        val cover = InvoiceBundle.signals(0, listOf(t(300, 400, "STUDIO CONTABILE ESEMPIO"), t(300, 500, "Fatture di acquisto marzo 2031")), null)
        val withCover = listOf(cover) + pages.map { it.copy(index = it.index + 1) }
        val segs = InvoiceBundle.segments(withCover, CUSTOMER_VAT)
        assertEquals(Kind.UNKNOWN, segs[0].kind)
        val joinedCover = InvoiceBundle.join(segs, withCover, 1, CUSTOMER_VAT)
        assertEquals(Kind.UNKNOWN, joinedCover[0].kind)
        assertEquals(0..1, joinedCover[0].first..joinedCover[0].last)
        val marked = InvoiceBundle.splitAt(joinedCover, withCover, 1, CUSTOMER_VAT)
        assertEquals(listOf(Kind.UNKNOWN, Kind.INVOICE), marked.take(2).map { it.kind })
        assertEquals(Start.BY_HAND, marked[1].start)
        assertEquals(MEAT.supplierVat, marked[1].supplierVat)
        assertTrue(marked[1].sure)
    }

    @Test fun anInvoiceWithoutItsEnd() {
        // The total page of the first invoice is missing: it has no end, and the next start is not sure.
        val meat = EInvoicePrintoutFixture.pages(MEAT).single().filterNot { it.text.contains("TOTALE DOCUMENTO") }
        val found = InvoiceBundle.find(listOf(meat) + EInvoicePrintoutFixture.pages(HOUSEHOLD), CUSTOMER_VAT)
        assertEquals(listOf(setOf(Flag.NO_TOTAL), setOf(Flag.START_UNSURE)), found.map { it.flags })
    }

    @Test fun pagesWithoutText() {
        val blank = emptyList<OcrLine>()
        val pages = listOf(blank) + EInvoicePrintoutFixture.pages(MEAT) + listOf(blank) + EInvoicePrintoutFixture.pages(HOUSEHOLD)
        val signals = pages.mapIndexed { i, p -> InvoiceBundle.signals(i, p, CUSTOMER_VAT) }
        val found = InvoiceBundle.segments(signals, CUSTOMER_VAT)
        assertEquals(listOf(Kind.UNKNOWN, Kind.INVOICE, Kind.INVOICE), found.map { it.kind })
        assertEquals(listOf(0..0, 1..2, 3..3), found.map { it.first..it.last })
        assertTrue(Flag.NO_TEXT in found[0].flags && Flag.NO_TEXT in found[1].flags)
        assertTrue(InvoiceBundle.usable(signals))
        assertFalse(InvoiceBundle.usable(signals + List(5) { InvoiceBundle.PageSignals(10 + it, hasText = false) }))
        assertFalse(InvoiceBundle.usable(emptyList()))
    }

    @Test fun twoInvoicesOnTheSamePageAreFlagged() {
        val a = EInvoicePrintoutFixture.pages(MEAT).single()
        val b = EInvoicePrintoutFixture.pages(HOUSEHOLD).single().map { it.copy(top = it.top + 3400, bottom = it.bottom + 3400) }
            .filterNot { it.text.startsWith("Cedente") } // the second invoice on the same sheet, below the first
        val found = InvoiceBundle.find(listOf(a + b), CUSTOMER_VAT)
        assertEquals(1, found.size)
        assertTrue(Flag.SEVERAL_IN_ONE in found.single().flags)
    }

    @Test fun theNextBodyOfAnAssoSoftwareFile() {
        // A file of two bodies: the parties are printed once, the second body opens with its identity row on top.
        val second = EInvoicePrintoutFixture.pages(WHOLESALER.copy(supplierVat = MEAT.supplierVat, supplierName = MEAT.supplierName)).single()
        val identityTop = second.first { it.text == "ART." }.top
        val body = second.filter { it.top >= identityTop }.map { it.copy(top = it.top - identityTop + 177, bottom = it.bottom - identityTop + 177) }
        val found = InvoiceBundle.find(EInvoicePrintoutFixture.pages(MEAT) + listOf(body), CUSTOMER_VAT)
        assertEquals(listOf(Start.ASSO, Start.ASSO_BODY), found.map { it.start })
        assertEquals(listOf(MEAT.supplierVat, MEAT.supplierVat), found.map { it.supplierVat })
        assertEquals(listOf("318", WHOLESALER.number), found.map { it.number })
        assertTrue(found.all { it.sure })
    }

    @Test fun signalsSurviveAsText() {
        val pages = EInvoicePrintoutFixture.bundle(listOf(MEAT, WHOLESALER, ENERGY, HOUSEHOLD)) + Sdi.file(Sdi.ABC, listOf(Sdi.Body("A|1\\x", "2031-03-02", "61.00")))
        for ((i, p) in pages.withIndex()) {
            val s = InvoiceBundle.signals(i, p, CUSTOMER_VAT)
            assertTrue(s.rowTexts.size <= InvoiceBundle.MAX_ROWS)
            assertEquals(s.copy(rowTexts = emptyList()), InvoiceBundle.decode(InvoiceBundle.encode(s)))
        }
        assertEquals("A|1\\x", InvoiceBundle.decode(InvoiceBundle.encode(InvoiceBundle.signals(4, pages[4], CUSTOMER_VAT)))?.number)
        assertNull(InvoiceBundle.decode("garbage"))
        // A newer app's key is skipped.
        assertEquals(3, InvoiceBundle.decode("i:3|t:1|zz:new")?.index)
    }

    @Test fun fastOnALongFile() {
        val pages = EInvoicePrintoutFixture.bundle(List(30) { eleven }.flatten())
        val t0 = System.nanoTime()
        val found = InvoiceBundle.find(pages, CUSTOMER_VAT)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(eleven.size * 30, found.size)
        assertTrue("took $ms ms for ${pages.size} pages", ms < 30_000)
    }

    /** INVENTED pages in the layout of the SdI stylesheet: one column of "label: value" lines under headings. */
    private object Sdi {
        val ABC = EInvoicePrintoutFixture.pIva("0246813000") to "ABC S.r.l."
        val VERDE = EInvoicePrintoutFixture.pIva("0135792000") to "VERDE FRESCO S.p.A."

        data class Body(val number: String, val date: String, val total: String?, val type: String = "TD01 (fattura)", val summary: Boolean = true)

        fun file(supplier: Pair<String, String>, bodies: List<Body>, bodyOnNewPage: Boolean = false, headerAlone: Boolean = false): List<List<OcrLine>> {
            val pages = mutableListOf<MutableList<OcrLine>>(mutableListOf())
            var y = 60
            fun newPage() { pages += mutableListOf<OcrLine>(); y = 60 }
            fun add(x: Int, text: String) {
                if (y > 3200) newPage()
                pages.last() += t(x, y, text, cw = 16, h = 30); y += 44
            }
            add(900, "FATTURA ELETTRONICA"); pages.last() += t(1900, 70, "Versione FPR12", cw = 14, h = 26)
            add(100, "Dati relativi alla trasmissione")
            add(140, "Identificativo del trasmittente: IT${supplier.first}"); add(140, "Progressivo di invio: 00001")
            add(140, "Formato Trasmissione: FPR12"); add(140, "Codice identificativo destinatario: ABC1234")
            add(100, "Dati del cedente / prestatore"); add(120, "Dati anagrafici")
            add(140, "Identificativo fiscale ai fini IVA: IT${supplier.first}"); add(140, "Codice fiscale: ${supplier.first}")
            add(140, "Denominazione: ${supplier.second}"); add(140, "Regime fiscale: RF01 (ordinario)")
            add(120, "Dati della sede"); add(140, "Indirizzo: VIA DI PROVA 12"); add(140, "CAP: 00100"); add(140, "Comune: ROMA")
            add(100, "Dati del cessionario / committente"); add(120, "Dati anagrafici")
            add(140, "Identificativo fiscale ai fini IVA: IT$CUSTOMER_VAT"); add(140, "Codice fiscale: $CUSTOMER_VAT")
            add(140, "Denominazione: RISTORANTE PROVA SAS"); add(120, "Dati della sede"); add(140, "Indirizzo: VIA ESEMPIO 1")
            add(100, "Versione prodotta con foglio di stile SdI www.fatturapa.gov.it")
            for ((k, b) in bodies.withIndex()) {
                if (bodyOnNewPage && (k > 0 || headerAlone)) newPage()
                if (bodies.size > 1) add(100, "Numero documento nel lotto: ${k + 1}")
                add(1900, "Versione FPR12")
                add(100, "Dati generali del documento")
                add(140, "Tipologia documento: ${b.type}"); add(140, "Valuta importi: EUR")
                add(140, "Data documento: ${b.date} (${b.date.takeLast(2)} Marzo 2031)"); add(140, "Numero documento: ${b.number}")
                b.total?.let { add(140, "Importo totale documento: $it") }
                add(100, "Dati relativi alle linee di dettaglio della fornitura")
                add(140, "Nr. linea: 1"); add(140, "Descrizione bene/servizio: POLLO INTERO"); add(140, "Quantità: 10.00")
                add(140, "Unità di misura: KG"); add(140, "Valore unitario: 5.00"); add(140, "Valore totale: 50.00"); add(140, "IVA (%): 22.00")
                if (b.summary) {
                    add(100, "Dati di riepilogo per aliquota IVA e natura")
                    add(140, "Aliquota IVA (%): 22.00"); add(140, "Totale imponibile/importo: 50.00"); add(140, "Totale imposta: 11.00")
                    add(140, "Esigibilità IVA: I (esigibilità immediata)")
                }
                add(100, "Dati relativi al pagamento"); add(140, "Modalità: MP05 (bonifico)")
            }
            return pages
        }
    }
}
