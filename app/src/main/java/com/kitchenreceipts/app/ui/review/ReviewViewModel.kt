package com.kitchenreceipts.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.AppContainer
import com.kitchenreceipts.app.data.DuplicateInfo
import com.kitchenreceipts.app.data.ProductEntity
import com.kitchenreceipts.app.data.ProductNameTakenException
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.core.DocumentDraft
import com.kitchenreceipts.core.DraftField
import com.kitchenreceipts.core.DraftValidator
import com.kitchenreceipts.core.ErrorCode
import com.kitchenreceipts.core.ItalianDates
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.LineItemDraft
import com.kitchenreceipts.core.ValidDocument
import com.kitchenreceipts.core.ValidationResult
import com.kitchenreceipts.core.VatBasis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class HeaderField(val key: String) {
    SELLER("seller"), DATE("date"), NUMBER("number"), CURRENCY("currency"),
    SUBTOTAL("subtotal"), VAT("vat"), TOTAL("total"),
}

enum class ItemField(val key: String) {
    DESCRIPTION("description"), QUANTITY("quantity"), UNIT("unit"), UNIT_PRICE("unitPrice"),
    LINE_TOTAL("lineTotal"), VAT_RATE("vatRate"), LOT("lot"), EXPIRY("expiry"),
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
    /** "seller", "item:12.quantity" -> error */
    val errors: Map<String, ErrorCode> = emptyMap(),
    val confirm: SaveConfirmation? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val savedId: Long? = null,
)

data class SaveConfirmation(val duplicates: List<DuplicateInfo>, val uncertainCount: Int, val valid: ValidDocument)

class ReviewViewModel(private val c: AppContainer, private val documentId: Long?) : ViewModel() {

    private val repo = c.repository
    private val _state = MutableStateFlow(ReviewState(isNew = documentId == null))
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    private var storedFile: StoredFile? = null
    private var ocrText: String? = null
    private var nextKey = 10_000L

    val products: StateFlow<List<ProductEntity>> = repo.products().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sellerNames: StateFlow<List<String>> =
        repo.sellers().map { list -> list.map { it.name } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { if (documentId == null) loadPending() else loadExisting(documentId) }
    }

    private suspend fun loadPending() {
        val pending = c.pendingImport
        if (pending == null) {
            _state.value = ReviewState(loading = false, fatal = "nothing_to_review")
            return
        }
        storedFile = pending.file
        ocrText = pending.ocrText
        var draft = DocumentDraft.fromParsed(pending.parsed)
        // Pre-fill products the user previously assigned for the same seller + exact description.
        val seller = draft.seller.text
        if (seller.isNotBlank()) {
            val names = repo.productsOnce().associate { it.id to it.name }
            draft = draft.copy(items = draft.items.map { item ->
                val pid = repo.rememberedProduct(seller, item.description.text)
                if (pid != null && names.containsKey(pid)) item.copy(productId = pid, productName = names[pid]) else item
            })
        }
        _state.value = ReviewState(
            loading = false, isNew = true, draft = draft,
            filePath = pending.file.relativePath, mimeType = pending.file.mimeType, pageCount = pending.file.pageCount,
            engineName = pending.engineName, ocrError = pending.ocrError, pagesRead = pending.pagesRead,
        )
    }

    private suspend fun loadExisting(id: Long) {
        val doc = repo.documentOnce(id)
        if (doc == null) {
            _state.value = ReviewState(loading = false, fatal = "not_found", isNew = false)
            return
        }
        val sellerName = repo.observeDocument(id).first()?.sellerName ?: ""
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
                )
            },
        )
        _state.value = ReviewState(
            loading = false, isNew = false, draft = draft,
            filePath = doc.filePath, mimeType = doc.mimeType, pageCount = doc.pageCount,
        )
    }

    // ------------------------------------------------------------ editing

    private fun editDraft(f: (DocumentDraft) -> DocumentDraft) = _state.update { it.copy(draft = f(it.draft), saveError = null) }

    fun setHeader(field: HeaderField, text: String) = editDraft { d -> d.withHeader(field) { it.confirmed(text) } }
    fun confirmHeader(field: HeaderField) = editDraft { d -> d.withHeader(field) { it.confirmed() } }

    fun setDate(date: LocalDate) = setHeader(HeaderField.DATE, ItalianDates.format(date))

    fun setVatBasis(v: VatBasis) = editDraft { it.copy(vatBasis = v, vatBasisUncertain = false) }
    fun confirmVatBasis() = editDraft { it.copy(vatBasisUncertain = false) }

    fun setItem(key: Long, field: ItemField, text: String) = editItem(key) { it.withField(field) { f -> f.confirmed(text) } }
    fun confirmItem(key: Long, field: ItemField) = editItem(key) { it.withField(field) { f -> f.confirmed() } }

    fun useComputedTotal(key: Long) = editItem(key) { item ->
        val cents = item.computedTotalCents() ?: return@editItem item
        item.copy(lineTotal = item.lineTotal.confirmed(ItalianNumbers.centsToEditText(cents)))
    }

    fun addItem() = editDraft { it.copy(items = it.items + LineItemDraft(key = nextKey++)) }
    fun removeItem(key: Long) = editDraft { d -> d.copy(items = d.items.filterNot { it.key == key }) }

    fun assignProduct(key: Long, product: ProductEntity?) =
        editItem(key) { it.copy(productId = product?.id, productName = product?.name) }

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
                    val dups = repo.findDuplicates(r.document, storedFile?.sha256, documentId)
                    val uncertain = s.draft.uncertainCount
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

    private suspend fun save(doc: ValidDocument) {
        _state.update { it.copy(saving = true, saveError = null) }
        try {
            val id = repo.saveDocument(doc, storedFile, ocrText, documentId)
            if (documentId == null) c.pendingImport = null
            _state.update { it.copy(saving = false, savedId = id) }
        } catch (e: Exception) {
            _state.update { it.copy(saving = false, saveError = e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Throws away an unsaved import, including its stored file. */
    fun discard() {
        if (documentId == null) {
            storedFile?.let { c.fileStore.delete(it.relativePath) }
            c.pendingImport = null
        }
    }

    private fun mapErrorKey(field: String, draft: DocumentDraft): String {
        val m = Regex("^items\\[(\\d+)]\\.(\\w+)$").find(field) ?: return field
        val item = draft.items.getOrNull(m.groupValues[1].toInt()) ?: return field
        return itemErrorKey(item.key, m.groupValues[2])
    }

    companion object {
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
}
