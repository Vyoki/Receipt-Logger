package com.kitchenreceipts.core

import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Used by the CI job that runs the real AI model on a synthetic invoice (see .github/workflows/android.yml,
 * job ai-model-check). Skipped in normal test runs.
 * - AI_EXPORT_DIR: writes instruction.txt and grammar.gbnf exactly as the app sends them;
 * - AI_ANSWER_DIR: checks answer-*.json against the expected lines and prints a report.
 */
class AiModelCheckTest {

    private companion object {
        val LAYOUT_HEADINGS = listOf("CODICE", "COLLI", "DESCRIZIONE BENI", "TIPO CONF.", "TOT.", "PREZZO", "IMPORTO", "COD")
        const val FILETTO = "0 10000051 FILETTO B/A KG 3,5+ S/V -, CS KG 4,24 29,900 126,78"
        const val CANDEGGINA = "O 10000032x3 CANDEGGINA NORMALE LT.5- MARCA C FL LT 5 6 1,790 10,74 22"
        val FILETTO_CHOICES = listOf(
            LineChoice(java.math.BigDecimal("3.5"), java.math.BigDecimal("36.223"), 12678L),
            LineChoice(java.math.BigDecimal("4.24"), java.math.BigDecimal("29.900"), 12678L),
        )
    }

    private val ocrText: String get() = javaClass.classLoader!!.getResource("fixtures/ocr_mlkit_cash_and_carry.txt")!!.readText()

    @Test fun exportPrompt() {
        val dir = System.getenv("AI_EXPORT_DIR")
        assumeTrue(dir != null)
        File(dir!!).mkdirs()
        File(dir, "instruction.txt").writeText(AiReader.instruction(ocrText, AiReader.Lang.EN))
        File(dir, "instruction-no-ocr.txt").writeText(AiReader.instruction("", AiReader.Lang.EN))
        File(dir, "grammar.gbnf").writeText(AiReader.GRAMMAR)
        val head = "CODICE COLLI DESCRIZIONE BENI TIPO CONF. TOT. PREZZ0 IMPORTO COD"
        File(dir, "row-filetto.txt").writeText(AiReader.rowInstruction(head, FILETTO, AiReader.Lang.EN))
        File(dir, "row-candeggina.txt").writeText(AiReader.rowInstruction(head, CANDEGGINA, AiReader.Lang.EN))
        File(dir, "row.gbnf").writeText(AiReader.ROW_GRAMMAR)
        // Same questions with the instructions in Italian, to measure which language reads better.
        File(dir, "instruction-it.txt").writeText(AiReader.instruction(ocrText, AiReader.Lang.IT))
        File(dir, "row-filetto-it.txt").writeText(AiReader.rowInstruction(head, FILETTO, AiReader.Lang.IT))
        File(dir, "row-candeggina-it.txt").writeText(AiReader.rowInstruction(head, CANDEGGINA, AiReader.Lang.IT))
        // Multiple choice: the right reading is B (the answer is one letter).
        for (lang in AiReader.Lang.entries) {
            File(dir, "choice-filetto-${lang.name.lowercase()}.txt").writeText(AiReader.choiceInstruction(head, FILETTO, FILETTO_CHOICES, lang))
        }
        File(dir, "choice.gbnf").writeText(AiReader.CHOICE_GRAMMAR)
        // One number: the TOT. column of the FILETTO line (4,24).
        for (lang in AiReader.Lang.entries) {
            File(dir, "number-filetto-${lang.name.lowercase()}.txt").writeText(AiReader.numberInstruction("TOT.", head, FILETTO, lang))
        }
        File(dir, "number.gbnf").writeText(AiReader.NUMBER_GRAMMAR)
        // The column headings: what each column holds (one letter per heading).
        for (lang in AiReader.Lang.entries) {
            File(dir, "layout-${lang.name.lowercase()}.txt").writeText(AiReader.layoutInstruction(LAYOUT_HEADINGS, lang))
        }
        File(dir, "layout.gbnf").writeText(AiReader.layoutGrammar(LAYOUT_HEADINGS.size))
    }

    /** The heading answers: code, colli, description, (pack: unit or other), quantity, price, amount, VAT. */
    @Test fun checkLayoutAnswers() {
        val dir = System.getenv("AI_ANSWER_DIR")
        assumeTrue(dir != null)
        val right = listOf(setOf("A"), setOf("B"), setOf("C"), setOf("D", "K"), setOf("E"), setOf("F"), setOf("H"), setOf("I"))
        val report = StringBuilder()
        File(dir!!).listFiles { f -> f.name.startsWith("layoutanswer-") }!!.sorted().forEach { f ->
            val raw = f.readText().trim()
            val letters = raw.uppercase().split(',').map { it.trim() }
            val ok = letters.size == right.size && letters.zip(right).all { (l, r) -> l in r }
            val wrong = letters.zip(right).withIndex().filter { (_, p) -> p.first !in p.second }.joinToString(" ") { (i, p) -> "${LAYOUT_HEADINGS[i]}=${p.first}" }
            report.append("${f.name}: answer='$raw' ${if (ok) "RIGHT" else "WRONG $wrong"}\n")
        }
        File(dir, "layout-report.txt").writeText(report.toString())
        println(report)
    }

    /** The one-number answers: 4,24 is right. */
    @Test fun checkNumberAnswers() {
        val dir = System.getenv("AI_ANSWER_DIR")
        assumeTrue(dir != null)
        val report = StringBuilder()
        File(dir!!).listFiles { f -> f.name.startsWith("numberanswer-") }!!.sorted().forEach { f ->
            val raw = f.readText().trim()
            val ok = ItalianNumbers.parse(raw)?.compareTo(java.math.BigDecimal("4.24")) == 0
            report.append("${f.name}: answer='$raw' ${if (ok) "RIGHT" else "WRONG"}\n")
        }
        File(dir, "number-report.txt").writeText(report.toString())
        println(report)
    }

    /** The one-letter answers: B is right. */
    @Test fun checkChoiceAnswers() {
        val dir = System.getenv("AI_ANSWER_DIR")
        assumeTrue(dir != null)
        val report = StringBuilder()
        File(dir!!).listFiles { f -> f.name.startsWith("choiceanswer-") }!!.sorted().forEach { f ->
            val raw = f.readText().trim()
            val pick = AiReader.decodeChoice(raw, FILETTO_CHOICES)
            report.append("${f.name}: answer='$raw' ${if (pick == FILETTO_CHOICES[1]) "RIGHT" else "WRONG"}\n")
        }
        File(dir, "choice-report.txt").writeText(report.toString())
        println(report)
    }

    /** The small questions: one line each, answered from a strip of headings + the line. */
    @Test fun checkRowAnswers() {
        val dir = System.getenv("AI_ANSWER_DIR")
        assumeTrue(dir != null)
        val expected = mapOf("filetto" to Triple("4.24", "29.900", 12678L), "candeggina" to Triple("6", "1.790", 1074L))
        val report = StringBuilder()
        File(dir!!).listFiles { f -> f.name.startsWith("rowanswer-") }!!.sorted().forEach { f ->
            val raw = f.readText()
            val a = AiReader.decodeItem(raw)
            assertNotNull("${f.name}: not the expected JSON:\n$raw", a)
            val key = expected.keys.first { f.name.contains(it) }
            val (q, p, t) = expected.getValue(key)
            val ok = a!!.quantity?.let { ItalianNumbers.parse(it) }?.compareTo(q.toBigDecimal()) == 0 &&
                a.price?.let { ItalianNumbers.parse(it) }?.compareTo(p.toBigDecimal()) == 0 &&
                a.amount?.let { ItalianNumbers.parse(it) }?.let { ItalianNumbers.toCents(it) } == t
            report.append("${f.name}: ${if (ok) "RIGHT" else "WRONG"} desc=${a.description} q=${a.quantity} p=${a.price} t=${a.amount} colli=${a.colli} code=${a.code}\n")
        }
        File(dir, "row-report.txt").writeText(report.toString())
        println(report)
    }

    @Test fun checkAnswers() {
        val dir = System.getenv("AI_ANSWER_DIR")
        assumeTrue(dir != null)
        val expected = listOf(
            Triple("BISCOTTI", "1", "3.450"), Triple("FETTE", "1", "1.090"), Triple("CANDEGGINA", "6", "1.790"),
            Triple("FILONE", "4.45", "4.390"), Triple("FILETTO", "4.24", "29.900"), Triple("ACQUA", "6", "0.420"),
            Triple("UOVA", "2", "40.900"), Triple("PARMIGIANO", "4", "15.550"), Triple("SALAMELLA", "0.48", "10.210"),
            Triple("CARBONE", "1", "11.320"), Triple("CIPOLLA", "10", "1.490"), Triple("RICOTTA", "2", "4.850"),
        )
        val totals = listOf(345L, 109L, 1074L, 1954L, 12678L, 252L, 8180L, 6220L, 490L, 1132L, 1490L, 970L)
        val colli = listOf("1x1", "1x1", "2x3", "1", "1", "1x6", "2x1", "1x4", "1", "1x1", "1x10", "1x2")
        val report = StringBuilder()
        File(dir!!).listFiles { f -> f.name.startsWith("answer-") && f.name.endsWith(".json") }!!.sorted().forEach { f ->
            val raw = f.readText()
            val a = AiReader.decode(raw)
            assertNotNull("${f.name}: not valid JSON of the expected shape:\n$raw", a)
            val ocr = if (f.name.contains("no-ocr")) "" else ocrText
            val d = AiReader.toParsed(a!!, ocr)
            var amountsOk = 0; var qtyPriceOk = 0; var colliOk = 0; var namesOk = 0
            expected.forEachIndexed { i, (name, q, p) ->
                val it = d.lineItems.firstOrNull { li -> li.originalDescription.uppercase().startsWith(name) }
                if (it != null) namesOk++
                if (it?.lineTotalCents?.value == totals[i]) amountsOk++
                if (it?.quantity?.value?.compareTo(q.toBigDecimal()) == 0 && it.unitPrice?.value?.compareTo(p.toBigDecimal()) == 0) qtyPriceOk++
                if (it?.packages?.value == colli[i]) colliOk++
            }
            report.append("${f.name}: items=${d.lineItems.size}/12 names=$namesOk amounts=$amountsOk qty+price=$qtyPriceOk colli=$colliOk ")
                .append("seller=${d.sellerName?.value} number=${d.documentNumber?.value} date=${d.documentDate?.value} total=${d.totalCents?.value}\n")
            d.lineItems.forEach { li ->
                report.append("  ${li.itemCode}|${li.packages?.value}|${li.originalDescription}|q=${li.quantity?.value} ${li.unit?.value} p=${li.unitPrice?.value} t=${li.lineTotalCents?.value} ${li.quantity?.confidence}\n")
            }
        }
        File(dir, "report.txt").writeText(report.toString())
        println(report)
    }
}
