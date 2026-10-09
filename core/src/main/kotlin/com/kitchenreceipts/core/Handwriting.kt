package com.kitchenreceipts.core

/**
 * Handwritten documents (a delivery note filled in by hand, a market's receipt book) and very poor photos. The OCR
 * engine says how sure it is of each word; on handwriting it is often unsure of the words, while the numbers still
 * check each other (quantity x price = amount, lines adding up to the total).
 *
 * So: numbers proven by the arithmetic are kept as proven, whatever the OCR's doubt; a product name with words the
 * OCR was unsure of is marked for a look, and the AI is asked to read that line from the picture (its reading is
 * offered as the replacement; it never changes a proven number).
 */
object Handwriting {

    /** Below this the OCR engine is guessing (ML Kit gives about 0.9 and more on clean print). */
    const val UNSURE = 0.6f

    /**
     * The words that make the document: not its small print (conditions of sale, privacy and legal notices, printed
     * much smaller than the rest) nor long lines of prose. A blurred footer must not make a clear page "unclear", nor
     * make a product name doubtful because the same word was unsure down there.
     */
    private fun words(pages: List<List<OcrLine>>) = pages.flatMap { page ->
        val lines = page.filter { l -> (l.words.ifEmpty { listOf(l) }).size <= 12 }
        val heights = lines.flatMap { l -> l.words.ifEmpty { listOf(l) } }.map { it.height }.sorted()
        val usual = heights.getOrNull(heights.size / 2) ?: 0
        lines.flatMap { l -> l.words.ifEmpty { listOf(l) } }.filter { w -> w.height >= usual * 0.75 }
    }.filter { w -> w.text.count(Char::isLetterOrDigit) >= 2 }

    /** The OCR gave no confidence at all (another engine, or an older reading): nothing to say. */
    fun known(pages: List<List<OcrLine>>) = words(pages).any { it.confidence < 1f }

    /** Share of words the OCR was unsure of. */
    fun unsureShare(pages: List<List<OcrLine>>): Double {
        val w = words(pages)
        if (w.isEmpty()) return 0.0
        return w.count { it.confidence < UNSURE }.toDouble() / w.size
    }

    /** Most likely handwritten, or a photo too poor to trust the letters: a quarter of the words or more unsure. */
    fun likely(pages: List<List<OcrLine>>) = known(pages) && unsureShare(pages) >= 0.25

    /**
     * Marks the product names with words the OCR was unsure of (they are looked at, and asked to the AI), and the
     * numbers it was unsure of on lines the arithmetic does not prove.
     */
    fun mark(doc: ParsedDocument, pages: List<List<OcrLine>>): ParsedDocument {
        if (!known(pages)) return doc
        val unsure = words(pages).filter { it.confidence < UNSURE }.map { norm(it.text) }.filter { it.isNotEmpty() }.toSet()
        if (unsure.isEmpty()) return doc.copy(handwritten = false)
        val items = doc.lineItems.map { it ->
            if (it.adjustment) return@map it
            val nameUnsure = it.originalDescription.split(' ').any { w -> w.count(Char::isLetter) >= 3 && norm(w) in unsure }
            val q = it.quantity; val p = it.unitPrice; val t = it.lineTotalCents
            val proven = q != null && p != null && t != null && LineDiscount.matches(q.value, p.value, it.discount?.value, t.value) &&
                ParseWarning.LINE_TOTAL_MISMATCH !in it.warnings
            fun <T> doubt(e: Extracted<T>?, printed: (T) -> String): Extracted<T>? =
                if (e != null && !proven && norm(printed(e.value)) in unsure) e.copy(confidence = Confidence.LOW) else e
            it.copy(
                nameDoubt = it.nameDoubt || nameUnsure,
                quantity = doubt(q) { v -> ItalianNumbers.toEditText(v) },
                unitPrice = doubt(p) { v -> ItalianNumbers.toEditText(v) },
                lineTotalCents = doubt(t) { v -> ItalianNumbers.centsToEditText(v) },
            )
        }
        return doc.copy(lineItems = items, handwritten = likely(pages))
    }

    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }
}
