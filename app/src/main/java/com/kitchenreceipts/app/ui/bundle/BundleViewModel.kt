package com.kitchenreceipts.app.ui.bundle

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.AppContainer
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.jobs.BundleStore
import com.kitchenreceipts.app.jobs.ReadingService
import com.kitchenreceipts.app.jobs.ReadingTime
import com.kitchenreceipts.core.BundlePlan
import com.kitchenreceipts.core.BundleStatus
import com.kitchenreceipts.core.BundleWizard
import com.kitchenreceipts.core.InvoiceBundle
import com.kitchenreceipts.core.InvoiceOutcome
import com.kitchenreceipts.app.jobs.JobStatus
import com.kitchenreceipts.core.SupplierCategory
import com.kitchenreceipts.core.SupplierChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the bundle screen shows now, from the plan's status and step. [order] runs along the wizard. */
sealed class BundlePage(val order: Int) {
    data object Missing : BundlePage(0)
    data object Scanning : BundlePage(1)
    data object AskLong : BundlePage(2)
    data object Found : BundlePage(10)
    data object Pages : BundlePage(11)
    /** One new supplier: [at] is its place in BundleWizard.newSuppliers. */
    data class Supplier(val at: Int) : BundlePage(20 + at)
    data object Read : BundlePage(1000)
    data object Preparing : BundlePage(1001)
    data object Reading : BundlePage(1002)
    data object Done : BundlePage(1003)
    data object Ended : BundlePage(1004)
}

/**
 * The import of one file of many invoices, step by step (see core BundleWizard for every rule). Every choice is saved
 * to the plan at once, through BundleStore, in the app's scope: leaving the screen half-way, or the app, loses nothing,
 * and the import carries on where it was.
 */
class BundleViewModel(private val c: AppContainer, val planId: String) : ViewModel() {

    private val store = c.bundleStore

    /** The supplier shown in the Suppliers step; null = the first one not sorted yet. Not saved: coming back starts there. */
    private val supplierAt = MutableStateFlow<Int?>(null)
    /** When the wizard last moved (uptime millis): see [settled]. */
    private var movedAt = 0L
    private val error = MutableStateFlow<Int?>(null)

    data class State(
        val plan: BundlePlan? = null,
        val page: BundlePage = BundlePage.Missing,
        val scan: BundleStore.ScanProgress? = null,
        /** A string resource: why the last action did not go through. */
        val error: Int? = null,
        /** Seconds the reading of the invoices to read will take, about; while they are read, what is left. */
        val seconds: Long = 0,
    )

    /** The AI reader helps with each document (it makes the reading longer). Found off the main thread. */
    private val aiOn = MutableStateFlow(false)

    private val planFlow = store.plans.map { list -> list.firstOrNull { it.id == planId } }

    val state: StateFlow<State> = combine(
        planFlow,
        store.scans.map { it[planId] },
        combine(store.preparing, store.missing) { p, m -> (planId in p) to (planId in m) },
        combine(supplierAt, error, aiOn) { a, e, ai -> Triple(a, e, ai) },
    ) { plan, scan, (preparing, missing), (at, err, ai) ->
        State(plan, pageOf(plan, preparing, missing, at), scan, err, plan?.let { seconds(it, ai) } ?: 0)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State(plan = store.plan(planId)).let { it.copy(page = pageOf(it.plan, false, false, null)) })

    init {
        viewModelScope.launch(Dispatchers.Default) { aiOn.value = runCatching { c.aiUse() != null }.getOrDefault(false) }
    }

    private fun pageOf(plan: BundlePlan?, preparing: Boolean, missing: Boolean, at: Int?): BundlePage = when {
        plan == null || missing -> BundlePage.Missing
        preparing || plan.status == BundleStatus.PREPARING -> BundlePage.Preparing
        plan.status == BundleStatus.SCANNING -> BundlePage.Scanning
        plan.status == BundleStatus.NOT_SPLITTABLE -> BundlePage.AskLong
        plan.status == BundleStatus.SORTING -> when (stepOf(plan)) {
            BundleWizard.Step.FOUND -> BundlePage.Found
            BundleWizard.Step.PAGES -> BundlePage.Pages
            BundleWizard.Step.SUPPLIERS -> BundlePage.Supplier(supplierIndex(plan, at))
            BundleWizard.Step.READ -> BundlePage.Read
        }
        plan.status == BundleStatus.QUEUED || plan.status == BundleStatus.PAUSED -> BundlePage.Reading
        plan.status == BundleStatus.DONE -> BundlePage.Done
        else -> BundlePage.Ended
    }

    /** The plan's step, or the one that stands for it when it has nothing to show (no new supplier, no pages). */
    private fun stepOf(plan: BundlePlan): BundleWizard.Step = when {
        plan.step == BundleWizard.Step.SUPPLIERS && BundleWizard.newSuppliers(plan).isEmpty() -> BundleWizard.Step.READ
        plan.step == BundleWizard.Step.PAGES && plan.file.isBlank() -> BundleWizard.Step.FOUND
        else -> plan.step
    }

    private fun supplierIndex(plan: BundlePlan, at: Int?): Int {
        val news = BundleWizard.newSuppliers(plan)
        if (at != null) return at.coerceIn(0, (news.size - 1).coerceAtLeast(0))
        return news.indexOfFirst { it.include && it.category == null }.coerceAtLeast(0)
    }

    /**
     * The reading time of what goes to be read: each invoice's pages (at most 20 are read), and the AI per invoice.
     * While the file is read: of what is still to be read, from the running averages measured on this phone.
     */
    private fun seconds(plan: BundlePlan, ai: Boolean): Long {
        val reading = plan.status == BundleStatus.QUEUED || plan.status == BundleStatus.PAUSED
        val read = if (reading) plan.invoices.filter { it.outcome == InvoiceOutcome.WAITING || it.outcome == InvoiceOutcome.QUEUED }
        else BundleWizard.toRead(plan)
        // An e-invoice is read from its own values: no pages to read, no AI.
        if (plan.file.isBlank()) return read.size.toLong()
        val pages = read.sumOf { minOf(it.pageCount, InvoiceBundle.PAGE_LIMIT) }
        return ReadingTime.seconds(c.settings, pages, read.size, ai)
    }

    // ------------------------------------------------------------------ the file's list while it is read

    /** The job being read now (its row says so). */
    val readingJob: StateFlow<String?> = c.importQueue.jobs.map { list -> list.firstOrNull { it.status == JobStatus.READING }?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Jobs the app is checking again right now: not opened meanwhile. */
    val rechecking: StateFlow<Set<String>> = c.importQueue.rechecking

    /** "5 more from this supplier saved by the app" (see BundleStore.rechecked). */
    val rechecked: StateFlow<BundleStore.Rechecked?> = store.rechecked

    fun recheckedShown(r: BundleStore.Rechecked) = store.shown(r)

    /** The next invoice to check, in plan order (its job). */
    fun nextToCheck(): String? = store.next(planId)

    /** "Try again" on an invoice that could not be read. */
    fun retry(jobId: String) = change {
        retry(planId, jobId)
        ReadingService.ensureRunning(c.appContext)
    }

    // ------------------------------------------------------------------ the operator's choices

    /** Saved at once, in the app's scope: the change is kept even when the screen is left right after. */
    private fun change(f: suspend BundleStore.() -> Unit) {
        error.value = null
        c.appScope.launch { runCatching { store.f() }.onFailure { c.log.error("bundleAction", it) } }
    }

    private fun step(to: BundleWizard.Step) = change { update(planId) { if (it.status == BundleStatus.SORTING) it.copy(step = to) else it } }

    /**
     * True when the wizard may move on now. A double tap (a wet finger) would otherwise also hit what slid into the
     * same place: the next supplier's category, or the next step's button ("Read 173 invoices"). So right after a
     * move, the screen settles first and a second tap does nothing.
     */
    private fun settled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - movedAt < SETTLE_MS) return false
        movedAt = now
        return true
    }

    /** From the first step: the suppliers to sort, or straight to Read when there is none. */
    fun next() {
        val plan = state.value.plan ?: return
        if (!settled()) return
        supplierAt.value = null
        step(if (BundleWizard.toSort(plan).isNotEmpty()) BundleWizard.Step.SUPPLIERS else BundleWizard.Step.READ)
    }

    fun openPages() {
        if (settled()) step(BundleWizard.Step.PAGES)
    }

    fun toRead() {
        if (settled()) step(BundleWizard.Step.READ)
    }

    /**
     * One step back inside the wizard (a supplier back, the page check back to the first step). False when there is
     * none: on the first step the screen asks whether to leave, elsewhere it is left.
     */
    fun back(): Boolean {
        val plan = state.value.plan ?: return false
        val page = state.value.page
        if (page != BundlePage.Pages && page !is BundlePage.Supplier && page != BundlePage.Read) return false
        if (!settled()) return true
        when (val p = page) {
            BundlePage.Pages -> step(BundleWizard.Step.FOUND)
            is BundlePage.Supplier -> if (p.at > 0) supplierAt.value = p.at - 1 else step(BundleWizard.Step.FOUND)
            BundlePage.Read -> {
                val news = BundleWizard.newSuppliers(plan)
                if (news.isEmpty()) {
                    step(BundleWizard.Step.FOUND)
                } else {
                    supplierAt.value = news.size - 1
                    step(BundleWizard.Step.SUPPLIERS)
                }
            }
            else -> return false
        }
        return true
    }

    /** A category tapped for the supplier shown: saved, and on to the next supplier (Read after the last). */
    fun pick(key: String, category: SupplierCategory) = choose { p ->
        BundleWizard.setSupplierIncluded(BundleWizard.setCategory(p, key, category.key), key, true)
    }

    fun decideLater(key: String) = choose { p ->
        BundleWizard.setSupplierIncluded(BundleWizard.setCategory(p, key, SupplierChoice.LATER), key, true)
    }

    fun leaveOut(key: String) = choose { p -> BundleWizard.setSupplierIncluded(p, key, false) }

    /** A supplier already sorted (folded at the bottom) gets another category: for this file, remembered at Read. */
    fun changeKnown(key: String, category: SupplierCategory) = change {
        update(planId) { p -> if (p.status == BundleStatus.SORTING) BundleWizard.setCategory(p, key, category.key) else p }
    }

    private fun choose(f: (BundlePlan) -> BundlePlan) {
        val plan = state.value.plan ?: return
        val at = (state.value.page as? BundlePage.Supplier)?.at ?: return
        if (!settled()) return
        val last = at + 1 >= BundleWizard.newSuppliers(plan).size
        if (!last) supplierAt.value = at + 1
        change { update(planId) { p -> f(p).let { if (last) it.copy(step = BundleWizard.Step.READ) else it } } }
    }

    /** "Use the suggestion for N suppliers": then the first supplier still to sort, or Read when none is left. */
    fun useSuggestions() {
        val plan = state.value.plan ?: return
        if (!settled()) return
        val after = BundleWizard.useSuggestions(plan)
        val first = BundleWizard.newSuppliers(after).indexOfFirst { it.include && it.category == null }
        if (first >= 0) supplierAt.value = first
        change { update(planId) { p -> BundleWizard.useSuggestions(p).let { if (first < 0) it.copy(step = BundleWizard.Step.READ) else it } } }
    }

    /** The pages that are no invoice: read as one document each, or left out (the default). */
    fun readUnknown(read: Boolean) = change {
        update(planId) { p ->
            p.invoices.indices.filter { !p.invoices[it].isInvoice }.fold(p) { acc, i -> BundleWizard.setInvoiceIncluded(acc, i, read) }
        }
    }

    /** Tick or untick the invoice that starts on page [first] (by page: the list may have changed meanwhile). */
    fun setIncluded(first: Int, include: Boolean) = onInvoice(first) { p, i -> BundleWizard.setInvoiceIncluded(p, i, include) }

    /** "This invoice is right": the invoice that starts on page [first] needs no more look. */
    fun keep(first: Int) = onInvoice(first) { p, i -> BundleWizard.keep(p, i) }

    private fun onInvoice(first: Int, f: (BundlePlan, Int) -> BundlePlan) = change {
        update(planId) { p -> p.invoices.indexOfFirst { it.first == first }.let { i -> if (i < 0) p else f(p, i) } }
    }

    /** "These pages belong to the invoice before", for the invoice that starts on page [first]. */
    fun join(first: Int) = change { join(planId, first) }

    fun split(page: Int) = change { splitAt(planId, page) }

    /** Read: the invoices are cut out of the file and queued (a few seconds), then read in the background. */
    fun read() {
        if (!settled()) return
        error.value = null
        c.appScope.launch {
            runCatching {
                when (val r = store.confirm(planId)) {
                    is BundleStore.Prepared.Queued -> if (r.count > 0) ReadingService.ensureRunning(c.appContext)
                    is BundleStore.Prepared.Failed -> error.value = r.reason.text
                }
            }.onFailure { c.log.error("bundleRead", it) }
        }
    }

    /** "Read the N not read yet" (after "Stop reading this file"). */
    fun readRest() {
        error.value = null
        c.appScope.launch {
            runCatching {
                when (val r = store.readRest(planId)) {
                    is BundleStore.Prepared.Queued -> if (r.count > 0) ReadingService.ensureRunning(c.appContext)
                    is BundleStore.Prepared.Failed -> error.value = r.reason.text
                }
            }.onFailure { c.log.error("bundleReadRest", it) }
        }
    }

    /** The question on a long file: "Read the first 20 pages". */
    fun readAsOne() = change {
        readAsOne(planId)
        ReadingService.ensureRunning(c.appContext)
    }

    /** Stop looking through the file, "Don't import", "Cancel the import": nothing from the file is read. */
    fun cancel() = change { cancel(planId) }

    /** "Stop reading this file": saved invoices stay; the others can be read later. */
    fun stopReading() = change { stop(planId) }

    fun close() = change { close(planId) }

    // ------------------------------------------------------------------ what the screens look up

    /** A plan that was read as one document (nothing of its own to show). */
    fun isOneDocument(p: BundlePlan): Boolean = store.isOneDocument(p)

    /** The document saved earlier from this very file (read as one document): when, and how many lines. */
    data class SameFile(val id: Long, val savedOn: LocalDate, val lines: Int)

    suspend fun sameFile(id: Long): SameFile? = withContext(Dispatchers.IO) {
        runCatching {
            val doc = c.repository.documentOnce(id) ?: return@runCatching null
            SameFile(id, Instant.ofEpochMilli(doc.createdAt).atZone(ZoneId.systemDefault()).toLocalDate(), c.repository.itemsOnce(id).size)
        }.getOrNull()
    }

    /** Small page pictures for the page check, kept while the screen is open (a few MB at most). */
    private val thumbs = object : LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val rendering = Mutex()

    /** Page [page] (from 0) of the plan's file, about [THUMB_WIDTH] pixels wide. */
    suspend fun thumb(page: Int): Bitmap? {
        val file = state.value.plan?.file?.takeIf { it.isNotBlank() } ?: return null
        val key = "$file#$page"
        thumbs.get(key)?.let { return it }
        // One page at a time: a fast scroll does not queue dozens of pictures at once.
        return rendering.withLock {
            thumbs.get(key) ?: runCatching { c.pageRenderer.renderPage(file, FileStore.MIME_PDF, page, THUMB_WIDTH) }
                .onFailure { c.log.error("bundleThumb", it) }.getOrNull()?.also { thumbs.put(key, it) }
        }
    }

    override fun onCleared() {
        thumbs.evictAll()
    }

    companion object {
        const val THUMB_WIDTH = 300
        /** About the time the next supplier takes to slide in. */
        private const val SETTLE_MS = 600L
        private const val THUMB_CACHE_BYTES = 24 * 1024 * 1024
    }
}
