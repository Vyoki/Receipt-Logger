package com.kitchenreceipts.core

/**
 * What the app has learned about how one supplier prints its documents, from documents the operator confirmed.
 * Used the next time a document of that supplier is read (see [ReceiptParser.parsePages]):
 * - [headings]: column headings the regular reading does not know ("COLLI/DESCRIZIONE" -> description), learned
 *   from the AI's answer about the heading row, kept once a document read with them was confirmed;
 * - [numberShape]: how the document number looks ("99A/99999" for 12A/34567), so the number is found and certain;
 * - [lotsUnderItems]: lot numbers are printed on the row under each item;
 * - [readByColumns]: whether the table was read best by columns (word positions) or as text;
 * - [documents]: how many confirmed documents this comes from.
 * Stored on the phone only, like everything else the app learns.
 */
data class SupplierLayout(
    val headings: Map<String, TableReader.Kind> = emptyMap(),
    val numberShape: String? = null,
    val lotsUnderItems: Boolean = false,
    val readByColumns: Boolean? = null,
    val documents: Int = 0,
) {
    fun encode(): String = listOf(
        "v1",
        "docs=$documents",
        "number=${numberShape.orEmpty()}",
        "lots=${if (lotsUnderItems) 1 else 0}",
        "columns=${when (readByColumns) { true -> 1; false -> 0; null -> "" }}",
        "headings=" + headings.entries.joinToString(";") { (w, k) -> "${w.replace(";", "").replace("=", "")}=${k.name}" },
    ).joinToString("\n")

    companion object {
        fun decode(s: String?): SupplierLayout? {
            if (s.isNullOrBlank() || !s.startsWith("v1")) return null
            val f = s.lines().drop(1).associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val headings = f["headings"].orEmpty().split(';').filter { '=' in it }.mapNotNull { e ->
                val kind = runCatching { TableReader.Kind.valueOf(e.substringAfter('=')) }.getOrNull() ?: return@mapNotNull null
                e.substringBefore('=') to kind
            }.toMap()
            return SupplierLayout(
                headings = headings,
                numberShape = f["number"]?.ifBlank { null },
                lotsUnderItems = f["lots"] == "1",
                readByColumns = when (f["columns"]) { "1" -> true; "0" -> false; else -> null },
                documents = f["docs"]?.toIntOrNull() ?: 0,
            )
        }
    }
}

object SupplierLayouts {

    /**
     * The shape of a document number: digits as 9, letters as A, separators kept ("12A/34567" -> "99A/99999",
     * "B26 204177" -> "A99 999999").
     */
    fun shape(number: String): String = number.trim().replace(Regex("\\s+"), " ")
        .map { c -> when { c.isDigit() -> '9'; c.isLetter() -> 'A'; else -> c } }.joinToString("")

    /**
     * Whether [value] has the learned [shape]: same letters, separators and order; a run of digits may be one digit
     * longer or shorter (the counter grows: 34567, 134567).
     */
    fun matchesShape(value: String, shape: String): Boolean {
        val a = runs(shape(value))
        val b = runs(shape)
        if (a.size != b.size) return false
        return a.zip(b).all { (x, y) -> x.first == y.first && (if (x.first == '9') kotlin.math.abs(x.second - y.second) <= 1 else x.second == y.second) }
    }

    private fun runs(s: String): List<Pair<Char, Int>> {
        val out = mutableListOf<Pair<Char, Int>>()
        for (c in s) if (out.isNotEmpty() && out.last().first == c) out[out.lastIndex] = c to out.last().second + 1 else out += c to 1
        return out
    }

    /**
     * What a confirmed document teaches about its supplier. [read] is the document as the app read it (with the
     * layout it used, if any), [confirmed] what the operator saved. Headings are kept only when the reading with them
     * was mostly right (most lines saved with the amounts as read): a wrong answer about the headings is not learned.
     */
    fun learn(read: ParsedDocument, confirmed: DocumentDraft): SupplierLayout {
        val number = confirmed.number.text.trim().takeIf { it.length in 3..30 && it.any(Char::isDigit) }
        val readAmounts = read.lineItems.mapNotNull { it.lineTotalCents?.value }
        val savedAmounts = confirmed.items.mapNotNull { ItalianNumbers.parseCents(it.lineTotal.text) }
        val kept = savedAmounts.count { it in readAmounts }
        val mostlyRight = savedAmounts.isNotEmpty() && kept * 5 >= savedAmounts.size * 4
        val withLots = confirmed.items.count { it.lot.text.isNotBlank() }
        return SupplierLayout(
            headings = if (mostlyRight) read.layout?.headings.orEmpty() else emptyMap(),
            numberShape = number?.let(::shape),
            lotsUnderItems = confirmed.items.size >= 2 && withLots * 5 >= confirmed.items.size * 3 && read.lotsUnderItems,
            readByColumns = if (mostlyRight) read.itemsReadBy == "columns" else null,
            documents = 1,
        )
    }

    /** Adds what one more confirmed document taught; the newest document wins where they differ. */
    fun merge(old: SupplierLayout?, new: SupplierLayout): SupplierLayout {
        if (old == null) return new
        return SupplierLayout(
            headings = old.headings + new.headings,
            numberShape = new.numberShape ?: old.numberShape,
            lotsUnderItems = new.lotsUnderItems || (old.lotsUnderItems && new.documents == 0),
            readByColumns = new.readByColumns ?: old.readByColumns,
            documents = old.documents + new.documents,
        )
    }

    /** "COLLI/DESCRIZIONE" -> "colli/descrizione": heading words as [TableReader] compares them. */
    fun headingKey(word: String): String =
        java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()
            .trim('.', ':', ',', '\'', '’', '"', '|', '(', ')')
}
