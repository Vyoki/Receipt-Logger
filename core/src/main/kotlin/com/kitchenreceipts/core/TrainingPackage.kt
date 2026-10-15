package com.kitchenreceipts.core

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/**
 * A saved document as a training example for the app's own AI (tools/training): what the camera read (the OCR lines
 * with their positions, as in the reading report) and the truth (the document as the operator checked and saved it),
 * in the same form as the invented documents (gen_docs.py's truth.json), so the same exporter and notebook use both.
 *
 * Only documents the arithmetic proves are taken: a model trained on a wrong answer learns the mistake. Proven means
 * every line's quantity x price (less its discount) is its amount, and the lines add up to the taxable amount or the
 * total to the cent.
 */
object TrainingPackage {

    data class Line(
        val description: String,
        val code: String? = null,
        val packages: String? = null,
        val quantity: BigDecimal? = null,
        val unit: String? = null,
        val price: BigDecimal? = null,
        val amountCents: Long?,
        val vatRate: BigDecimal? = null,
        val lot: String? = null,
        val discount: String? = null,
        /** A discount or charge line: an amount only. */
        val adjustment: Boolean = false,
    )

    data class Doc(
        val seller: String?,
        val sellerVat: String?,
        val number: String?,
        val date: LocalDate?,
        val subtotalCents: Long?,
        val vatCents: Long?,
        val totalCents: Long?,
        val lines: List<Line>,
    )

    enum class NotProven { NO_LINES, LINE_DOES_NOT_ADD_UP, SUM_DOES_NOT_MATCH, NO_READING }

    /** Why [d] cannot teach the model, or null when its arithmetic proves it. */
    fun whyNot(d: Doc): NotProven? {
        val products = d.lines.filter { !it.adjustment }
        if (products.isEmpty() || d.lines.any { it.amountCents == null }) return NotProven.NO_LINES
        for (l in products) {
            val q = l.quantity ?: return NotProven.LINE_DOES_NOT_ADD_UP
            val p = l.price ?: return NotProven.LINE_DOES_NOT_ADD_UP
            if (!LineDiscount.matches(q, p, l.discount, l.amountCents!!)) return NotProven.LINE_DOES_NOT_ADD_UP
        }
        val sum = d.lines.sumOf { it.amountCents!! }
        if (sum != d.subtotalCents && sum != d.totalCents) return NotProven.SUM_DOES_NOT_MATCH
        return null
    }

    /** One page of the reading, written as the reading report writes it (tools/training's exporter reads this). */
    fun reading(page: List<OcrLine>): String = buildString {
        for (l in page) {
            val conf = if (l.confidence < 1f) ",${"%.2f".format(Locale.ROOT, l.confidence)}" else ""
            append("${l.left},${l.top},${l.right},${l.bottom},${"%.1f".format(Locale.ROOT, l.angle)}$conf | ${l.text.replace('\n', ' ')}")
            if (l.words.isNotEmpty()) append("  [").append(l.words.joinToString(" ") { w ->
                "${w.left}-${w.right}:${w.text}" + if (w.confidence < 1f) "@${"%.2f".format(Locale.ROOT, w.confidence)}" else ""
            }).append(']')
            append('\n')
        }
    }

    /**
     * truth.json for [d] read as [pages] from photos of [sizes] (width, height). Each line is placed on its page and
     * row (by its name and amount on the row), and its numbers are written as printed there ("0,50", not "0,5"): the
     * model learns to copy what it sees.
     */
    fun truth(d: Doc, pages: List<List<OcrLine>>, sizes: List<Pair<Int, Int>>, ownVat: String? = null): String {
        val rows = pages.map { p -> LayoutRows.rows(p).map { r -> Row(r.joinToString(" ") { it.text.trim() }, r) } }
        val used = mutableSetOf<Pair<Int, Int>>()
        val items = d.lines.map { l ->
            val amount = l.amountCents?.let(ItalianNumbers::formatCents)
            val at = place(l, amount, rows, used)
            at?.let { used += it }
            val row = at?.let { (p, r) -> rows[p][r] }
            fun printed(v: BigDecimal?): String? = v?.let { x ->
                row?.text?.split(' ')?.map { it.trim(',', ';') }?.firstOrNull { t -> t.any(Char::isDigit) && ItalianNumbers.parse(t)?.compareTo(x) == 0 }
                    ?: ItalianNumbers.formatDecimal(x, maxScale = 4)
            }
            obj(
                "kind" to (if (l.adjustment) "adjustment" else "product"),
                "description" to l.description,
                "code" to l.code,
                "colli" to l.packages,
                "unit" to l.unit,
                "quantity" to printed(l.quantity),
                "price" to printed(l.price),
                "discount" to l.discount,
                "amount" to amount,
                "amount_cents" to l.amountCents,
                "vat" to l.vatRate?.let { r -> printedRate(row?.text, r) },
                "vat_rate" to l.vatRate?.let { ItalianNumbers.formatDecimal(it) },
                "lot" to l.lot,
                "page" to (at?.first ?: 0),
                "box" to row?.box(),
            )
        }
        val texts = rows.map { p -> p.joinToString("\n") { it.text } }
        val total = d.totalCents?.let(ItalianNumbers::formatCents)
        val totalsPage = total?.let { t -> texts.indexOfLast { it.contains(t) }.takeIf { it >= 0 } } ?: (pages.size - 1)
        val dateText = d.date?.let { date ->
            texts.asSequence().flatMap { ItalianDates.findDates(it).asSequence() }.firstOrNull { it.date == date }?.text ?: ItalianDates.format(date)
        }
        val lang = if (ITALIAN.containsMatchIn(texts.joinToString("\n"))) "it" else "en"
        val photos = sizes.map { (w, h) -> obj("width" to w, "height" to h) }
        return obj(
            "source" to "app",
            "lang" to lang,
            "own_vat" to ownVat,
            "seller" to d.seller,
            "seller_vat" to d.sellerVat,
            "number" to d.number,
            "date" to d.date?.toString(),
            "date_text" to dateText,
            "items" to Raw(items.joinToString(",", "[", "]")),
            "totals" to Raw(obj(
                "subtotal" to d.subtotalCents?.let(ItalianNumbers::formatCents),
                "vat" to d.vatCents?.let(ItalianNumbers::formatCents),
                "total" to total,
                "subtotal_cents" to d.subtotalCents, "vat_cents" to d.vatCents, "total_cents" to d.totalCents,
            )),
            "totals_page" to totalsPage,
            "pages" to pages.size,
            "photo" to Raw(photos.firstOrNull() ?: "null"),
            "photos" to Raw(photos.joinToString(",", "[", "]")),
        )
    }

    private class Row(val text: String, val words: List<OcrLine>) {
        fun box() = Raw("[${words.minOf { it.left }},${words.minOf { it.top }},${words.maxOf { it.right }},${words.maxOf { it.bottom }}]")
    }

    /** The page and row of [l]: the most of its name's words on the row, its amount on the row breaking ties. */
    private fun place(l: Line, amount: String?, rows: List<List<Row>>, used: Set<Pair<Int, Int>>): Pair<Int, Int>? {
        val name = words(l.description)
        var best: Pair<Int, Int>? = null
        var bestScore = 0.0
        for ((p, page) in rows.withIndex()) for ((r, row) in page.withIndex()) {
            if ((p to r) in used) continue
            val ocr = words(row.text)
            val found = if (name.isEmpty()) 0.0 else name.count { w -> ocr.any { o -> o == w || (w.length >= 4 && close(o, w)) } }.toDouble() / name.size
            val withAmount = if (amount != null && row.text.contains(amount.removePrefix("-"))) 0.5 else 0.0
            val s = found + withAmount
            if (s > bestScore) { bestScore = s; best = p to r }
        }
        return best.takeIf { bestScore >= 0.4 }
    }

    /** The VAT code as the row prints it ("04" for 4%), else the rate. */
    private fun printedRate(row: String?, rate: BigDecimal): String {
        val plain = ItalianNumbers.formatDecimal(rate)
        val last = row?.trim()?.split(' ')?.lastOrNull()?.trimEnd('%')
        return if (last != null && last.all(Char::isDigit) && last.isNotEmpty() && BigDecimal(last).compareTo(rate) == 0) last else plain
    }

    private fun words(s: String) = s.uppercase().split(rx("[^A-Z0-9]+")).filter { it.length >= 3 && it.any(Char::isLetter) }

    private fun close(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length] <= 1
    }

    private val ITALIAN = rx("(?i)\\b(fattura|totale|imponibile|quantit|prezzo|descrizione|documento|iva)\\b")

    /** JSON written as is (an object or list already built). */
    private class Raw(val json: String)

    private fun obj(vararg kv: Pair<String, Any?>) = kv.joinToString(",", "{", "}") { (k, v) -> q(k) + ":" + value(v) }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Raw -> v.json
        is Number -> v.toString()
        is Boolean -> v.toString()
        else -> q(v.toString())
    }

    private fun q(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format(Locale.ROOT, "\\u%04x", c.code)) else append(c)
        }
        append('"')
    }
}
