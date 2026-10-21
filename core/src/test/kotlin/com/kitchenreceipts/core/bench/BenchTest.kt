package com.kitchenreceipts.core.bench

import com.kitchenreceipts.core.OcrLine
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/** The documents of the bench, also used by other tests. */
object BenchDocs {

    private fun resource(name: String) = BenchDocs::class.java.classLoader!!.getResource(name)?.readText()

    /** Line and word boxes as the app's "Recognised text" report prints them. */
    private fun boxes(text: String): List<List<OcrLine>> {
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

    /** Invented copies of documents read on the phone, with what is really printed on them. */
    fun realDocs(): List<BenchDoc> {
        val own = DocGen.OWN_VAT
        val out = mutableListOf<BenchDoc>()
        resource("fixtures/ddt_surgelati_boxes.txt")?.let { t ->
            fun i(d: String, q: String, u: String, p: String, c: Long, v: Int, lot: String) = ItemTruth(d, BigDecimal(q), u, BigDecimal(p), c, v, lot)
            out += BenchDoc(
                "real-ddt-surgelati", boxes(t),
                DocTruth(
                    "ddt", "VERDE FRESCO S.p.A.", "01234567897", "B26 204177", LocalDate.of(2026, 9, 22), 24602, 1857, 26459,
                    listOf(
                        i("CINGHIALE POLPA EXTRA", "5", "kg", "11.616", 5808, 10, "789431"),
                        i("TORTA AL TESTO SPICCHI", "24", "pz", "2.384", 5722, 10, "792983"),
                        i("PEPERONCINO FRANTUMATO BRIK", "4", "pz", "4.732", 1893, 10, "B2611-745"),
                        i("OLIO GIRASOLE PET LT.5", "4", "pz", "9.570", 3828, 4, "791891"),
                        i("SEMI DI ZUCCA SGUSC. SECCHIELLO", "1", "pz", "11.264", 1126, 10, "B2611-742"),
                        i("POMODORO DOPPIO CONCENTRATO", "6", "pz", "3.115", 1869, 4, "797512"),
                        i("POMODORI PELATI GR.2550", "2", "ct", "21.780", 4356, 4, "792620"),
                    ),
                ),
                own,
            )
        }
        resource("fixtures/ddt_tilted_sections_boxes.txt")?.let { t ->
            fun i(d: String, q: String, u: String, p: String, c: Long, v: Int, lot: String) = ItemTruth(d, BigDecimal(q), u, BigDecimal(p), c, v, lot)
            out += BenchDoc(
                "real-ddt-tilted-sections", boxes(t),
                DocTruth(
                    "ddt", "VERDE FRESCO S.p.A.", "01234567897", "B26 305511", LocalDate.of(2026, 9, 15), 20976, 2151, 23127,
                    listOf(
                        i("TORTA AL TESTO SPICCHI (PAMI)", "40", "pz", "2.384", 9536, 10, "788058"),
                        i("PANNA COTTA (ALSA-CARTE D'OR)", "3", "pz", "8.820", 2646, 10, "B269-27519"),
                        i("SEMOLA G.DURO RIMACINATA (GMI)", "5", "kg", "1.054", 527, 4, "788816"),
                        i("ACETO DI VINO BIANCO", "12", "pz", "0.851", 1021, 10, "782515"),
                        i("SALE MARINO GROSSO", "20", "pz", "0.422", 844, 22, "792323"),
                        i("SALE MARINO FINO", "10", "pz", "0.422", 422, 22, "792320"),
                        i("POMODORI PELATI (R.GARG)", "2", "ct", "21.780", 4356, 4, "792621"),
                        i("CARTA FORNO 40CM X 50M C/ASTUCCIO", "3", "pz", "5.412", 1624, 22, "B269-27522"),
                    ),
                ),
                own,
            )
        }
        resource("fixtures/fattura_cash_and_carry_5_pagine.txt")?.let { t ->
            out += BenchDoc(
                "real-cash-and-carry-5-pages", emptyList(),
                DocTruth("cashcarry", "ABC S.r.l.", "01234567897", "12A/34567", LocalDate.of(2026, 9, 23), 41332, 3551, 44883, null),
                own, text = t,
            )
        }
        return out
    }

    fun generated(): List<BenchDoc> =
        (1..120).map { DocGen.generate(it, DocGen.Noise.CLEAN) } +
            (1001..1200).map { DocGen.generate(it, DocGen.Noise.LIGHT) } +
            (2001..2120).map { DocGen.generate(it, DocGen.Noise.HEAVY) }

    /** Invented e-invoice printouts (see [EInvoicePrintGen]): a group of their own, kept out of the headline numbers. */
    fun printouts(): List<BenchDoc> = EInvoicePrintGen.benchDocs()
}

/** A group's counts as bench/baseline.txt records them. */
internal data class Counts(val fields: Long, val right: Long, val silent: Long, val docs: Long, val autoWrong: Long) {
    fun line(group: String) = "group $group | fields $fields right $right silent $silent | documents $docs autoWrong $autoWrong"

    /** Where this is worse than [b]. Shares are compared as exact fractions, so a group may gain documents. */
    fun worseThan(b: Counts): List<String> = listOfNotNull(
        "fields right $right of $fields, recorded ${b.right} of ${b.fields}".takeIf { right * b.fields < b.right * fields },
        "silent errors $silent in $fields fields, recorded ${b.silent} in ${b.fields}".takeIf { silent * b.fields > b.silent * fields },
        "saved with errors $autoWrong of $docs documents, recorded ${b.autoWrong} of ${b.docs}".takeIf { autoWrong * b.docs > b.autoWrong * docs },
    )

    companion object {
        fun of(s: Scorecard): Counts {
            val f = s.fields.getValue("all fields")
            val right = f.counts[Outcome.SURE.ordinal] + f.counts[Outcome.CHECK.ordinal]
            return Counts(f.total.toLong(), right.toLong(), s.silent.toLong(), s.docs.toLong(), s.autoWrong.toLong())
        }

        private val LINE = Regex("group (.+) \\| fields (\\d+) right (\\d+) silent (\\d+) \\| documents (\\d+) autoWrong (\\d+)")

        fun parse(line: String): Pair<String, Counts> {
            val m = LINE.matchEntire(line.trim()) ?: throw AssertionError("bench/baseline.txt: cannot read '$line'")
            val (g, f, r, s, d, w) = m.destructured
            return g to Counts(f.toLong(), r.toLong(), s.toLong(), d.toLong(), w.toLong())
        }
    }
}

/**
 * The test bench: hundreds of invented documents in many layouts (see [DocGen]) plus invented copies of real
 * documents, read and scored field by field (see [Scorecard]). The scores may not fall below
 * bench/baseline.txt, overall or in any group: a change that helps one document but hurts the others fails here.
 * The invented e-invoice printouts ([EInvoicePrintGen]) are a group of their own, outside the headline numbers.
 * BENCH_REPORT=<file> writes the full report (CI shows it as "Reading scorecard").
 */
class BenchTest {

    internal fun realDocs() = BenchDocs.realDocs()
    internal fun generated() = BenchDocs.generated()

    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)?.readText()

    /** As the report prints it. */
    private fun p2(x: Double) = String.format(Locale.ROOT, "%.2f", x).toDouble()

    @Test fun scorecard() {
        val all = Scorecard()
        val byGroup = linkedMapOf<String, Scorecard>()
        val docs = realDocs() + generated()
        for (d in docs) {
            val group = when {
                d.name.startsWith("real") -> "real documents (invented copies)"
                else -> "invented: " + d.truth.kind + " / " + when (d.name.substringAfter("gen-").substringBefore('-').toInt()) {
                    in 1..999 -> "clean"; in 1000..1999 -> "light noise"; else -> "heavy noise"
                }
            }
            val s = byGroup.getOrPut(group) { Scorecard() }
            runCatching { s.score(d); all.score(d) }.onFailure { e -> throw AssertionError("${d.name}: ${e.message}", e) }
        }
        val printouts = Scorecard()
        val printoutSilent = mutableListOf<String>()
        for (d in BenchDocs.printouts()) {
            val before = printouts.silent
            runCatching { printouts.score(d) }.onFailure { e -> throw AssertionError("${d.name}: ${e.message}", e) }
            if (printouts.silent > before) printoutSilent += d.name
        }
        val groups = byGroup.toSortedMap() + (PRINTOUT to printouts)
        val report = buildString {
            append(all.report("All documents"))
            byGroup.toSortedMap().forEach { (g, s) -> append('\n').append(s.report(g)) }
            append("\n== Saved without review although something is wrong\n")
            all.savedWithErrors.forEach { append(it).append('\n') }
            append("\n== Words the page map could not explain (examples)\n")
            all.unexplainedExamples.forEach { append(it).append('\n') }
            append("\n== Examples of what went wrong\n")
            all.worst.forEach { append(it).append('\n') }
            append('\n').append(printouts.report(PRINTOUT))
            append("\n== $PRINTOUT: what went wrong\n")
            printouts.worst.forEach { append(it).append('\n') }
            append("\nCounts per group (the lines of bench/baseline.txt):\n")
            groups.forEach { (g, s) -> append(Counts.of(s).line(g)).append('\n') }
        }
        System.getenv("BENCH_REPORT")?.let { File(it).writeText(report) }
        println(report)

        // The gate: never worse than the recorded baseline, with no margin (the bench gives the same numbers on every
        // run).
        val baseLines = resource("bench/baseline.txt")?.lines().orEmpty().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val base = baseLines.filter { !it.startsWith("group ") && "=" in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().toDouble() }
        val f = all.fields.getValue("all fields")
        val right = f.right
        val silent = f.pct(Outcome.SILENT)
        val autoWrong = 100.0 * all.autoWrong / all.docs
        base["right"]?.let { assertTrue("fields right $right% < baseline $it%", p2(right) >= it) }
        base["silent"]?.let { assertTrue("silent errors $silent% > baseline $it%", p2(silent) <= it) }
        base["autoWrong"]?.let { assertTrue("saved with errors $autoWrong% > baseline $it%", p2(autoWrong) <= it) }
        println(String.format("BASELINE right=%.2f silent=%.2f autoWrong=%.2f", right, silent, autoWrong))

        // Each group, to the last field and document.
        val worse = baseLines.filter { it.startsWith("group ") }.map { Counts.parse(it) }.flatMap { (g, b) ->
            val s = groups[g] ?: return@flatMap listOf("$g: in bench/baseline.txt but not measured")
            Counts.of(s).worseThan(b).map { "$g: $it" }
        }
        assertTrue("worse than bench/baseline.txt:\n" + worse.joinToString("\n"), worse.isEmpty())

        // The printouts: no wrong value left unhighlighted, nothing wrong saved by itself.
        val newSilent = printoutSilent - PRINTOUT_TODO_SILENT
        assertTrue("$PRINTOUT, wrong values not highlighted: $newSilent", newSilent.isEmpty())
        val saved = printouts.savedWithErrors.map { it.substringBefore(": ") }
        val newSaved = saved - PRINTOUT_TODO_SAVED
        assertTrue("$PRINTOUT, saved by itself although wrong: $newSaved", newSaved.isEmpty())
        (PRINTOUT_TODO_SILENT - printoutSilent.toSet() + (PRINTOUT_TODO_SAVED - saved.toSet())).takeIf { it.isNotEmpty() }?.let {
            println("$PRINTOUT: now read right, take them off the TODO lists in BenchTest: $it")
        }
    }

    companion object {
        const val PRINTOUT = "e-invoice printout"

        /**
         * TODO (reading): printouts with a wrong value not highlighted today. Take a name off once it reads right;
         * never add one.
         */
        val PRINTOUT_TODO_SILENT = setOf(
            // The item table is read as text, not by columns: the VAT summary rows, the "Importo bollo ... Totale
            // documento" row (with the total) and the payment rows become lines, the last ones not highlighted; a line
            // of 4 pieces at 4% VAT can come out with quantity and price swapped (4 x 17,2082 as 17,2082 x 4).
            // Two fuel lines:
            "printout-115-fuel",
            // A utility bill whose rows are a few units out of line:
            "printout-106-utility",
            // "Totale pagina" / "A riportare" / "Riporto" rows on a multi-page invoice (pages-10 also without them):
            "printout-pages-1-asso", "printout-pages-2-asso", "printout-pages-3-asso", "printout-pages-5-asso",
            "printout-pages-6-asso", "printout-pages-7-asso", "printout-pages-8-asso", "printout-pages-9-asso",
            "printout-pages-10-asso", "printout-pages-11-asso", "printout-pages-12-asso",
            // Names printed letter by letter: quantity and price swapped (4 x 7,7501 as 7,7501 x 4).
            "printout-64-goods",
        )

        /** TODO (reading): printouts the app would save by itself although it must not. Never add one. */
        val PRINTOUT_TODO_SAVED = setOf(
            // Addressed to another business (the customer's VAT is not the restaurant's): no hold on printouts.
            "printout-4-goods", "printout-54-goods", "printout-92-goods",
            // A credit note (TD04) whose intermediary's name has the word "FATTURE" above it: taken for an invoice.
            "printout-94-goods",
        )
    }
}
