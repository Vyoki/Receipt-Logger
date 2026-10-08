package com.kitchenreceipts.app.ui.review

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
)

data class SaveConfirmation(val duplicates: List<DuplicateInfo>, val uncertainCount: Int, val valid: ValidDocument)

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
    private var supplierKey: String? = null
    /** What the OCR found where, for showing the part of the photo behind a field (new documents only). */
    private var ocrPages: List<List<com.kitchenreceipts.core.OcrLine>> = emptyList()
    private var ocrWidths: List<Int> = emptyList()
    private var peekPage: Pair<Int, android.graphics.Bitmap>? = null
    private var openedAt = System.currentTimeMillis()

    val products: StateFlow<List<ProductEntity>> = repo.products().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sellerNames: StateFlow<List<String>> =
        repo.sellers().map { list -> list.map { it.name } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { if (documentId == null) loadPending() else loadExisting(documentId) }
    }

    private suspend fun loadPending() {
        val pending = jobId?.let { c.importQueue.job(it) }?.pending
        if (pending == null) {
            _state.value = ReviewState(loading = false, fatal = "nothing_to_review")
            return
        }
        storedFile = pending.file
        ocrText = pending.ocrText
        // Heavy (supplier, products, price history): off the screen's thread, so opening the review never stutters.
        val prepared = withContext(Dispatchers.Default) { c.preparer.prepare(pending) }
        ocrSellerRaw = prepared.ocrSellerRaw
        supplierKey = prepared.supplierKey
        ocrPages = pending.rawLines
        readParsed = pending.parsed
        ocrWidths = pending.ocrWidths
        initialDraft = prepared.initialDraft
        _state.value = ReviewState(
            loading = false,
            isNew = true, draft = prepared.draft,
            filePath = pending.file.relativePath, mimeType = pending.file.mimeType, pageCount = pending.file.pageCount,
            engineName = if (pending.parsed.itemsReadBy == "ai") "${pending.engineName} + AI" else pending.engineName,
            ocrError = pending.ocrError, pagesRead = pending.pagesRead,
            recognisedText = pending.debugReport(),
            recognition = prepared.recognition,
            reviewReasons = prepared.reasons,
            priceChanges = prepared.priceChanges,
        )
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
                )
            },
        )
        _state.value = ReviewState(
            loading = false, isNew = false, draft = draft,
            filePath = doc.filePath, mimeType = doc.mimeType, pageCount = doc.pageCount,
            recognisedText = doc.ocrText?.takeIf { it.isNotBlank() },
            reviewReasons = AutoAccept.reasons(draft),
            priceChanges = priceChanges(draft),
        )
        initialDraft = draft
    }

    // ------------------------------------------------------------ editing

    private fun editDraft(f: (DocumentDraft) -> DocumentDraft) {
        _state.update {
            val d = f(it.draft)
            it.copy(draft = d, saveError = null, reviewReasons = AutoAccept.reasons(d))
        }
        refreshPrices()
    }

    fun setHeader(field: HeaderField, text: String) = editDraft { d -> d.withHeader(field) { it.confirmed(text) } }
    fun confirmHeader(field: HeaderField) = editDraft { d -> d.withHeader(field) { it.confirmed() } }

    fun setDate(date: LocalDate) = setHeader(HeaderField.DATE, ItalianDates.format(date))

    fun setVatBasis(v: VatBasis) = editDraft { it.copy(vatBasis = v, vatBasisUncertain = false) }
    fun confirmVatBasis() = editDraft { it.copy(vatBasisUncertain = false) }

    fun setItem(key: Long, field: ItemField, text: String) = editItem(key) { item ->
        val edited = item.withField(field) { f -> f.confirmed(text) }
        // Typing the numbers by hand replaces the offered readings.
        if (field in NUMBER_FIELDS) edited.copy(choices = emptyList()) else edited
    }

    /**
     * The operator picked one reading of a line that added up several ways. Remembered for this supplier, so the
     * same doubt is settled by itself next time.
     */
    fun pickChoice(key: Long, choice: LineChoice) {
        editItem(key) { it.pick(choice) }
        if (_state.value.isNew) {
            val key = supplierKey
            viewModelScope.launch(Dispatchers.IO) { runCatching { c.learning.addRule(key, choice.rule) } }
            c.log.event("CHOICE_PICKED", "rule" to choice.rule.encode())
        }
    }
    fun confirmItem(key: Long, field: ItemField) = editItem(key) { it.withField(field) { f -> f.confirmed() } }

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

    /** The part of the page photo a field was read from, with the value outlined; null when it cannot be found. */
    suspend fun peek(source: String?, value: String): com.kitchenreceipts.app.ui.components.PeekImage? {
        val path = storedFile?.relativePath ?: return null
        val mime = storedFile?.mimeType ?: return null
        val spot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.kitchenreceipts.core.FieldLocator.locate(ocrPages, source, value)
        } ?: return null
        val page = peekPage?.takeIf { it.first == spot.page }?.second ?: run {
            val bmp = runCatching {
                c.pageRenderer.renderForReading(path, mime, spot.page, com.kitchenreceipts.app.ocr.ImportProcessor.OCR_LONG_SIDE)
            }.getOrNull() ?: return null
            peekPage?.second?.recycle()
            peekPage = spot.page to bmp
            bmp
        }
        val scale = page.width.toFloat() / (ocrWidths.getOrNull(spot.page) ?: page.width).coerceAtLeast(1)
        fun px(v: Int, max: Int) = (v * scale).toInt().coerceIn(0, max)
        val l = px(spot.area.left, page.width - 1); val t = px(spot.area.top, page.height - 1)
        val r = px(spot.area.right, page.width).coerceAtLeast(l + 1); val b = px(spot.area.bottom, page.height).coerceAtLeast(t + 1)
        val crop = android.graphics.Bitmap.createBitmap(page, l, t, r - l, b - t)
            .let { if (it === page) it.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else it }
        fun fx(v: Int) = ((v * scale - l) / (r - l)).coerceIn(0f, 1f)
        fun fy(v: Int) = ((v * scale - t) / (b - t)).coerceIn(0f, 1f)
        return com.kitchenreceipts.app.ui.components.PeekImage(
            crop, fx(spot.mark.left), fy(spot.mark.top), fx(spot.mark.right), fy(spot.mark.bottom), spot.exact,
        )
    }

    override fun onCleared() {
        peekPage?.second?.recycle()
        peekPage = null
    }

    // ------------------------------------------------------------ saving

    fun requestSave() {
        val s = _state.value
        if (s.saving) return
        when (val r = DraftValidator.validate(s.draft)) {
            is ValidationResult.Invalid -> {
                val keyed = r.errors.associate { e -> mapErrorKey(e.field, s.draft) to e.code }
                _state.update { it.copy(errors = keyed) }
            }
            is ValidationResult.Valid -> {
                _state.update { it.copy(errors = emptyMap()) }
                viewModelScope.launch {
                    val dups = withContext(Dispatchers.Default) { repo.findDuplicates(r.document, storedFile?.sha256, documentId) }
                    val uncertain = s.draft.uncertainCount
                    if (dups.isNotEmpty()) {
                        c.log.event("DUPLICATE_WARNING", "matches" to dups.size, "reasons" to dups.flatMap { it.match.reasons }.distinct().joinToString(","))
                    }
                    if (dups.isEmpty() && uncertain == 0) save(r.document)
                    else _state.update { it.copy(confirm = SaveConfirmation(dups, uncertain, r.document)) }
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
                ocrText?.let { SellerLearning(it, ocrSellerRaw, own) }
            } else {
                docOcrText?.let { SellerLearning(it, null, own) }
            }
            val id = repo.saveDocument(doc, storedFile, ocrText, documentId, learning)
            // Files on the phone (queue, readings, corrections, what was learned): not on the screen's thread.
            withContext(Dispatchers.IO) { afterSave(id, doc, own, auto) }
            c.checkDishes(id)
            _state.update { it.copy(saving = false, savedId = id, autoSaved = auto) }
        } catch (e: Exception) {
            c.log.error("save", e)
            _state.update { it.copy(saving = false, saveError = e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun afterSave(id: Long, doc: ValidDocument, own: String?, auto: Boolean) {
        run {
            if (documentId == null) jobId?.let { c.importQueue.markSaved(it) }
            // The reading as the camera saw it, so later versions can read this document again (see ReadingChecker).
            if (documentId == null) runCatching { c.readings.save(id, ocrPages) }
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
            }
        }
    }

    /** Throws away a document that was read but not saved, including its stored original. */
    fun discard() {
        c.log.event("DISCARDED", "new" to (documentId == null), "seconds" to (System.currentTimeMillis() - openedAt) / 1000)
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
