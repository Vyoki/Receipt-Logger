package com.kitchenreceipts.core

/**
 * Proof for the supplier's name, the way the arithmetic is proof for numbers: a name is sure only when independent
 * places on the document agree on it, or when the supplier's VAT number (its check digit valid) is one the phone
 * already knows. A reading the camera found "clear" proves nothing on its own: a stylised logo read as "ABE" for
 * "ABC" looks perfectly clear.
 *
 * The places a supplier's name is printed, each read separately by the camera:
 *  - the name the reading chose (usually the logo or the letterhead heading);
 *  - the supplier's box ("Cedente/prestatore", "Mittente", "Fornitore");
 *  - every other line naming a company with its legal form (letterhead block, footer "Reg. Imp. / Cap. Soc."),
 *    except the customer's box, carriers, banks and parent companies;
 *  - the website, email or PEC address (abcsrl.it, abcsrl@pec.it), which confirms a spelling without giving one;
 *  - the phone's own registry: the supplier saved before under this VAT number ([ParseOptions.supplierByVat]);
 *  - the AI's reading of those places (see [withAi]).
 *
 * Two places on different lines that spell the name the same way prove it. One reading alone leaves it unproven
 * (the AI is then asked); two spellings with equal support are a conflict, shown with both alternatives.
 */
object SupplierProof {

    enum class Kind { READING, SUPPLIER_BOX, COMPANY_LINE, WEB, REGISTRY, AI }

    /**
     * One place the name was read. [name] is null for a web address (it confirms, it does not spell). [line] is the
     * printed line it was read on (for the AI's crop); [anchor]: the place is where the supplier itself is printed
     * (the reading, its box, its VAT block or legal footer), not just some company mentioned on the page.
     */
    data class Source(val kind: Kind, val name: String?, val stem: String?, val page: Int, val line: String, val anchor: Boolean)

    enum class State { PROVEN, UNPROVEN, CONFLICT, NONE }

    data class Result(
        val state: State,
        /** The name to show: the proven spelling, or the best supported one. */
        val name: String?,
        /** The spellings that compete with [name] (a conflict), best supported first. */
        val alternatives: List<String>,
        /** Every place read, for the AI's question and for troubleshooting. */
        val sources: List<Source>,
        /** How many independent places support [name]. */
        val support: Int,
    ) {
        fun describe(): String = "${state.name.lowercase()} by $support: " +
            sources.joinToString("; ") { "${it.kind.name.lowercase()}=${it.name ?: it.stem}" }
    }

    // ------------------------------------------------------------------ collecting the places

    /** Lines that name a company that is not the supplier: carrier, bank, parent company, agent, destination. */
    private val OTHER_PARTY = rx(
        "(?i)\\b(vettor\\w*|trasport\\w*|corriere|spedizion\\w*|banca|banco|bank|iban|abi|cab|coordinamento|direzione|agente|" +
            "destinazione|consegna|assicura\\w*|vostro|vs\\.?\\s|cliente|spett\\w*|destinatario|intestatario)\\b",
    )
    private val LEGAL_LINE = rx("(?i)(reg\\.?\\s*imp|\\brea\\b|cap\\.?\\s*soc|capitale|sede\\s+legale|p\\.?\\s?iva|partita\\s+iva|c\\.?\\s?f\\.?\\s|codice\\s+fiscale)")
    private val WEB = rx("(?i)(?:www\\.|https?://)([a-z0-9][a-z0-9-]{1,40})\\.(?:it|com|eu|net|org|biz|info|shop)\\b")
    private val EMAIL = rx("(?i)\\b([a-z0-9][a-z0-9._-]{1,40})@([a-z0-9][a-z0-9-]{1,40})\\.(?:it|com|eu|net|org|biz|info)\\b")
    /** Mail providers: their name says nothing about the supplier (the part before @ may). */
    private val GENERIC_DOMAIN = setOf(
        "gmail", "libero", "hotmail", "outlook", "yahoo", "tiscali", "alice", "virgilio", "pec", "legalmail", "arubapec",
        "aruba", "postecert", "pecimprese", "tin", "fastwebnet", "email", "mail", "live", "icloud", "pecsicura", "cert",
        "registerpec", "sicurezzapostale", "gigapec", "mypec", "casellapec", "postacertificata", "messaggipec",
    )
    /** Mailbox names that are not a company: info@, amministrazione@... */
    private val GENERIC_MAILBOX = rx("^(info|amministrazione|ammin\\w*|ordini|ordine|commerciale|vendite|fatture|fatturazione|contabilita|segreteria|ufficio\\w*|direzione|posta|mail|pec|sales|office|admin|acquisti|logistica|magazzino)$")

    fun collect(pages: List<List<String>>, reading: Extracted<String>?, boxName: String?, options: ParseOptions): List<Source> {
        val ownVat = options.ownVatNumber?.filter(Char::isDigit)?.takeIf { it.length >= 8 }
        val ownName = DuplicateDetector.normalizeSeller(options.ownBusinessName)?.takeIf { it.length >= 4 }
        val all = pages.joinToString("\n") { it.joinToString("\n") }
        val supplierVat = SellerProfiles.supplierVatNumber(all, ownVat)
        val out = mutableListOf<Source>()
        fun own(name: String) = ownName != null && (DuplicateDetector.normalizeSeller(name) ?: "").contains(ownName)

        // The phone already knows this VAT number: the name saved for it.
        if (supplierVat != null) options.supplierByVat?.invoke(supplierVat)?.let { known ->
            out += Source(Kind.REGISTRY, known, null, -1, "registry: $supplierVat", anchor = true)
        }

        pages.take(4).forEachIndexed { p, page ->
            val lines = page.map { SellerProfiles.repairDigits(it) }
            val customer = BooleanArray(lines.size)
            lines.forEachIndexed { i, l ->
                val label = ReceiptParser.CUSTOMER_LABEL.find(l)
                if (label != null && label.range.first < 4) for (k in i..minOf(i + 4, lines.lastIndex)) customer[k] = true
                // The operator's own VAT number ends the customer's box: from its label (when just above) to here.
                if (ownVat != null && l.filter(Char::isDigit).contains(ownVat)) {
                    val from = (i downTo maxOf(0, i - 5)).firstOrNull { k -> ReceiptParser.CUSTOMER_LABEL.containsMatchIn(lines[k]) } ?: maxOf(0, i - 2)
                    for (k in from..i) customer[k] = true
                }
            }
            val vatLine = supplierVat?.let { v -> lines.indexOfFirst { it.replace(" ", "").contains(v) } } ?: -1
            lines.forEachIndexed { i, l ->
                if (customer[i] || OTHER_PARTY.containsMatchIn(l)) return@forEachIndexed
                // Companies named with their legal form on this line.
                for (m in ReceiptParser.COMPANY_SUFFIX.findAll(l)) {
                    val before = l.substring(0, m.range.first)
                    // The name: the words just before the legal form, after any label ("Denominazione:", "Ditta").
                    val tail = before.split(rx("[:;|]|\\s-\\s")).last().trim()
                    val words = tail.split(' ').filter { it.isNotBlank() }.takeLast(5)
                    val name = (words.joinToString(" ") + " " + m.value.trim()).trim()
                    if (words.joinToString("").count(Char::isLetter) < 3 || own(name)) continue
                    if (ReceiptParser.ADDRESS_OR_CONTACT.containsMatchIn(words.joinToString(" "))) continue
                    val anchor = (vatLine >= 0 && i <= vatLine && vatLine - i <= 6) || LEGAL_LINE.containsMatchIn(l) || i <= 3
                    out += Source(Kind.COMPANY_LINE, ReceiptParser.cleanSeller(name), null, p, page[i], anchor)
                }
                // Web and mail addresses.
                for (m in WEB.findAll(l)) stem(m.groupValues[1])?.let { out += Source(Kind.WEB, null, it, p, page[i], anchor = false) }
                for (m in EMAIL.findAll(l)) {
                    val box = m.groupValues[1].lowercase()
                    val domain = m.groupValues[2].lowercase()
                    val s = if (domain in GENERIC_DOMAIN || domain.startsWith("pec")) {
                        box.substringBefore('.').takeUnless { GENERIC_MAILBOX.matches(it) }
                    } else domain
                    s?.let(::stem)?.let { out += Source(Kind.WEB, null, it, p, page[i], anchor = false) }
                }
            }
        }
        // Own name or customer in a web address: not the supplier's.
        out.removeAll { it.kind == Kind.WEB && ownName != null && it.stem != null && ownName.replace(" ", "").startsWith(it.stem) }

        // The printed line a name was read on (the same line read twice is one place, not two).
        fun placeOf(name: String): Pair<Int, String> {
            val n = norm(name)
            pages.take(4).forEachIndexed { p, page -> page.firstOrNull { norm(it).contains(n) }?.let { return p to it } }
            return 0 to name
        }
        if (reading != null && !own(reading.value)) {
            val boxed = reading.source?.startsWith("supplier box") == true
            val (page, line) = placeOf(reading.value)
            out += Source(if (boxed) Kind.SUPPLIER_BOX else Kind.READING, reading.value, null, page, line, anchor = true)
        }
        if (boxName != null && !own(boxName)) {
            val (page, line) = placeOf(boxName)
            out += Source(Kind.SUPPLIER_BOX, boxName, null, page, line, anchor = true)
        }
        return out
    }

    /** "abc-srl" -> "abc", "verdefrescospa" -> "verdefresco": letters and digits only, without a legal form at the end. */
    private fun stem(s: String): String? {
        val t = s.lowercase().filter(Char::isLetterOrDigit).replace(rx("(srls|srl|spa|snc|sas|group|italia|it)$"), "")
        return t.takeIf { it.length >= 3 }
    }

    private fun key(name: String): String? = DuplicateDetector.normalizeSeller(name)?.filter(Char::isLetterOrDigit)?.takeIf { it.length >= 2 }

    /** A web address confirms a spelling when its name is that spelling ("abc.it" for "A.B.C. S.r.l."). */
    private fun webSupports(stem: String, key: String): Boolean =
        stem == key || (key.length >= 4 && stem.startsWith(key)) || (stem.length >= 4 && key.startsWith(stem))

    // ------------------------------------------------------------------ judging

    fun judge(sources: List<Source>): Result {
        val named = sources.filter { it.name != null && key(it.name) != null }
        val anchored = named.filter { it.anchor }
        if (anchored.isEmpty()) return Result(State.NONE, null, emptyList(), sources, 0)
        // Spellings in play: the supplier as read (the reading, its box, the registry, the AI) and other mentions of
        // the same company, a misread letter included. A different company printed elsewhere never replaces it: who
        // the supplier is was decided by the reading (customer's box, own VAT number...); here only its spelling and
        // its proof are settled.
        val identity = named.filter { it.kind == Kind.READING || it.kind == Kind.SUPPLIER_BOX || it.kind == Kind.REGISTRY || it.kind == Kind.AI }
            .ifEmpty { anchored }
        val inPlay = named.filter { s ->
            s in identity || identity.any { a -> SellerProfiles.sameCompanyOrMisread(a.name!!, s.name!!) }
        }
        val byKey = inPlay.groupBy { key(it.name!!)!! }
        val webs = sources.filter { it.kind == Kind.WEB && it.stem != null }
        // Support: the independent places that spell it exactly this way (one per printed line; the registry and the
        // AI count as places of their own), plus a web address with that name.
        fun support(k: String, group: List<Source>): Int {
            val places = group.map { if (it.kind == Kind.REGISTRY || it.kind == Kind.AI) it.kind.name else "${it.page}|${norm(it.line)}" }.distinct().size
            return places + webs.map { it.stem }.distinct().count { webSupports(it!!, k) }
        }
        val ranked = byKey.map { (k, g) -> Triple(k, g, support(k, g)) }
            .sortedWith(compareByDescending<Triple<String, List<Source>, Int>> { it.third }.thenBy { t -> t.second.minOf { rank(it.kind) } })
        val (bestKey, bestGroup, bestSupport) = ranked.first()
        val name = display(bestGroup)
        val others = ranked.drop(1)
        val alternatives = others.map { display(it.second) }
        // The registry decides when it is in play: a supplier saved before under this VAT number, unless two places
        // on the page clearly name another company (that VAT number was learned by mistake).
        val registry = bestGroup.firstOrNull { it.kind == Kind.REGISTRY } ?: inPlay.firstOrNull { it.kind == Kind.REGISTRY }
        if (registry != null) {
            val regKey = key(registry.name!!)!!
            val rival = ranked.firstOrNull { it.first != regKey && it.third >= 2 && !SellerProfiles.sameCompanyOrMisread(display(it.second), registry.name) }
            if (rival == null) {
                val regGroup = byKey[regKey].orEmpty()
                return Result(State.PROVEN, registry.name, ranked.filter { it.first != regKey }.map { display(it.second) }, sources, support(regKey, regGroup))
            }
        }
        val state = when {
            others.any { it.third == bestSupport } -> State.CONFLICT
            bestSupport >= 2 -> State.PROVEN
            else -> State.UNPROVEN
        }
        return Result(state, name, alternatives, sources, bestSupport)
    }

    /**
     * The AI read the supplier's places too: one more independent reading, or null when its answer matches none of
     * the places read (then it is only offered, never taken). An answer that is only part of a name read ("MARIO"
     * for "ROSSI DI ROSSI MARIO") adds nothing; a fuller one ("MACELLERIA ROSSI ...") or one letter different
     * ("ABC" where the logo read "ABE") is its own spelling, set against the others.
     */
    fun withAi(result: Result, aiName: String): Result? {
        val aiKey = key(aiName) ?: return null
        val base = result.sources.filter { it.kind != Kind.AI }
        val named = base.filter { it.name != null && key(it.name) != null }
        val related = named.filter { SellerProfiles.sameCompanyOrMisread(it.name!!, aiName) } +
            base.filter { it.kind == Kind.WEB && it.stem != null && webSupports(it.stem, aiKey) }
        if (related.isEmpty()) return null
        if (named.none { key(it.name!!) == aiKey } && named.any { key(it.name!!)!!.contains(aiKey) }) return result
        return judge(base + Source(Kind.AI, aiName, null, -1, "AI", anchor = true))
    }

    /** Which spelling to show first on a tie: the AI (it sees the picture) and printed lines over the camera's logo. */
    private fun rank(k: Kind) = when (k) {
        Kind.REGISTRY -> 0; Kind.AI -> 1; Kind.SUPPLIER_BOX -> 2; Kind.COMPANY_LINE -> 3; Kind.READING -> 4; Kind.WEB -> 5
    }

    /** The cleanest spelling in a group: with its legal form, from a printed line rather than the logo. */
    private fun display(group: List<Source>): String =
        group.sortedWith(compareBy<Source>({ if (ReceiptParser.COMPANY_SUFFIX.containsMatchIn(it.name!!)) 0 else 1 }, { rank(it.kind) }))
            .first().name!!

    private fun norm(s: String) = s.uppercase().filter(Char::isLetterOrDigit)

    // ------------------------------------------------------------------ applying it to a reading

    /**
     * The supplier as proven: sure only when proven; otherwise highlighted (and asked to the AI), with the other
     * spellings read when they disagree. A reading the camera found clear is no longer sure on its own.
     */
    fun apply(doc: ParsedDocument, pages: List<List<String>>, box: Parties.Supplier?, options: ParseOptions): ParsedDocument {
        val sources = collect(pages, doc.sellerName, box?.name?.value, options)
        val r = judge(sources)
        return applyResult(doc, r)
    }

    fun applyResult(doc: ParsedDocument, r: Result): ParsedDocument {
        val cur = doc.sellerName
        return when (r.state) {
            State.NONE -> doc.copy(
                sellerName = cur?.copy(confidence = Confidence.LOW),
                supplierProof = r,
            )
            State.PROVEN -> {
                // Keep the reading's own spelling when it is the proven one (it may be written more fully).
                val value = if (cur != null && key(cur.value) == key(r.name!!)) cur.value else r.name!!
                doc.copy(sellerName = Extracted(value, Confidence.HIGH, "proven: " + r.describe()), supplierProof = r)
            }
            State.UNPROVEN, State.CONFLICT -> doc.copy(
                sellerName = Extracted(r.name ?: cur?.value ?: "", Confidence.LOW, r.describe()).takeIf { it.value.isNotBlank() },
                supplierProof = r,
            )
        }
    }
}
