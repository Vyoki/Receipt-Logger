package com.kitchenreceipts.app.jobs

import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.data.SellerLearning
import com.kitchenreceipts.app.data.SellerRecognition
import com.kitchenreceipts.app.diagnostics.AppLog
import com.kitchenreceipts.app.ocr.PendingImport
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.AutoAccept
import com.kitchenreceipts.core.Confidence
import com.kitchenreceipts.core.DocumentDraft
import com.kitchenreceipts.core.DraftField
import com.kitchenreceipts.core.DraftValidator
import com.kitchenreceipts.core.Extracted
import com.kitchenreceipts.core.ItalianDates
import com.kitchenreceipts.core.PriceChange
import com.kitchenreceipts.core.PriceWatch
import com.kitchenreceipts.core.ReviewReason
import com.kitchenreceipts.core.SellerMatchReason
import com.kitchenreceipts.core.ValidationResult
import com.kitchenreceipts.core.VatBasis
import com.kitchenreceipts.core.LineSolver
import com.kitchenreceipts.core.SupplierMemory
import com.kitchenreceipts.app.learning.LearningStore

/** A reading turned into a draft the operator can review (or the app can save by itself). */
data class PreparedDraft(
    val draft: DocumentDraft,
    /** What the reading proposed before products were linked (to log the operator's corrections). */
    val initialDraft: DocumentDraft,
    val recognition: SellerRecognition?,
    val reasons: List<ReviewReason>,
    val priceChanges: List<PriceChange>,
    val ocrSellerRaw: String?,
    /** The supplier's key in what the app learned (see LearningStore). */
    val supplierKey: String? = null,
)

/**
 * Turns a finished reading into a draft: recognises the supplier, works out the VAT basis from the arithmetic,
 * links lines to products, fixes swapped quantity/price from history, and checks what still needs the operator.
 * Shared by the review screen and the background reader (which saves by itself when everything checks out).
 */
class DraftPreparer(
    private val repo: ReceiptRepository,
    private val settings: AppSettings,
    private val log: AppLog,
    private val learning: LearningStore? = null,
) {

    suspend fun prepare(pending: PendingImport): PreparedDraft {
        var draft = DocumentDraft.fromParsed(pending.parsed)
        val ocrSellerRaw = pending.parsed.sellerName?.value

        // Recognise the supplier from earlier documents (VAT number, past corrections, letterhead).
        val recognition = runCatching {
            repo.identifySeller(
                pending.ocrText, ocrSellerRaw, settings.ownVatNumber.ifBlank { null },
                ocrSellerReliable = pending.parsed.sellerName?.confidence == Confidence.HIGH,
            )
        }.getOrNull()
        if (recognition != null) {
            val m = recognition.match
            draft = draft.copy(
                seller = DraftField(m.name, uncertain = m.reason == SellerMatchReason.LAYOUT, source = draft.seller.source ?: draft.seller.text),
            )
            val usual = recognition.usualVatBasis
            if (usual != null && draft.vatBasis == VatBasis.UNKNOWN) draft = draft.copy(vatBasis = usual, vatBasisUncertain = true)
        }
        // The line amounts prove whether prices include VAT: no need to ask.
        if (draft.vatBasis == VatBasis.UNKNOWN || draft.vatBasisUncertain) {
            AutoAccept.inferVatBasis(draft)?.let { draft = draft.copy(vatBasis = it, vatBasisUncertain = false) }
        }
        // Numbers proven by the arithmetic over the whole document need no confirmation.
        draft = AutoAccept.settleProven(draft)
        val initial = draft

        // Link each line to a product: remembered for this supplier, recognised despite typos, or new.
        draft = draft.copy(
            items = runCatching { repo.autoAssign(draft.seller.text, draft.items, settings.autoLinkProducts) }
                .onFailure { log.error("autoAssign", it) }.getOrDefault(draft.items),
        )
        // Quantity and price the wrong way round? The product's own price history tells.
        runCatching { repo.fixSwappedQuantities(draft.items) }.getOrNull()?.let { fixed ->
            val swapped = fixed.zip(draft.items).count { (a, b) -> a.quantity.text != b.quantity.text }
            if (swapped > 0) log.event("QTY_PRICE_SWAPPED", "lines" to swapped)
            draft = draft.copy(items = fixed)
        }
        // A line that adds up two ways, which the operator already settled once for this supplier: same choice.
        val supplierKey = SupplierMemory.key(pending.ocrText, settings.ownVatNumber.ifBlank { null }, draft.seller.text)
        learning?.rules(supplierKey)?.takeIf { it.isNotEmpty() }?.let { rules ->
            val before = draft.items.count { it.choices.isNotEmpty() }
            draft = draft.copy(items = LineSolver.applyRules(draft.items, rules))
            val settled = before - draft.items.count { it.choices.isNotEmpty() }
            if (settled > 0) log.event("CHOICE_REMEMBERED", "lines" to settled)
        }
        val reasons = AutoAccept.reasons(draft)
        val changes = priceChanges(draft, null)
        logParsed(pending, draft, recognition, reasons, changes)
        return PreparedDraft(draft, initial, recognition, reasons, changes, ocrSellerRaw, supplierKey)
    }

    suspend fun priceChanges(d: DocumentDraft, excludeDocumentId: Long?): List<PriceChange> = runCatching {
        repo.priceChangesForDraft(d.seller.text, ItalianDates.parse(d.date.text), d.vatBasis, d.items, excludeDocumentId)
    }.getOrDefault(emptyList())

    /**
     * Saves without the operator when everything was read with confidence, adds up, and is not a possible duplicate
     * (and the operator allows it). Returns the saved document's id, or null if it needs a look.
     */
    suspend fun autoSave(pending: PendingImport, p: PreparedDraft): Long? {
        if (!settings.autoSave || p.reasons.isNotEmpty()) return null
        val valid = (DraftValidator.validate(p.draft) as? ValidationResult.Valid)?.document ?: return null
        if (repo.findDuplicates(valid, pending.file.sha256, null).isNotEmpty()) return null
        val id = repo.saveDocument(
            valid, pending.file, pending.ocrText, null,
            SellerLearning(pending.ocrText, p.ocrSellerRaw, settings.ownVatNumber.ifBlank { null }),
        )
        log.event("SAVED", "doc" to id, "new" to true, "auto" to true, "items" to valid.items.size, "priceChanges" to p.priceChanges.size)
        learning?.addExamples(p.supplierKey, SupplierMemory.examplesFrom(p.draft))
        runCatching { learning?.addLayout(p.supplierKey, com.kitchenreceipts.core.SupplierLayouts.learn(pending.parsed, p.draft)) }
        return id
    }

    /**
     * Price changes worth a notification (5% or more) for a document read in the background: of the saved
     * document when the app saved it, otherwise of the draft waiting to be checked. Empty if the operator turned
     * price notifications off.
     */
    suspend fun priceAlerts(savedDocumentId: Long?, p: PreparedDraft): List<PriceChange> {
        if (!settings.priceAlerts) return emptyList()
        val changes = if (savedDocumentId != null) {
            runCatching { repo.priceChangesForDocument(savedDocumentId) }.getOrDefault(emptyList())
        } else {
            p.priceChanges
        }
        return PriceWatch.alerts(changes)
    }

    private fun logParsed(
        pending: PendingImport,
        draft: DocumentDraft,
        recognition: SellerRecognition?,
        reasons: List<ReviewReason>,
        changes: List<PriceChange>,
    ) {
        val p = pending.parsed
        fun <T> f(e: Extracted<T>?) = if (e == null) "missing" else if (e.confidence == Confidence.HIGH) "ok" else "uncertain"
        log.event(
            "PARSED",
            "seller" to f(p.sellerName), "supplierMatch" to recognition?.match?.reason,
            "date" to f(p.documentDate), "number" to f(p.documentNumber), "total" to f(p.totalCents),
            "subtotal" to f(p.subtotalCents), "vat" to f(p.vatCents), "vatBasis" to (p.vatBasis?.value ?: "missing"),
            "items" to p.lineItems.size, "itemsUncertain" to draft.items.count { it.uncertainCount > 0 },
            "warnings" to p.warnings.joinToString(",").ifEmpty { null },
        )
        log.block("recognised text", pending.ocrText.lines().filter { it.isNotBlank() })
        log.event(
            "AUTO_CHECK",
            "reasons" to reasons.joinToString(",").ifEmpty { "none" },
            "linked" to draft.items.count { it.productId != null }, "newProducts" to draft.items.count { it.newProductName != null },
            "priceChanges" to changes.size,
        )
    }
}
