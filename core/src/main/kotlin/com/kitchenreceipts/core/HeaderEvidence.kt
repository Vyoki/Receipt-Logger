package com.kitchenreceipts.core

/**
 * Supplier and document number, chosen by weighing evidence instead of taking the first line that matches a pattern.
 *
 * Every plausible candidate on the first pages is collected and scored with facts that hold for Italian supplier
 * documents in general: a supplier is a company (legal form), heads the block that ends with its VAT number, is not
 * in the customer's box (the box with the operator's own VAT number, or under "Spett.le / Destinatario"), and is
 * printed again on every page; a document number sits next to a "numero / n." label or under it, is repeated on
 * every page, and is not a postcode, a phone, a VAT number, an amount or a date. The best candidate wins; it is
 * certain only when the evidence is strong and no other candidate comes close (otherwise it is shown for checking).
 * Each candidate keeps the reasons for its score, for troubleshooting.
 */
object HeaderEvidence {

    data class Candidate(val value: String, val score: Int, val source: String, val reasons: List<String>)

    private val DOC_WORDS = Regex(
        "(?i)\\b(fattura|documento|ddt|d\\.d\\.t|scontrino|ricevuta|totale|pagamento|partita|codice|fiscale|pag\\.|data|" +
            "numero|lotto|descrizione|quantit\\S*|prezzo|importo|imponibile|aliquota|banca|iban|abi|cab|cliente|fornitore|" +
            "destinazione|destinatario|spett\\S*|vettore|trasporto|causale|ordine|agente|riferimento|merce)\\b",
    )
    private val LEGAL_NOTICE = Regex("(?i)\\b(soggett\\w*|direzione|coordinamento|unipersonale|capitale|sede\\s+legale|iscr\\w*|reg\\.?\\s*imp\\w*|rea\\b|cap\\.?\\s*soc)")
    private val OWN_LINE_VAT = Regex("\\d{11}")

    // ------------------------------------------------------------------ supplier

    fun seller(pages: List<List<String>>, options: ParseOptions): Extracted<String>? {
        val ownVat = options.ownVatNumber?.filter(Char::isDigit)?.takeIf { it.length >= 8 }
        val ownName = DuplicateDetector.normalizeSeller(options.ownBusinessName)?.takeIf { it.length >= 4 }
        val supplierVat = SellerProfiles.supplierVatNumber(pages.joinToString("\n") { it.joinToString("\n") }, ownVat)
        val all = mutableListOf<Candidate>()
        pages.take(4).forEachIndexed { p, page ->
            val lines = page.take(30).map { SellerProfiles.repairDigits(it) }
            val vatLine = supplierVat?.let { v -> lines.indexOfFirst { it.replace(" ", "").contains(v) } } ?: -1
            // The customer's box: lines starting with a customer label and the few after it, and the lines just above the
            // operator's own VAT number (with no other VAT number in between).
            val customer = BooleanArray(lines.size)
            lines.forEachIndexed { i, l ->
                val label = ReceiptParser.CUSTOMER_LABEL.find(l)
                if (label != null && label.range.first < 4) for (k in i..minOf(i + 4, lines.lastIndex)) customer[k] = true
                if (ownVat != null && l.filter(Char::isDigit).contains(ownVat)) {
                    for (k in maxOf(0, i - 5)..i) {
                        val between = (k + 1 until i).any { j -> OWN_LINE_VAT.findAll(lines[j]).any { m -> m.value != ownVat && SellerProfiles.isValidPartitaIva(m.value) } }
                        if (!between) customer[k] = true
                    }
                }
            }
            lines.forEachIndexed { i, raw ->
                for ((text, sideBySide) in segments(raw)) {
                    // A name wrapped before its legal form ("POPULAR BOOK" / "CO. (M) SDN BHD"): the two lines are one name.
                    val prev = lines.getOrNull(i - 1)
                    val joined = if (prev != null && WRAPPED_FORM.containsMatchIn(text) && !customer[i - 1] && prev.none(Char::isDigit) &&
                        prev.count(Char::isLetter) >= 4 && prev.split(' ').count { it.isNotBlank() } <= 5 &&
                        !ReceiptParser.ADDRESS_OR_CONTACT.containsMatchIn(prev) && ReceiptParser.COMPANY_SUFFIX.find(prev) == null
                    ) "$prev $text" else text
                    candidate(joined, raw, i, p, vatLine, customer[i] && !sideBySide, ownVat, ownName)?.let { all += it }
                }
            }
        }
        if (all.isEmpty()) return null
        // The same name on several pages: more evidence for it.
        val byName = all.groupBy { DuplicateDetector.normalizeSeller(it.value) ?: it.value }
        val merged = byName.values.map { group ->
            var best = group.maxBy { it.score }
            val pagesSeen = group.map { it.source.substringBefore(':') }.distinct().size
            if (pagesSeen >= 2) best = best.copy(score = best.score + 2, reasons = best.reasons + "on $pagesSeen pages")
            // The same name printed in the customer's box elsewhere ("LUOGO DI DESTINAZIONE ...") is the customer.
            if (group.any { "customer box" in it.reasons }) best = best.copy(score = best.score - 6, reasons = best.reasons + "also in the customer box")
            best
        }.sortedByDescending { it.score }
        val best = merged.first()
        if (best.score < 3) return null
        val second = merged.getOrNull(1)?.score ?: 0
        val strong = best.reasons.any { it == "legal form" || it == "heads the VAT block" }
        val complete = !ReceiptParser.TRUNCATED_SUFFIX.containsMatchIn(best.value)
        // Garbled reading ("?. o Bere AG", "WR. D.I.Y. (4)"): stray symbols or digits in the name are never sure.
        val clean = best.value.split(' ').filter { it.isNotBlank() }.all { (rx("^[\\p{L}&.,'’()\\-/+]+$").matches(it) || it.all(Char::isDigit)) }
        val sure = strong && complete && clean && best.score >= 7 && best.score - second >= 3
        return Extracted(best.value, if (sure) Confidence.HIGH else Confidence.LOW, best.source)
    }

    /** A line that is only the end of a company name: "CO. (M) SDN BHD", "& SONS LTD", "(M) BHD". */
    private val WRAPPED_FORM = Regex("(?i)^\\W*(co\\.?|&|and|\\(m\\)|company)\\s")

    /** A row may hold the supplier on the left and the customer box on the right ("ABC S.r.l.   Spett.le ..."). */
    private fun segments(line: String): List<Pair<String, Boolean>> {
        val label = ReceiptParser.CUSTOMER_LABEL.find(line)
        if (label != null && label.range.first >= 4) return listOf(line.substring(0, label.range.first).trim() to true)
        return listOf(line to false)
    }

    private fun candidate(
        text: String, raw: String, index: Int, page: Int, vatLine: Int, inCustomerBox: Boolean, ownVat: String?, ownName: String?,
    ): Candidate? {
        val letters = text.count { it.isLetter() }
        if (letters < 3) return null
        val reasons = mutableListOf<String>()
        var score = 0
        var value = text
        val suffix = ReceiptParser.COMPANY_SUFFIX.find(text)
        val truncated = ReceiptParser.TRUNCATED_SUFFIX.find(text)
        when {
            suffix != null && text.substring(0, suffix.range.first).count { it.isLetter() } >= 2 -> {
                value = text.substring(0, suffix.range.last + 1); score += 6; reasons += "legal form"
            }
            truncated != null -> { score += 4; reasons += "legal form cut short" }
        }
        // The name without its legal form ("S.r.l." is not a document word).
        val name = if (suffix != null && value.length > suffix.range.first) value.substring(0, suffix.range.first) else value
        if (vatLine >= 0 && index < vatLine && vatLine - index <= 8 && !inCustomerBox) { score += 4; reasons += "heads the VAT block" }
        if (index <= 2) { score += 2; reasons += "top of the page" }
        if (inCustomerBox) { score -= 8; reasons += "customer box" }
        if (ownVat != null && raw.filter(Char::isDigit).contains(ownVat)) { score -= 10; reasons += "own VAT number" }
        if (ownName != null && (DuplicateDetector.normalizeSeller(value) ?: "").contains(ownName)) { score -= 10; reasons += "own name" }
        if (DOC_WORDS.containsMatchIn(name)) { score -= 5; reasons += "document words" }
        if (LEGAL_NOTICE.containsMatchIn(value)) { score -= 5; reasons += "legal notice" }
        if (ReceiptParser.ADDRESS_OR_CONTACT.containsMatchIn(value) || rx("\\b\\d{5}\\b").containsMatchIn(value)) { score -= 5; reasons += "address or contact" }
        if (letters < value.count { !it.isWhitespace() } * 0.6) { score -= 4; reasons += "mostly digits" }
        if (ItalianDates.findDates(value).isNotEmpty() || ReceiptParser.lastAmountCents(value) != null) { score -= 4; reasons += "date or amount" }
        if (value.split(' ').count { it.isNotBlank() } > 7) { score -= 3; reasons += "long sentence" }
        if (score < 0 && "customer box" !in reasons && "own VAT number" !in reasons) return null
        return Candidate(ReceiptParser.cleanSeller(value), score, "page ${page + 1}: $raw", reasons)
    }

    // ------------------------------------------------------------------ document number

    private val LABEL = Regex(
        "(?i)(?:\\b(?:fattura|ft|documento(?:\\s+di\\s+trasporto)?|ddt|d\\.d\\.t|doc|bolla|ricevuta|scontrino|nota\\s+di\\s+credito)\\.?\\s*" +
            "(?:n(?:r|um|ro)?\\.?|n°|nº|numero|#)|\\bn(?:r|ro)?\\.?\\s*(?:doc(?:umento)?\\.?)|\\bnumero\\b|\\bn°|\\bnº|\\bnum\\.|" +
            // "Invoice No.", "Invoice #", "Receipt No", "Bill No", "Facture n°", "N° de facture", "Rechnungsnummer", "Factuurnummer", "Factura nº"
            "\\b(?:tax\\s+)?(?:invoice|receipt|bill|document|order)\\s*(?:no|nr|number|num|#)\\.?|\\binv\\.?\\s*no\\.?|\\bfacture\\s*(?:n[°º]|no|num[ée]ro)|" +
            "\\bn[°º]\\s*de\\s+facture|\\bnum[ée]ro\\s+de\\s+facture|\\brechnungs(?:nummer|nr)\\.?|\\brechnung\\s*nr\\.?|\\bfactuur(?:nummer|nr)\\.?|" +
            "\\bfactura\\s*(?:n[°º]|no|nr)\\.?|\\bn[úu]mero\\s+de\\s+factura|\\bnr\\s+faktury)\\s*[:.]?",
    )
    private val DOC_WORD = Regex("(?i)^\\s*(?:copia\\s+)?(?:fattura(?:\\s+(?:immediata|differita|accompagnatoria))?|ddt|d\\.d\\.t\\.?|bolla|ricevuta|(?:tax\\s+)?invoice|facture|rechnung|factuur|factura|faktura)\\s+")
    private val BARE_N = Regex("(?i)(?<![A-Za-z.])n\\s?[.°º]\\s*(?=[A-Za-z0-9]*\\d)")
    private val DOC_WORD_ANY = Regex("(?i)\\b(fattura|ddt|d\\.d\\.t|documento|bolla|ricevuta|scontrino|nota\\s+di\\s+credito|invoice|receipt|facture|rechnung|factuur|factura|faktura)\\b")
    private val PAGE = Regex("^\\d{1,2}/\\d{1,2}$")
    /** Words that come before a number but are not part of it ("DDT 4855", "N 1962"). */
    private val NOT_PREFIX = setOf("N", "NR", "NO", "NUM", "DDT", "DOC", "DEL", "DATA", "ORD", "RIF", "PAG", "COD", "TEL", "FAX", "CAP", "IVA")

    /** A number token: a digit in it, not an amount, not a page "1/5", not a date. */
    private fun numberToken(t: String): Boolean =
        t.any(Char::isDigit) && t.length in 1..20 && !ReceiptParser.DECIMAL_AMOUNT.containsMatchIn(t) && !PAGE.matches(t) &&
            ItalianDates.findDates(t).isEmpty()

    /**
     * The value starting at token [i]: "B26 204177" and "FT 12345" are one number printed in two parts (a short
     * prefix with letters, then digits); "2026/0456", "12A/34567" are one token.
     */
    private fun valueAt(tokens: List<String>, i: Int): String? {
        val a = tokens.getOrNull(i)?.trim(',', ';', ':', '|', '.') ?: return null
        val b = tokens.getOrNull(i + 1)?.trim(',', ';', ':', '|')
        val prefix = a.length <= 4 && a.any(Char::isLetter) && a.all(Char::isLetterOrDigit) && a.uppercase() !in NOT_PREFIX
        if (prefix && b != null && b.length >= 3 && b.all(Char::isDigit) && ItalianDates.findDates(b).isEmpty()) return "$a $b"
        // "O47 140501" whose letter O the OCR cleanup made a zero: a short part starting with 0 is not a number on its own.
        if (a.length in 2..3 && a.startsWith('0') && a.all(Char::isDigit) && b != null && b.length >= 5 && b.all(Char::isDigit)) return "$a $b"
        return a.takeIf { numberToken(it) }
    }

    fun number(pages: List<List<String>>, layout: SupplierLayout?): Extracted<String>? {
        val merged = numberCandidates(pages, layout)
        if (merged.isEmpty()) return null
        val best = merged.first()
        if (best.score < 3) return null
        val second = merged.getOrNull(1)?.score ?: 0
        val sure = best.score >= 7 && best.score - second >= 3
        return Extracted(best.value, if (sure) Confidence.HIGH else Confidence.LOW, best.source.substringAfter(": "))
    }

    /** Every candidate for the document number with its score and reasons, best first (for troubleshooting too). */
    fun numberCandidates(pages: List<List<String>>, layout: SupplierLayout?): List<Candidate> {
        val all = mutableListOf<Candidate>()
        pages.take(6).forEachIndexed { p, page ->
            val lines = page.take(40)
            lines.forEachIndexed { i, line ->
                val toks = line.split(' ').filter { it.isNotBlank() }
                // 1. Right after a label: "Fattura n. 123", "N. documento: B26 204177", "Numero: 2026/0456".
                for (m in LABEL.findAll(line)) {
                    val after = line.substring(m.range.last + 1).trimStart()
                    val t = after.split(' ').filter { it.isNotBlank() }
                    val v = valueAt(t, 0) ?: continue
                    add(all, v, 7, "after the label '${m.value.trim()}'", p, line)
                }
                // 1b. A bare "N." before a value ("FATTURA IMMEDIATA N. S41 953171"); stronger after a document word,
                // never on an address line ("VIA ROMA N. 1").
                // Not on a registration line either ("REA n. 123456/RM", "Iscr. Albo n. ...").
                if (!ReceiptParser.ADDRESS_OR_CONTACT.containsMatchIn(line) && !LEGAL_NOTICE.containsMatchIn(line) &&
                    !rx("(?i)\\b(rea|albo|registro|cciaa|autorizz\\w*|licenza)\\b").containsMatchIn(line) && LABEL.find(line) == null
                ) {
                    for (m in BARE_N.findAll(line)) {
                        val v = valueAt(line.substring(m.range.last + 1).trimStart().split(' ').filter { it.isNotBlank() }, 0) ?: continue
                        val afterDocWord = DOC_WORD_ANY.containsMatchIn(line.substring(0, m.range.first))
                        add(all, v, if (afterDocWord) 7 else 4, "after 'N.'" + if (afterDocWord) " following a document word" else "", p, line)
                    }
                }
                // 2. "FATTURA 2026/0311 18/03/2025": a document word followed by a value.
                DOC_WORD.find(line)?.let { m ->
                    val t = line.substring(m.range.last + 1).split(' ').filter { it.isNotBlank() }
                    valueAt(t, 0)?.let { v -> if (t.firstOrNull()?.let { LABEL.matches(it) } != true) add(all, v, 3, "after '${m.value.trim()}'", p, line) }
                }
                // 3. A row of headings with "NUMERO"/"N.RO" and no values, the values on a row below.
                val labelOnly = line.replace(rx("\\b\\d{11}\\b"), " ")
                if (ReceiptParser.NUMBER_LABEL.containsMatchIn(labelOnly) && labelOnly.none(Char::isDigit)) {
                    val below = (i + 1..minOf(i + 3, lines.lastIndex)).map { lines[it] }
                    val row = below.firstOrNull { ItalianDates.findDates(it).isNotEmpty() } ?: below.firstOrNull()
                    if (row != null) {
                        val rt = row.split(' ').filter { it.isNotBlank() }
                        val dateIdx = rt.indexOfFirst { ItalianDates.findDates(it).isNotEmpty() }
                        val withDate = rx("(?i)\\bdata\\b").containsMatchIn(labelOnly)
                        // The number is printed just before the date ("... B26 204177 22/09/2026"), else the first number token.
                        val v = if (dateIdx > 0) {
                            (maxOf(0, dateIdx - 2) until dateIdx).firstNotNullOfOrNull { k -> valueAt(rt, k)?.takeIf { k + it.split(' ').size == dateIdx } }
                        } else rt.indices.firstNotNullOfOrNull { k -> valueAt(rt, k)?.takeIf { v -> v.length >= 2 } }
                        if (v != null) add(all, v, if (dateIdx > 0 && withDate) 7 else 4, "under the heading '${line.trim().take(40)}'", p, "$line / $row")
                    }
                }
                // 4. A value printed just before a date, on a row under headings that name the date ("DATA"): the number
                // heading may be too small to read ("B26 305511 15/09/2026" under "DATA PAG."). Never sure on its own.
                if (i > 0 && !ReceiptParser.ADDRESS_OR_CONTACT.containsMatchIn(line) && !LEGAL_NOTICE.containsMatchIn(line)) {
                    val dateIdx = toks.indexOfFirst { ItalianDates.findDates(it).isNotEmpty() }
                    if (dateIdx > 0) {
                        val v = (maxOf(0, dateIdx - 2) until dateIdx).firstNotNullOfOrNull { k -> valueAt(toks, k)?.takeIf { k + it.split(' ').size == dateIdx } }
                        val dateHeading = (maxOf(0, i - 2) until i).any { rx("(?i)\\bdata\\b").containsMatchIn(lines[it]) }
                        if (v != null && v.count(Char::isDigit) >= 4) add(all, v, if (dateHeading) 6 else 3, "just before the date", p, line)
                    }
                }
            }
        }
        if (all.isEmpty()) return emptyList()
        fun key(v: String) = v.uppercase().filter(Char::isLetterOrDigit)
        // Each value counted once per page, its best evidence; the same value on several pages is stronger.
        val merged = all.groupBy { key(it.value) }.values.map { g ->
            val best = g.maxBy { it.score }
            val pagesSeen = g.map { it.source.substringBefore(':') }.distinct().size
            var s = best.score + (pagesSeen - 1).coerceAtMost(3) * 3
            val reasons = best.reasons.toMutableList()
            if (pagesSeen > 1) reasons += "on $pagesSeen pages"
            if (layout?.numberShape != null && !layout.numberShape.all { it == '9' } && SupplierLayouts.matchesShape(best.value, layout.numberShape)) {
                s += 4; reasons += "this supplier's number format"
            }
            if (best.value.filter(Char::isDigit).length <= 1) { s -= 3; reasons += "one digit" }
            best.copy(score = s, reasons = reasons)
        }.sortedByDescending { it.score }
        return merged
    }

    /** Order and customer references printed with "N°" too: "N° BC# BC03984", "Order No", "Ordernummer", "Customer No". */
    private val OTHER_REFERENCE = Regex("(?i)(\\bbc\\s*#|\\bbc\\d|\\border\\w*|\\bcommande|\\bbestell\\w*|\\bpedido|\\bcustomer\\s*(?:no|nr|#)|\\bclient\\s*(?:no|n°)|\\bkunden\\w*|\\bklant\\w*|\\bpo\\s*(?:no|#|number))")

    private fun add(all: MutableList<Candidate>, value: String, score: Int, why: String, page: Int, line: String) {
        if (!ReceiptParser.plausibleDocNumber(value)) return
        if (OTHER_REFERENCE.containsMatchIn(value)) return
        all += Candidate(value, score, "page ${page + 1}: $line", listOf(why))
    }
}
