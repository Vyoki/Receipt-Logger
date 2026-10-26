package com.kitchenreceipts.core

import java.math.BigDecimal

/**
 * What a small checker model learns from: every value of a document the operator checked and saved, described by
 * how it was read (flagged or not, where it came from, what the document's arithmetic says) and whether the operator
 * kept it. Numbers only, plus the field's kind and a word for its source from a fixed list: no name, number, amount or
 * text of the document is ever in a case, so the cases can be shared for training without the documents.
 *
 * The model (see [CheckerModel], trained by tools/checker) gives the chance that a flagged value is right; it is used
 * only after it cleared no wrong value on checked invoices it did not learn from.
 */
object CheckerCases {

    /** The version of [FEATURES]: a file of cases holds one version only. */
    const val VERSION = 1

    /** One value: [group] the document (a random id), [kind] the field, [src] where it came from, [right] the label. */
    data class Case(val group: String, val kind: String, val src: String, val right: Boolean, val features: Map<String, Double>)

    /** What the draft alone does not say about the reading. */
    data class Reading(
        /** Every page was the PDF's own text (no camera); null when not known. */
        val textLayer: Boolean? = null,
        val pages: Int = 1,
        /** The supplier was recognised from documents saved before. */
        val knownSupplier: Boolean = false,
    )

    /** The features, in the order written to the file (the model looks them up by name). */
    val FEATURES = listOf(
        "uncertain", "length", "digits", "letters",
        "textLayer", "pages", "lines", "confident", "quality", "warnings", "vatProblems", "sumOk", "proof", "proofSupport",
        "knownSupplier", "byColumns", "docUncertain", "aiUsed",
        "isLine", "lineOk", "choices", "aiDiffers", "adjustment", "nameUnknown", "product",
    )

    /** Words a value's source starts with, kept as they are; any other source is "other". */
    private val SOURCES = listOf(
        "proven", "unproven", "conflict", "under", "vat", "column", "columns", "ai", "registry", "supplier", "summary",
        "heading", "the", "row", "page", "lot", "lotto", "read", "printed", "learned", "totale", "tipo", "doc", "documento",
        "data", "numero", "fattura", "ddt", "imponibile", "iva",
    )

    fun src(source: String?): String {
        if (source.isNullOrBlank()) return "none"
        val first = source.trim().lowercase().takeWhile { it.isLetter() }
        return if (first in SOURCES) first else "other"
    }

    /**
     * The cases of one document: [shown] the draft as the operator first saw it (flags included), [saved] the draft
     * saved, [parsed] the reading (null when not kept). A value read empty is no case (nothing to clear); a line the
     * operator removed makes its values wrong; a line added has no reading to judge.
     */
    fun cases(group: String, shown: DocumentDraft, saved: DocumentDraft, parsed: ParsedDocument?, reading: Reading = Reading()): List<Case> {
        val doc = docFeatures(shown, parsed, reading)
        val out = mutableListOf<Case>()
        fun add(kind: String, a: DraftField, b: DraftField?, money: Boolean, line: Map<String, Double>) {
            if (a.text.isBlank()) return
            val right = b != null && same(a.text, b.text, kind, money)
            val f = LinkedHashMap<String, Double>()
            f["uncertain"] = if (a.uncertain) 1.0 else 0.0
            f["length"] = a.text.trim().length.toDouble()
            f["digits"] = a.text.count(Char::isDigit).toDouble()
            f["letters"] = a.text.count(Char::isLetter).toDouble()
            f.putAll(doc)
            f.putAll(line)
            out += Case(group, kind, src(a.source), right, f)
        }
        val noLine = mapOf("isLine" to 0.0, "lineOk" to 0.0, "choices" to 0.0, "aiDiffers" to 0.0, "adjustment" to 0.0, "nameUnknown" to 0.0, "product" to 0.0)
        add("seller", shown.seller, saved.seller, false, noLine)
        add("date", shown.date, saved.date, false, noLine)
        add("number", shown.number, saved.number, false, noLine)
        add("subtotal", shown.subtotal, saved.subtotal, true, noLine)
        add("vat", shown.vat, saved.vat, true, noLine)
        add("total", shown.total, saved.total, true, noLine)
        val after = saved.items.associateBy { it.key }
        for (a in shown.items) {
            val b = after[a.key]
            fun line(field: String) = mapOf(
                "isLine" to 1.0,
                "lineOk" to if (lineOk(a)) 1.0 else 0.0,
                "choices" to a.choices.size.toDouble(),
                "aiDiffers" to if (a.aiRead.containsKey(field)) 1.0 else 0.0,
                "adjustment" to if (a.isCharge) 1.0 else 0.0,
                "nameUnknown" to a.nameUnknown.size.toDouble(),
                "product" to when (a.productSource) { ProductSource.REMEMBERED -> 3.0; ProductSource.RECOGNISED -> 2.0; ProductSource.NEW -> 1.0; else -> 0.0 },
            )
            add("description", a.description, b?.description, false, line("description"))
            add("quantity", a.quantity, b?.quantity, true, line("quantity"))
            add("unit", a.unit, b?.unit, false, line("unit"))
            add("price", a.unitPrice, b?.unitPrice, true, line("price"))
            add("amount", a.lineTotal, b?.lineTotal, true, line("amount"))
            add("vatRate", a.vatRate, b?.vatRate, true, line("vatRate"))
            add("lot", a.lot, b?.lot, false, line("lot"))
            add("expiry", a.expiry, b?.expiry, false, line("expiry"))
        }
        return out
    }

    private fun docFeatures(d: DocumentDraft, p: ParsedDocument?, r: Reading): Map<String, Double> {
        val proof = p?.supplierProof
        return mapOf(
            "textLayer" to when (r.textLayer) { true -> 1.0; false -> 0.0; null -> -1.0 },
            "pages" to r.pages.toDouble(),
            "lines" to d.items.size.toDouble(),
            "confident" to if (p != null && ReceiptParser.isConfident(p)) 1.0 else 0.0,
            "quality" to (p?.let { ReceiptParser.quality(it) } ?: 0).toDouble(),
            "warnings" to d.warnings.size.toDouble(),
            "vatProblems" to d.vatGroupProblems.size.toDouble(),
            "sumOk" to if (d.items.isNotEmpty() && AutoAccept.sumMatches(d)) 1.0 else 0.0,
            "proof" to when (proof?.state) { SupplierProof.State.PROVEN -> 3.0; SupplierProof.State.CONFLICT -> 2.0; SupplierProof.State.UNPROVEN -> 1.0; else -> 0.0 },
            "proofSupport" to (proof?.support ?: 0).toDouble(),
            "knownSupplier" to if (r.knownSupplier) 1.0 else 0.0,
            "byColumns" to if (p?.itemsReadBy == "columns") 1.0 else 0.0,
            "docUncertain" to d.uncertainCount.toDouble(),
            "aiUsed" to if (d.items.any { it.aiRead.isNotEmpty() } || p?.lineItems?.any { it.aiRead.isNotEmpty() } == true) 1.0 else 0.0,
        )
    }

    /** Quantity x price (less the discount) is the amount, all three read. */
    private fun lineOk(l: LineItemDraft): Boolean {
        val q = ItalianNumbers.parse(l.quantity.text) ?: return false
        val p = ItalianNumbers.parse(l.unitPrice.text) ?: return false
        val a = ItalianNumbers.parseCents(l.lineTotal.text) ?: return false
        return runCatching { LineDiscount.matches(q, p, l.discount.text, a) }.getOrDefault(false)
    }

    /** The value kept: the same number, the same date, or the same words (case and spaces aside). */
    private fun same(a: String, b: String, kind: String, money: Boolean): Boolean {
        if (money) {
            val x: BigDecimal? = ItalianNumbers.parse(a)
            val y: BigDecimal? = ItalianNumbers.parse(b)
            if (x != null && y != null) return x.compareTo(y) == 0
        }
        if (kind == "date" || kind == "expiry") {
            val x = ItalianDates.parse(a)
            val y = ItalianDates.parse(b)
            if (x != null && y != null) return x == y
        }
        fun n(s: String) = s.trim().lowercase().replace(rx("\\s+"), " ")
        return n(a) == n(b)
    }

    // ------------------------------------------------------------------ the file

    const val HEADER_PREFIX = "group,kind,src,right"

    fun header(): String = HEADER_PREFIX + "," + FEATURES.joinToString(",")

    /** One line of the file: the features in [FEATURES] order, numbers only. */
    fun row(c: Case): String =
        listOf(c.group, c.kind, c.src, if (c.right) "1" else "0").joinToString(",") + "," +
            FEATURES.joinToString(",") { f -> num(c.features[f] ?: 0.0) }

    private fun num(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}

/**
 * A checker model as tools/checker/train_checker.py writes it: boosted decision trees over the features of
 * [CheckerCases] (the field's kind and source one-hot, "kind=amount", "src=proven"), the chance that a value is right,
 * and the threshold above which a flagged value may be taken as checked. [ready] only when, on checked invoices it did
 * not learn from, it cleared no wrong value and made no document save with a wrong value.
 */
class CheckerModel private constructor(
    val features: List<String>,
    private val base: Double,
    private val rate: Double,
    private val trees: List<Tree>,
    val threshold: Double,
    val ready: Boolean,
    val stats: Map<String, String>,
) {
    private class Tree(val f: IntArray, val t: DoubleArray, val l: IntArray, val r: IntArray, val v: DoubleArray)

    /** The chance (0..1) that the value of [case] is right. */
    fun chance(case: CheckerCases.Case): Double {
        val x = DoubleArray(features.size) { i ->
            val name = features[i]
            when {
                name.startsWith("kind=") -> if (case.kind == name.removePrefix("kind=")) 1.0 else 0.0
                name.startsWith("src=") -> if (case.src == name.removePrefix("src=")) 1.0 else 0.0
                else -> case.features[name] ?: 0.0
            }
        }
        var score = base
        for (t in trees) {
            var n = 0
            // A node with feature -2 is a leaf (as scikit-learn writes it); left when the value is at most the threshold.
            while (t.f[n] >= 0) n = if (x[t.f[n]] <= t.t[n]) t.l[n] else t.r[n]
            score += rate * t.v[n]
        }
        return 1.0 / (1.0 + kotlin.math.exp(-score))
    }

    /** A flagged value the model may take as checked. */
    fun clears(case: CheckerCases.Case): Boolean = ready && chance(case) >= threshold

    companion object {
        fun parse(json: String): CheckerModel {
            val m = Json.parse(json) as Map<*, *>
            require((m["version"] as? String)?.toIntOrNull() == 1) { "Unknown checker model version" }
            fun d(v: Any?) = (v as String).toDouble()
            fun ints(v: Any?) = (v as List<*>).map { (it as String).toInt() }.toIntArray()
            fun doubles(v: Any?) = (v as List<*>).map { d(it) }.toDoubleArray()
            val trees = (m["trees"] as List<*>).map { t ->
                t as Map<*, *>
                Tree(ints(t["f"]), doubles(t["t"]), ints(t["l"]), ints(t["r"]), doubles(t["v"]))
            }
            val features = (m["features"] as List<*>).map { it as String }
            for (t in trees) require(t.f.all { it < features.size }) { "A tree uses a feature the model does not name" }
            return CheckerModel(
                features, d(m["base"]), d(m["rate"]), trees, d(m["threshold"]), m["ready"] == true,
                (m["stats"] as? Map<*, *>).orEmpty().entries.associate { (k, v) -> k.toString() to v.toString() },
            )
        }
    }
}
