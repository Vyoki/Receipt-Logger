package com.kitchenreceipts.core.bench

import com.kitchenreceipts.core.SellerProfiles
import com.kitchenreceipts.core.bench.EInvoicePrintGen.Layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The invented printout generator checks its own truth: what the bench scores against must add up. */
class EInvoicePrintGenTest {

    private fun text(page: List<com.kitchenreceipts.core.OcrLine>) = page.joinToString("\n") { it.text }

    @Test fun vatNumbersAreInventedAndWellFormed() {
        assertEquals(12, EInvoicePrintGen.VATS.toSet().size)
        for (v in EInvoicePrintGen.VATS) {
            assertTrue(v, SellerProfiles.isValidPartitaIva(v))
            // Tax office "000" is never assigned: no real company has these numbers.
            assertEquals(v, "000", v.substring(7, 10))
            assertTrue(v != EInvoicePrintGen.OWN_VAT)
        }
    }

    @Test fun truthAddsUp() {
        val invoices = (1..400).map { EInvoicePrintGen.invoice(it) } +
            (1..40).map { EInvoicePrintGen.invoice(it, "utility") } + (1..40).map { EInvoicePrintGen.invoice(it, "fuel") }
        for (inv in invoices) assertEquals(inv.number, emptyList<String>(), EInvoicePrintGen.check(inv))
        // Every optional part shows up.
        assertTrue(invoices.any { it.bolloCents > 0 } && invoices.any { it.discountCents > 0 } && invoices.any { it.roundingCents != 0L })
        assertTrue(invoices.any { it.creditNote } && invoices.any { it.otherBuyer } && invoices.any { it.soleTrader })
        assertTrue(invoices.any { it.intermediaryVat != null } && invoices.any { it.groups.size == 3 } && invoices.any { it.instalments == 2 })
        assertTrue(invoices.any { inv -> inv.lines.any { it.letterSpaced } } && invoices.any { inv -> inv.lines.any { it.lotForm.isNotEmpty() } })
        // A stamp duty, a discount or a rounding is exactly what sets the total apart from taxable + VAT.
        for (inv in invoices) {
            assertEquals(inv.number, inv.bolloCents - inv.discountCents + inv.roundingCents, inv.totalCents - inv.taxableCents - inv.taxCents)
        }
    }

    @Test fun printedAmountsAreTheTruth() {
        for (seed in 1..60) {
            val p = EInvoicePrintGen.single(seed)
            val all = p.pages.flatten().map { it.text }
            val inv = p.invoice
            for (l in inv.lines) assertTrue("${p.name}: ${l.desc}", EInvoicePrintGen.money(l.amountCents) in all)
            for (g in inv.groups) assertTrue(p.name, EInvoicePrintGen.money(g.taxableCents) in all && EInvoicePrintGen.money(g.taxCents) in all)
            assertTrue(p.name, EInvoicePrintGen.money(inv.totalCents) in all)
            assertTrue(p.name, all.any { it.endsWith(inv.supplierVat) })
        }
    }

    @Test fun sameSeedSamePages() {
        assertEquals(EInvoicePrintGen.single(7).pages, EInvoicePrintGen.single(7).pages)
        assertEquals(EInvoicePrintGen.bundle(3, 9).pages, EInvoicePrintGen.bundle(3, 9).pages)
    }

    @Test fun boxesMoveAtMostSixUnits() {
        for (seed in 1..20) for (layout in Layout.entries) {
            val p = EInvoicePrintGen.single(seed, layout = layout)
            val still = EInvoicePrintGen.pages(p.invoice, p.print.copy(jitter = 0))
            assertEquals(still.map { it.size }, p.pages.map { it.size })
            for ((a, b) in still.flatten().zip(p.pages.flatten())) {
                assertEquals(a.text, b.text)
                assertTrue("${p.name} ${a.text}", abs(a.left - b.left) <= EInvoicePrintGen.JITTER && abs(a.top - b.top) <= EInvoicePrintGen.JITTER)
            }
        }
    }

    @Test fun bundlesKnowWhereEachInvoiceStarts() {
        for (seed in 1..15) for (layout in Layout.entries) {
            val b = EInvoicePrintGen.bundle(seed, 5 + seed % 8, layout)
            assertEquals(5 + seed % 8, b.parts.size)
            // The ranges follow each other and cover every page after the cover.
            var next = b.cover?.let { it.last + 1 } ?: 0
            for (part in b.parts) {
                assertEquals(next, part.pages.first)
                next = part.pages.last + 1
                val first = text(b.pages[part.pages.first])
                when {
                    layout == Layout.ASSO -> assertTrue(first.contains("Cedente/prestatore"))
                    part.print.inBatch != null && part.print.inBatch!! > 1 -> assertTrue(first.contains("Numero documento nel lotto"))
                    else -> assertTrue(first.contains("FATTURA ELETTRONICA"))
                }
                assertEquals(emptyList<String>(), EInvoicePrintGen.check(part.invoice))
            }
            assertEquals(b.pages.size, next)
        }
        // A cover page, the same supplier twice in a row, an SdI batch and a long invoice all show up.
        val bundles = (1..15).flatMap { s -> Layout.entries.map { EInvoicePrintGen.bundle(s, 5 + s % 8, it) } }
        assertTrue(bundles.any { it.cover != null })
        assertTrue(bundles.any { b -> b.parts.zipWithNext().any { (x, y) -> x.invoice.supplierVat == y.invoice.supplierVat } })
        assertTrue(bundles.any { b -> b.parts.any { it.print.batchSize > 1 } })
        assertTrue(bundles.any { b -> b.parts.any { it.pages.count() >= 3 } })
    }

    @Test fun multiPageSinglesRepeatTheirHeaderAndCloseOnTheLastPage() {
        val singles = EInvoicePrintGen.multiPageSingles()
        assertEquals(24, singles.size)
        for (p in singles) {
            assertTrue(p.name, p.pages.size in 2..8)
            val pages = p.pages.map(::text)
            val last = pages.last()
            for ((i, page) in pages.withIndex()) {
                assertTrue("${p.name} p.${i + 1}", page.contains(if (p.print.layout == Layout.ASSO) "Cedente/prestatore" else "Dati del cedente / prestatore"))
                assertTrue("${p.name} p.${i + 1}", page.contains(p.invoice.number))
                if (page !== last) assertTrue("${p.name} p.${i + 1}", page.contains("A riportare", ignoreCase = true))
            }
            // The document's total only on the last page.
            val closing = if (p.print.layout == Layout.ASSO) Regex("(?im)^totale documento$") else Regex("Dati di riepilogo per aliquota IVA e natura")
            assertEquals(p.name, listOf(pages.size - 1), pages.indices.filter { closing.containsMatchIn(pages[it]) })
            assertEquals(emptyList<String>(), EInvoicePrintGen.check(p.invoice))
        }
    }
}
