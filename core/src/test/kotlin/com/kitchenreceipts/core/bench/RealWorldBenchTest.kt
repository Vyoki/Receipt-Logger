package com.kitchenreceipts.core.bench

import com.kitchenreceipts.core.AutoAccept
import com.kitchenreceipts.core.Confidence
import com.kitchenreceipts.core.DocumentDraft
import com.kitchenreceipts.core.Extracted
import com.kitchenreceipts.core.OcrLine
import com.kitchenreceipts.core.ParseOptions
import com.kitchenreceipts.core.ParsedDocument
import com.kitchenreceipts.core.ReceiptParser
import org.junit.Test
import java.io.File
import java.text.Normalizer
import java.time.LocalDate

/**
 * Real documents from public datasets (see tools/realworld): invoices and receipts in several languages, as printed
 * and as bad photos (crooked, curled, blurred, dark, all at once), read by OCR and then by the app's parser.
 * REALWORLD_DIR=<folder made by realworld.py> runs it; REALWORLD_REPORT=<file> writes the report.
 * Not a gate: the datasets are not in the repository. It shows where the reading rules fail on documents nobody here
 * invented.
 */
class RealWorldBenchTest {

    enum class Out { RIGHT_SURE, RIGHT_CHECK, WRONG_FLAGGED, WRONG_SILENT, MISSING }

    private class Tally {
        val fields = linkedMapOf<String, IntArray>()
        var docs = 0
        var savedRight = 0
        var savedWrong = 0
        var linesTruth = 0
        var linesFound = 0
        var linesExtra = 0
        fun add(field: String, o: Out) { fields.getOrPut(field) { IntArray(Out.entries.size) }[o.ordinal]++ }
        fun report(title: String) = buildString {
            append("== $title: $docs readings")
            if (docs > 0) append(String.format("  saved without review: right %.1f%%, WITH ERRORS %.1f%%", 100.0 * savedRight / docs, 100.0 * savedWrong / docs))
            append('\n')
            append(String.format("%-9s %7s %7s %7s %8s %7s %8s\n", "field", "right", "sure", "check", "flagged", "SILENT", "missing"))
            for ((f, a) in fields) {
                val n = a.sum().coerceAtLeast(1)
                fun p(vararg o: Out) = 100.0 * o.sumOf { a[it.ordinal] } / n
                append(String.format("%-9s %6.1f%% %6.1f%% %6.1f%% %7.1f%% %6.1f%% %7.1f%%  (n=%d)\n", f,
                    p(Out.RIGHT_SURE, Out.RIGHT_CHECK), p(Out.RIGHT_SURE), p(Out.RIGHT_CHECK), p(Out.WRONG_FLAGGED), p(Out.WRONG_SILENT), p(Out.MISSING), n))
            }
            if (linesTruth > 0) append(String.format("lines: %.1f%% of the printed lines found with their amount, %d extra lines\n", 100.0 * linesFound / linesTruth, linesExtra))
        }
    }

    private fun pages(text: String): List<List<OcrLine>> {
        val pages = mutableListOf<MutableList<OcrLine>>()
        val word = Regex("(-?\\d+)-(-?\\d+):(\\S+)")
        for (l in text.lines()) {
            if (l.startsWith("=== Raw lines")) { pages += mutableListOf<OcrLine>(); continue }
            if (pages.isEmpty() || !l.contains(" | ")) continue
            val (g, rest) = l.split(" | ", limit = 2)
            val n = g.split(",")
            if (n.size < 5) continue
            val bracket = rest.lastIndexOf("  [")
            val boxed = bracket >= 0 && rest.endsWith("]")
            val words = if (boxed) rest.substring(bracket + 3, rest.length - 1).split(' ').mapNotNull { w ->
                word.matchEntire(w)?.let { m -> OcrLine(m.groupValues[3], m.groupValues[1].toInt(), n[1].toInt(), m.groupValues[2].toInt(), n[3].toInt()) }
            } else emptyList()
            pages.last() += OcrLine(if (boxed) rest.substring(0, bracket) else rest, n[0].toInt(), n[1].toInt(), n[2].toInt(), n[3].toInt(), n[4].toFloat(), words)
        }
        return pages
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").uppercase()
    private fun words(s: String) = norm(s).split(Regex("[^A-Z0-9]+")).filter { it.length >= 2 && it !in LEGAL }.toSet()
    private val LEGAL = setOf("SDN", "BHD", "SRL", "SPA", "SAS", "SARL", "GMBH", "BV", "LTD", "INC", "LLC", "NV", "SA", "THE", "DE", "DI")

    private fun <T> judge(t: Tally, field: String, truth: T?, read: Extracted<T>?, same: (T, T) -> Boolean) {
        if (truth == null) return
        val o = when {
            read == null -> Out.MISSING
            same(truth, read.value) -> if (read.confidence == Confidence.HIGH) Out.RIGHT_SURE else Out.RIGHT_CHECK
            read.confidence == Confidence.HIGH -> Out.WRONG_SILENT
            else -> Out.WRONG_FLAGGED
        }
        t.add(field, o)
    }

    @Test fun realWorld() {
        val dir = System.getenv("REALWORLD_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val all = Tally()
        val byGroup = sortedMapOf<String, Tally>()
        val bad = mutableListOf<String>()
        for (f in dir.listFiles { x -> x.name.endsWith(".txt") && "__" in x.name }!!.sortedBy { it.name }) {
            val id = f.name.substringBefore("__")
            val variant = f.name.substringAfter("__").removeSuffix(".txt")
            val truthFile = File(dir, "$id.truth").takeIf { it.exists() } ?: continue
            val truth = truthFile.readLines().filter { "=" in it }.map { it.substringBefore('=') to it.substringAfter('=') }
            fun tv(k: String) = truth.firstOrNull { it.first == k }?.second?.takeIf { it.isNotBlank() && it != "None" }
            val tLines = truth.filter { it.first == "line" }.mapNotNull { Regex("\"t\": (-?\\d+)").find(it.second)?.groupValues?.get(1)?.toLong() }

            val d: ParsedDocument = runCatching { ReceiptParser.parsePages(pages(f.readText()), ParseOptions()) }.getOrElse { ParsedDocument.EMPTY }
            val dataset = id.substringBefore('-')
            val groups = listOf(all, byGroup.getOrPut("$dataset / $variant") { Tally() }, byGroup.getOrPut("all / $variant") { Tally() }, byGroup.getOrPut("$dataset / all") { Tally() })
            val wrong = mutableListOf<String>()
            for (t in groups) {
                t.docs++
                judge(t, "supplier", tv("seller"), d.sellerName) { a, b -> val w = words(a); w.isNotEmpty() && w.count { it in words(b) } * 2 >= w.size }
                judge(t, "date", tv("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, d.documentDate) { a, b -> a == b }
                judge(t, "number", tv("number"), d.documentNumber) { a, b -> norm(a).filter(Char::isLetterOrDigit) == norm(b).filter(Char::isLetterOrDigit) }
                judge(t, "total", tv("total")?.toLongOrNull(), d.totalCents) { a, b -> a == b }
                judge(t, "taxable", tv("subtotal")?.toLongOrNull(), d.subtotalCents) { a, b -> a == b }
                judge(t, "vat", tv("vat")?.toLongOrNull(), d.vatCents) { a, b -> a == b }
                if (tLines.isNotEmpty()) {
                    val read = d.lineItems.mapNotNull { it.lineTotalCents?.value }.toMutableList()
                    var found = 0
                    for (x in tLines) { val i = read.indexOf(x); if (i >= 0) { found++; read.removeAt(i) } }
                    t.linesTruth += tLines.size; t.linesFound += found; t.linesExtra += read.size
                }
            }
            if (System.getenv("REALWORLD_SILENT") != null) {
                fun sil(f: String, ok: Boolean, e: Extracted<*>?) { if (e != null && e.confidence == Confidence.HIGH && !ok) println("SILENT $f ${this@RealWorldBenchTest.javaClass.simpleName.take(0)}${f.padEnd(8)} ${id}__$variant: read '${e.value}' <${e.source.take(70)}>") }
                tv("date")?.let { t -> sil("date", d.documentDate?.value?.toString() == t, d.documentDate) }
                tv("seller")?.let { t -> sil("supplier", d.sellerName?.value?.let { r -> words(t).let { w -> w.isNotEmpty() && w.count { it in words(r) } * 2 >= w.size } } ?: true, d.sellerName) }
                tv("number")?.let { t -> sil("number", d.documentNumber?.value?.let { r -> norm(r).filter(Char::isLetterOrDigit) == norm(t).filter(Char::isLetterOrDigit) } ?: true, d.documentNumber) }
                tv("total")?.let { t -> sil("total", d.totalCents?.value?.toString() == t, d.totalCents) }
                if (tv("date") != null && d.documentDate?.confidence == Confidence.HIGH && d.documentDate.value.toString() != tv("date")) println("   printed date ${tv("date")}")
            }
            if (System.getenv("REALWORLD_DEBUG")?.let { variant == it || it == "all" } == true) {
                println("${f.name}: seller='${d.sellerName?.value}' vs '${tv("seller")}' | date=${d.documentDate?.value} vs ${tv("date")} | number=${d.documentNumber?.value} vs ${tv("number")} | " +
                    "total=${d.totalCents?.value} vs ${tv("total")} | sub=${d.subtotalCents?.value} vs ${tv("subtotal")} | vat=${d.vatCents?.value} vs ${tv("vat")} | lines ${d.lineItems.size}/${tLines.size}")
            }
            // Would the app save it without anyone looking? Then everything it saved must be right.
            val draft = AutoAccept.settleProven(DocumentDraft.fromParsed(d))
            val saved = AutoAccept.reasons(draft).isEmpty()
            if (tv("total")?.toLongOrNull()?.let { it != d.totalCents?.value } == true) wrong += "total ${d.totalCents?.value} (printed ${tv("total")})"
            if (tv("date") != null && d.documentDate?.value?.toString() != tv("date")) wrong += "date ${d.documentDate?.value} (printed ${tv("date")})"
            for (t in groups) if (saved) { if (wrong.isEmpty()) t.savedRight++ else t.savedWrong++ }
            if (saved && wrong.isNotEmpty()) bad += "${f.name}: SAVED WITH ERRORS: ${wrong.joinToString("; ")}"
            else if (d.totalCents != null && d.totalCents.confidence == Confidence.HIGH && tv("total") != null && d.totalCents.value != tv("total")!!.toLong()) bad += "${f.name}: total ${d.totalCents.value} sure but printed ${tv("total")}"
        }
        val report = buildString {
            append(all.report("All real documents"))
            byGroup.forEach { (g, t) -> append('\n').append(t.report(g)) }
            append("\n== Wrong and not flagged (first 80)\n")
            bad.take(80).forEach { append(it).append('\n') }
        }
        System.getenv("REALWORLD_REPORT")?.let { File(it).writeText(report) }
        println(report)
    }
}
