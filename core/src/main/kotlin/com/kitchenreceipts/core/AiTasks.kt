package com.kitchenreceipts.core

import java.math.BigDecimal

/**
 * What the AI is asked, in the words a model understands best.
 *
 *  - [Style.FULL]: the long instructions of [AiReader], for a general model that has to be told everything.
 *  - [Style.COMPACT]: a few words per question, for the model trained on exactly these questions: the phone reads a
 *    short prompt several times faster, and the trained model needs no explanation. The answers (and their grammars)
 *    are the same in both styles.
 *
 * Also the agent's steps: [decide] (which of the open questions to ask next, from a short summary of the document,
 * no picture) and the follow-up that tells the model why a line is asked again ([previous] answer and its arithmetic).
 */
object AiTasks {

    enum class Style { FULL, COMPACT }

    /** The instruction and the answer's grammar for [t]. [previous]: the AI's earlier whole-line answer (a follow-up). */
    fun question(
        t: AiTarget,
        style: Style,
        lang: AiReader.Lang = AiReader.defaultLang,
        examples: List<AiReader.RowExample> = emptyList(),
        previous: String? = null,
    ): Pair<String, String> = when (style) {
        Style.FULL -> when (t) {
            is AiTarget.Row -> AiReader.rowInstruction(t.headerText, t.rowText, lang, examples) to AiReader.ROW_GRAMMAR
            is AiTarget.Choice -> AiReader.choiceInstruction(t.headerText, t.rowText, t.choices, lang) to AiReader.CHOICE_GRAMMAR
            is AiTarget.Number -> AiReader.numberInstruction(t.column, t.headerText, t.rowText, lang) +
                (previous?.let { why(it, lang) } ?: "") to AiReader.NUMBER_GRAMMAR
            is AiTarget.Header -> AiReader.headerInstruction(lang) to AiReader.HEADER_GRAMMAR
            is AiTarget.Totals -> AiReader.totalsInstruction(lang) to AiReader.TOTALS_GRAMMAR
            is AiTarget.Supplier -> AiReader.supplierInstruction(t.boxText, lang) to AiReader.SUPPLIER_GRAMMAR
        }
        Style.COMPACT -> when (t) {
            is AiTarget.Row -> "ROW\nH: ${t.headerText.take(200)}\nOCR: ${t.rowText.take(250)}" to AiReader.ROW_GRAMMAR
            is AiTarget.Choice -> buildString {
                append("CHOICE\nH: ").append(t.headerText.take(200)).append("\nOCR: ").append(t.rowText.take(250))
                t.choices.take(4).forEachIndexed { i, c ->
                    append('\n').append("ABCD"[i]).append(": ").append(n(c.quantity)).append(" | ").append(n(c.unitPrice))
                        .append(" | ").append(money(c.lineTotalCents))
                }
            } to AiReader.CHOICE_GRAMMAR
            is AiTarget.Number -> buildString {
                append(if (t.field == AiTarget.Field.AMOUNT) "AMOUNT" else "QTY").append(" ").append(t.column)
                append("\nH: ").append(t.headerText.take(200)).append("\nOCR: ").append(t.rowText.take(250))
                previous?.let { append("\nPREV: ").append(summary(it)) }
            } to AiReader.NUMBER_GRAMMAR
            is AiTarget.Header -> "HEADER" to AiReader.HEADER_GRAMMAR
            is AiTarget.Totals -> "TOTALS" to AiReader.TOTALS_GRAMMAR
            is AiTarget.Supplier -> "SUPPLIER\nOCR: ${t.boxText.take(250)}" to AiReader.SUPPLIER_GRAMMAR
        }
    }

    /** The column headings question (see [AiTargets.layoutQuestion]) in the compact style. */
    fun layout(headings: List<String>): Pair<String, String> =
        ("COLUMNS\n" + headings.mapIndexed { i, h -> "${i + 1}) ${h.take(40)}" }.joinToString("\n")) to AiReader.layoutGrammar(headings.size)

    // ------------------------------------------------------------------ the agent's choice

    /** Worth asking the model which question comes next: several open questions of different kinds. */
    fun worthDeciding(candidates: List<AiTarget>): Boolean =
        candidates.size >= 2 && candidates.take(MAX_OPTIONS).map { AiLoop.priority(it) }.distinct().size >= 2

    /**
     * Which of the open questions to ask next, from a short summary of the document (no picture: fast). The answer is
     * one letter ([decodeDecide]); the arithmetic still decides what any answer is worth.
     */
    fun decide(doc: ParsedDocument, candidates: List<AiTarget>): Pair<String, String> {
        val options = candidates.take(MAX_OPTIONS)
        val text = buildString {
            append("NEXT\nSTATE: ").append(state(doc))
            options.forEachIndexed { i, t -> append('\n').append(LETTERS[i]).append(") ").append(describe(t, doc)) }
        }
        return text to "root ::= [${LETTERS.take(options.size).joinToString("")}]\n"
    }

    fun decodeDecide(raw: String, candidates: List<AiTarget>): Int? {
        val i = LETTERS.indexOf(raw.trim().trim('"').uppercase().take(1))
        return i.takeIf { it >= 0 && it < minOf(candidates.size, MAX_OPTIONS) }
    }

    /** "12 lines, 10 add up; supplier unsure; number sure; date sure; taxable + VAT = total: yes; lines = taxable: no". */
    fun state(d: ParsedDocument): String {
        fun sure(e: Extracted<*>?) = when { e == null -> "missing"; e.confidence == Confidence.LOW -> "unsure"; else -> "sure" }
        val goods = d.lineItems.filter { !it.adjustment }
        val ok = goods.count { lineAddsUp(it) }
        val sub = d.subtotalCents?.value; val vat = d.vatCents?.value; val tot = d.totalCents?.value
        val sums = d.lineItems.mapNotNull { it.lineTotalCents?.value }
        val closes = if (sub != null && vat != null && tot != null) (if (kotlin.math.abs(sub + vat - tot) <= 2) "yes" else "no") else "?"
        val linesTotal = if (sums.size == d.lineItems.size && sums.isNotEmpty() && (sub ?: tot) != null)
            (if (kotlin.math.abs(sums.sum() - (sub ?: tot)!!) <= maxOf(2L, sums.size.toLong())) "yes" else "no") else "?"
        return "${goods.size} lines, $ok add up; supplier ${sure(d.sellerName)}; number ${sure(d.documentNumber)}; date ${sure(d.documentDate)}; " +
            "taxable+VAT=total: $closes; lines=taxable: $linesTotal"
    }

    /** One open question in a few words, with what the arithmetic says about it. */
    fun describe(t: AiTarget, d: ParsedDocument): String = when (t) {
        is AiTarget.Supplier -> "supplier box"
        is AiTarget.Header -> if (t.verify) "check number and date" else "number and date"
        is AiTarget.Totals -> if (t.verify) "check totals" else "totals"
        is AiTarget.Row -> t.itemIndex?.let { i -> "line ${i + 1}: " + lineState(d.lineItems.getOrNull(i)) } ?: "line not read: ${t.rowText.take(40)}"
        is AiTarget.Choice -> "line ${t.itemIndex + 1}: ${t.choices.size} readings add up"
        is AiTarget.Number -> "line ${t.itemIndex + 1}: " + (if (t.verify) "check " else "") +
            (if (t.field == AiTarget.Field.AMOUNT) "amount" else "quantity") + " alone"
    }

    private fun lineState(it: ParsedLineItem?): String {
        if (it == null) return "?"
        val name = it.originalDescription.take(24)
        val q = it.quantity?.value; val p = it.unitPrice?.value; val t = it.lineTotalCents?.value
        return when {
            q == null || p == null || t == null -> "$name, numbers missing"
            lineAddsUp(it) -> "$name, adds up"
            else -> "$name, ${n(q)} x ${n(p)} = ${money(LineDiscount.net(q, p, it.discount?.value))}, read ${money(t)}"
        }
    }

    private fun lineAddsUp(it: ParsedLineItem): Boolean {
        val q = it.quantity?.value ?: return false; val p = it.unitPrice?.value ?: return false; val t = it.lineTotalCents?.value ?: return false
        return ParseWarning.LINE_TOTAL_MISMATCH !in it.warnings && LineDiscount.matches(q, p, it.discount?.value, t)
    }

    // ------------------------------------------------------------------ the follow-up's reason

    /** The AI's earlier answer for the line and why it was not taken: "qty 0,422 price 4,62 amount 4,62: 0,422 x 4,62 = 1,95". */
    fun summary(previous: String): String {
        val a = AiReader.decodeItem(previous) ?: return "unreadable answer"
        val q = a.quantity?.let { ItalianNumbers.parse(it) }
        val p = a.price?.let { ItalianNumbers.parse(it) }
        val calc = if (q != null && p != null) ": ${n(q)} x ${n(p)} = ${money(LineDiscount.net(q, p, a.discount))}" else ""
        return "qty ${a.quantity ?: "-"} price ${a.price ?: "-"} amount ${a.amount ?: "-"}$calc"
    }

    private fun why(previous: String, lang: AiReader.Lang): String = if (lang == AiReader.Lang.IT) {
        "La lettura precedente della riga non torna (${summary(previous)}): guarda solo quella colonna.\n"
    } else {
        "The earlier reading of this line does not add up (${summary(previous)}): look at that column only.\n"
    }

    private fun n(v: BigDecimal) = ItalianNumbers.formatDecimal(v, maxScale = 4)
    private fun money(c: Long) = ItalianNumbers.formatDecimal(ItalianNumbers.centsToDecimal(c), minScale = 2, maxScale = 2)

    const val MAX_OPTIONS = 6
    private val LETTERS = listOf("A", "B", "C", "D", "E", "F")
}
