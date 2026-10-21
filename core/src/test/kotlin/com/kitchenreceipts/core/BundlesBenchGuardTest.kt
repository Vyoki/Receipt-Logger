package com.kitchenreceipts.core

import com.kitchenreceipts.core.EInvoicePrintoutFixture.CUSTOMER_VAT
import com.kitchenreceipts.core.bench.BenchTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A single document is never cut into several: every benchmark document, every invented e-invoice printout (also
 * when it reprints its header on every page) and the core fixtures each give exactly one segment.
 */
class BundlesBenchGuardTest {

    private fun assertOne(name: String, pages: List<List<OcrLine>>, ownVat: String?) {
        val found = InvoiceBundle.find(pages, ownVat)
        assertEquals("$name: ${found.map { "${it.first}-${it.last} ${it.kind} ${it.start}" }}", 1, found.size)
        assertFalse(name, InvoiceBundle.isBundle(found))
    }

    /** Text with no boxes: one line per text line, stacked down the page; pages split at the page break. */
    private fun stacked(text: String): List<List<OcrLine>> = text.split(ReceiptParser.PAGE_BREAK).map { page ->
        page.lines().filter { it.isNotBlank() }.mapIndexed { i, l -> OcrLine(l.trim(), 40, 40 + i * 40, 40 + l.trim().length * 18, 70 + i * 40) }
    }

    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)?.readText()

    @Test fun everyBenchmarkDocumentIsOneSegment() {
        val bench = BenchTest()
        for (d in bench.realDocs() + bench.generated()) {
            val pages = if (d.pages.isEmpty() && d.text != null) stacked(d.text) else d.pages
            assertOne(d.name, pages, d.ownVat)
        }
    }

    @Test fun everyInventedPrintoutIsOneSegment() {
        val singles = listOf(
            EInvoicePrintoutFixture.MEAT, EInvoicePrintoutFixture.WHOLESALER, EInvoicePrintoutFixture.ENERGY, EInvoicePrintoutFixture.HOUSEHOLD,
            EInvoicePrintoutFixture.TWO_LINE_NAME, EInvoicePrintoutFixture.LONG,
            EInvoicePrintoutFixture.WHOLESALER.copy(type = "TD04 nota di credito"),
            EInvoicePrintoutFixture.LONG.copy(lines = EInvoicePrintoutFixture.LONG.lines + EInvoicePrintoutFixture.LONG.lines + EInvoicePrintoutFixture.LONG.lines),
        )
        for (inv in singles) {
            val pages = EInvoicePrintoutFixture.pages(inv)
            assertOne(inv.number, pages, CUSTOMER_VAT)
            assertOne(inv.number + " without own VAT", pages, null)
            // The same invoice with its parties and identity printed again at the top of every page.
            if (pages.size > 1) {
                val head = pages[0].filter { it.top < pages[0].first { l -> l.text == "COD. ARTICOLO" }.top - 20 }
                val shift = head.maxOf { it.bottom } + 40 - 177
                val repeated = listOf(pages[0]) + pages.drop(1).map { p -> head + p.map { it.copy(top = it.top + shift, bottom = it.bottom + shift) } }
                assertOne(inv.number + " header on every page", repeated, CUSTOMER_VAT)
            }
        }
    }

    @Test fun coreFixturesAreOneSegment() {
        resource("fixtures/ocr_mlkit_lines_4_photos.txt")?.let { text ->
            val photos = mutableListOf<MutableList<OcrLine>>()
            for (l in text.lines()) {
                if (l.startsWith("# ")) photos += mutableListOf<OcrLine>()
                else if (l.contains(" | ")) {
                    val (g, t) = l.split(" | ", limit = 2)
                    val n = g.split(",")
                    photos.last() += OcrLine(t, n[0].toInt(), n[1].toInt(), n[2].toInt(), n[3].toInt(), n[4].toFloat())
                }
            }
            assertEquals(4, photos.size)
            for ((i, p) in photos.withIndex()) assertOne("photo $i", listOf(p), "09876543217")
            assertOne("four photos as one file", photos, "09876543217")
        }
        resource("fixtures/pdf_text_sconti.txt")?.let { text ->
            val lines = text.lines().filter { it.contains(" | ") }.map { l ->
                val (g, rest) = l.split(" | ", limit = 2)
                val n = g.split(",")
                OcrLine(rest.substringBefore("  ["), n[0].toInt(), n[1].toInt(), n[2].toInt(), n[3].toInt())
            }
            assertOne("pdf_text_sconti", listOf(lines), "09876543217")
        }
        resource("fixtures/fattura_cash_and_carry_5_pagine.txt")?.let { text ->
            val pages = stacked(text)
            assertEquals(5, pages.size)
            assertOne("cash and carry, 5 pages", pages, "09876543217")
        }
    }
}
