package com.kitchenreceipts.core

/**
 * The AI's questions one at a time, each chosen from the document as it stands after the previous answer:
 *
 *  1. list what is not proven yet (see [AiTargets.plan]);
 *  2. ask the question that settles the most first: the supplier's box, lines the reading missed, lines that do not
 *     add up, the totals, the header; the double-checks of values already proven last;
 *  3. apply the answer ([AiReader.applyTargets], which never lets the AI overrule the arithmetic) and read the
 *     document again: an answer that settles other doubts removes their questions, one that shows a new doubt adds one;
 *  4. a line still not proven after the AI read it whole is asked again more narrowly: its amount alone, then its
 *     quantity alone;
 *  5. stop when nothing is left to ask, after [maxQuestions], or when the caller stops (time limit, cancel).
 *
 * The app drives it ([next], then [answer]); no question is asked twice. The answers, in order, are all that is
 * needed to get the same document again ([replay]), e.g. after the app restarts.
 */
class AiLoop(
    private val lines: List<List<OcrLine>>,
    private val text: String,
    private val options: ParseOptions,
    start: ParsedDocument,
    val maxQuestions: Int = MAX_QUESTIONS,
) {
    var doc: ParsedDocument = start
        private set

    /** The answers given so far, in the order asked. */
    val answers = mutableListOf<String>()

    private val asked = mutableSetOf<String>()
    private val rowsRead = linkedMapOf<String, AiTarget.Row>()

    /** The next question, or null when there is nothing more to ask. */
    fun next(): AiTarget? {
        if (answers.size >= maxQuestions) return null
        val planned = AiTargets.plan(lines, doc, spotCheck = true).orEmpty()
        val open = (planned + followUps(planned)).filter { key(it) !in asked }
        return open.withIndex().minWithOrNull(compareBy({ priority(it.value) }, { it.index }))?.value
    }

    /** Applies the AI's [raw] answer to [target] ("" when the AI gave none: the question still counts as asked). */
    fun answer(target: AiTarget, raw: String) {
        asked += key(target)
        answers += raw
        if (target is AiTarget.Row) rowsRead[target.rowText] = target
        if (raw.isBlank()) return
        val before = doc.aiCheck
        val after = AiReader.applyTargets(doc, listOf(target to raw), text, options)
        doc = after.copy(aiCheck = merge(before, after.aiCheck))
    }

    /**
     * A line the AI read whole that is still not proven: its amount, then its quantity, each asked on its own (a
     * smaller question the model answers more reliably).
     */
    private fun followUps(planned: List<AiTarget>): List<AiTarget> {
        val out = mutableListOf<AiTarget>()
        for ((rowText, row) in rowsRead) {
            val i = row.itemIndex ?: continue
            val item = doc.lineItems.getOrNull(i) ?: continue
            // Still the same line (a line inserted above would have moved it).
            val firstWord = item.originalDescription.split(' ').firstOrNull { w -> w.count(Char::isLetter) >= 3 } ?: continue
            if (!rowText.uppercase().contains(firstWord.uppercase())) continue
            if (proven(item) || planned.none { key(it) == key(row) }) continue
            val amount = item.lineTotalCents?.value?.let { ItalianNumbers.centsToDecimal(it) } ?: java.math.BigDecimal.ZERO
            out += AiTarget.Number(row.page, row.boxes, i, "IMPORTO", amount, rowText, row.headerText, AiTarget.Field.AMOUNT)
            out += AiTarget.Number(row.page, row.boxes, i, "QUANTITA'", item.quantity?.value ?: java.math.BigDecimal.ZERO, rowText, row.headerText, AiTarget.Field.QUANTITY)
        }
        return out
    }

    companion object {
        /** Enough for a messy document; a clean one needs none or a couple. The caller also stops on its time limit. */
        const val MAX_QUESTIONS = 12

        /** The document after the same questions with the same answers (the questions are chosen the same way). */
        fun replay(lines: List<List<OcrLine>>, text: String, options: ParseOptions, start: ParsedDocument, answers: List<String>): ParsedDocument {
            val loop = AiLoop(lines, text, options, start, maxQuestions = answers.size)
            for (raw in answers) {
                val t = loop.next() ?: break
                loop.answer(t, raw)
            }
            return loop.doc
        }

        /** The same question asked twice is recognised by what it asks about, not by its place in the list. */
        fun key(t: AiTarget): String = when (t) {
            is AiTarget.Row -> "row:" + t.rowText
            is AiTarget.Choice -> "choice:" + t.rowText
            is AiTarget.Number -> "number:${t.field}:${t.verify}:" + t.rowText
            is AiTarget.Header -> "header"
            is AiTarget.Totals -> "totals"
            is AiTarget.Supplier -> "supplier"
        }

        /** What settles the most first. */
        fun priority(t: AiTarget): Int = when (t) {
            is AiTarget.Supplier -> 0
            is AiTarget.Row -> if (t.itemIndex == null) 1 else 2
            is AiTarget.Choice -> 2
            is AiTarget.Number -> if (t.verify) 6 else 3
            is AiTarget.Totals -> if (t.verify) 7 else 4
            is AiTarget.Header -> if (t.verify) 8 else 5
        }

        private fun proven(it: ParsedLineItem): Boolean {
            val q = it.quantity ?: return false; val p = it.unitPrice ?: return false; val t = it.lineTotalCents ?: return false
            return ParseWarning.LINE_TOTAL_MISMATCH !in it.warnings && LineDiscount.matches(q.value, p.value, it.discount?.value, t.value) &&
                t.confidence == Confidence.HIGH
        }

        private fun merge(a: AiCheck?, b: AiCheck?): AiCheck? = when {
            a == null -> b
            b == null || b === a -> a
            else -> AiCheck(a.checked + b.checked, a.disagreements + b.disagreements, a.header + b.header)
        }
    }
}
