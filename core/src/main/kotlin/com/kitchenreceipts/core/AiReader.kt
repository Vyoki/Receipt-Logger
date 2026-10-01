package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer

/**
 * Everything about the on-phone AI reader that is not the model itself: what it is asked, the shape its
 * answer must have, and — most important — how its answer is checked before anything reaches the operator.
 *
 * The AI (a vision-language model running on the phone) looks at the photo *and* at the text the regular
 * OCR read, and returns the document as JSON. It is good at structure (which number is in which column,
 * where an item starts and ends) and the OCR is good at exact characters, so the two are cross-checked:
 * - every amount, quantity and price must either appear in the OCR text or be proven by the arithmetic
 *   (quantity x price = amount); otherwise it is shown as uncertain;
 * - a lot number is kept only if it is really printed, and never if it is a date;
 * - the AI's reading replaces the regular one only where it explains the document better
 *   (more lines that add up, lines that add up to the total).
 * The AI never gets the last word on its own: the same checks decide.
 */
object AiReader {

    /** Changes when the prompt or grammar change, so logged results can be compared. */
    const val PROMPT_VERSION = 5

    private const val MAX_OCR_CHARS = 5000

    /**
     * Language the instructions are written in. The documents are Italian whatever the app language is; which
     * instruction language reads them better is measured per model in CI (job ai-model-check).
     */
    enum class Lang { EN, IT }

    /** Used when nothing else is said: the language that read best in CI (see README, "AI instruction language"). */
    @Volatile var defaultLang: Lang = Lang.EN

    /** The instruction given with the photo. [ocrText] is what the regular OCR read on the same page. */
    fun instruction(ocrText: String, lang: Lang = defaultLang): String = buildString {
        if (lang == Lang.IT) {
            append("La foto mostra un documento di un fornitore italiano per un ristorante (fattura, DDT o scontrino). ")
            append("Leggilo e rispondi in JSON. Copia i valori esattamente come sono stampati: non calcolare, non arrotondare, ")
            append("non tradurre e non indovinare. Usa null per ciò che non è stampato.\n")
            append("- seller: l'azienda che ha emesso il documento (intestazione in alto). Mai il cliente indicato dopo ")
            append("'Spett.le', 'Destinatario', 'Cliente' o 'Luogo di destinazione'.\n")
            append("- seller_vat: la Partita IVA del fornitore.\n")
            append("- number: il numero del documento. date: la data del documento (non di consegna, pagamento o scadenza).\n")
            append("- subtotal: imponibile. vat: IVA totale. total: totale del documento.\n")
            append("- items: una voce per ogni riga di prodotto, dall'alto in basso. Salta intestazioni e totali. Righe come ")
            append("'Merce non deperibile - Congelato', 'Merce non deperibile - Fresco' o 'Merce non alimentare' sono titoli di ")
            append("sezione, mai prodotti: il prodotto è la riga con il codice articolo. Per ciascuna: code (codice articolo), ")
            append("colli (colonna COLLI, es. '5' o '1x6'), description (solo il nome del prodotto, senza codice, colli, quantità ")
            append("o prezzi), unit (U.M.), quantity (colonna QUANTITA'/QTA/TOT.), price (prezzo unitario), discount (SC.%), ")
            append("amount (IMPORTO della riga), vat_rate (% IVA o codice IVA), lot (solo se stampato come lotto; mai una data).\n")
            append("Scrivi i numeri con la virgola, esattamente come stampati, es. \"1.234,50\" o \"2,384\".\n")
        } else {
            append("This photo shows an Italian supplier document for a restaurant (fattura, DDT or scontrino). ")
            append("Read it and return JSON. Copy values exactly as printed: do not calculate, round, translate or guess. ")
            append("Use null for anything that is not printed.\n")
            append("- seller: the company that issued the document (letterhead at the top). Never the customer shown after ")
            append("'Spett.le', 'Destinatario', 'Cliente' or 'Luogo di destinazione'.\n")
            append("- seller_vat: the seller's Partita IVA.\n")
            append("- number: the document number. date: the document date (not delivery, payment or expiry dates).\n")
            append("- subtotal: taxable amount (imponibile). vat: total VAT (IVA). total: total of the document.\n")
            append("- items: one entry per product line, top to bottom. Skip headings and totals. Lines such as 'Merce non deperibile - ")
            append("Congelato', 'Merce non deperibile - Fresco' or 'Merce non alimentare' are section titles, never products: the product ")
            append("is the line with the article code (e.g. 'CARTA FORNO ...' under 'Merce non alimentare'). For each: ")
            append("code (article code), colli (the COLLI column: packages or cartons, e.g. '5' or '1x6'), ")
            append("description (the product name only, without code, colli, quantity or prices), unit (U.M.), ")
            append("quantity (QUANTITA'/QTA/TOT. column), price (unit price, PREZZO), discount (SCONTO %), ")
            append("amount (line total, IMPORTO), vat_rate (% IVA or IVA code), lot (only when printed as lot/lotto; never a date).\n")
            append("Write numbers with the Italian comma exactly as printed, e.g. \"1.234,50\" or \"2,384\".\n")
        }
        val ocr = ocrText.trim()
        if (ocr.isNotEmpty()) {
            if (lang == Lang.IT) {
                append("\nTesto letto dall'OCR sulla stessa pagina (può contenere caratteri sbagliati e righe divise o unite; ")
                append("usalo per controllare le cifre, ma per sapere quale valore sta in quale colonna fidati della foto):\n<<<\n")
            } else {
                append("\nText read from the same page by OCR (it may contain misread characters and rows split or merged; ")
                append("use it to check digits, but trust the photo for which value is in which column):\n<<<\n")
            }
            append(if (ocr.length > MAX_OCR_CHARS) ocr.take(MAX_OCR_CHARS) + "\n…" else ocr)
            append("\n>>>\n")
        }
    }

    private val HEADER_KEYS = listOf("seller", "seller_vat", "number", "date", "subtotal", "vat", "total")
    private val ITEM_KEYS = listOf("code", "colli", "description", "unit", "quantity", "price", "discount", "amount", "vat_rate", "lot")

    /**
     * GBNF grammar the model's output is constrained to: always valid JSON with exactly these keys, every value a
     * short string or null. The model cannot ramble, add comments or produce half a JSON document.
     */
    val GRAMMAR: String = buildString {
        // A space or a line break is allowed between fields: the models write JSON that way. (Forcing fully compact
        // JSON was tried in CI: the models then answered only nulls.)
        fun fields(keys: List<String>) = keys.joinToString(" \",\" ws ") { "\"\\\"$it\\\":\" ws value" }
        append("root ::= \"{\" ws ").append(fields(HEADER_KEYS))
        append(" \",\" ws \"\\\"items\\\":\" ws \"[\" ws (item (\",\" ws item)*)? ws \"]\" ws \"}\"\n")
        append("item ::= \"{\" ws ").append(fields(ITEM_KEYS)).append(" ws \"}\"\n")
        append("value ::= \"null\" | \"\\\"\" char{0,100} \"\\\"\"\n")
        append("char ::= [^\"\\\\\\x00-\\x1F] | \"\\\\\" [\"\\\\/nt]\n")
        append("ws ::= [ \\n]?\n")
    }

    // ------------------------------------------------------------------ small questions (see AiTargets)

    private fun grammarFor(keys: List<String>): String = buildString {
        append("root ::= \"{\" ws ").append(keys.joinToString(" \",\" ws ") { "\"\\\"$it\\\":\" ws value" }).append(" ws \"}\"\n")
        append("value ::= \"null\" | \"\\\"\" char{0,100} \"\\\"\"\n")
        append("char ::= [^\"\\\\\\x00-\\x1F] | \"\\\\\" [\"\\\\/nt]\n")
        append("ws ::= [ \\n]?\n")
    }

    /** One product line: the answer is a single item. */
    val ROW_GRAMMAR: String = grammarFor(ITEM_KEYS)
    val HEADER_GRAMMAR: String = grammarFor(listOf("seller", "seller_vat", "number", "date"))
    val TOTALS_GRAMMAR: String = grammarFor(listOf("subtotal", "vat", "total"))

    /**
     * One product line. [examples] are lines of the same supplier the operator confirmed before ("row text" to the
     * right answer): the model follows the pattern, which is how it learns a supplier's layout without retraining.
     */
    fun rowInstruction(headerText: String, rowText: String, lang: Lang = defaultLang, examples: List<RowExample> = emptyList()): String = buildString {
        if (lang == Lang.IT) {
            append("L'immagine mostra due strisce di un documento di un fornitore italiano (fattura o DDT): sopra le intestazioni ")
            append("delle colonne della tabella prodotti, sotto una riga di prodotto (nome o lotto possono continuare su una seconda riga). ")
            append("Copia quella riga leggendo ogni valore nella colonna sotto la sua intestazione. Copia i valori esattamente come ")
            append("stampati, con la virgola; non calcolare e non indovinare; null per una colonna vuota.\n")
            append("code = codice articolo, colli = colonna COLLI, description = solo il nome del prodotto, unit = U.M., ")
            append("quantity = QUANTITA'/QTA/TOT., price = prezzo unitario (PREZZO), discount = SC.%, amount = IMPORTO della riga, ")
            append("vat_rate = % IVA, lot = numero di lotto se stampato.\n")
            append("L'OCR ha letto le intestazioni come: ").append(headerText.take(300)).append('\n')
            append("e la riga come: ").append(rowText.take(400)).append(" (può essere letta male o mescolata con una riga vicina)\n")
            if (examples.isNotEmpty()) append("Righe già confermate dello stesso fornitore, con la risposta giusta:\n")
        } else {
            append("The picture shows two strips of an Italian supplier document (fattura or DDT): on top the column headings of ")
            append("the product table, below them one product line (its name or lot may continue on a second line). ")
            append("Copy that product line, reading each value in the column under its heading. Copy values exactly as printed, ")
            append("with the Italian comma; do not calculate or guess; null for an empty column.\n")
            append("code = article code, colli = COLLI column, description = product name only, unit = U.M., quantity = QUANTITA'/QTA/TOT., ")
            append("price = unit price (PREZZO), discount = SC.%, amount = line total (IMPORTO), vat_rate = % IVA, lot = lot number if printed.\n")
            append("The regular OCR read the headings as: ").append(headerText.take(300)).append('\n')
            append("and the line as: ").append(rowText.take(400)).append(" (it may be misread or mixed with a neighbouring line)\n")
            if (examples.isNotEmpty()) append("Lines of the same supplier already confirmed, with the right answer:\n")
        }
        examples.take(MAX_EXAMPLES).forEach { e -> append("- ").append(e.rowText.take(200)).append(" => ").append(e.answerJson()).append('\n') }
    }

    /** A line of a supplier's earlier document, as the OCR read it, and what the operator confirmed it to be. */
    data class RowExample(
        val rowText: String, val code: String?, val colli: String?, val description: String, val unit: String?,
        val quantity: String?, val price: String?, val amount: String?, val vatRate: String?,
    ) {
        fun answerJson(): String {
            fun v(x: String?) = if (x == null) "null" else "\"" + x.replace("\\", "").replace("\"", "") + "\""
            return "{\"code\":${v(code)},\"colli\":${v(colli)},\"description\":${v(description)},\"unit\":${v(unit)}," +
                "\"quantity\":${v(quantity)},\"price\":${v(price)},\"amount\":${v(amount)},\"vat_rate\":${v(vatRate)}}"
        }
    }

    private const val MAX_EXAMPLES = 3

    fun headerInstruction(lang: Lang = defaultLang): String = if (lang == Lang.IT) {
        "L'immagine mostra la parte alta di un documento di un fornitore italiano (fattura, DDT o scontrino). Restituisci: " +
            "seller = l'azienda che lo ha emesso (intestazione), mai il cliente dopo 'Spett.le'/'Destinatario'; seller_vat = la sua " +
            "Partita IVA; number = numero del documento; date = data del documento (non di consegna o pagamento). Copia esattamente " +
            "come stampato; null se assente.\n"
    } else {
        "The picture shows the top of an Italian supplier document (fattura, DDT or scontrino). Return: seller = the company " +
            "that issued it (letterhead), never the customer after 'Spett.le'/'Destinatario'; seller_vat = its Partita IVA; " +
            "number = document number; date = document date (not delivery or payment dates). Copy exactly as printed; null if absent.\n"
    }

    fun totalsInstruction(lang: Lang = defaultLang): String = if (lang == Lang.IT) {
        "L'immagine mostra la parte bassa di un documento di un fornitore italiano. Restituisci: subtotal = imponibile " +
            "(colonna TOTALI del riepilogo IVA), vat = IVA totale (importo IVA), total = totale del documento (totale documento / " +
            "da pagare). Copia esattamente come stampato, con la virgola; null se assente.\n"
    } else {
        "The picture shows the bottom part of an Italian supplier document. Return: subtotal = taxable amount (imponibile, " +
            "TOTALI column of the VAT summary), vat = total VAT (importo IVA), total = total of the document (totale documento / " +
            "da pagare). Copy exactly as printed with the Italian comma; null if absent.\n"
    }

    // ------------------------------------------------------------------ one number (a quantity the OCR missed)

    /** The answer is one number as printed ("1", "2,50") or X when the column is empty. */
    val NUMBER_GRAMMAR: String = "root ::= [0-9]{1,5} ([,.] [0-9]{1,3})? | \"X\"\n"

    fun numberInstruction(column: String, headerText: String, rowText: String, lang: Lang = defaultLang): String = if (lang == Lang.IT) {
        "L'immagine mostra le intestazioni delle colonne di un documento di un fornitore italiano e, sotto, una riga di prodotto. " +
            "Scrivi solo il numero stampato in quella riga nella colonna \"$column\" (X se la colonna è vuota). Non calcolare.\n" +
            "Intestazioni (OCR): ${headerText.take(300)}\nRiga (OCR): ${rowText.take(400)}\n"
    } else {
        "The picture shows the column headings of an Italian supplier document and, below them, one product line. " +
            "Write only the number printed on that line in the column \"$column\" (X if the column is empty). Do not calculate.\n" +
            "Headings (OCR): ${headerText.take(300)}\nLine (OCR): ${rowText.take(400)}\n"
    }

    // ------------------------------------------------------------------ multiple choice (a line that adds up two ways)

    /** The answer is one letter: which reading is printed on the line, or X when none is. */
    val CHOICE_GRAMMAR: String = "root ::= [A-DX]\n"

    private val LETTERS = listOf("A", "B", "C", "D")

    /** Asks which of [choices] (all adding up) is what the line shows: one letter to write instead of a whole line. */
    fun choiceInstruction(headerText: String, rowText: String, choices: List<LineChoice>, lang: Lang = defaultLang): String = buildString {
        fun n(v: BigDecimal) = ItalianNumbers.formatDecimal(v, maxScale = 4)
        if (lang == Lang.IT) {
            append("L'immagine mostra le intestazioni delle colonne di un documento di un fornitore italiano e, sotto, una riga ")
            append("di prodotto. I numeri della riga tornano in più di un modo. Guarda sotto quale intestazione sta ciascun numero ")
            append("e rispondi con una sola lettera: quale lettura è quella stampata (X se nessuna).\n")
            append("Intestazioni (OCR): ").append(headerText.take(300)).append("\nRiga (OCR): ").append(rowText.take(400)).append('\n')
            choices.take(4).forEachIndexed { i, c ->
                append(LETTERS[i]).append(": QUANTITA' ").append(n(c.quantity)).append(", PREZZO ").append(n(c.unitPrice))
                    .append(", IMPORTO ").append(ItalianNumbers.formatDecimal(ItalianNumbers.centsToDecimal(c.lineTotalCents), minScale = 2, maxScale = 2)).append('\n')
            }
        } else {
            append("The picture shows the column headings of an Italian supplier document and, below them, one product line. ")
            append("The line's numbers add up in more than one way. Look at which heading each number stands under and answer ")
            append("with one letter: which reading is the one printed (X if none).\n")
            append("Headings (OCR): ").append(headerText.take(300)).append("\nLine (OCR): ").append(rowText.take(400)).append('\n')
            choices.take(4).forEachIndexed { i, c ->
                append(LETTERS[i]).append(": QUANTITY ").append(n(c.quantity)).append(", PRICE ").append(n(c.unitPrice))
                    .append(", AMOUNT ").append(ItalianNumbers.formatDecimal(ItalianNumbers.centsToDecimal(c.lineTotalCents), minScale = 2, maxScale = 2)).append('\n')
            }
        }
    }

    /** The choice the AI picked, or null (no clear answer, "X", or a letter with no choice behind it). */
    fun decodeChoice(raw: String, choices: List<LineChoice>): LineChoice? {
        val letter = raw.trim().trim('"').uppercase().take(1)
        val i = LETTERS.indexOf(letter)
        return if (i >= 0) choices.getOrNull(i) else null
    }

    private fun obj(json: String): Map<*, *>? = runCatching { Json.parse(json.trim()) }.getOrNull() as? Map<*, *>
    private fun str(m: Map<*, *>, k: String): String? = (m[k] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "null" }

    fun decodeItem(json: String): AiItem? = obj(json)?.let { m ->
        AiItem(str(m, "code"), str(m, "colli"), str(m, "description"), str(m, "unit"), str(m, "quantity"),
            str(m, "price"), str(m, "discount"), str(m, "amount"), str(m, "vat_rate"), str(m, "lot"))
    }

    /**
     * Applies the answers to the small questions to the regular reading. A line is replaced only when the AI's version
     * proves itself (quantity x price = amount, digits seen by the OCR) and the old one did not; a missed line is added
     * only when it proves itself; header and totals only fill what was missing or uncertain, and only with values
     * the OCR also saw. Everything is then cross-checked again.
     */
    /** Put in a value's source when the AI's double-check read it differently: the value stays highlighted. */
    const val DISAGREE = "⚠ AI read"

    fun applyTargets(doc: ParsedDocument, answers: List<Pair<AiTarget, String>>, text: String, options: ParseOptions = ParseOptions()): ParsedDocument {
        val items = doc.lineItems.toMutableList<ParsedLineItem?>()
        val inserts = mutableListOf<Pair<Int, ParsedLineItem>>()
        var d = doc
        val ev = Evidence(text)
        // The double-check: how many values the AI looked at again, and where it read something else.
        var checked = 0
        val disagreements = mutableListOf<String>()
        fun <T> flag(e: Extracted<T>, what: String, aiRead: String): Extracted<T> {
            disagreements += "$what: ${e.value.let { if (it is java.time.LocalDate) ItalianDates.format(it) else it.toString() }} / AI $aiRead"
            return e.copy(confidence = Confidence.LOW, source = "${e.source} ($DISAGREE $aiRead)")
        }
        for ((target, raw) in answers) {
            when (target) {
                is AiTarget.Row -> {
                    val a = decodeItem(raw) ?: continue
                    val rowEv = Evidence(target.rowText + "\n" + text)
                    val new = item(a, rowEv)?.let { fixHeading(it, text) } ?: continue
                    val proven = ParseWarning.LINE_TOTAL_MISMATCH !in new.warnings && new.quantity?.confidence == Confidence.HIGH &&
                        new.lineTotalCents?.confidence == Confidence.HIGH && !ReceiptParser.isSectionHeading(new.originalDescription)
                    if (!proven) continue
                    val idx = target.itemIndex
                    if (idx != null) {
                        val old = items[idx] ?: continue
                        items[idx] = new.copy(
                            originalDescription = if (ReceiptParser.isSectionHeading(old.originalDescription) || old.originalDescription.isBlank()) new.originalDescription else old.originalDescription,
                            lotNumber = old.lotNumber ?: new.lotNumber,
                            expiryDate = old.expiryDate,
                            itemCode = old.itemCode ?: new.itemCode,
                            packages = old.packages ?: new.packages,
                        )
                    } else if (doc.lineItems.none { it.lineTotalCents?.value == new.lineTotalCents?.value && it.originalDescription == new.originalDescription }) {
                        inserts += target.insertAfter to new
                    }
                }
                is AiTarget.Choice -> {
                    val idx = target.itemIndex
                    val old = items[idx] ?: continue
                    val pick = decodeChoice(raw, target.choices) ?: continue
                    // Both readings add up, so the AI's pick is a suggestion: shown first, still for the operator to confirm.
                    items[idx] = old.copy(
                        quantity = Extracted(pick.quantity, Confidence.LOW, target.rowText),
                        unitPrice = Extracted(pick.unitPrice, Confidence.LOW, target.rowText),
                        lineTotalCents = Extracted(pick.lineTotalCents, Confidence.LOW, target.rowText),
                        choices = listOf(pick) + old.choices.filter { it != pick },
                    )
                }
                is AiTarget.Number -> {
                    val idx = target.itemIndex
                    val old = items[idx] ?: continue
                    val answer = raw.trim().trim('"')
                    val read = ItalianNumbers.parse(answer) ?: continue
                    checked++
                    val agrees = read.compareTo(target.expected) == 0
                    val line = "line ${idx + 1}"
                    items[idx] = when (target.field) {
                        // The AI read the same number the arithmetic gives: two independent sources agree, the line is proven.
                        AiTarget.Field.QUANTITY -> old.quantity?.let { q ->
                            old.copy(quantity = if (agrees) q.copy(confidence = Confidence.HIGH, source = q.source + " (AI read $answer)") else flag(q, "$line quantity", answer))
                        } ?: old
                        AiTarget.Field.AMOUNT -> old.lineTotalCents?.let { t ->
                            if (agrees) old else old.copy(lineTotalCents = flag(t, "$line amount", answer))
                        } ?: old
                    }
                }
                is AiTarget.Header -> {
                    val m = obj(raw) ?: continue
                    val parsed = toParsed(AiAnswer(str(m, "seller"), str(m, "seller_vat"), str(m, "number"), str(m, "date"), null, null, null, emptyList()), text, options)
                    fun <T> better(old: Extracted<T>?, new: Extracted<T>?) = if (new != null && new.confidence == Confidence.HIGH && (old == null || old.confidence == Confidence.LOW)) new else old
                    // Double-check of values read with confidence: a different reading highlights them.
                    fun <T> check(old: Extracted<T>?, new: Extracted<T>?, what: String, same: (T, T) -> Boolean): Extracted<T>? {
                        if (old == null || new == null || old.confidence != Confidence.HIGH) return better(old, new)
                        checked++
                        return if (same(old.value, new.value)) old else flag(old, what, new.value.let { if (it is java.time.LocalDate) ItalianDates.format(it) else it.toString() })
                    }
                    d = d.copy(
                        sellerName = check(d.sellerName, parsed.sellerName, "supplier") { a, b -> sameName(a, b) },
                        documentNumber = check(d.documentNumber, parsed.documentNumber, "number") { a, b -> docNumberKey(a) == docNumberKey(b) },
                        documentDate = check(d.documentDate, parsed.documentDate, "date") { a, b -> a == b },
                    )
                }
                is AiTarget.Totals -> {
                    val m = obj(raw) ?: continue
                    fun money(k: String): Extracted<Long>? = str(m, k)?.let(::number)?.takeIf { ev.hasNumber(it) }
                        ?.let { Extracted(ItalianNumbers.toCents(it), Confidence.HIGH, "AI $k: ${str(m, k)}") }
                    fun check(old: Extracted<Long>?, new: Extracted<Long>?, what: String): Extracted<Long>? {
                        if (new != null && (old == null || old.confidence == Confidence.LOW)) return new
                        if (old == null || new == null) return old
                        checked++
                        return if (old.value == new.value) old else flag(old, what, ItalianNumbers.formatCents(new.value))
                    }
                    d = d.copy(
                        subtotalCents = check(d.subtotalCents, money("subtotal"), "taxable amount"),
                        vatCents = check(d.vatCents, money("vat"), "VAT"),
                        totalCents = check(d.totalCents, money("total"), "total"),
                    )
                }
            }
        }
        val out = mutableListOf<ParsedLineItem>()
        inserts.filter { it.first < 0 }.forEach { out += it.second }
        items.forEachIndexed { i, it ->
            if (it != null) out += it
            inserts.filter { ins -> ins.first == i }.forEach { ins -> out += ins.second }
        }
        val aiCheck = if (checked > 0) AiCheck(checked, disagreements) else d.aiCheck
        return ReceiptParser.finish(
            d.copy(lineItems = out, itemsReadBy = if (answers.isEmpty()) d.itemsReadBy else d.itemsReadBy + "+ai", aiCheck = aiCheck), text,
        )
    }

    private fun sameName(a: String, b: String): Boolean {
        val x = DuplicateDetector.normalizeSeller(a) ?: return false
        val y = DuplicateDetector.normalizeSeller(b) ?: return false
        return x == y || x.contains(y) || y.contains(x) || SmartMatcher.damerau(x, y, 2) <= 2
    }

    private fun docNumberKey(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    // ------------------------------------------------------------------ parsing the answer

    data class AiItem(
        val code: String?, val colli: String?, val description: String?, val unit: String?, val quantity: String?,
        val price: String?, val discount: String?, val amount: String?, val vatRate: String?, val lot: String?,
    )

    data class AiAnswer(
        val seller: String?, val sellerVat: String?, val number: String?, val date: String?,
        val subtotal: String?, val vat: String?, val total: String?, val items: List<AiItem>,
    )

    /** Reads the model's JSON; null if it is not the expected shape. */
    fun decode(json: String): AiAnswer? {
        val root = runCatching { Json.parse(json.trim()) }.getOrNull() as? Map<*, *> ?: return null
        fun str(m: Map<*, *>, k: String): String? = (m[k] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "null" }
        val items = (root["items"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map { m ->
            AiItem(
                str(m, "code"), str(m, "colli"), str(m, "description"), str(m, "unit"), str(m, "quantity"),
                str(m, "price"), str(m, "discount"), str(m, "amount"), str(m, "vat_rate"), str(m, "lot"),
            )
        }
        return AiAnswer(
            str(root, "seller"), str(root, "seller_vat"), str(root, "number"), str(root, "date"),
            str(root, "subtotal"), str(root, "vat"), str(root, "total"), items,
        )
    }

    /** What the OCR saw on the page, to check the AI's answer against. */
    class Evidence(ocrText: String) {
        private val cleaned = OcrCleanup.clean(ocrText)
        private val numbers: Set<BigDecimal> = Regex("\\d[\\d.,]*").findAll(cleaned)
            .flatMap { m -> listOfNotNull(ItalianNumbers.parse(m.value.trimEnd('.', ','))) }
            .map { it.stripTrailingZeros() }.toSet()
        private val compact: String = alnum(cleaned)
        private val words: Set<String> = normalize(cleaned).split(' ').filter { it.length >= 3 }.toSet()
        val dates: Set<java.time.LocalDate> = cleaned.lines().flatMap { ItalianDates.findDates(it).map { d -> d.date } }.toSet()

        fun hasNumber(v: BigDecimal) = v.stripTrailingZeros() in numbers
        fun hasText(s: String): Boolean { val a = alnum(s); return a.length >= 2 && compact.contains(a) }

        /** Share of the name's words that were also read by the OCR. */
        fun nameSupport(name: String): Double {
            val w = normalize(name).split(' ').filter { it.length >= 3 }
            if (w.isEmpty()) return 0.0
            return w.count { it in words }.toDouble() / w.size
        }
    }

    /**
     * Turns the AI's answer into a checked reading of one page. Values without support in the OCR text or the
     * arithmetic come out with LOW confidence (highlighted for the operator); lots that are not printed are dropped.
     */
    fun toParsed(answer: AiAnswer, ocrText: String, options: ParseOptions = ParseOptions()): ParsedDocument {
        val ev = Evidence(ocrText)
        fun money(raw: String?, what: String): Extracted<Long>? {
            val v = raw?.let(::number) ?: return null
            if (v.abs() > BigDecimal("10000000")) return null
            return Extracted(ItalianNumbers.toCents(v), if (ev.hasNumber(v)) Confidence.HIGH else Confidence.LOW, "AI $what: $raw")
        }
        val ownVat = options.ownVatNumber?.filter(Char::isDigit)
        val ownName = DuplicateDetector.normalizeSeller(options.ownBusinessName)
        val sellerName = answer.seller?.let { cleanText(it) }?.takeIf { it.count(Char::isLetter) >= 3 }
            ?.takeUnless { n -> ownName != null && ownName.length >= 4 && (DuplicateDetector.normalizeSeller(n) ?: "").contains(ownName) }
        val seller = sellerName?.let { Extracted(it, if (ev.nameSupport(it) >= 0.5) Confidence.HIGH else Confidence.LOW, "AI seller") }
        val date = answer.date?.let { ItalianDates.findDates(it).firstOrNull()?.date }?.let {
            Extracted(it, if (it in ev.dates) Confidence.HIGH else Confidence.LOW, "AI date: ${answer.date}")
        }
        // The same checks as the regular reading: never a postcode and town ("00100 ROMA"), an address or a VAT number.
        val number = answer.number?.let { cleanText(it) }?.takeIf { ReceiptParser.plausibleDocNumber(it) }
            ?.takeUnless { ownVat != null && it.filter(Char::isDigit) == ownVat }
            ?.let { Extracted(it, if (ev.hasText(it)) Confidence.HIGH else Confidence.LOW, "AI number") }

        val items = answer.items.mapNotNull { item(it, ev) }.map { fixHeading(it, ocrText) }
        return finish(
            ParsedDocument(
                sellerName = seller,
                documentDate = date,
                documentNumber = number,
                currency = Extracted("EUR", Confidence.HIGH, "AI"),
                subtotalCents = money(answer.subtotal, "subtotal"),
                vatCents = money(answer.vat, "vat"),
                totalCents = money(answer.total, "total"),
                vatBasis = null,
                lineItems = items,
                warnings = emptySet(),
                itemsReadBy = "ai",
            ),
            ocrText,
        )
    }

    private fun finish(d: ParsedDocument, text: String) = ReceiptParser.finish(d, text)

    /**
     * The AI named a line after a section title ("Merce non alimentare"): the real name is on the OCR line with the
     * same article code ("24195 CARTA FORNO 40CM X 50M C/ASTUCCIO").
     */
    private fun fixHeading(item: ParsedLineItem, ocrText: String): ParsedLineItem {
        if (!ReceiptParser.isSectionHeading(item.originalDescription)) return item
        val code = item.itemCode ?: return item
        val line = OcrCleanup.clean(ocrText).lines().firstOrNull { l -> l.split(' ').any { it == code } && !ReceiptParser.isSectionHeading(l) } ?: return item
        val name = ReceiptParser.parseItemLine(line)?.originalDescription
            ?: cleanText(line.split(' ').dropWhile { it != code }.drop(1).dropWhile { ReceiptParser.COLLI_PATTERN.matches(it) || it.all(Char::isDigit) }.joinToString(" "))
        return if (name.count(Char::isLetter) >= 3 && !ReceiptParser.isSectionHeading(name)) item.copy(originalDescription = name) else item
    }

    private val MARKER_CODE = Regex("^[A-Z0]\\s+(?=\\d)")
    private val CODE_WITH_COLLI = Regex("^(\\d{5,})(\\d{1,2}[xX×]\\d{1,3})$")

    /**
     * The unit column as the model copied it may hold packaging and pack size ("SK GR 800", "CF GR", "NC KG 1"):
     * a weight/volume with a number is the pack size (the item is counted in pieces, the size goes with the name);
     * a unit alone ("NC KG") is the unit. Returns (unit, text to add to the description).
     */
    fun splitUnit(raw: String?): Pair<String?, String?> {
        val t = raw?.let { cleanText(it) }?.takeIf { it.isNotEmpty() } ?: return null to null
        Units.normalizeKnown(t)?.let { return it to null }
        val tokens = t.split(' ').filter { it.isNotBlank() }
        val units = tokens.mapIndexedNotNull { i, tok -> Units.normalizeKnown(tok.trimEnd('.'))?.let { i to it } }
        val physical = units.firstOrNull { Units.dimension(it.second) != null }
        if (physical != null) {
            val rest = tokens.drop(physical.first)
            val hasNumber = rest.drop(1).any { tok -> tok.any(Char::isDigit) }
            return if (hasNumber) "pz" to rest.joinToString(" ").uppercase() else physical.second to null
        }
        return (units.lastOrNull()?.second) to null
    }

    private fun item(a: AiItem, ev: Evidence): ParsedLineItem? {
        var description = a.description?.let { cleanText(it) }?.takeIf { it.count(Char::isLetter) >= 2 } ?: return null
        val source = "AI: " + listOfNotNull(a.code, a.colli, a.description, a.unit, a.quantity, a.price, a.discount, a.amount, a.vatRate).joinToString(" ")
        var qty = a.quantity?.let(::number)
        var price = a.price?.let(::number)
        val amount = a.amount?.let(::number)?.let { ItalianNumbers.toCents(it) }
        val discount = a.discount?.let(::discountOf)
        var consistent = false
        if (qty != null && price != null && amount != null) {
            consistent = ReceiptParser.matches(qty, price, amount) || discountMatches(qty, price, discount, amount)
        }
        val printed = { v: BigDecimal? -> v != null && ev.hasNumber(v) }
        val amountConf = if (amount != null && (consistent || ev.hasNumber(ItalianNumbers.centsToDecimal(amount)))) Confidence.HIGH else Confidence.LOW
        val qpConf = if (consistent && (printed(qty) || printed(price))) Confidence.HIGH else Confidence.LOW
        val (readUnit, packSize) = splitUnit(a.unit)
        var unit = readUnit
        if (packSize != null && !description.uppercase().endsWith(packSize)) description = "$description $packSize"
        // "GR" with 0,480 means kilograms.
        val q0 = qty
        if (q0 != null && q0.stripTrailingZeros().scale() > 0 && q0 < BigDecimal(100)) {
            if (unit == "g") unit = "kg"
            if (unit == "ml") unit = "l"
        }
        val rate = a.vatRate?.let(::number)?.takeIf { it.stripTrailingZeros().toPlainString() in setOf("0", "4", "5", "10", "22") }
        // A lot must be printed on the page and must not be a date (expiry dates are never lots).
        val lot = a.lot?.let { cleanText(it) }?.takeIf { it.any(Char::isDigit) && ev.hasText(it) && ItalianDates.findDates(it).isEmpty() && it.length <= 40 }
        var colli = a.colli?.let { cleanText(it) }?.takeIf { it.any(Char::isDigit) && it.length <= 12 }
        // "O 10000032x3": offer marker, article code and colli printed together.
        var codeText = a.code?.let { cleanText(it) }?.replace(MARKER_CODE, "")
        CODE_WITH_COLLI.find(codeText ?: "")?.let { m -> codeText = m.groupValues[1]; colli = m.groupValues[2] }
        val code = codeText?.takeIf { it.any(Char::isDigit) && ev.hasText(it) && it.length <= 30 }
        return ParsedLineItem(
            originalDescription = description,
            quantity = qty?.let { Extracted(it, qpConf, source) },
            unit = unit?.let { Extracted(it, if (consistent) Confidence.HIGH else Confidence.LOW, source) },
            unitPrice = price?.let { Extracted(it, qpConf, source) },
            lineTotalCents = amount?.let { Extracted(it, amountConf, source) },
            vatRatePercent = rate?.let { Extracted(it.stripTrailingZeros(), Confidence.HIGH, source) },
            lotNumber = lot?.let { Extracted(it, Confidence.HIGH, source) },
            expiryDate = null,
            warnings = if (qty != null && price != null && amount != null && !consistent) setOf(ParseWarning.LINE_TOTAL_MISMATCH) else emptySet(),
            itemCode = code,
            packages = colli?.let { Extracted(it.lowercase().replace('×', 'x').replace(" ", ""), if (ev.hasText(it)) Confidence.HIGH else Confidence.LOW, source) },
        )
    }

    /**
     * Combines the regular reading and the AI's: header fields come from whichever read them with confidence
     * (the regular reading first), the items from whichever reading explains the document better.
     */
    fun merge(regular: ParsedDocument, ai: ParsedDocument, text: String): ParsedDocument {
        fun <T> pick(r: Extracted<T>?, a: Extracted<T>?): Extracted<T>? = when {
            r != null && r.confidence == Confidence.HIGH -> r
            a != null && a.confidence == Confidence.HIGH -> a
            else -> r ?: a
        }
        val subtotal = pick(regular.subtotalCents, ai.subtotalCents)
        val total = pick(regular.totalCents, ai.totalCents)
        val useAi = ReceiptParser.itemScore(ai.lineItems, subtotal?.value, total?.value) >
            ReceiptParser.itemScore(regular.lineItems, subtotal?.value, total?.value)
        // A line the AI still named after a section title takes the name the regular reading gave the same amount.
        val aiItems = ai.lineItems.map { a ->
            if (!ReceiptParser.isSectionHeading(a.originalDescription)) a
            else regular.lineItems.firstOrNull { r -> r.lineTotalCents?.value == a.lineTotalCents?.value && !ReceiptParser.isSectionHeading(r.originalDescription) }
                ?.let { r -> a.copy(originalDescription = r.originalDescription, itemCode = a.itemCode ?: r.itemCode) } ?: a
        }
        val merged = regular.copy(
            sellerName = pick(regular.sellerName, ai.sellerName),
            documentDate = pick(regular.documentDate, ai.documentDate),
            documentNumber = pick(regular.documentNumber, ai.documentNumber),
            currency = regular.currency ?: ai.currency,
            subtotalCents = subtotal,
            vatCents = pick(regular.vatCents, ai.vatCents),
            totalCents = total,
            lineItems = if (useAi) aiItems else regular.lineItems,
            itemsReadBy = if (useAi) "ai" else regular.itemsReadBy,
        )
        return ReceiptParser.finish(merged, text)
    }

    /**
     * Which pages the AI should read, so it spends its minutes only where they help: pages with a line that does
     * not add up (or has no amount / an uncertain quantity), the first page when the supplier or date is missing,
     * the last page when the total is missing. When the problem cannot be pinned to a page, every page.
     * [pages] = each page read on its own by the regular reader; [whole] = the whole document.
     */
    fun pagesToRead(pages: List<ParsedDocument>, whole: ParsedDocument): List<Int> {
        if (pages.isEmpty()) return emptyList()
        val out = sortedSetOf<Int>()
        pages.forEachIndexed { i, p ->
            if (p.lineItems.any { ParseWarning.LINE_TOTAL_MISMATCH in it.warnings || it.lineTotalCents == null || it.quantity?.confidence == Confidence.LOW }) out += i
        }
        val lineProblems = out.isNotEmpty()
        if (whole.sellerName == null || whole.documentDate == null) out += 0
        if (whole.totalCents == null) out += pages.lastIndex
        if ((ParseWarning.ITEMS_SUM_MISMATCH in whole.warnings || ParseWarning.NO_ITEMS_FOUND in whole.warnings) && !lineProblems) {
            return pages.indices.toList()
        }
        return if (out.isEmpty()) pages.indices.toList() else out.toList()
    }

    /**
     * The document's items page by page: the AI's lines on the pages it read, where they explain that page better
     * than the regular reading; the regular reading's lines everywhere else.
     */
    fun combineItems(regularPages: List<ParsedDocument>, aiPages: List<ParsedDocument?>): List<ParsedLineItem> =
        regularPages.indices.flatMap { i ->
            val regular = regularPages[i].lineItems
            val ai = aiPages.getOrNull(i)?.lineItems
            if (ai != null && ReceiptParser.itemScore(ai, null, null) > ReceiptParser.itemScore(regular, null, null)) ai else regular
        }

    /** Joins the AI's page-by-page readings: header from the first page that has it, totals from the last. */
    fun joinPages(pages: List<ParsedDocument>, text: String): ParsedDocument? {
        if (pages.isEmpty()) return null
        val withTotal = pages.lastOrNull { it.totalCents != null }
        val d = ParsedDocument(
            sellerName = pages.firstNotNullOfOrNull { it.sellerName },
            documentDate = pages.firstNotNullOfOrNull { it.documentDate },
            documentNumber = pages.firstNotNullOfOrNull { it.documentNumber },
            currency = pages.firstNotNullOfOrNull { it.currency },
            subtotalCents = withTotal?.subtotalCents ?: pages.lastOrNull { it.subtotalCents != null }?.subtotalCents,
            vatCents = withTotal?.vatCents ?: pages.lastOrNull { it.vatCents != null }?.vatCents,
            totalCents = withTotal?.totalCents,
            vatBasis = null,
            lineItems = pages.flatMap { it.lineItems },
            warnings = emptySet(),
            itemsReadBy = "ai",
        )
        return ReceiptParser.finish(d, text)
    }

    // ------------------------------------------------------------------ helpers

    private fun number(raw: String): BigDecimal? {
        val s = OcrCleanup.fixNumericToken(raw.replace("€", "").replace(" ", "").trim().trimEnd('-', '%'))
        if (s.isEmpty() || !s.any(Char::isDigit) || !s.all { it.isDigit() || it in ".,-" }) return null
        return ItalianNumbers.parse(s)
    }

    private fun discountOf(raw: String): BigDecimal? {
        val parts = raw.replace("%", "").split('+').mapNotNull { number(it) }
        if (parts.isEmpty() || parts.any { it.signum() < 0 || it >= BigDecimal(100) }) return null
        var keep = BigDecimal.ONE
        for (p in parts) keep = keep.multiply(BigDecimal.ONE.subtract(p.movePointLeft(2)))
        return BigDecimal.ONE.subtract(keep).movePointRight(2)
    }

    private fun discountMatches(q: BigDecimal, p: BigDecimal, d: BigDecimal?, total: Long): Boolean {
        if (d == null || d.signum() == 0) return false
        val net = q.multiply(p).multiply(BigDecimal(100).subtract(d)).divide(BigDecimal(100), 6, RoundingMode.HALF_UP)
        return kotlin.math.abs(ItalianNumbers.toCents(net) - total) <= 2
    }

    private fun cleanText(s: String) = s.replace(Regex("\\s+"), " ").trim().trim('"', '\'', ' ', ',', ';')

    private fun alnum(s: String) = normalize(s).replace(" ", "")

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}

/** Minimal JSON reader (objects, arrays, strings, numbers, true/false/null) for the AI's answers. */
internal object Json {
    fun parse(s: String): Any? {
        val p = P(s)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.i == s.length) { "Trailing characters at ${p.i}" }
        return v
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "Unexpected end" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }
        fun lit(word: String, v: Any?): Any? { require(s.startsWith(word, i)) { "Bad literal at $i" }; i += word.length; return v }
        fun num(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            require(i > start) { "Bad value at $start" }
            return s.substring(start, i)
        }
        fun obj(): Map<String, Any?> {
            i++
            val m = linkedMapOf<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                require(s[i] == ':') { "Expected : at $i" }; i++
                m[k] = value(); ws()
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> throw IllegalArgumentException("Expected , or } at $i") }
            }
        }
        fun arr(): List<Any?> {
            i++
            val l = mutableListOf<Any?>()
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> throw IllegalArgumentException("Expected , or ] at $i") }
            }
        }
        fun str(): String {
            require(s[i] == '"') { "Expected string at $i" }
            i++
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b')
                            'f' -> b.append('\u000C'); '/' -> b.append('/'); '\\' -> b.append('\\'); '"' -> b.append('"')
                            'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw IllegalArgumentException("Bad escape at $i")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }
    }
}
