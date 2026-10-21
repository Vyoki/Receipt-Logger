package com.kitchenreceipts.core

import java.time.LocalDate

/** Where an import of a file of many invoices is. */
enum class BundleStatus { SCANNING, NOT_SPLITTABLE, SORTING, PREPARING, QUEUED, CANCELLED }

/** Why an invoice of the file starts left out (the operator can still take it). */
enum class LeftOut { ALREADY_SAVED, OTHER_BUYER, BY_OPERATOR }

/** One invoice found in the file: its pages and what its first page prints. */
data class PlannedInvoice(
    val first: Int,
    val last: Int,
    val supplierVat: String?,
    val supplierName: String?,
    val customerVat: String?,
    val docType: String?,
    val number: String?,
    val date: LocalDate?,
    val totalCents: Long?,
    val problems: Set<InvoiceBundle.Problem>,
    val include: Boolean = true,
    val leftOut: LeftOut? = null,
    /** When it is already in the app: the saved document's date, as shown. */
    val savedOn: String? = null,
    /** The operator looked at this uncertain invoice and kept it as it is. */
    val checked: Boolean = false,
) {
    val pageCount: Int get() = last - first + 1
    val creditNote: Boolean get() = docType == "TD04" || docType == "TD08"
    val uncertain: Boolean get() = problems.isNotEmpty() && !checked
}

/** One supplier of the file: its invoices are imported or not together, and it gets one category. */
data class SupplierChoice(
    /** The VAT number, or the name when no VAT number was found. */
    val key: String,
    val name: String?,
    val vat: String?,
    /** A [SupplierCategory] key, [LATER] ("decide later"), or null while not chosen. */
    val category: String?,
    /** Its category was saved before: nothing to ask. */
    val known: Boolean,
    val suggestion: String?,
    val include: Boolean = true,
) {
    val chosen: Boolean get() = category != null

    companion object {
        const val LATER = "later"
    }
}

data class BundlePlan(
    val id: String,
    /** The app's copy of the file (its stored name) and its fingerprint. */
    val file: String,
    val fileSha: String,
    val pageCount: Int,
    val status: BundleStatus,
    val scannedPages: Int = 0,
    val invoices: List<PlannedInvoice> = emptyList(),
    val suppliers: List<SupplierChoice> = emptyList(),
    val createdAt: Long = 0L,
    /** After queuing: how many invoices went to be read, and how many of them are saved or thrown away. */
    val queued: Int = 0,
    val saved: Int = 0,
    val discarded: Int = 0,
) {
    fun supplierOf(inv: PlannedInvoice): SupplierChoice? = suppliers.firstOrNull { it.key == BundleWizard.supplierKey(inv) }
}

/**
 * The steps of importing a file of many invoices, as pure decisions: what is found, which suppliers to sort, which
 * invoices need a look, and what goes to be read. Nothing here is guessed: a category is the operator's choice (a
 * suggestion is only shown), an invoice already in the app or addressed to another company starts left out, and the
 * operator can take any of them back.
 */
object BundleWizard {

    enum class Step { FOUND, SUPPLIERS, CHECK, SUMMARY }

    fun supplierKey(inv: PlannedInvoice): String =
        inv.supplierVat ?: DuplicateDetector.normalizeSeller(inv.supplierName)?.takeIf { it.isNotBlank() } ?: "?"

    /**
     * The plan from the scan. [known]: the saved category of each supplier VAT number the app knows (null when the
     * supplier is known but not sorted). [savedOn]: the date to show when an invoice is already in the app, else null.
     * [suggestions]: the suggested category of each segment (same order).
     */
    fun plan(
        base: BundlePlan,
        segments: List<InvoiceBundle.Segment>,
        suggestions: List<SupplierCategory?>,
        known: Map<String, String?>,
        ownVat: String?,
        savedOn: (PlannedInvoice) -> String?,
    ): BundlePlan {
        // The restaurant: its own VAT number, or the one most invoices are addressed to.
        val buyer = ownVat?.filter(Char::isDigit)?.takeIf { it.length >= 8 }
            ?: segments.mapNotNull { it.customerVat }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val invoices = segments.map { s ->
            val inv = PlannedInvoice(
                first = s.first, last = s.last, supplierVat = s.supplierVat, supplierName = s.supplierName,
                customerVat = s.customerVat, docType = s.docType, number = s.number, date = s.date, totalCents = s.totalCents,
                problems = s.problems,
            )
            val saved = runCatching { savedOn(inv) }.getOrNull()
            when {
                saved != null -> inv.copy(include = false, leftOut = LeftOut.ALREADY_SAVED, savedOn = saved)
                buyer != null && inv.customerVat != null && inv.customerVat != buyer -> inv.copy(include = false, leftOut = LeftOut.OTHER_BUYER)
                else -> inv
            }
        }
        val suppliers = invoices.withIndex().groupBy { supplierKey(it.value) }.map { (key, group) ->
            val firstInv = group.first().value
            val vat = firstInv.supplierVat
            val knownCategory = vat?.let { known[it] }
            val suggestion = group.mapNotNull { suggestions.getOrNull(it.index) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            SupplierChoice(
                key = key, name = group.firstNotNullOfOrNull { it.value.supplierName }, vat = vat,
                category = knownCategory, known = knownCategory != null, suggestion = suggestion?.key,
            )
        }
        return base.copy(status = BundleStatus.SORTING, invoices = invoices, suppliers = suppliers)
    }

    /** The invoices that go to be read, in page order. */
    fun toQueue(plan: BundlePlan): List<PlannedInvoice> = plan.invoices.filter { inv -> inv.include && plan.supplierOf(inv)?.include != false }

    /** The steps to show; a step with nothing to do is left out. */
    fun steps(plan: BundlePlan): List<Step> = buildList {
        add(Step.FOUND)
        val queued = toQueue(plan)
        val suppliers = queued.map(::supplierKey).toSet()
        if (plan.suppliers.any { it.key in suppliers && !it.known }) add(Step.SUPPLIERS)
        if (plan.invoices.any { it.include && it.problems.isNotEmpty() }) add(Step.CHECK)
        add(Step.SUMMARY)
    }

    /** The suppliers of the file that still need a category (imported, new, not yet chosen). */
    fun toSort(plan: BundlePlan): List<SupplierChoice> {
        val queued = toQueue(plan).map(::supplierKey).toSet()
        return plan.suppliers.filter { it.key in queued && !it.known && !it.chosen }
    }

    fun canContinue(plan: BundlePlan, step: Step): Boolean = when (step) {
        Step.SUPPLIERS -> toSort(plan).isEmpty()
        Step.SUMMARY -> toQueue(plan).isNotEmpty()
        else -> true
    }

    fun setCategory(plan: BundlePlan, key: String, category: String?): BundlePlan =
        plan.copy(suppliers = plan.suppliers.map { if (it.key == key) it.copy(category = category) else it })

    fun setSupplierIncluded(plan: BundlePlan, key: String, include: Boolean): BundlePlan =
        plan.copy(suppliers = plan.suppliers.map { if (it.key == key) it.copy(include = include) else it })

    fun setInvoiceIncluded(plan: BundlePlan, index: Int, include: Boolean): BundlePlan =
        plan.copy(invoices = plan.invoices.mapIndexed { i, it ->
            if (i != index) it else it.copy(include = include, leftOut = if (include) null else (it.leftOut ?: LeftOut.BY_OPERATOR))
        })

    fun keep(plan: BundlePlan, index: Int): BundlePlan =
        plan.copy(invoices = plan.invoices.mapIndexed { i, it -> if (i == index) it.copy(checked = true) else it })

    /** The pages of invoice [index] belong to the one before it (the app took a page for the start of a new one). */
    fun joinWithPrevious(plan: BundlePlan, index: Int): BundlePlan {
        if (index <= 0 || index >= plan.invoices.size) return plan
        val prev = plan.invoices[index - 1]
        val cur = plan.invoices[index]
        val total = cur.totalCents ?: prev.totalCents
        val number = prev.number ?: cur.number
        val problems = (prev.problems + cur.problems).toMutableSet().apply {
            if (total != null) remove(InvoiceBundle.Problem.NO_TOTAL)
            if (number != null) remove(InvoiceBundle.Problem.NO_NUMBER)
            if (prev.supplierVat != null || prev.supplierName != null) remove(InvoiceBundle.Problem.NO_SUPPLIER)
            if (prev.totalCents != null && cur.totalCents != null) add(InvoiceBundle.Problem.SEVERAL_DOCUMENTS)
        }
        val merged = prev.copy(
            last = cur.last, supplierVat = prev.supplierVat ?: cur.supplierVat, supplierName = prev.supplierName ?: cur.supplierName,
            customerVat = prev.customerVat ?: cur.customerVat, number = number, date = prev.date ?: cur.date, totalCents = total,
            problems = problems, checked = false,
        )
        val invoices = plan.invoices.toMutableList().apply { this[index - 1] = merged; removeAt(index) }
        // A supplier with no invoice left goes.
        val keys = invoices.map(::supplierKey).toSet()
        return plan.copy(invoices = invoices, suppliers = plan.suppliers.filter { it.key in keys })
    }

    data class Summary(
        val invoices: Int,
        val suppliers: Int,
        val first: LocalDate?,
        val last: LocalDate?,
        /** Sum of the totals printed on the invoices going to be read, credit notes not included, in cents. */
        val totalCents: Long,
        val totalsMissing: Int,
        val byCategory: Map<String?, Int>,
        val leftOut: Map<LeftOut, Int>,
        val creditNotes: Int,
        /** Sum of the totals printed on the credit notes, as printed (never netted against the invoices). */
        val creditNoteCents: Long,
    )

    fun summary(plan: BundlePlan, of: List<PlannedInvoice> = toQueue(plan)): Summary {
        val dates = of.mapNotNull { it.date }
        return Summary(
            invoices = of.size,
            suppliers = of.map(::supplierKey).distinct().size,
            first = dates.minOrNull(), last = dates.maxOrNull(),
            totalCents = of.filter { !it.creditNote }.sumOf { it.totalCents ?: 0L },
            totalsMissing = of.count { it.totalCents == null },
            byCategory = of.groupingBy { plan.supplierOf(it)?.category?.takeIf { c -> c != SupplierChoice.LATER } }.eachCount(),
            leftOut = plan.invoices.filter { !it.include || plan.supplierOf(it)?.include == false }
                .groupingBy { it.leftOut ?: LeftOut.BY_OPERATOR }.eachCount(),
            creditNotes = of.count { it.creditNote },
            creditNoteCents = of.filter { it.creditNote }.sumOf { it.totalCents ?: 0L },
        )
    }
}

/** The plan as text (one value per line), so a file being imported survives the app being closed. */
object BundlePlanCodec {

    private fun esc(s: String?): String = s?.replace("\\", "\\\\")?.replace("|", "\\p")?.replace("\n", "\\n") ?: "\\0"
    private fun unesc(s: String): String? {
        if (s == "\\0") return null
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 'p' -> b.append('|'); 'n' -> b.append('\n'); '\\' -> b.append('\\'); else -> b.append(s[i + 1]) }
                i += 2
            } else { b.append(c); i++ }
        }
        return b.toString()
    }
    private fun fields(line: String): List<String?> {
        val out = mutableListOf<String?>()
        val cur = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) { cur.append(c).append(line[i + 1]); i += 2; continue }
            if (c == '|') { out += unesc(cur.toString()); cur.clear() } else cur.append(c)
            i++
        }
        out += unesc(cur.toString())
        return out
    }

    fun encode(p: BundlePlan): String = buildString {
        append("v=1\n")
        append("id=").append(esc(p.id)).append('\n')
        append("file=").append(esc(p.file)).append('\n')
        append("sha=").append(esc(p.fileSha)).append('\n')
        append("pages=").append(p.pageCount).append('\n')
        append("status=").append(p.status.name).append('\n')
        append("scanned=").append(p.scannedPages).append('\n')
        append("created=").append(p.createdAt).append('\n')
        append("queued=").append(p.queued).append('\n')
        append("saved=").append(p.saved).append('\n')
        append("discarded=").append(p.discarded).append('\n')
        for (i in p.invoices) {
            append("inv=").append(listOf(
                i.first.toString(), i.last.toString(), esc(i.supplierVat), esc(i.supplierName), esc(i.customerVat), esc(i.docType),
                esc(i.number), esc(i.date?.toString()), esc(i.totalCents?.toString()), i.problems.joinToString(",") { it.name },
                i.include.toString(), esc(i.leftOut?.name), esc(i.savedOn), i.checked.toString(),
            ).joinToString("|")).append('\n')
        }
        for (s in p.suppliers) {
            append("sup=").append(listOf(
                esc(s.key), esc(s.name), esc(s.vat), esc(s.category), s.known.toString(), esc(s.suggestion), s.include.toString(),
            ).joinToString("|")).append('\n')
        }
    }

    fun decode(text: String): BundlePlan? = runCatching {
        val single = mutableMapOf<String, String>()
        val invoices = mutableListOf<PlannedInvoice>()
        val suppliers = mutableListOf<SupplierChoice>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            val key = line.substringBefore('=')
            val value = line.substringAfter('=')
            when (key) {
                "inv" -> {
                    val f = fields(value)
                    invoices += PlannedInvoice(
                        first = f[0]!!.toInt(), last = f[1]!!.toInt(), supplierVat = f[2], supplierName = f[3], customerVat = f[4],
                        docType = f[5], number = f[6], date = f[7]?.let(LocalDate::parse), totalCents = f[8]?.toLong(),
                        problems = f[9].orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { n -> InvoiceBundle.Problem.entries.firstOrNull { it.name == n } }.toSet(),
                        include = f[10] == "true", leftOut = f[11]?.let { n -> LeftOut.entries.firstOrNull { it.name == n } },
                        savedOn = f.getOrNull(12), checked = f.getOrNull(13) == "true",
                    )
                }
                "sup" -> {
                    val f = fields(value)
                    suppliers += SupplierChoice(
                        key = f[0]!!, name = f[1], vat = f[2], category = f[3], known = f[4] == "true", suggestion = f[5], include = f.getOrNull(6) != "false",
                    )
                }
                else -> single[key] = value
            }
        }
        BundlePlan(
            id = unesc(single.getValue("id"))!!, file = unesc(single.getValue("file"))!!, fileSha = unesc(single["sha"] ?: "\\0") ?: "",
            pageCount = single["pages"]?.toIntOrNull() ?: 0,
            status = BundleStatus.entries.firstOrNull { it.name == single["status"] } ?: BundleStatus.CANCELLED,
            scannedPages = single["scanned"]?.toIntOrNull() ?: 0, invoices = invoices, suppliers = suppliers,
            createdAt = single["created"]?.toLongOrNull() ?: 0L, queued = single["queued"]?.toIntOrNull() ?: 0,
            saved = single["saved"]?.toIntOrNull() ?: 0, discarded = single["discarded"]?.toIntOrNull() ?: 0,
        )
    }.getOrNull()
}
