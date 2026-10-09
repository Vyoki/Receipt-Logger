package com.kitchenreceipts.training

import com.kitchenreceipts.core.AiImagePlan
import com.kitchenreceipts.core.AiLoop
import com.kitchenreceipts.core.AiReader
import com.kitchenreceipts.core.AiTarget
import com.kitchenreceipts.core.AiTargets
import com.kitchenreceipts.core.AiTasks
import com.kitchenreceipts.core.Confidence
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.Json
import com.kitchenreceipts.core.LayoutRows
import com.kitchenreceipts.core.LineDiscount
import com.kitchenreceipts.core.OcrLine
import com.kitchenreceipts.core.PageBox
import com.kitchenreceipts.core.ParseOptions
import com.kitchenreceipts.core.ParsedDocument
import com.kitchenreceipts.core.ReceiptParser
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.random.Random

/**
 * Turns generated documents (tools/training/gen_docs.py: page.jpg, reading.txt, truth.json) into training samples
 * for the app's AI, using the app's own code: its reading, its question planner, its question loop and its prompts.
 * What the model learns is exactly what the phone will ask it.
 *
 * Each sample: the areas of the page to show (as the phone stacks them), the prompt, the right answer (from the
 * truth). Three kinds:
 *  - look: one question about one area (a line, a number, the supplier's box, the totals, the header, the headings);
 *  - follow-up: a line asked again one number at a time after a whole-line answer that did not add up, with that
 *    earlier answer and its arithmetic in the prompt (the model learns to correct itself);
 *  - decide: which open question to ask next, from the summary of the document; the right answer is the question that
 *    proves the most when answered (tried on a copy of the loop), so the model learns to plan.
 *
 * Run (after compiling core with this file): java -cp ... com.kitchenreceipts.training.TrainingExportKt IN_DIR OUT.jsonl [seed]
 */
fun main(args: Array<String>) {
    val inDir = File(args[0])
    val out = File(args[1])
    val seed = args.getOrNull(2)?.toInt() ?: 1
    val r = Random(seed)
    var docs = 0
    var samples = 0
    out.bufferedWriter().use { w ->
        for (d in inDir.listFiles()!!.filter { File(it, "truth.json").exists() }.sortedBy { it.name }) {
            val n = runCatching { Exporter(d, r).run { export() } }.onFailure { System.err.println("${d.name}: ${it.message}") }.getOrNull() ?: continue
            n.forEach { w.write(it); w.write("\n") }
            docs++
            samples += n.size
        }
    }
    println("$docs documents, $samples samples -> $out")
}

private class Exporter(private val dir: File, private val r: Random) {
    private val truth = Json.parse(File(dir, "truth.json").readText()) as Map<*, *>
    private val lines = parseReport(File(dir, "reading.txt").readText())
    private val lang = truth["lang"] as String
    private val items = (truth["items"] as List<*>).map { it as Map<*, *> }
    private val options = ParseOptions(ownVatNumber = "09876543217", today = LocalDate.of(2026, 12, 31))
    private val text = LayoutRows.toText(lines)
    private val photo = truth["photo"] as Map<*, *>
    private val scale = AiImagePlan.plan(lines, int(photo["width"]), int(photo["height"])).scale
    private val out = mutableListOf<String>()

    fun export(): List<String> {
        val start = ReceiptParser.parsePages(listOf(lines), options)
        // 1. Single looks at every kind of area: every line, the header, the totals, the supplier's box (as if each
        //    were in doubt), and the planner's own double-checks.
        val doubted = doubt(start)
        val singles = (AiTargets.plan(listOf(lines), doubted, spotCheck = false).orEmpty() +
            AiTargets.plan(listOf(lines), start, spotCheck = true).orEmpty()).distinctBy { AiLoop.key(it) }
        for (t in singles) look(t, "look", null)
        AiTargets.layoutQuestion(listOf(lines), start.copy(itemsReadBy = "text", layout = null))?.let { q -> layout(q.boxes, q.headings) }
        // 2. The loop, as the phone runs it, with the right answers, some whole-line answers that do not add up (as a
        //    small model gives them) and the follow-ups that correct them, and the choice of what to ask next.
        val loop = AiLoop(listOf(lines), text, options, start)
        while (true) {
            val open = loop.candidates()
            if (open.isEmpty()) break
            var pick = 0
            if (AiTasks.worthDeciding(open)) {
                pick = best(loop, open)
                val (prompt, grammar) = AiTasks.decide(loop.doc, open)
                // The planner's first is usually right: fewer of those, so the model learns when to choose another.
                if (pick != 0 || r.nextDouble() < 0.35) emit("decide", null, emptyList(), prompt, "ABCDEF"[pick].toString(), "compact", grammar)
            }
            val t = open[pick]
            val right = answer(t) ?: break
            val given = if (t is AiTarget.Row && t.itemIndex != null && r.nextDouble() < 0.35) wrong(right) ?: right else right
            if (given == right) look(t, "loop", loop.previous(t)) else Unit
            loop.answer(t, given, pick)
        }
        return out
    }

    // ------------------------------------------------------------------ samples

    private fun look(t: AiTarget, kind: String, previous: String?) {
        val a = answer(t) ?: return
        val k = if (previous != null) "follow-up" else kind
        val (compact, grammar) = AiTasks.question(t, AiTasks.Style.COMPACT, previous = previous)
        emit(k, t.page, t.boxes, compact, a, "compact", grammar)
        // Some in the long style too, so the model also understands the general instructions.
        if (r.nextDouble() < 0.2) {
            val l = if (lang == "it") AiReader.Lang.IT else AiReader.Lang.EN
            emit(k, t.page, t.boxes, AiTasks.question(t, AiTasks.Style.FULL, l, previous = previous).first, a, "full", grammar)
        }
    }

    private fun layout(boxes: List<PageBox>, headings: List<String>) {
        val cols = (truth["headings"] as Map<*, *>).entries.associate { (k, v) -> norm(v as String) to k as String }
        val letters = headings.map { h ->
            val n = norm(h)
            val col = cols.entries.firstOrNull { (t, _) -> t == n } ?: cols.entries.firstOrNull { (t, _) -> t.contains(n) || n.contains(t) }
            LAYOUT[col?.value] ?: "K"
        }
        val (prompt, grammar) = AiTasks.layout(headings)
        emit("look", 0, boxes, prompt, letters.joinToString(","), "compact", grammar)
    }

    private fun emit(kind: String, page: Int?, boxes: List<PageBox>, prompt: String, answer: String, style: String, grammar: String) {
        out += buildString {
            append("{\"doc\":").append(q(dir.name)).append(",\"kind\":").append(q(kind)).append(",\"style\":").append(q(style))
            append(",\"lang\":").append(q(lang)).append(",\"image\":").append(if (page == null) "null" else q("page.jpg"))
            append(",\"boxes\":[").append(boxes.joinToString(",") { "[${it.left},${it.top},${it.right},${it.bottom}]" }).append("]")
            append(",\"scale\":").append(scale).append(",\"prompt\":").append(q(prompt)).append(",\"answer\":").append(q(answer))
            append(",\"grammar\":").append(q(grammar)).append("}")
        }
    }

    // ------------------------------------------------------------------ the right answers

    /**
     * The truth's item the question is about: the line whose name and amount the OCR's text of the row shows (the
     * area shown may hold the lines around it too), the strip's overlap breaking ties. Null when the row is no item.
     */
    private fun itemFor(t: AiTarget): Map<*, *>? {
        val strip = t.boxes.lastOrNull() ?: return null
        val rowText = when (t) { is AiTarget.Row -> t.rowText; is AiTarget.Number -> t.rowText; is AiTarget.Choice -> t.rowText; else -> return null }
        val ocr = words(rowText)
        val scored = items.map { it to score(it, ocr, rowText) + 0.001 * overlap(it, strip) }
        val best = scored.maxByOrNull { it.second } ?: return null
        return best.first.takeIf { best.second >= 0.4 }
    }

    private fun score(item: Map<*, *>, ocr: List<String>, rowText: String): Double {
        val name = words(item["description"] as? String ?: "")
        if (name.isEmpty()) return 0.0
        val found = name.count { w -> ocr.any { o -> o == w || (w.length >= 4 && editDistance(o, w) <= 1) } }
        val amount = (item["amount"] as? String)?.let { a -> if (rowText.contains(a)) 0.5 else 0.0 } ?: 0.0
        return found.toDouble() / name.size + amount
    }

    private fun words(s: String) = s.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.length >= 3 && it.any(Char::isLetter) }

    private fun editDistance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    private fun overlap(item: Map<*, *>, b: PageBox): Int {
        val box = (item["box"] as? List<*>)?.map { int(it) } ?: return 0
        val top = maxOf(box[1], b.top); val bottom = minOf(box[3], b.bottom)
        return (bottom - top).coerceAtLeast(0)
    }

    private fun answer(t: AiTarget): String? = when (t) {
        is AiTarget.Row -> rowAnswer(itemFor(t))
        is AiTarget.Number -> itemFor(t)?.let { s(it, if (t.field == AiTarget.Field.AMOUNT) "amount" else "quantity") } ?: "X"
        is AiTarget.Choice -> {
            val it = itemFor(t)
            val q = it?.let { s(it, "quantity") }?.let(ItalianNumbers::parse)
            val p = it?.let { s(it, "price") }?.let(ItalianNumbers::parse)
            val i = t.choices.indexOfFirst { c -> q != null && p != null && c.quantity.compareTo(q) == 0 && c.unitPrice.compareTo(p) == 0 }
            if (i >= 0) "ABCD"[i].toString() else "X"
        }
        is AiTarget.Header -> obj("seller" to truth["seller"], "seller_vat" to truth["seller_vat"], "number" to truth["number"], "date" to truth["date_text"])
        is AiTarget.Totals -> (truth["totals"] as Map<*, *>).let { tt -> obj("subtotal" to tt["subtotal"], "vat" to tt["vat"], "total" to tt["total"]) }
        is AiTarget.Supplier -> obj("name" to truth["seller"], "vat" to truth["seller_vat"])
    }

    private fun rowAnswer(it: Map<*, *>?): String = obj(
        "code" to it?.get("code"), "colli" to it?.get("colli"), "description" to it?.get("description"), "unit" to it?.get("unit"),
        "quantity" to it?.get("quantity"), "price" to it?.get("price"), "discount" to it?.get("discount"), "amount" to it?.get("amount"),
        "vat_rate" to it?.get("vat"), "lot" to it?.get("lot"),
    )

    /** A whole-line answer as a small model gets it wrong: the columns shifted by one, or a digit misread in the amount. */
    private fun wrong(right: String): String? {
        val m = Json.parse(right) as Map<*, *>
        val q = m["quantity"] as? String; val p = m["price"] as? String; val a = m["amount"] as? String
        if (q == null || p == null || a == null) return null
        val bad = if (r.nextBoolean()) mapOf("quantity" to p, "price" to a, "amount" to a)
        else mapOf("amount" to a.map { c -> if (c.isDigit() && r.nextDouble() < 0.3) ('0' + (c - '0' + 1 + r.nextInt(8)) % 10) else c }.joinToString(""))
        val merged = linkedMapOf<String, Any?>().apply { putAll(m.entries.associate { it.key as String to it.value }); putAll(bad) }
        // Only when it really does not add up: otherwise it is no mistake to correct.
        val qq = ItalianNumbers.parse(merged["quantity"] as String); val pp = ItalianNumbers.parse(merged["price"] as String)
        val aa = ItalianNumbers.parseCents(merged["amount"] as String)
        if (qq != null && pp != null && aa != null && LineDiscount.matches(qq, pp, merged["discount"] as? String, aa)) return null
        return obj(*merged.entries.map { it.key to it.value }.toTypedArray())
    }

    /** The open question that proves the most when answered right (tried on copies of the loop); the planner's first on a tie. */
    private fun best(loop: AiLoop, open: List<AiTarget>): Int {
        var best = 0
        var bestDoubt = Int.MAX_VALUE
        for ((i, t) in open.take(AiTasks.MAX_OPTIONS).withIndex()) {
            val c = loop.copy()
            c.answer(t, answer(t) ?: "", i)
            val d = c.doubt()
            if (d < bestDoubt) { bestDoubt = d; best = i }
        }
        return best
    }

    // ------------------------------------------------------------------ helpers

    /** Every value in doubt, so the planner asks about every line, the header, the totals and the supplier's box. */
    private fun doubt(d: ParsedDocument): ParsedDocument = d.copy(
        sellerName = d.sellerName?.copy(confidence = Confidence.LOW),
        documentDate = d.documentDate?.copy(confidence = Confidence.LOW),
        totalCents = d.totalCents?.copy(confidence = Confidence.LOW),
        lineItems = d.lineItems.map { it.copy(quantity = it.quantity?.copy(confidence = Confidence.LOW), unitPrice = it.unitPrice?.copy(confidence = Confidence.LOW)) },
    )

    /** A number from the JSON reader (which keeps numbers as text). */
    private fun int(v: Any?): Int = (v as? Number)?.toInt() ?: v.toString().toDouble().toInt()

    private fun s(m: Map<*, *>, k: String) = (m[k] as? String)?.takeIf { it.isNotEmpty() }

    private fun obj(vararg kv: Pair<String, Any?>) = kv.joinToString(",", "{", "}") { (k, v) ->
        q(k) + ":" + (v?.toString()?.takeIf { it.isNotEmpty() }?.let(::q) ?: "null")
    }

    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    companion object {
        val LAYOUT = mapOf("code" to "A", "colli" to "B", "desc" to "C", "unit" to "D", "qty" to "E", "price" to "F", "disc" to "G",
            "amount" to "H", "vat" to "I", "lot" to "J")

        fun q(s: String) = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
            append('"')
        }

        /** The app's "Raw lines" report format: "l,t,r,b,angle[,conf] | text  [l-r:word@conf ...]". */
        fun parseReport(text: String): List<OcrLine> = text.lines().filter { it.contains(" | ") }.map { l ->
            val (g, rest) = l.split(" | ", limit = 2)
            val n = g.split(",")
            val top = n[1].toInt(); val bottom = n[3].toInt()
            val bracket = rest.lastIndexOf("  [")
            val boxed = bracket >= 0 && rest.endsWith("]")
            val words = if (boxed) rest.substring(bracket + 3, rest.length - 1).split(Regex(" (?=-?\\d+--?\\d+:)")).mapNotNull { w ->
                val m = Regex("^(-?\\d+)-(-?\\d+):(.*)$").find(w) ?: return@mapNotNull null
                val wt = m.groupValues[3]
                val c = wt.substringAfterLast('@', "").toFloatOrNull()
                OcrLine(if (c != null) wt.substringBeforeLast('@') else wt, m.groupValues[1].toInt(), top, m.groupValues[2].toInt(), bottom, confidence = c ?: 1f)
            } else emptyList()
            OcrLine(if (boxed) rest.substring(0, bracket) else rest, n[0].toInt(), top, n[2].toInt(), bottom, n[4].toFloat(), words,
                n.getOrNull(5)?.trim()?.toFloatOrNull() ?: 1f)
        }
    }
}
