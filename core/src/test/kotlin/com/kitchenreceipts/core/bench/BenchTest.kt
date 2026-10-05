package com.kitchenreceipts.core.bench

import com.kitchenreceipts.core.OcrLine
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The test bench: hundreds of invented documents in many layouts (see [DocGen]) plus invented copies of real
 * documents, read and scored field by field (see [Scorecard]). The scores may not fall below
 * bench/baseline.txt: a change that helps one document but hurts the others fails here.
 * BENCH_REPORT=<file> writes the full report (CI shows it as "Reading scorecard").
 */
class BenchTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)?.readText()

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
    private fun realDocs(): List<BenchDoc> {
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

    private fun generated(): List<BenchDoc> =
        (1..120).map { DocGen.generate(it, DocGen.Noise.CLEAN) } +
            (1001..1200).map { DocGen.generate(it, DocGen.Noise.LIGHT) } +
            (2001..2120).map { DocGen.generate(it, DocGen.Noise.HEAVY) }

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
        val report = buildString {
            append(all.report("All documents"))
            byGroup.toSortedMap().forEach { (g, s) -> append('\n').append(s.report(g)) }
            append("\n== Saved without review although something is wrong\n")
            all.savedWithErrors.forEach { append(it).append('\n') }
            append("\n== Examples of what went wrong\n")
            all.worst.forEach { append(it).append('\n') }
        }
        System.getenv("BENCH_REPORT")?.let { File(it).writeText(report) }
        println(report)

        // The gate: never worse than the recorded baseline.
        val base = resource("bench/baseline.txt")?.lines()?.filter { it.isNotBlank() && !it.startsWith("#") && "=" in it }?.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().toDouble() }.orEmpty()
        val f = all.fields.getValue("all fields")
        val right = f.right
        val silent = f.pct(Outcome.SILENT)
        val autoWrong = 100.0 * all.autoWrong / all.docs
        base["right"]?.let { assertTrue("fields right $right% < baseline $it%", right >= it - 0.3) }
        base["silent"]?.let { assertTrue("silent errors $silent% > baseline $it%", silent <= it + 0.2) }
        base["autoWrong"]?.let { assertTrue("saved with errors $autoWrong% > baseline $it%", autoWrong <= it + 0.3) }
        println(String.format("BASELINE right=%.2f silent=%.2f autoWrong=%.2f", right, silent, autoWrong))
    }
}
