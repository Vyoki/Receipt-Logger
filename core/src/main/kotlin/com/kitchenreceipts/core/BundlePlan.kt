package com.kitchenreceipts.core

import java.time.LocalDate

/** Where an import of a file of many invoices is. */
enum class BundleStatus { SCANNING, NOT_SPLITTABLE, SORTING, PREPARING, QUEUED, PAUSED, DONE, CANCELLED, CLOSED }

/** What became of one invoice of the file. */
enum class InvoiceOutcome { WAITING, QUEUED, READY, SAVED_BY_APP, SAVED_BY_YOU, CHECK_LATER, STOPPED, FAILED, LEFT_OUT }

/** Why an invoice starts left out. The operator can take it back, except a credit note (never imported). */
enum class LeftOut { NOT_RECOGNISED, ALREADY_SAVED, CREDIT_NOTE, OTHER_BUYER, SEVERAL_IN_ONE, NEXT_IMPORT, BY_OPERATOR }

/** One invoice found in the file: its pages, what they print, and what became of it. */
data class PlannedInvoice(
    val first: Int,
    val last: Int,
    val kind: InvoiceBundle.Kind = InvoiceBundle.Kind.INVOICE,
    val start: InvoiceBundle.Start = InvoiceBundle.Start.ASSO,
    /** For a plan made from an e-invoice file: this invoice's own file. Null for pages of the PDF. */
    val file: String? = null,
    val supplierVat: String? = null,
    val supplierName: String? = null,
    val number: String? = null,
    val date: LocalDate? = null,
    /** As printed; only [totalProven] ones are added up. */
    val printedTotalCents: Long? = null,
    val totalProven: Boolean = false,
    val creditNote: Boolean = false,
    val flags: Set<InvoiceBundle.Flag> = emptySet(),
    val suggestion: SupplierCategory? = null,
    /** A saved document with the same supplier VAT number, number and date. */
    val alreadySavedId: Long? = null,
    /** When that document was saved (epoch milliseconds), when known. */
    val alreadySavedOn: Long? = null,
    /** A saved document with the same name, number and date, but no VAT number to compare: maybe the same one. */
    val maybeSavedId: Long? = null,
    val include: Boolean = true,
    val leftOut: LeftOut? = null,
    /** The operator looked at this flagged invoice and kept it as it is. */
    val checked: Boolean = false,
    val jobId: String? = null,
    val outcome: InvoiceOutcome = InvoiceOutcome.WAITING,
    val documentId: Long? = null,
    /** Its prices changed by 5% or more against the last purchase (one message for the whole file, not one each). */
    val pricesChanged: Boolean = false,
) {
    val pageCount: Int get() = last - first + 1
    val isInvoice: Boolean get() = kind == InvoiceBundle.Kind.INVOICE
}

/** One supplier of the file: one category for all its invoices. */
data class SupplierChoice(
    /** The VAT number, else the normalised name, else [BundleWizard.NO_SUPPLIER]. */
    val key: String,
    val name: String?,
    val vat: String?,
    /** A [SupplierCategory] key, [LATER] ("decide later"), or null while not chosen. */
    val category: String? = null,
    /** Its category is remembered by VAT number: nothing to ask. */
    val known: Boolean = false,
    /** The category chosen last time for the same name (no VAT number to be sure): shown, never applied. */
    val lastTime: String? = null,
    val suggestion: String? = null,
    val include: Boolean = true,
) {
    companion object {
        const val LATER = "later"
    }
}

data class BundlePlan(
    /** The file's SHA-256: the same file shared again reopens this plan. */
    val id: String,
    /** The app's copy of the file (its stored name). */
    val file: String,
    val pageCount: Int,
    val status: BundleStatus,
    /** Pages looked through so far, and their signals: a scan resumes from here. */
    val scanned: Int = 0,
    val signals: List<InvoiceBundle.PageSignals> = emptyList(),
    val invoices: List<PlannedInvoice> = emptyList(),
    val suppliers: List<SupplierChoice> = emptyList(),
    /** Choices made for suppliers that have no invoice right now (their pages were joined to another): a split that
     * brings them back brings back the choice. */
    val retired: List<SupplierChoice> = emptyList(),
    val step: BundleWizard.Step = BundleWizard.Step.FOUND,
    val createdAt: Long = 0L,
    /** A document saved earlier from this very file (read as one document): reading it again counts its invoices twice. */
    val sameFileDocumentId: Long? = null,
    /** The one message "Invoices read: N · Saved: N…" was posted for this reading (not again after a restart). */
    val announced: Boolean = false,
    /**
     * Files that earlier imports of this same file kept (its original, the e-invoices of invoices not imported), when
     * it was imported again after they ended: still kept, also when this import is cancelled (question 3).
     */
    val kept: List<String> = emptyList(),
) {
    val sameFileWarning: Boolean get() = sameFileDocumentId != null

    fun supplierOf(inv: PlannedInvoice): SupplierChoice? =
        if (!inv.isInvoice) null else suppliers.firstOrNull { it.key == BundleWizard.supplierKey(inv) }
}

/**
 * The decisions of importing a file of many invoices: what is found, which suppliers to sort, what is read and in
 * which order, and what became of each invoice. Nothing here is guessed: a category is the operator's choice (a
 * suggestion is only shown), an invoice already in the app or addressed to another business starts left out, and only
 * totals the VAT summary proves are added up.
 */
object BundleWizard {

    /** At most this many invoices are planned at once; the rest wait for the same file to be imported again. */
    const val MAX_INVOICES = 300
    const val NO_SUPPLIER = "?"

    /** The wizard's steps. [PAGES] (where each invoice starts) is opened from [FOUND] and has no number of its own. */
    enum class Step { FOUND, PAGES, SUPPLIERS, READ }

    /** A document already saved in the app, as the duplicate check sees it. */
    data class Saved(
        val id: Long,
        val sellerVat: String?,
        val sellerName: String?,
        val number: String?,
        val date: LocalDate?,
        val fileSha256: String?,
        val savedAt: Long? = null,
    )

    fun supplierKey(inv: PlannedInvoice): String =
        digits(inv.supplierVat) ?: inv.supplierVat?.uppercase()?.filterNot(Char::isWhitespace)
            ?: DuplicateDetector.normalizeSeller(inv.supplierName) ?: NO_SUPPLIER

    // ------------------------------------------------------------------ the plan

    /**
     * The plan from the scan. [memory]: the remembered categories by memory key ([SupplierCategories.memoryKeys]);
     * [saved]: the documents already in the app.
     */
    fun plan(
        base: BundlePlan,
        segments: List<InvoiceBundle.Segment>,
        memory: Map<String, String> = emptyMap(),
        saved: List<Saved> = emptyList(),
    ): BundlePlan = prepare(base, segments.map { invoiceOf(it, base.signals) }, memory, saved)

    /** The plan from invoices already known (an e-invoice file of many invoices), or from [plan]. */
    fun prepare(base: BundlePlan, invoices: List<PlannedInvoice>, memory: Map<String, String> = emptyMap(), saved: List<Saved> = emptyList()): BundlePlan {
        val marked = alreadySaved(base.copy(invoices = invoices), saved)
        var n = 0
        val ticked = marked.invoices.map(::withDefaultTick).map {
            if (it.include && ++n > MAX_INVOICES) it.copy(include = false, leftOut = LeftOut.NEXT_IMPORT) else it
        }
        return marked.copy(status = BundleStatus.SORTING, step = Step.FOUND, invoices = ticked, suppliers = choices(ticked, memory, emptyList()))
    }

    private fun invoiceOf(s: InvoiceBundle.Segment, signals: List<InvoiceBundle.PageSignals>) = PlannedInvoice(
        first = s.first, last = s.last, kind = s.kind, start = s.start,
        supplierVat = s.supplierVat, supplierName = s.supplierName, number = s.number, date = s.date,
        printedTotalCents = s.printedTotalCents, totalProven = s.totalProven, creditNote = s.creditNote, flags = s.flags,
        suggestion = if (s.kind == InvoiceBundle.Kind.INVOICE) SupplierCategories.suggest(signals.filter { it.index in s.first..s.last }) else null,
    )

    /** Credit notes, invoices to another business, several invoices in one, and unrecognised pages start left out. */
    private fun withDefaultTick(inv: PlannedInvoice): PlannedInvoice {
        val reason = when {
            !inv.isInvoice -> LeftOut.NOT_RECOGNISED
            inv.alreadySavedId != null -> LeftOut.ALREADY_SAVED
            inv.creditNote -> LeftOut.CREDIT_NOTE
            InvoiceBundle.Flag.OTHER_BUYER in inv.flags -> LeftOut.OTHER_BUYER
            InvoiceBundle.Flag.SEVERAL_IN_ONE in inv.flags -> LeftOut.SEVERAL_IN_ONE
            else -> null
        }
        return inv.copy(include = reason == null, leftOut = reason)
    }

    private fun choices(invoices: List<PlannedInvoice>, memory: Map<String, String>, previous: List<SupplierChoice>): List<SupplierChoice> =
        invoices.filter { it.isInvoice }.groupBy(::supplierKey).map { (key, group) ->
            val vat = group.firstNotNullOfOrNull { it.supplierVat }
            val name = group.firstNotNullOfOrNull { it.supplierName }
            val suggestion = group.mapNotNull { it.suggestion }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.key
            previous.firstOrNull { it.key == key }?.let { return@map it.copy(suggestion = suggestion, name = it.name ?: name, vat = it.vat ?: vat) }
            fun remembered(prefix: String) = SupplierCategories.memoryKeys(vat, name).firstOrNull { it.startsWith(prefix) }
                ?.let { memory[it] }?.takeIf { SupplierCategory.fromKey(it) != null }
            val byVat = remembered(SupplierCategories.VAT_PREFIX)
            SupplierChoice(
                key = key, name = name, vat = vat, category = byVat, known = byVat != null,
                lastTime = if (byVat == null) remembered(SupplierCategories.NAME_PREFIX) else null, suggestion = suggestion,
            )
        }

    /**
     * Marks the invoices already in the app: same supplier VAT number, same number (normalised) and same date. A match
     * by name only (no VAT number on one side) is a "maybe" and stays ticked. A document saved from this very file
     * (the whole file read as one document) never marks an invoice: it sets [BundlePlan.sameFileDocumentId] instead.
     * An invoice newly found saved is left out; the operator's ticks are kept otherwise.
     */
    fun alreadySaved(plan: BundlePlan, saved: List<Saved>): BundlePlan {
        fun sameFile(s: Saved) = s.fileSha256 != null && s.fileSha256.equals(plan.id, ignoreCase = true)
        val others = saved.filterNot(::sameFile)
        val invoices = plan.invoices.map { inv ->
            val num = DuplicateDetector.normalizeNumber(inv.number)
            val date = inv.date
            if (!inv.isInvoice || num == null || date == null) return@map inv.copy(alreadySavedId = null, alreadySavedOn = null, maybeSavedId = null)
            val vat = digits(inv.supplierVat)
            val same = others.filter { DuplicateDetector.normalizeNumber(it.number) == num && it.date == date }
            val match = same.firstOrNull { vat != null && digits(it.sellerVat) == vat }
            val name = DuplicateDetector.normalizeSeller(inv.supplierName)
            val maybe = if (match != null || name == null) null
                else same.firstOrNull { (vat == null || digits(it.sellerVat) == null) && DuplicateDetector.normalizeSeller(it.sellerName) == name }
            val out = inv.copy(alreadySavedId = match?.id, alreadySavedOn = match?.savedAt, maybeSavedId = maybe?.id)
            if (match != null && inv.alreadySavedId == null && inv.include) out.copy(include = false, leftOut = LeftOut.ALREADY_SAVED) else out
        }
        return plan.copy(invoices = invoices, sameFileDocumentId = saved.firstOrNull(::sameFile)?.id)
    }

    private fun digits(vat: String?): String? = vat?.filter(Char::isDigit)?.takeIf { it.length == 11 }

    // ------------------------------------------------------------------ the steps

    /** The invoices that go to be read: ticked, and their supplier not left out. In page order. */
    fun toRead(plan: BundlePlan): List<PlannedInvoice> = plan.invoices.filter { isRead(plan, it) }

    private fun isRead(plan: BundlePlan, inv: PlannedInvoice) = inv.include && plan.supplierOf(inv)?.include != false

    /** Suppliers of ticked invoices whose category is not remembered by VAT number (the Suppliers step). */
    fun newSuppliers(plan: BundlePlan): List<SupplierChoice> = plan.suppliers.filter { s ->
        s.key != NO_SUPPLIER && !s.known && plan.invoices.any { it.isInvoice && it.include && supplierKey(it) == s.key }
    }

    /** Suppliers of ticked invoices already sorted by VAT number (folded into "N suppliers already sorted"). */
    fun knownSuppliers(plan: BundlePlan): List<SupplierChoice> = plan.suppliers.filter { s ->
        s.known && plan.invoices.any { it.isInvoice && it.include && supplierKey(it) == s.key }
    }

    /** The new suppliers still without a choice. */
    fun toSort(plan: BundlePlan): List<SupplierChoice> = newSuppliers(plan).filter { it.include && it.category == null }

    /** The numbered steps: Found and Read always, Suppliers only when there is a supplier to sort. */
    fun steps(plan: BundlePlan): List<Step> = buildList {
        add(Step.FOUND)
        if (newSuppliers(plan).isNotEmpty()) add(Step.SUPPLIERS)
        add(Step.READ)
    }

    /** "Step n of N" for a shown step (the page check shares Found's number), or null. */
    fun numberOf(plan: BundlePlan, step: Step): Int? =
        steps(plan).indexOf(if (step == Step.PAGES) Step.FOUND else step).takeIf { it >= 0 }?.plus(1)

    fun canContinue(plan: BundlePlan, step: Step): Boolean = when (step) {
        Step.READ -> toRead(plan).isNotEmpty()
        // The suppliers left without a choice are decided later.
        else -> true
    }

    fun setCategory(plan: BundlePlan, key: String, category: String?): BundlePlan =
        plan.copy(suppliers = plan.suppliers.map { if (it.key == key) it.copy(category = category) else it })

    /** The suppliers a suggestion can be used for ("Use the suggestion for N suppliers"). */
    fun suggestable(plan: BundlePlan): List<SupplierChoice> = toSort(plan).filter { it.suggestion != null }

    fun useSuggestions(plan: BundlePlan): BundlePlan {
        val keys = suggestable(plan).map { it.key }.toSet()
        return plan.copy(suppliers = plan.suppliers.map { if (it.key in keys) it.copy(category = it.suggestion) else it })
    }

    fun setSupplierIncluded(plan: BundlePlan, key: String, include: Boolean): BundlePlan =
        plan.copy(suppliers = plan.suppliers.map { if (it.key == key) it.copy(include = include) else it })

    /** Tick or untick one invoice. A credit note is never imported (money back is not a purchase). */
    fun setInvoiceIncluded(plan: BundlePlan, index: Int, include: Boolean): BundlePlan {
        val inv = plan.invoices.getOrNull(index) ?: return plan
        if (include && (inv.creditNote || (!inv.include && toRead(plan).size >= MAX_INVOICES))) return plan
        return plan.copy(invoices = plan.invoices.mapIndexed { i, it ->
            if (i != index) it else it.copy(include = include, leftOut = if (include) null else (it.leftOut ?: LeftOut.BY_OPERATOR))
        })
    }

    /** The operator looked at a flagged invoice and keeps it as it is. */
    fun keep(plan: BundlePlan, index: Int): BundlePlan =
        plan.copy(invoices = plan.invoices.mapIndexed { i, it -> if (i == index) it.copy(checked = true) else it })

    /** The invoice needs a look in the page check (its start, end, supplier or text). */
    fun needsLook(inv: PlannedInvoice): Boolean = inv.isInvoice && !inv.checked &&
        (inv.flags - InvoiceBundle.Flag.OTHER_BUYER).isNotEmpty() && (inv.include || inv.leftOut == LeftOut.SEVERAL_IN_ONE)

    /** "These pages belong to the invoice before": every field worked out again from the pages' signals. */
    fun joinWithPrevious(plan: BundlePlan, index: Int, ownVat: String? = null, memory: Map<String, String> = emptyMap()): BundlePlan {
        if (index <= 0 || index >= plan.invoices.size || plan.signals.isEmpty()) return plan
        val starts = plan.invoices.filterIndexed { i, _ -> i != index }.map { it.first to it.start }
        return replan(plan, InvoiceBundle.rebuild(plan.signals, starts, ownVat), memory)
    }

    /** "A new invoice starts here" on [page]: every field worked out again from the pages' signals. */
    fun splitAt(plan: BundlePlan, page: Int, ownVat: String? = null, memory: Map<String, String> = emptyMap()): BundlePlan {
        if (plan.signals.isEmpty() || page <= 0 || plan.invoices.any { it.first == page } || plan.invoices.none { page in it.first..it.last }) return plan
        val starts = plan.invoices.map { it.first to it.start } + (page to InvoiceBundle.Start.BY_HAND)
        return replan(plan, InvoiceBundle.rebuild(plan.signals, starts, ownVat), memory)
    }

    /** Invoices whose pages did not change keep everything the operator did; the others start again. */
    private fun replan(plan: BundlePlan, segments: List<InvoiceBundle.Segment>, memory: Map<String, String>): BundlePlan {
        val invoices = segments.map { s ->
            plan.invoices.firstOrNull { it.first == s.first && it.last == s.last && it.kind == s.kind && it.start == s.start }
                ?: withDefaultTick(invoiceOf(s, plan.signals))
        }
        // A supplier whose invoices are joined away keeps its choice aside, and gets it back when they come back.
        val previous = plan.suppliers + plan.retired.filter { r -> plan.suppliers.none { it.key == r.key } }
        val suppliers = choices(invoices, memory, previous)
        return plan.copy(invoices = invoices, suppliers = suppliers, retired = previous.filter { p -> suppliers.none { it.key == p.key } })
    }

    // ------------------------------------------------------------------ what the screens show

    data class Found(
        val invoices: Int,
        val suppliers: Int,
        val first: LocalDate?,
        val last: LocalDate?,
        val alreadySaved: Int,
        val maybeSaved: Int,
        val creditNotes: Int,
        val otherBuyer: Int,
        /** Pages that are no invoice the app recognises. */
        val unknownPages: Int,
        val needLook: Int,
        val newSuppliers: Int,
    )

    /** The first step: "This file holds N invoices from M suppliers, dated ...", and its rows. */
    fun found(plan: BundlePlan): Found {
        val inv = plan.invoices.filter { it.isInvoice }
        val dates = inv.mapNotNull { it.date }
        return Found(
            invoices = inv.size,
            suppliers = inv.map(::supplierKey).filter { it != NO_SUPPLIER }.distinct().size,
            first = dates.minOrNull(), last = dates.maxOrNull(),
            alreadySaved = inv.count { it.alreadySavedId != null && !it.include },
            maybeSaved = inv.count { it.maybeSavedId != null },
            creditNotes = inv.count { it.creditNote },
            otherBuyer = inv.count { it.leftOut == LeftOut.OTHER_BUYER },
            unknownPages = plan.invoices.filter { !it.isInvoice && !it.include }.sumOf { it.pageCount },
            needLook = inv.count(::needsLook),
            newSuppliers = newSuppliers(plan).size,
        )
    }

    data class SupplierGroup(
        val supplier: SupplierChoice,
        /** The invoices to read (credit notes apart). */
        val invoices: Int,
        val first: LocalDate?,
        val last: LocalDate?,
        /** The printed totals the VAT summary proves, added up. */
        val provenCents: Long,
        /** Invoices whose total was not found or not proven: "+ N not found/not checked". */
        val notProven: Int,
        val creditNotes: Int,
        /** Proven credit-note totals as printed: shown apart, never added to or taken from the invoices. */
        val creditNoteCents: Long,
        val creditNotesNotProven: Int,
    ) {
        /** Every invoice's total is proven: the sum can be shown on its own. */
        val sumComplete: Boolean get() = notProven == 0
    }

    fun groupBySupplier(plan: BundlePlan): List<SupplierGroup> = plan.suppliers.map { s ->
        val mine = plan.invoices.filter { it.isInvoice && supplierKey(it) == s.key }
        val inv = mine.filter { !it.creditNote && it.include }
        val credits = mine.filter { it.creditNote }
        val dates = inv.mapNotNull { it.date }
        SupplierGroup(
            supplier = s, invoices = inv.size, first = dates.minOrNull(), last = dates.maxOrNull(),
            provenCents = inv.filter { it.totalProven }.sumOf { it.printedTotalCents ?: 0L }, notProven = inv.count { !it.totalProven || it.printedTotalCents == null },
            creditNotes = credits.size, creditNoteCents = credits.filter { it.totalProven }.sumOf { it.printedTotalCents ?: 0L },
            creditNotesNotProven = credits.count { !it.totalProven || it.printedTotalCents == null },
        )
    }

    data class Summary(
        val invoices: Int,
        val suppliers: Int,
        val first: LocalDate?,
        val last: LocalDate?,
        val provenCents: Long,
        val notProven: Int,
        /** Invoices to read by the supplier's category key (null: not sorted, or decide later). */
        val byCategory: Map<String?, Int>,
        val leftOut: Map<LeftOut, Int>,
        val creditNotes: Int,
    )

    /** The Read step: what goes to be read, by category, and what is left out. */
    fun summary(plan: BundlePlan): Summary {
        val read = toRead(plan)
        val dates = read.mapNotNull { it.date }
        return Summary(
            invoices = read.size,
            suppliers = read.map(::supplierKey).filter { it != NO_SUPPLIER }.distinct().size,
            first = dates.minOrNull(), last = dates.maxOrNull(),
            provenCents = read.filter { it.totalProven }.sumOf { it.printedTotalCents ?: 0L },
            notProven = read.count { !it.totalProven || it.printedTotalCents == null },
            byCategory = read.groupingBy { categoryOf(plan, it) }.eachCount(),
            leftOut = plan.invoices.filter { !isRead(plan, it) }.groupingBy { it.leftOut ?: LeftOut.BY_OPERATOR }.eachCount(),
            creditNotes = plan.invoices.count { it.isInvoice && it.creditNote },
        )
    }

    /** The category chosen for the invoice's supplier, or null (not sorted, or decide later). */
    fun categoryOf(plan: BundlePlan, inv: PlannedInvoice): String? =
        plan.supplierOf(inv)?.category?.takeIf { SupplierCategory.fromKey(it) != null }

    data class Estimate(val newSuppliers: Int, val notGoods: Int, val flagged: Int) {
        /** "At least N will need your check": each invoice counted once. */
        val atLeast: Int get() = newSuppliers + notGoods + flagged
    }

    /**
     * The invoices that will surely need the operator's check: the first invoice of each new supplier, then the bills of
     * suppliers that sell no goods (electricity, fuel, repairs, other), then the flagged ones (each counted once).
     */
    fun estimate(plan: BundlePlan): Estimate {
        val order = planOrder(plan)
        val first = firstsOfNewSuppliers(plan, order).toSet()
        val notGoods = order.filter { it !in first && SupplierCategory.fromKey(categoryOf(plan, plan.invoices[it]))?.goods == false }.toSet()
        val flagged = order.count { it !in first && it !in notGoods && plan.invoices[it].let { inv -> inv.flags.isNotEmpty() || !inv.isInvoice } }
        return Estimate(first.size, notGoods.size, flagged)
    }

    /**
     * The order the invoices are read in (indexes into [BundlePlan.invoices]): the first invoice of each supplier not
     * known by VAT number first (once it is saved, that supplier is known and its other invoices check out more often),
     * then the rest in page order.
     */
    fun planOrder(plan: BundlePlan): List<Int> {
        val read = plan.invoices.indices.filter { isRead(plan, plan.invoices[it]) }
        val first = firstsOfNewSuppliers(plan, read)
        return first + read.filter { it !in first.toSet() }
    }

    private fun firstsOfNewSuppliers(plan: BundlePlan, order: List<Int>): List<Int> = order.filter { i ->
        val s = plan.supplierOf(plan.invoices[i])
        s != null && s.key != NO_SUPPLIER && !s.known
    }.distinctBy { supplierKey(plan.invoices[it]) }

    // ------------------------------------------------------------------ reading and its outcomes

    /** The operator taps Read: the invoices not read are marked left out. */
    fun confirm(plan: BundlePlan): BundlePlan = plan.copy(
        status = BundleStatus.PREPARING, step = Step.READ,
        invoices = plan.invoices.map { inv -> if (isRead(plan, inv)) inv.copy(outcome = InvoiceOutcome.WAITING) else inv.copy(outcome = InvoiceOutcome.LEFT_OUT) },
    )

    /** The invoices were split and queued: [jobs] maps an invoice's index to its job. */
    fun queued(plan: BundlePlan, jobs: Map<Int, String>): BundlePlan = plan.copy(
        status = BundleStatus.QUEUED,
        invoices = plan.invoices.mapIndexed { i, inv -> jobs[i]?.let { inv.copy(jobId = it, outcome = InvoiceOutcome.QUEUED) } ?: inv },
    )

    private val SAVED = setOf(InvoiceOutcome.SAVED_BY_APP, InvoiceOutcome.SAVED_BY_YOU)
    private val OPEN = setOf(InvoiceOutcome.WAITING, InvoiceOutcome.QUEUED, InvoiceOutcome.READY, InvoiceOutcome.CHECK_LATER)

    /**
     * A job of the plan moved on. A saved invoice stays saved (a late event never undoes it). When nothing is left to
     * read or to check, the plan is done.
     */
    fun record(plan: BundlePlan, jobId: String, outcome: InvoiceOutcome, documentId: Long? = null): BundlePlan {
        val invoices = plan.invoices.map { inv ->
            if (inv.jobId != jobId || (inv.outcome in SAVED && outcome !in SAVED)) inv
            else inv.copy(outcome = outcome, documentId = documentId ?: inv.documentId)
        }
        val reading = plan.status == BundleStatus.QUEUED || plan.status == BundleStatus.PAUSED
        val done = reading && invoices.none { it.outcome in OPEN }
        return plan.copy(invoices = invoices, status = if (done) BundleStatus.DONE else plan.status)
    }

    /** "Check later": to the end of the chain. */
    fun checkLater(plan: BundlePlan, index: Int): BundlePlan = plan.copy(invoices = plan.invoices.mapIndexed { i, inv ->
        if (i == index && inv.outcome == InvoiceOutcome.READY) inv.copy(outcome = InvoiceOutcome.CHECK_LATER) else inv
    })

    /** The jobs "Stop reading this file" removes: not saved yet. */
    fun unsavedJobs(plan: BundlePlan): List<String> = plan.invoices.filter { it.outcome in OPEN }.mapNotNull { it.jobId }

    /** Stop reading this file: what is not saved yet is stopped; saved invoices are untouched. */
    fun stop(plan: BundlePlan): BundlePlan = plan.copy(
        // The operator stopped it: no "all read" message follows.
        status = BundleStatus.DONE, announced = true,
        invoices = plan.invoices.map { if (it.outcome in OPEN) it.copy(outcome = InvoiceOutcome.STOPPED, jobId = null) else it },
    )

    /** "Read the N not read yet": the stopped invoices go to be read again. */
    fun readRest(plan: BundlePlan): BundlePlan = plan.copy(
        status = BundleStatus.PREPARING, announced = false,
        invoices = plan.invoices.map { if (it.outcome == InvoiceOutcome.STOPPED) it.copy(outcome = InvoiceOutcome.WAITING, jobId = null) else it },
    )

    data class Counts(
        /** Invoices that go (or went) to be read. */
        val total: Int,
        val read: Int,
        val savedByApp: Int,
        val savedByYou: Int,
        val toCheck: Int,
        /** Still to be read. */
        val reading: Int,
        val failed: Int,
        val stopped: Int,
        val leftOut: Int,
    ) {
        val saved: Int get() = savedByApp + savedByYou
    }

    /** The Home card and the messages: always from the plan's outcomes, never from the job list. */
    fun counts(plan: BundlePlan): Counts {
        val by = plan.invoices.groupingBy { it.outcome }.eachCount()
        fun n(vararg o: InvoiceOutcome) = o.sumOf { by[it] ?: 0 }
        return Counts(
            total = plan.invoices.size - n(InvoiceOutcome.LEFT_OUT),
            read = n(InvoiceOutcome.READY, InvoiceOutcome.CHECK_LATER, InvoiceOutcome.SAVED_BY_APP, InvoiceOutcome.SAVED_BY_YOU, InvoiceOutcome.FAILED),
            savedByApp = n(InvoiceOutcome.SAVED_BY_APP), savedByYou = n(InvoiceOutcome.SAVED_BY_YOU),
            toCheck = n(InvoiceOutcome.READY, InvoiceOutcome.CHECK_LATER),
            reading = n(InvoiceOutcome.WAITING, InvoiceOutcome.QUEUED),
            failed = n(InvoiceOutcome.FAILED), stopped = n(InvoiceOutcome.STOPPED), leftOut = n(InvoiceOutcome.LEFT_OUT),
        )
    }

    /** The review chain: invoices ready to check in plan order, then the ones put off with "Check later". */
    fun chain(plan: BundlePlan): List<Int> {
        val order = planOrder(plan)
        return order.filter { plan.invoices[it].outcome == InvoiceOutcome.READY } + order.filter { plan.invoices[it].outcome == InvoiceOutcome.CHECK_LATER }
    }

    /** "Invoice n of N to check": n (from 1) and N, or null when [index] is not to check. */
    fun positionInChain(plan: BundlePlan, index: Int): Pair<Int, Int>? {
        val c = chain(plan)
        val at = c.indexOf(index)
        return if (at < 0) null else (at + 1) to c.size
    }

    /**
     * The next invoice to check after [after] (an index), going round; null when nothing is ready. [skip]: invoices not
     * to open now (the app is checking them again).
     */
    fun nextToCheck(plan: BundlePlan, after: Int? = null, skip: (Int) -> Boolean = { false }): Int? {
        val order = planOrder(plan)
        val pos = after?.let { order.indexOf(it) } ?: -1
        val ready = order.filter { it != after && !skip(it) && plan.invoices[it].outcome == InvoiceOutcome.READY }
        return ready.firstOrNull { order.indexOf(it) > pos } ?: ready.firstOrNull()
            ?: order.firstOrNull { it != after && !skip(it) && plan.invoices[it].outcome == InvoiceOutcome.CHECK_LATER }
    }

    /** [nextToCheck] by job: after the invoice of job [afterJob] (from the start when null), skipping the jobs [skip]. */
    fun nextJob(plan: BundlePlan, afterJob: String? = null, skip: Set<String> = emptySet()): String? {
        val after = afterJob?.let { j -> plan.invoices.indexOfFirst { it.jobId == j } }?.takeIf { it >= 0 }
        return nextToCheck(plan, after) { plan.invoices[it].jobId.let { j -> j == null || j in skip } }?.let { plan.invoices[it].jobId }
    }

    /** The prices of the invoice of job [jobId] changed (see [PlannedInvoice.pricesChanged]). */
    fun pricesChanged(plan: BundlePlan, jobId: String): BundlePlan =
        plan.copy(invoices = plan.invoices.map { if (it.jobId == jobId && it.isInvoice) it.copy(pricesChanged = true) else it })

    /**
     * The file was read: nothing waits to be read any more, at least one invoice was read, and its one message was not
     * posted yet. Invoices still to check do not hold it back (the message counts them).
     */
    fun toAnnounce(plan: BundlePlan): Boolean {
        if (plan.announced || plan.status !in setOf(BundleStatus.QUEUED, BundleStatus.PAUSED, BundleStatus.DONE)) return false
        val read = setOf(InvoiceOutcome.READY, InvoiceOutcome.CHECK_LATER, InvoiceOutcome.SAVED_BY_APP, InvoiceOutcome.SAVED_BY_YOU, InvoiceOutcome.FAILED)
        return plan.invoices.none { it.outcome == InvoiceOutcome.WAITING || it.outcome == InvoiceOutcome.QUEUED } && plan.invoices.any { it.outcome in read }
    }

    fun announce(plan: BundlePlan): BundlePlan = plan.copy(announced = true)

    /**
     * The operator just saved the invoice of job [savedJob]. When it is the first of its supplier they saved in this
     * file, that supplier is now known (by its VAT number), so its other invoices waiting to be checked are worth
     * checking again: their jobs, in plan order. Only by VAT number, the proof the registry gives.
     */
    fun recheckAfterSave(plan: BundlePlan, savedJob: String): List<String> {
        val at = plan.invoices.indexOfFirst { it.jobId == savedJob }.takeIf { it >= 0 } ?: return emptyList()
        val vat = digits(plan.invoices[at].supplierVat) ?: return emptyList()
        val same = plan.invoices.indices.filter { it != at && plan.invoices[it].isInvoice && digits(plan.invoices[it].supplierVat) == vat }
        if (same.any { plan.invoices[it].outcome == InvoiceOutcome.SAVED_BY_YOU }) return emptyList()
        val waiting = setOf(InvoiceOutcome.READY, InvoiceOutcome.CHECK_LATER)
        return planOrder(plan).filter { it in same && plan.invoices[it].outcome in waiting }.mapNotNull { plan.invoices[it].jobId }
    }

    /** The plan's own files: the file of many invoices, and each invoice's own e-invoice. */
    fun ownFiles(plan: BundlePlan): List<String> = listOfNotNull(plan.file.ifBlank { null }) + plan.invoices.mapNotNull { it.file }

    /**
     * The files the plan keeps on the phone: its own, unless it was cancelled, and always the ones earlier imports of
     * the same file kept.
     */
    fun files(plan: BundlePlan): Set<String> =
        (plan.kept + if (plan.status == BundleStatus.CANCELLED) emptyList() else ownFiles(plan)).toSet()

    /**
     * A new plan [new] for a file whose plan [earlier] it replaces (the same file imported again after its import
     * ended): what the earlier one kept is kept by the new one, so nothing the earlier import kept is ever removed
     * with the new one.
     */
    fun carryKept(new: BundlePlan, earlier: BundlePlan): BundlePlan =
        new.copy(kept = (new.kept + files(earlier)).distinct())

    /** The Done screen: saved invoices by category key (null: not sorted). */
    fun savedByCategory(plan: BundlePlan): Map<String?, Int> =
        plan.invoices.filter { it.outcome in SAVED }.groupingBy { categoryOf(plan, it) }.eachCount()

    /**
     * A closed or cancelled plan keeps only the pages, outcomes and document ids: supplier names, VAT numbers,
     * document numbers, dates, totals and the pages' signals are dropped.
     */
    fun close(plan: BundlePlan, status: BundleStatus = BundleStatus.CLOSED): BundlePlan = plan.copy(
        status = status, signals = emptyList(), suppliers = emptyList(), retired = emptyList(),
        invoices = plan.invoices.map {
            PlannedInvoice(
                first = it.first, last = it.last, kind = it.kind, start = it.start, file = it.file, include = it.include, leftOut = it.leftOut,
                alreadySavedId = it.alreadySavedId, jobId = it.jobId, outcome = it.outcome, documentId = it.documentId,
            )
        },
    )
}

/**
 * The plan as text, one record per line, so an import survives the app being closed. Versioned; a reader skips keys
 * it does not know, and a field left out takes its default.
 */
object BundlePlanCodec {

    const val VERSION = 2

    fun encode(p: BundlePlan): String = buildString {
        fun line(k: String, v: Any?) { if (v != null) append(k).append('=').append(BundleText.esc(v.toString())).append('\n') }
        // A record is one line already (its fields escaped).
        fun record(k: String, r: String) { append(k).append('=').append(r).append('\n') }
        line("v", VERSION)
        line("id", p.id)
        line("file", p.file)
        line("pages", p.pageCount)
        line("status", p.status.name)
        line("scanned", p.scanned)
        line("created", p.createdAt)
        line("step", p.step.name)
        line("samefile", p.sameFileDocumentId)
        line("ann", flag(p.announced))
        for (f in p.kept) line("kept", f)
        for (s in p.signals) record("sig", InvoiceBundle.encode(s))
        for (i in p.invoices) record("inv", BundleText.record(
            "f" to i.first.toString(), "l" to i.last.toString(),
            "k" to i.kind.takeIf { it != InvoiceBundle.Kind.INVOICE }?.name, "s" to i.start.name, "fi" to i.file,
            "sv" to i.supplierVat, "sn" to i.supplierName, "n" to i.number, "d" to i.date?.toString(),
            "pt" to i.printedTotalCents?.toString(), "pr" to flag(i.totalProven), "cn" to flag(i.creditNote),
            "fl" to i.flags.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }, "sg" to i.suggestion?.key,
            "as" to i.alreadySavedId?.toString(), "ao" to i.alreadySavedOn?.toString(), "ms" to i.maybeSavedId?.toString(),
            "in" to if (i.include) null else "0", "lo" to i.leftOut?.name, "ck" to flag(i.checked),
            "j" to i.jobId, "o" to i.outcome.takeIf { it != InvoiceOutcome.WAITING }?.name, "doc" to i.documentId?.toString(),
            "pc" to flag(i.pricesChanged),
        ))
        for ((k, list) in listOf("sup" to p.suppliers, "ret" to p.retired)) for (s in list) record(k, BundleText.record(
            "k" to s.key, "n" to s.name, "v" to s.vat, "c" to s.category, "kn" to flag(s.known), "lt" to s.lastTime,
            "sg" to s.suggestion, "in" to if (s.include) null else "0",
        ))
    }

    fun decode(text: String): BundlePlan? = runCatching {
        val single = mutableMapOf<String, String>()
        val signals = mutableListOf<InvoiceBundle.PageSignals>()
        val invoices = mutableListOf<PlannedInvoice>()
        val suppliers = mutableListOf<SupplierChoice>()
        val retired = mutableListOf<SupplierChoice>()
        val kept = mutableListOf<String>()
        for (raw in text.lines()) {
            val eq = raw.indexOf('=')
            if (raw.isBlank() || eq <= 0) continue
            val key = raw.substring(0, eq)
            val value = raw.substring(eq + 1)
            when (key) {
                "kept" -> BundleText.unesc(value).takeIf { it.isNotBlank() }?.let { kept += it }
                "sig" -> InvoiceBundle.decode(value)?.let { signals += it }
                "inv" -> invoice(BundleText.fields(value))?.let { invoices += it }
                "sup" -> supplier(BundleText.fields(value))?.let { suppliers += it }
                "ret" -> supplier(BundleText.fields(value))?.let { retired += it }
                else -> single[key] = BundleText.unesc(value)
            }
        }
        BundlePlan(
            id = single.getValue("id"), file = single.getValue("file"),
            pageCount = single["pages"]?.toIntOrNull() ?: 0,
            // A status this version does not know (a newer app's): DONE reads nothing more and keeps the file.
            status = enumOr(single["status"], BundleStatus.DONE),
            scanned = single["scanned"]?.toIntOrNull() ?: 0, signals = signals, invoices = invoices, suppliers = suppliers, retired = retired,
            step = enumOr(single["step"], BundleWizard.Step.FOUND),
            createdAt = single["created"]?.toLongOrNull() ?: 0L, sameFileDocumentId = single["samefile"]?.toLongOrNull(),
            announced = single["ann"] == "1", kept = kept,
        )
    }.getOrNull()

    private fun invoice(f: Map<String, String?>): PlannedInvoice? = runCatching {
        PlannedInvoice(
            first = f.getValue("f")!!.toInt(), last = f.getValue("l")!!.toInt(),
            kind = enumOr(f["k"], InvoiceBundle.Kind.INVOICE), start = enumOr(f["s"], InvoiceBundle.Start.ASSO), file = f["fi"],
            supplierVat = f["sv"], supplierName = f["sn"], number = f["n"], date = f["d"]?.let(LocalDate::parse),
            printedTotalCents = f["pt"]?.toLong(), totalProven = f["pr"] == "1", creditNote = f["cn"] == "1",
            flags = f["fl"].orEmpty().split(',').mapNotNull { n -> InvoiceBundle.Flag.entries.firstOrNull { it.name == n } }.toSet(),
            suggestion = SupplierCategory.fromKey(f["sg"]),
            alreadySavedId = f["as"]?.toLong(), alreadySavedOn = f["ao"]?.toLong(), maybeSavedId = f["ms"]?.toLong(),
            include = f["in"] != "0", leftOut = f["lo"]?.let { n -> LeftOut.entries.firstOrNull { it.name == n } }, checked = f["ck"] == "1",
            jobId = f["j"], outcome = enumOr(f["o"], InvoiceOutcome.WAITING), documentId = f["doc"]?.toLong(),
            pricesChanged = f["pc"] == "1",
        )
    }.getOrNull()

    private fun supplier(f: Map<String, String?>): SupplierChoice? = f["k"]?.let { key ->
        SupplierChoice(
            key = key, name = f["n"], vat = f["v"], category = f["c"], known = f["kn"] == "1", lastTime = f["lt"],
            suggestion = f["sg"], include = f["in"] != "0",
        )
    }

    private fun flag(b: Boolean): String? = if (b) "1" else null

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
