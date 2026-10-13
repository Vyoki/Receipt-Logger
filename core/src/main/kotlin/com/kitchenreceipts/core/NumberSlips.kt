package com.kitchenreceipts.core

/**
 * A document number read with a letter among its digits ("34s67"): the camera misread a digit. The number is printed
 * on every page of a multi-page document (and twice on some): another place where it reads with digits only, the
 * same number otherwise, gives the digits. Still shown for a check (nothing proves which digit is right).
 */
object NumberSlips {

    /** A letter with digits on both sides. */
    private val SLIP = rx("\\d[A-Za-z]\\d")
    private val TOKEN = rx("[A-Za-z0-9][A-Za-z0-9/\\-]{3,}[A-Za-z0-9]")

    fun apply(doc: ParsedDocument, pages: List<String>): ParsedDocument {
        val n = doc.documentNumber ?: return doc
        if (!SLIP.containsMatchIn(n.value)) return doc
        val clean = pages.withIndex().flatMap { (p, text) -> TOKEN.findAll(text).map { p to it.value } }
            .firstOrNull { (_, t) -> !SLIP.containsMatchIn(t) && t.length == n.value.length && FieldLocator.like(t, n.value) }
            ?: return doc
        return doc.copy(documentNumber = Extracted(clean.second, Confidence.LOW, (n.source ?: "") + " (digits as read on page ${clean.first + 1})"))
    }
}
