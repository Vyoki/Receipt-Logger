package com.kitchenreceipts.app.ui.review

import com.kitchenreceipts.core.checksStillFor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.AppContainer
import com.kitchenreceipts.app.data.DuplicateInfo
import com.kitchenreceipts.app.data.ProductEntity
import com.kitchenreceipts.app.data.ProductNameTakenException
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.app.data.SellerLearning
import com.kitchenreceipts.app.data.SellerRecognition
import com.kitchenreceipts.app.ocr.PendingImport
import com.kitchenreceipts.core.ItemMark
import com.kitchenreceipts.core.AutoAccept
import com.kitchenreceipts.core.Confidence
import com.kitchenreceipts.core.PriceChange
import com.kitchenreceipts.core.ReviewReason
import com.kitchenreceipts.core.Corrections
import com.kitchenreceipts.core.DocumentDraft
import com.kitchenreceipts.core.Extracted
import com.kitchenreceipts.core.SellerMatchReason
import com.kitchenreceipts.core.DraftField
import com.kitchenreceipts.core.DraftValidator
import com.kitchenreceipts.core.ErrorCode
import com.kitchenreceipts.core.ItalianDates
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.LineItemDraft
import com.kitchenreceipts.core.LineChoice
import com.kitchenreceipts.core.SupplierCategory
import com.kitchenreceipts.core.SupplierMemory
import com.kitchenreceipts.core.ValidDocument
import com.kitchenreceipts.core.ValidationResult
import com.kitchenreceipts.core.VatBasis
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class HeaderField(val key: String) {
    SELLER("seller"), DATE("date"), NUMBER("number"), CURRENCY("currency"),
    SUBTOTAL("subtotal"), VAT("vat"), TOTAL("total"),
}

enum class ItemField(val key: String) {
    DESCRIPTION("description"), QUANTITY("quantity"), UNIT("unit"), UNIT_PRICE("unitPrice"),
    LINE_TOTAL("lineTotal"), VAT_RATE("vatRate"), LOT("lot"), EXPIRY("expiry"), PACKAGES("packages"), PACK_SIZE("packSize"), DISCOUNT("discount"),
}

data class ReviewState(
    val loading: Boolean = true,
    /** Set when there is nothing to review (e.g. the app was restarted before saving). */
    val fatal: String? = null,
    val isNew: Boolean = true,
    val draft: DocumentDraft = DocumentDraft(),
    val filePath: String? = null,
    val mimeType: String? = null,
    val pageCount: Int = 1,
    val engineName: String? = null,
    val ocrError: String? = null,
    val pagesRead: Int = 0,
    /** Recognised text (and raw OCR lines for a new import), for checking or sharing. */
    val recognisedText: String? = null,
    /** Supplier recognised from what was learned on earlier documents. */
    val recognition: SellerRecognition? = null,
    /** "seller", "item:12.quantity" -> error */
    val errors: Map<String, ErrorCode> = emptyMap(),
    val confirm: SaveConfirmation? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val savedId: Long? = null,
    /** Saved without review because everything was read with confidence and added up. */
    val autoSaved: Boolean = false,
    /** What still needs the operator's eyes (empty = everything checks out). */
    val reviewReasons: List<ReviewReason> = emptyList(),
    /** Prices that differ from the last purchase of the same product. */
    val priceChanges: List<PriceChange> = emptyList(),
    /** The operator's corrections from last time were brought back (they had left before saving). */
    val restored: Boolean = false,
    /** One-tap replacements under the doubtful values (see core Replacements), worked out off the screen's thread. */
    val replacements: Map<String, List<String>> = emptyMap(),
    /** A new document's kind, read from its title (null: not printed); shown and changed with one tap before saving. */
    val kind: com.kitchenreceipts.core.DocKind? = null,
    /** An invoice of a file of many, saved or put off: where the chain goes now (see [ChainMove]). */
    val chainMove: ChainMove? = null,
    /**
     * What the supplier sells: this invoice's own choice, else remembered by its VAT number (a saved document: by its
     * name too, as on Sellers); null = not sorted. Where things are shown only: no amount of the invoice depends on it
     * (the lines of a Coperti supplier are left out of food cost).
     */
    val supplierCategory: SupplierCategory? = null,
    /** The category chosen last time for a supplier of the same name (no VAT number to be sure): shown, never applied. */
    val categoryChosenBefore: SupplierCategory? = null,
    /** What the invoice's own lines suggest for a supplier not sorted yet (a laundry: Coperti): shown, never applied. */
    val categorySuggested: SupplierCategory? = null,
)

/**
 * Where the review of an invoice of a file of many is in that file's chain of invoices to check: "Invoice 3 of 25 to
 * check" ([position] of [of]; null when not known) and the invoices still being read.
 */
data class Chain(val planId: String, val position: Int?, val of: Int, val stillReading: Int)

/**
 * The chain moves on after a save or "Check later": to the invoice of job [next] (from [nextSupplier]), or back to the
 * file's list when none is ready. [saved]: after a save ("Saved ✓ · next: …" first).
 */
data class ChainMove(val planId: String, val next: String?, val nextSupplier: String?, val saved: Boolean)

data class SaveConfirmation(
    val duplicates: List<DuplicateInfo>,
    val uncertainCount: Int,
    val valid: ValidDocument,
    /** What still does not check out besides unchecked values (lines not adding up, a missing date, another buyer...). */
    val reasons: List<ReviewReason> = emptyList(),
)

/** The reasons to look again that are not "values not checked" (those are counted on their own). */
fun openReasons(reasons: List<ReviewReason>): List<ReviewReason> = reasons.filter { it != ReviewReason.UNCERTAIN_VALUES }

class ReviewViewModel(private val c: AppContainer, private val documentId: Long?, private val jobId: String? = null) : ViewModel() {

    private val repo = c.repository
    private val _state = MutableStateFlow(ReviewState(isNew = documentId == null))
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    private var storedFile: StoredFile? = null
    private var docOcrText: String? = null
    private var ocrText: String? = null
    private var nextKey = 10_000L
    /** What the OCR proposed, to log the operator's corrections on save. */
    private var initialDraft: DocumentDraft? = null
    private var ocrSellerRaw: String? = null
    /** The document as the app read it (to learn the supplier's layout from what the operator confirms). */
    private var readParsed: com.kitchenreceipts.core.ParsedDocument? = null
    /** Every page was the PDF's own text; the supplier was recognised (for the checker's cases). */
    private var readAllText: Boolean? = null
    private var readKnownSupplier = false
    private var supplierKey: String? = null
    /** An invoice cut from a file of many whose pages need a look (see ImportJob.flags): held for the operator. */
    private var bundleFlags: Set<String> = emptySet()
    /** What the OCR found where, for showing the part of the photo behind a field (new documents only). */
    private var ocrPages: List<List<com.kitchenreceipts.core.OcrLine>> = emptyList()
    private var ocrWidths: List<Int> = emptyList()
    private var ocrHeights: List<Int> = emptyList()
    private var cachedPage: Pair<Int, android.graphics.Bitmap>? = null
    private var openedAt = System.currentTimeMillis()

    /**
     * The operator's corrections, kept on the phone until the document is saved or thrown away: leaving the screen,
     * the app lock or Android closing the app no longer loses them. A saved document's edits are kept with the time
     * it was last saved, so they never come back over a newer version.
     */
    private val keptFile: java.io.File? = when {
        documentId != null -> java.io.File(c.appContext.filesDir, "edits/$documentId.json")
        jobId != null -> c.importQueue.draftFile(jobId)
        else -> null
    }
    private var keptStamp = 0L
    /** The draft as read (before any correction): nothing is kept while the draft is still this. */
    private var baseDraft: DocumentDraft? = null
    private var keepJob: Job? = null
    @Volatile private var finished = false

    private fun readKept(hints: DocumentDraft): DocumentDraft? {
        val f = keptFile?.takeIf { it.exists() } ?: return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val stamp = text.substringBefore('\n').toLongOrNull()
        val draft = if (stamp == keptStamp) com.kitchenreceipts.core.DraftCodec.decode(text.substringAfter('\n'), hints) else null
        if (draft == null) f.delete()
        return draft
    }

    private fun writeKept(d: DocumentDraft) {
        val f = keptFile ?: return
        if (finished) return
        runCatching {
            if (d == baseDraft) { f.delete(); return }
            f.parentFile?.mkdirs()
            val tmp = java.io.File(f.path + ".tmp")
            tmp.writeText(keptStamp.toString() + "\n" + com.kitchenreceipts.core.DraftCodec.encode(d))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { c.log.error("keepDraft", it) }
    }

    private fun dropKept() {
        finished = true
        keepJob?.cancel()
        keptFile?.delete()
    }

    private fun startKeys(d: DocumentDraft) {
        nextKey = maxOf(nextKey, (d.items.maxOfOrNull { it.key } ?: 0L) + 1)
    }

    /** The file of many invoices this document was cut from; null for a document on its own (or a saved one). */
    val bundleId: String? = if (documentId == null) jobId?.let { c.importQueue.job(it)?.bundleId } else null

    private var lastPosition: Pair<Int, Int>? = null

    /** "Invoice 3 of 25 to check (+ 40 still being read)", from the file's plan as it changes. */
    val chain: StateFlow<Chain?> = bundleId?.let { id ->
        c.bundleStore.plans.map { list -> list.firstOrNull { it.id == id }?.let(::chainOf) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, c.bundleStore.plan(id)?.let(::chainOf))
    } ?: MutableStateFlow(null)

    private fun chainOf(p: com.kitchenreceipts.core.BundlePlan): Chain {
        // Once saved it is no longer in the chain: its place stays as it was while it shows.
        val at = com.kitchenreceipts.core.BundleWizard.positionInChain(p, p.invoices.indexOfFirst { it.jobId == jobId })?.also { lastPosition = it } ?: lastPosition
        return Chain(p.id, at?.first, at?.second ?: com.kitchenreceipts.core.BundleWizard.chain(p).size, com.kitchenreceipts.core.BundleWizard.counts(p).reading)
    }

    /** "5 more from this supplier saved by the app" (see BundleStore.rechecked). */
    val rechecked: StateFlow<com.kitchenreceipts.app.jobs.BundleStore.Rechecked?> = c.bundleStore.rechecked

    fun recheckedShown(r: com.kitchenreceipts.app.jobs.BundleStore.Rechecked) = c.bundleStore.shown(r)

    val products: StateFlow<List<ProductEntity>> = repo.products().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Products bought only from non-food suppliers: folded at the end of the product picker. */
    val notFoodProducts: StateFlow<Set<Long>> = repo.notFoodProductIds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val sellerNames: StateFlow<List<String>> =
        repo.sellers().map { list -> list.map { it.name } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // While it is open here, the app does not save this document by itself (see ImportQueue.recheck).
        if (documentId == null) jobId?.let { c.importQueue.reviewing(it, true) }
        viewModelScope.launch { if (documentId == null) loadPending() else loadExisting(documentId) }
        // The suppliers saved on the phone come in a moment after opening: the supplier's replacements follow them.
        viewModelScope.launch { sellerNames.collect { if (!_state.value.loading) refreshChecks(0) } }
    }

    private suspend fun loadPending() {
        // Being checked again by the app right now (it may save it): opened once that check is over, as it is then.
        jobId?.let { id -> c.importQueue.rechecking.first { id !in it } }
        val job = jobId?.let { c.importQueue.job(it) }
        // In memory, or rebuilt from what was stored (an invoice of a file of many, a reading after a restart).
        val pending = job?.let { it.pending ?: c.importQueue.load(it.id) }
        if (job == null || pending == null) {
            _state.value = ReviewState(loading = false, fatal = "nothing_to_review")
            return
        }
        storedFile = pending.file
        ocrText = pending.ocrText
        // Heavy (supplier, products, price history): off the screen's thread, so opening the review never stutters.
        bundleFlags = job.flags
        val prepared = withContext(Dispatchers.Default) { c.preparer.prepare(pending, bundleFlags) }
        ocrSellerRaw = prepared.ocrSellerRaw
        supplierKey = prepared.supplierKey
        // The job's own category ("Just this invoice", or the file's wizard) before the one remembered by VAT number.
        val category = c.preparer.withJobCategory(prepared, job.category).supplierCategory
        val match = if (category == null) withContext(Dispatchers.Default) { c.preparer.categoryMatch(pending.ocrText, prepared.draft.seller.text) } else null
        ocrPages = pending.rawLines
        readParsed = pending.parsed
        readAllText = pending.rawLines.isNotEmpty() && pending.textLayerPages.size >= pending.rawLines.size
        readKnownSupplier = prepared.recognition != null
        ocrWidths = pending.ocrWidths
        ocrHeights = pending.ocrHeights
        initialDraft = prepared.initialDraft
        baseDraft = prepared.draft
        val kept = withContext(Dispatchers.IO) { readKept(prepared.draft) }
        val draft = kept ?: prepared.draft
        startKeys(draft)
        if (kept != null) c.log.event("DRAFT_RESTORED", "new" to true, "items" to draft.items.size)
        _state.value = ReviewState(
            loading = false,
            isNew = true, draft = draft, restored = kept != null,
            kind = com.kitchenreceipts.core.DocumentKinds.detect(pending.ocrText),
            filePath = pending.file.relativePath, mimeType = pending.file.mimeType, pageCount = pending.file.pageCount,
            engineName = pending.engineName,
            ocrError = pending.ocrError, pagesRead = pending.pagesRead,
            recognisedText = pending.debugReport(),
            recognition = prepared.recognition,
            reviewReasons = if (kept != null) AutoAccept.reasons(draft, bundleFlags) else prepared.reasons,
            priceChanges = if (kept != null) priceChanges(draft) else prepared.priceChanges,
            supplierCategory = category,
            categoryChosenBefore = match?.takeIf { !it.known }?.category,
            categorySuggested = if (category == null) withContext(Dispatchers.Default) {
                runCatching { com.kitchenreceipts.core.SupplierCategories.suggestFromLines(pending.ocrText.lines()) }.getOrNull()
            } else null,
        )
        refreshChecks(0)
        // The next invoice of the chain is made ready meanwhile (about a second), so it opens at once.
        bundleId?.let { id -> c.bundleStore.next(id, afterJob = jobId)?.let(::prefetch) }
    }

    private fun prefetch(next: String) {
        c.appScope.launch { runCatching { c.importQueue.load(next) }.onFailure { c.log.error("prefetch", it) } }
    }

    private suspend fun priceChanges(d: DocumentDraft): List<PriceChange> = c.preparer.priceChanges(d, documentId)

    private var priceJob: Job? = null

    /** Re-checks prices shortly after the operator stops typing. */
    private fun refreshPrices() {
        priceJob?.cancel()
        priceJob = viewModelScope.launch {
            delay(400)
            val d = _state.value.draft
            val changes = withContext(Dispatchers.Default) { priceChanges(d) }
            _state.update { it.copy(priceChanges = changes) }
        }
    }

    private suspend fun loadExisting(id: Long) {
        val doc = repo.documentOnce(id)
        if (doc == null) {
            _state.value = ReviewState(loading = false, fatal = "not_found", isNew = false)
            return
        }
        val sellerName = repo.observeDocument(id).first()?.sellerName ?: ""
        docOcrText = doc.ocrText
        val items = repo.itemsOnce(id)
        val cents = { v: Long? -> DraftField(ItalianNumbers.centsToEditText(v)) }
        val draft = DocumentDraft(
            seller = DraftField(sellerName),
            date = DraftField(doc.documentDate?.let(ItalianDates::format) ?: ""),
            number = DraftField(doc.documentNumber ?: ""),
            currency = DraftField(doc.currency ?: ""),
            subtotal = cents(doc.subtotalCents),
            vat = cents(doc.vatCents),
            total = cents(doc.totalCents),
            vatBasis = doc.vatBasis,
            items = items.map { row ->
                val it = row.item
                LineItemDraft(
                    key = nextKey++,
                    description = DraftField(it.originalDescription),
                    productId = it.productId,
                    productName = row.productName,
                    quantity = DraftField(ItalianNumbers.toEditText(it.quantity)),
                    unit = DraftField(it.unit ?: ""),
                    unitPrice = DraftField(ItalianNumbers.toEditText(it.unitPrice)),
                    lineTotal = DraftField(ItalianNumbers.centsToEditText(it.lineTotalCents)),
                    vatRate = DraftField(ItalianNumbers.toEditText(it.vatRate)),
                    lot = DraftField(it.lotNumber ?: ""),
                    expiry = DraftField(it.expiryDate?.let(ItalianDates::format) ?: ""),
                    packages = DraftField(it.packages ?: ""),
                    packSize = DraftField(it.packSize ?: ""),
                    discount = DraftField(it.discount ?: ""),
                    marks = ItemMark.decode(it.marks),
                )
            },
            // Not saved with the document: a document prints lots when one of its lines has one.
            lotsPrinted = items.any { it.item.lotNumber != null },
        )
        initialDraft = draft
        keptStamp = doc.updatedAt
        baseDraft = draft
        val kept = withContext(Dispatchers.IO) { readKept(draft) }
        val shown = kept ?: draft
        startKeys(shown)
        if (kept != null) c.log.event("DRAFT_RESTORED", "new" to false, "doc" to id)
        // A saved supplier's category as Sellers shows it: by its VAT number, else (no VAT number printed) by its name.
        val match = withContext(Dispatchers.Default) {
            c.preparer.categoryMatch(doc.ocrText, sellerName)?.takeIf { it.known || c.preparer.supplierVat(doc.ocrText) == null }
        }
        _state.value = ReviewState(
            loading = false, isNew = false, draft = shown, restored = kept != null,
            filePath = doc.filePath, mimeType = doc.mimeType, pageCount = doc.pageCount,
            recognisedText = doc.ocrText?.takeIf { it.isNotBlank() },
            reviewReasons = AutoAccept.reasons(shown),
            priceChanges = priceChanges(shown),
            supplierCategory = match?.category,
        )
        refreshChecks(0)
    }

    /** The edits differ from what was loaded (asked before leaving a saved document's edit). */
    val hasEdits: Boolean get() = baseDraft != null && _state.value.draft != baseDraft

    /** Leaving a saved document's edit without saving: the edits are thrown away. */
    fun dropEdits() {
        c.log.event("EDIT_DISCARDED", "doc" to documentId)
        dropKept()
    }

    // ------------------------------------------------------------ editing

    private var checkJob: Job? = null

    /**
     * The checks that go over the whole document (what is left to look at, the replacements under doubtful values):
     * on a background thread, a moment after the last keystroke, so typing on a long invoice stays smooth. Saving
     * works them out again itself.
     */
    private fun refreshChecks(delayMs: Long = 120) {
        checkJob?.cancel()
        checkJob = viewModelScope.launch(Dispatchers.Default) {
            delay(delayMs)
            val d = _state.value.draft
            val reasons = AutoAccept.reasons(d, bundleFlags)
            val known = sellerNames.value + runCatching { c.knowledge.pack.value.suppliers.map { it.name } }.getOrDefault(emptyList())
            val repl = com.kitchenreceipts.core.Replacements.compute(d, known, LocalDate.now())
            _state.update { if (it.draft === d) it.copy(reviewReasons = reasons, replacements = repl) else it }
        }
    }

    private fun editDraft(f: (DocumentDraft) -> DocumentDraft) {
        // Saved (the chain shows "Saved ✓ · next" for a moment): nothing changes any more, nothing would be kept.
        if (_state.value.savedId != null) return
        // The document's proof is dropped as soon as the lines it was made on change.
        _state.update { it.copy(draft = f(it.draft).checksStillFor(it.draft), saveError = null) }
        refreshChecks()
        refreshPrices()
        keepJob?.cancel()
        keepJob = viewModelScope.launch(Dispatchers.IO) {
            delay(700)
            writeKept(_state.value.draft)
        }
    }

    /** A field the operator just fixed is no longer marked red (the rest stay until they are fixed or Save checks again). */
    private fun clearError(key: String) {
        if (key in _state.value.errors) _state.update { it.copy(errors = it.errors - key) }
    }

    fun setHeader(field: HeaderField, text: String) { clearError(field.key); editDraft { d -> d.withHeader(field) { it.confirmed(text) } } }
    /** The "corrections kept" message was shown: not again for this screen. */
    fun restoredShown() { _state.value = _state.value.copy(restored = false) }

    fun confirmHeader(field: HeaderField) { clearError(field.key); editDraft { d -> d.withHeader(field) { it.confirmed() } } }

    fun setDate(date: LocalDate) = setHeader(HeaderField.DATE, ItalianDates.format(date))

    fun setKind(k: com.kitchenreceipts.core.DocKind?) {
        if (_state.value.savedId != null) return
        _state.update { it.copy(kind = k) }
        c.log.event("KIND_SET", "new" to true, "kind" to k)
    }

    /** A document read now (not a saved one) can keep a category for itself only: "Just this invoice". */
    val canChooseJustThis: Boolean get() = documentId == null && jobId != null

    /**
     * The operator chose what the supplier sells. [forSupplier] ("For all invoices from this supplier", the default):
     * remembered on this phone by the supplier's VAT number and name, as on Sellers; an invoice of a file of many
     * also moves its supplier there in the file. Otherwise "Just this invoice": kept with this document's job only,
     * the memory untouched. Display only: no amount changes.
     */
    fun chooseCategory(category: SupplierCategory, forSupplier: Boolean) {
        if (_state.value.savedId != null) return
        val all = forSupplier || !canChooseJustThis
        _state.update { it.copy(supplierCategory = category) }
        c.log.event("CATEGORY_SET", "category" to category.key, "forSupplier" to all, "new" to (documentId == null))
        val text = ocrText ?: docOcrText
        val name = _state.value.draft.seller.text.ifBlank { null }
        c.appScope.launch {
            runCatching {
                if (all) c.repository.rememberCategory(c.preparer.supplierVat(text), name, category)
                if (documentId == null) jobId?.let { id ->
                    c.importQueue.setCategory(id, category.key)
                    val planId = bundleId
                    if (all && planId != null) c.bundleStore.supplierCategoryChanged(planId, id, category)
                }
            }.onFailure { c.log.error("chooseCategory", it) }
        }
    }

    fun setVatBasis(v: VatBasis) = editDraft { it.copy(vatBasis = v, vatBasisUncertain = false) }
    fun confirmVatBasis() = editDraft { it.copy(vatBasisUncertain = false) }

    fun setItem(key: Long, field: ItemField, text: String) {
        clearError(itemErrorKey(key, field.key))
        setItemText(key, field, text)
    }

    private fun setItemText(key: Long, field: ItemField, text: String) = editItem(key) { item ->
        val edited = item.withField(field) { f -> f.confirmed(text) }
        // Typing the numbers by hand replaces the offered readings.
        if (field in NUMBER_FIELDS) edited.copy(choices = emptyList()) else edited
    }

    /**
     * The operator picked one reading of a line that added up several ways. Remembered for this supplier, so the
     * same doubt is settled by itself next time.
     */
    fun pickChoice(key: Long, choice: LineChoice) {
        if (_state.value.savedId != null) return
        editItem(key) { it.pick(choice) }
        if (_state.value.isNew) {
            val key = supplierKey
            viewModelScope.launch(Dispatchers.IO) { runCatching { c.learning.addRule(key, choice.rule) } }
            c.log.event("CHOICE_PICKED", "rule" to choice.rule.encode())
        }
    }
    fun confirmItem(key: Long, field: ItemField) { clearError(itemErrorKey(key, field.key)); editItem(key) { it.withField(field) { f -> f.confirmed() } } }

    fun useComputedTotal(key: Long) = editItem(key) { item ->
        val cents = item.computedTotalCents() ?: return@editItem item
        item.copy(lineTotal = item.lineTotal.confirmed(ItalianNumbers.centsToEditText(cents)))
    }

    fun addItem() = editDraft { it.copy(items = it.items + LineItemDraft(key = nextKey++)) }
    fun removeItem(key: Long) = editDraft { d -> d.copy(items = d.items.filterNot { it.key == key }) }

    fun assignProduct(key: Long, product: ProductEntity?) =
        editItem(key) { it.copy(productId = product?.id, productName = product?.name, productSource = null, newProductName = null) }

    /** Keeps the name proposed for a new product, or renames it before it is created. */
    fun renameNewProduct(key: Long, name: String) = editItem(key) { it.copy(newProductName = name.ifBlank { null }) }

    fun createProductAndAssign(key: Long, name: String) {
        if (_state.value.savedId != null) return
        viewModelScope.launch {
            val product = try {
                repo.createProduct(name)
            } catch (e: ProductNameTakenException) {
                e.existing // same name (ignoring case/accents) already exists: use it, never duplicate
            } catch (e: IllegalArgumentException) {
                return@launch
            }
            assignProduct(key, product)
        }
    }

    private fun editItem(key: Long, f: (LineItemDraft) -> LineItemDraft) =
        editDraft { d -> d.copy(items = d.items.map { if (it.key == key) f(it) else it }) }

    // ------------------------------------------------------------ where a value is on the photo

    /**
     * The page a field was read from, with the value (or its row, or its line's row) to outline; the first page,
     * whole, when its place is not found. Null when the page cannot be drawn.
     */
    suspend fun peek(r: com.kitchenreceipts.app.ui.components.PeekRequest): com.kitchenreceipts.app.ui.components.PeekImage? {
        val spot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.kitchenreceipts.core.FieldLocator.locate(ocrPages, r.source, r.value)
                ?: r.rowHint?.let { com.kitchenreceipts.core.FieldLocator.locate(ocrPages, it, "") }
        } ?: return peekPage(0)
        val page = pageBitmap(spot.page) ?: return null
        val scale = page.width.toFloat() / (ocrWidths.getOrNull(spot.page) ?: page.width).coerceAtLeast(1)
        fun box(b: com.kitchenreceipts.core.PageBox) =
            com.kitchenreceipts.core.PeekView.Box(b.left * scale, b.top * scale, b.right * scale, b.bottom * scale)
        return com.kitchenreceipts.app.ui.components.PeekImage(
            page, spot.page, storedFile?.pageCount ?: 1, box(spot.mark), box(spot.area), spot.lineHeight * scale, spot.exact,
        )
    }

    /** A whole page of the document (from 0), nothing outlined. */
    suspend fun peekPage(index: Int): com.kitchenreceipts.app.ui.components.PeekImage? {
        val count = storedFile?.pageCount ?: 1
        val i = index.coerceIn(0, (count - 1).coerceAtLeast(0))
        val page = pageBitmap(i) ?: return null
        return com.kitchenreceipts.app.ui.components.PeekImage(page, i, count, null, null, 0f, false)
    }

    /**
     * The page drawn at the resolution it was read at, kept for the next look. The one before is let go, not
     * recycled: the viewer may still be drawing it.
     */
    private suspend fun pageBitmap(index: Int): android.graphics.Bitmap? {
        cachedPage?.takeIf { it.first == index }?.let { return it.second }
        val path = storedFile?.relativePath ?: return null
        val mime = storedFile?.mimeType ?: return null
        val bmp = runCatching {
            c.pageRenderer.renderForReading(path, mime, index, com.kitchenreceipts.app.ocr.ImportProcessor.OCR_LONG_SIDE)
        }.getOrNull() ?: return null
        cachedPage = index to bmp
        return bmp
    }

    override fun onCleared() {
        if (documentId == null) jobId?.let { c.importQueue.reviewing(it, false) }
        cachedPage = null
        // The last corrections, if the short wait before keeping them had not passed yet (a small file).
        if (!finished && !_state.value.loading && _state.value.savedId == null) writeKept(_state.value.draft)
    }

    // ------------------------------------------------------------ saving

    fun requestSave() {
        val s = _state.value
        if (s.saving || s.savedId != null) return
        when (val r = DraftValidator.validate(s.draft)) {
            is ValidationResult.Invalid -> {
                val keyed = r.errors.associate { e -> mapErrorKey(e.field, s.draft) to e.code }
                _state.update { it.copy(errors = keyed) }
            }
            is ValidationResult.Valid -> {
                // Busy from the first tap: a second tap while the duplicate check runs must not save the document twice.
                _state.update { it.copy(errors = emptyMap(), saving = true) }
                viewModelScope.launch {
                    val dups = try {
                        withContext(Dispatchers.Default) { repo.findDuplicates(r.document, storedFile?.sha256, documentId, ocrText ?: docOcrText, c.settings.ownVatNumber) }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        c.log.error("save", e)
                        _state.update { it.copy(saving = false, saveError = com.kitchenreceipts.app.ui.friendlyError(com.kitchenreceipts.app.settings.AppSettings.wrapWithLanguage(c.appContext).resources, e)) }
                        return@launch
                    }
                    val uncertain = s.draft.uncertainCount
                    // Nothing wrong is saved without the operator seeing it: whatever still does not check out is
                    // listed and asked once (a clean document saves with one tap, as before).
                    val open = openReasons(AutoAccept.reasons(s.draft, bundleFlags))
                    if (dups.isNotEmpty()) {
                        c.log.event("DUPLICATE_WARNING", "matches" to dups.size, "reasons" to dups.flatMap { it.match.reasons }.distinct().joinToString(","))
                    }
                    if (dups.isEmpty() && uncertain == 0 && open.isEmpty()) save(r.document)
                    else {
                        if (open.isNotEmpty()) c.log.event("SAVE_ASKED", "reasons" to open.joinToString(","))
                        _state.update { it.copy(saving = false, confirm = SaveConfirmation(dups, uncertain, r.document, open)) }
                    }
                }
            }
        }
    }

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    fun confirmSave() {
        val doc = _state.value.confirm?.valid ?: return
        _state.update { it.copy(confirm = null) }
        viewModelScope.launch { save(doc) }
    }

    private suspend fun save(doc: ValidDocument, auto: Boolean = false) {
        _state.update { it.copy(saving = true, saveError = null) }
        try {
            val own = c.settings.ownVatNumber.ifBlank { null }
            val learning = if (documentId == null) {
                ocrText?.let {
                    SellerLearning(it, ocrSellerRaw, own, vatFromRoleBox = readParsed?.supplierVatSource == com.kitchenreceipts.core.SupplierVatSource.ROLE_BOX)
                }
            } else {
                docOcrText?.let { SellerLearning(it, null, own) }
            }
            val id = repo.saveDocument(doc, storedFile, ocrText, documentId, learning, kind = _state.value.kind.takeIf { documentId == null })
            withContext(Dispatchers.IO) { dropKept() }
            // Files on the phone (queue, readings, corrections, what was learned): not on the screen's thread.
            withContext(Dispatchers.IO) { afterSave(id, doc, own, auto) }
            c.checkDishes(id)
            // An invoice of a file of many: on to the next one to check (its plan has just recorded this save).
            val move = bundleId?.let { planId -> chainMove(planId, saved = true) }
            _state.update { it.copy(saving = false, savedId = id, autoSaved = auto, chainMove = move) }
        } catch (e: Exception) {
            c.log.error("save", e)
            _state.update { it.copy(saving = false, saveError = com.kitchenreceipts.app.ui.friendlyError(com.kitchenreceipts.app.settings.AppSettings.wrapWithLanguage(c.appContext).resources, e)) }
        }
    }

    private fun afterSave(id: Long, doc: ValidDocument, own: String?, auto: Boolean) {
        run {
            if (documentId == null) jobId?.let { c.importQueue.markSaved(it, id) }
            // The reading as the camera saw it, so later versions can read this document again (see ReadingChecker).
            if (documentId == null) runCatching { c.readings.save(id, ocrPages, ocrWidths, ocrHeights) }
            val finalDraft = _state.value.draft
            val corrections = initialDraft?.let { Corrections.diff(it, finalDraft) }.orEmpty()
            c.log.event(
                "SAVED",
                "doc" to id, "new" to (documentId == null), "seconds" to (System.currentTimeMillis() - openedAt) / 1000,
                "items" to doc.items.size, "uncertainLeft" to finalDraft.uncertainCount, "corrections" to corrections.size,
                "auto" to auto, "priceChanges" to _state.value.priceChanges.size,
            )
            c.log.block("corrections", corrections)
            // Every hand correction is kept with its reading report, to be sent in one go when the operator chooses.
            runCatching { c.problems.record(id, doc.sellerName, corrections, _state.value.recognisedText) }
            if (documentId == null) {
                // Confirmed lines of this supplier, shown to the AI as examples next time (corrected ones first).
                val key = ocrText?.let { SupplierMemory.key(it, own, doc.sellerName) } ?: supplierKey
                val corrected = initialDraft?.items.orEmpty().filter { it.uncertainCount > 0 || it.choices.isNotEmpty() }.map { it.key }.toSet()
                runCatching { c.learning.addExamples(key, SupplierMemory.examplesFrom(finalDraft, corrected)) }
                // How the operator named new products teaches the abbreviations ("TR." -> tenerissimo), for every supplier.
                finalDraft.items.forEach { i -> i.newProductName?.let { n -> runCatching { c.learning.learnName(i.description.text, n) } } }
                // How this supplier prints its documents (number format, lots, headings), for next time.
                readParsed?.let { read -> runCatching { c.learning.addLayout(key, com.kitchenreceipts.core.SupplierLayouts.learn(read, finalDraft)) } }
                // What the checker model learns from: each value as first shown, and whether the operator kept it.
                val shown = baseDraft
                if (!auto && shown != null) runCatching {
                    val reading = com.kitchenreceipts.core.CheckerCases.Reading(readAllText, ocrPages.size.coerceAtLeast(1), readKnownSupplier)
                    c.checkerLog.record(com.kitchenreceipts.core.CheckerCases.cases(java.util.UUID.randomUUID().toString(), shown, finalDraft, readParsed, reading))
                }.onFailure { c.log.error("checkerLog", it) }
            }
        }
    }

    /** The next invoice of the chain after this one (made ready at once), or none: back to the file's list. */
    private fun chainMove(planId: String, saved: Boolean): ChainMove {
        val next = c.bundleStore.next(planId, afterJob = jobId)
        next?.let(::prefetch)
        val supplier = next?.let { n ->
            c.bundleStore.plan(planId)?.invoices?.firstOrNull { it.jobId == n }?.supplierName ?: c.importQueue.job(n)?.sellerName
        }
        return ChainMove(planId, next, supplier, saved)
    }

    /**
     * "Check later": this invoice goes to the end of the file's chain, with the corrections made so far kept, and the
     * next one opens.
     */
    fun checkLater() {
        val planId = bundleId ?: return
        val job = jobId ?: return
        // Once: a second tap while it moves on does nothing.
        if (movingOn || _state.value.saving || _state.value.savedId != null) return
        movingOn = true
        c.log.event("CHECK_LATER", "job" to job.take(8))
        viewModelScope.launch {
            val draft = _state.value.draft
            if (!_state.value.loading) withContext(Dispatchers.IO) { writeKept(draft) }
            c.bundleStore.checkLater(planId, job)
            _state.update { it.copy(chainMove = chainMove(planId, saved = false)) }
        }
    }

    private var movingOn = false

    /** Throws away a document that was read but not saved, including its stored original. */
    fun discard() {
        // Saved a moment ago: it is a document now, not a reading to throw away.
        if (_state.value.savedId != null) return
        c.log.event("DISCARDED", "new" to (documentId == null), "seconds" to (System.currentTimeMillis() - openedAt) / 1000)
        dropKept()
        if (documentId == null) jobId?.let { c.importQueue.discard(it) } // deletes the stored original too
    }

    private fun mapErrorKey(field: String, draft: DocumentDraft): String {
        val m = Regex("^items\\[(\\d+)]\\.(\\w+)$").find(field) ?: return field
        val item = draft.items.getOrNull(m.groupValues[1].toInt()) ?: return field
        return itemErrorKey(item.key, m.groupValues[2])
    }

    companion object {
        private val NUMBER_FIELDS = setOf(ItemField.QUANTITY, ItemField.UNIT_PRICE, ItemField.LINE_TOTAL)

        fun itemErrorKey(itemKey: Long, field: String) = "item:$itemKey.$field"
    }
}

fun DocumentDraft.header(field: HeaderField): DraftField = when (field) {
    HeaderField.SELLER -> seller
    HeaderField.DATE -> date
    HeaderField.NUMBER -> number
    HeaderField.CURRENCY -> currency
    HeaderField.SUBTOTAL -> subtotal
    HeaderField.VAT -> vat
    HeaderField.TOTAL -> total
}

private fun DocumentDraft.withHeader(field: HeaderField, f: (DraftField) -> DraftField): DocumentDraft = when (field) {
    HeaderField.SELLER -> copy(seller = f(seller))
    HeaderField.DATE -> copy(date = f(date))
    HeaderField.NUMBER -> copy(number = f(number))
    HeaderField.CURRENCY -> copy(currency = f(currency))
    HeaderField.SUBTOTAL -> copy(subtotal = f(subtotal))
    HeaderField.VAT -> copy(vat = f(vat))
    HeaderField.TOTAL -> copy(total = f(total))
}

fun LineItemDraft.field(field: ItemField): DraftField = when (field) {
    ItemField.DESCRIPTION -> description
    ItemField.QUANTITY -> quantity
    ItemField.UNIT -> unit
    ItemField.UNIT_PRICE -> unitPrice
    ItemField.LINE_TOTAL -> lineTotal
    ItemField.VAT_RATE -> vatRate
    ItemField.LOT -> lot
    ItemField.EXPIRY -> expiry
    ItemField.PACKAGES -> packages
    ItemField.PACK_SIZE -> packSize
    ItemField.DISCOUNT -> discount
}

private fun LineItemDraft.withField(field: ItemField, f: (DraftField) -> DraftField): LineItemDraft = when (field) {
    ItemField.DESCRIPTION -> copy(description = f(description))
    ItemField.QUANTITY -> copy(quantity = f(quantity))
    ItemField.UNIT -> copy(unit = f(unit))
    ItemField.UNIT_PRICE -> copy(unitPrice = f(unitPrice))
    ItemField.LINE_TOTAL -> copy(lineTotal = f(lineTotal))
    ItemField.VAT_RATE -> copy(vatRate = f(vatRate))
    ItemField.LOT -> copy(lot = f(lot))
    ItemField.EXPIRY -> copy(expiry = f(expiry))
    ItemField.PACKAGES -> copy(packages = f(packages))
    ItemField.PACK_SIZE -> copy(packSize = f(packSize))
    ItemField.DISCOUNT -> copy(discount = f(discount))
}
