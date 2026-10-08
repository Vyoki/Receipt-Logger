package com.kitchenreceipts.core

/**
 * Who issued the document, read the way a person reads it: by the labels the document prints. Electronic-invoice
 * printouts and many invoicing programs put the two parties in boxes side by side, each under its role:
 *
 *     Cedente/prestatore (fornitore)          Cessionario/committente (cliente)
 *     Identificativo fiscale: IT01234567897   Identificativo fiscale: IT09876543217
 *     Denominazione: ABC S.r.l.               Denominazione: RISTORANTE PROVA SAS
 *
 * Read as text, the two boxes run together line by line. Here each box is taken on its own (the lines under its
 * role label, in its own column), the supplier's box gives the supplier's name, and the box with the operator's
 * own VAT number is never the supplier.
 */
object Parties {

    private val SUPPLIER = rx("(?i)\\b(cedente|prestatore|fornitore|mittente|venditore|emittente)")
    private val CUSTOMER = rx("(?i)(cessionario|committente|\\bcliente\\b|destinatario|spett\\.?\\s?le|spettabile|intestatario|acquirente)")
    private val NAME_LABEL = rx("(?i)^\\s*(denominazione|ragione\\s+sociale|rag\\.?\\s?soc(iale)?\\.?|deno\\w*|ditta)\\s*:?\\s*")
    private val OTHER_LABEL = rx(
        "(?i)^\\s*(identificativo|ldentificativo|codice|cod\\.|c\\.?\\s?f\\.?\\b|partita|p\\.?\\s?iva|regime|indirizzo|via\\b|viale|piazza|comune|cap\\b|" +
            "provincia|nazione|tel|fax|e-?mail|pec|sede|iban|sito|web|www)",
    )
    /** Labels whose value always has digits (a tax code, a VAT number). */
    private val ID_LABEL = rx("(?i)^\\s*(identificativo|ldentificativo|codice|cod\\.|c\\.?\\s?f\\.?\\b|partita|p\\.?\\s?iva)")

    data class Supplier(val name: Extracted<String>?, val vatNumber: String?, val box: PageBox)

    /** The supplier's box on the first page that has one, or null when the document prints no roles. */
    fun supplier(pages: List<LayoutRows.Layout>, ownVat: String?): Supplier? {
        val own = ownVat?.filter(Char::isDigit)?.takeIf { it.length >= 8 }
        for (layout in pages.take(2)) {
            val rows = layout.rows.map { r -> r.filter { it.text.isNotBlank() }.sortedBy { it.left } }
            for ((r, row) in rows.withIndex()) {
                for ((ci, cell) in row.withIndex()) {
                    val t = cell.text
                    if (t.split(' ').size > 6 || !SUPPLIER.containsMatchIn(t)) continue
                    // The box: the column from this label to the next role label on its row.
                    val right = row.drop(ci + 1).firstOrNull { CUSTOMER.containsMatchIn(it.text) || SUPPLIER.containsMatchIn(it.text) }?.left ?: Int.MAX_VALUE
                    val lines = mutableListOf<OcrLine>()
                    var empty = 0
                    for (k in r + 1 until minOf(rows.size, r + 14)) {
                        val inBox = rows[k].filter { c -> c.centerX >= cell.left - 40 && c.centerX < right && c.left < right }
                        if (inBox.any { c -> (SUPPLIER.containsMatchIn(c.text) || CUSTOMER.containsMatchIn(c.text)) && c.text.split(' ').size <= 6 }) break
                        if (inBox.isEmpty()) { if (++empty >= 2) break else continue }
                        // A wide gap ends the box (the next part of the page starts under it).
                        val last = lines.lastOrNull() ?: cell
                        if (inBox.minOf { it.top } - last.bottom > 2 * maxOf(last.height, cell.height)) break
                        empty = 0
                        lines += inBox
                    }
                    if (lines.isEmpty()) continue
                    val digits = lines.joinToString(" ") { SellerProfiles.repairDigits(it.text.replace(rx("(?i)\\bIT(?=[0-9O])"), "IT ")) }
                    if (own != null && digits.filter(Char::isDigit).contains(own)) continue // this is the operator's own box
                    val vat = rx("\\d{11}").findAll(digits).map { it.value }.firstOrNull { SellerProfiles.isValidPartitaIva(it) && it != own }
                    // A party's box says who it is: a name label or a VAT number. "Trasporto a cura del: Mittente" is no box.
                    if (vat == null && lines.none { NAME_LABEL.containsMatchIn(it.text) }) continue
                    val box = PageBox(lines.minOf { it.left }, cell.top, lines.maxOf { it.right }, lines.maxOf { it.bottom })
                    return Supplier(name(lines), vat, box)
                }
            }
        }
        return null
    }

    /**
     * The name in the box: after "Denominazione" / "Ragione sociale", with the line under it when the name goes
     * on; otherwise the lines that are no label at all. A value under a tax-code label that has no digit is not a
     * tax code: two printed lines read as one, it belongs to the name.
     */
    private fun name(lines: List<OcrLine>): Extracted<String>? {
        val source = lines.joinToString(" / ") { it.text }
        val parts = mutableListOf<String>()
        var labelled = false
        var afterName = false
        for (l in lines) {
            val t = l.text.trim()
            val nameLabel = NAME_LABEL.find(t)
            when {
                nameLabel != null -> {
                    labelled = true
                    afterName = true
                    t.substring(nameLabel.range.last + 1).trim().takeIf { it.count(Char::isLetter) >= 2 }?.let { parts += it }
                }
                ID_LABEL.containsMatchIn(t) -> {
                    val value = t.substringAfter(':', "").trim()
                    if (value.isNotEmpty() && value.none(Char::isDigit) && value.split(' ').count { w -> w.count(Char::isLetter) >= 2 } >= 2) parts += value
                    afterName = false
                }
                OTHER_LABEL.containsMatchIn(t) -> afterName = false
                // A line of only an id (a tax code alone): not part of the name.
                t.count(Char::isDigit) >= 5 -> afterName = false
                // The line under the name label continues the name; without a name label, the lines that are no label.
                t.count(Char::isLetter) >= 3 && (afterName || !labelled) -> parts += t
            }
        }
        val text = ReceiptParser.cleanSeller(parts.joinToString(" ").replace(rx("\\s+"), " "))
        if (text.count(Char::isLetter) < 3) return null
        // Sure only when a name label was read cleanly and nothing else had to be pieced together.
        val sure = labelled && parts.size == 1 && ReceiptParser.COMPANY_SUFFIX.containsMatchIn(text)
        return Extracted(text, if (sure) Confidence.HIGH else Confidence.LOW, source)
    }

    /**
     * The supplier from its box replaces a seller read elsewhere (the letterhead logic can take the customer's box,
     * printed next to it, or a role label); a seller whose name is in the supplier's box is kept as read.
     */
    fun apply(doc: ParsedDocument, s: Supplier?): ParsedDocument {
        val name = s?.name ?: return doc
        val boxed = name.copy(source = "supplier box: " + name.source)
        val cur = doc.sellerName ?: return doc.copy(sellerName = boxed)
        // The same company as the name in the box: the reading stands (it may be the cleaner spelling).
        val nameWords = words(name.value)
        val curWords = words(cur.value)
        val same = curWords.isNotEmpty() && curWords.count { it in nameWords } * 10 >= curWords.size * 6
        val curIsLabel = SUPPLIER.containsMatchIn(cur.value) || CUSTOMER.containsMatchIn(cur.value) || NAME_LABEL.containsMatchIn(cur.value) ||
            cur.value.contains(':')
        if (same && !curIsLabel) return doc
        return doc.copy(sellerName = boxed)
    }

    private fun words(s: String) = s.uppercase().split(rx("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
}
