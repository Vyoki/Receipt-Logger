package com.kitchenreceipts.app.data

import androidx.room.withTransaction
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.core.CostCalculator
import com.kitchenreceipts.core.CostSummary
import com.kitchenreceipts.core.DocumentFingerprint
import com.kitchenreceipts.core.DuplicateDetector
import com.kitchenreceipts.core.DuplicateMatch
import com.kitchenreceipts.core.ProductMatching
import com.kitchenreceipts.core.PurchaseExportRow
import com.kitchenreceipts.core.PurchaseRecord
import com.kitchenreceipts.core.ReportDocument
import com.kitchenreceipts.core.SearchQuery
import com.kitchenreceipts.core.SellerCandidate
import com.kitchenreceipts.core.SellerMatch
import com.kitchenreceipts.core.SellerProfiles
import com.kitchenreceipts.core.VatBasis
import com.kitchenreceipts.core.UnitConversion
import com.kitchenreceipts.core.ValidDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate

data class DuplicateInfo(val match: DuplicateMatch, val row: FingerprintRow)

data class ProductWithSummary(val product: ProductEntity, val summary: CostSummary, val purchaseCount: Int)

data class UnassignedGroup(
    val sellerId: Long,
    val sellerName: String,
    val description: String,
    val aliasKey: String,
    val lineItemIds: List<Long>,
)

/** What a saved document teaches the app about its supplier. */
data class SellerLearning(val documentText: String, val ocrSellerName: String?, val ownVatNumber: String?)

/** A supplier recognised on a new scan, with what the app learned about them. */
data class SellerRecognition(val match: SellerMatch, val usualVatBasis: VatBasis?, val documentCount: Int)

class ProductNameTakenException(val existing: ProductEntity) : Exception("Product name already exists")

class ReceiptRepository(private val db: AppDatabase, private val files: FileStore) {

    /**
     * Called when one VAT number appears on documents of two different suppliers: it must be the
     * operator's own (printed as the customer on every invoice), so it is never used to identify a supplier.
     */
    var onSharedVatNumber: (String) -> Unit = {}

    private val documents = db.documentDao()
    private val sellers = db.sellerDao()
    private val products = db.productDao()

    // ------------------------------------------------------------ documents

    fun recentDocuments(limit: Int = 8) = documents.recent(limit)
    /**
     * Free-text search over seller, number, line descriptions and lots. Dates typed in the query
     * ("14/03/2025", "03/2025", "marzo 2025") become a date filter unless one is already set.
     * Seller names also match ignoring punctuation and legal form ("rossi srl" finds "Rossi S.r.l.").
     */
    fun searchDocuments(query: String, sellerId: Long?, from: LocalDate?, to: LocalDate?): Flow<List<DocumentListRow>> {
        val q = SearchQuery.parse(query)
        val useQueryDates = from == null && to == null
        val f = if (useQueryDates) q.from else from
        val t = if (useQueryDates) q.to else to
        val norm = DuplicateDetector.normalizeSeller(q.text) ?: ""
        return documents.search(q.text, norm, sellerId, f?.toEpochDay(), t?.toEpochDay())
    }
    fun documentDates() = documents.documentDates()
    fun observeDocument(id: Long) = documents.observeDocument(id)
    fun observeItems(id: Long) = documents.observeItems(id)
    suspend fun documentOnce(id: Long) = documents.documentById(id)
    suspend fun itemsOnce(id: Long) = documents.itemsOnce(id)

    suspend fun findDuplicates(doc: ValidDocument, sha256: String?, excludeId: Long?): List<DuplicateInfo> {
        val rows = documents.fingerprints()
        val candidate = DocumentFingerprint(excludeId ?: -1, doc.sellerName, doc.number, doc.date, doc.totalCents, sha256)
        val existing = rows.map { DocumentFingerprint(it.id, it.sellerName, it.documentNumber, it.documentDate, it.totalCents, it.fileSha256) }
        val byId = rows.associateBy { it.id }
        return DuplicateDetector.findDuplicates(candidate, existing).map { DuplicateInfo(it, byId.getValue(it.existingId)) }
    }

    /**
     * Saves a reviewed document and its line items in one transaction.
     * [existingId] = null creates a new document from [file]; otherwise the document is updated
     * and its line items are replaced (the original file is kept).
     */
    suspend fun saveDocument(
        doc: ValidDocument,
        file: StoredFile?,
        ocrText: String?,
        existingId: Long?,
        learning: SellerLearning? = null,
    ): Long =
        db.withTransaction {
            val seller = findOrCreateSeller(doc.sellerName)
            if (learning != null) learnSeller(seller, learning, countLayout = existingId == null)
            val now = System.currentTimeMillis()
            val id = if (existingId == null) {
                requireNotNull(file) { "A new document needs its original file" }
                documents.insertDocument(
                    DocumentEntity(
                        sellerId = seller.id, documentDate = doc.date, documentNumber = doc.number,
                        currency = doc.currency, subtotalCents = doc.subtotalCents, vatCents = doc.vatCents,
                        totalCents = doc.totalCents, vatBasis = doc.vatBasis, filePath = file.relativePath,
                        mimeType = file.mimeType, pageCount = file.pageCount, fileSha256 = file.sha256,
                        ocrText = ocrText, createdAt = now, updatedAt = now,
                    ),
                )
            } else {
                val old = requireNotNull(documents.documentById(existingId)) { "Document $existingId not found" }
                documents.updateDocument(
                    old.copy(
                        sellerId = seller.id, documentDate = doc.date, documentNumber = doc.number,
                        currency = doc.currency, subtotalCents = doc.subtotalCents, vatCents = doc.vatCents,
                        totalCents = doc.totalCents, vatBasis = doc.vatBasis, updatedAt = now,
                    ),
                )
                documents.deleteItemsForDocument(existingId)
                existingId
            }
            documents.insertItems(
                doc.items.mapIndexed { i, it ->
                    LineItemEntity(
                        documentId = id, position = i, originalDescription = it.description, productId = it.productId,
                        quantity = it.quantity, unit = it.unit, unitPrice = it.unitPrice, lineTotalCents = it.lineTotalCents,
                        vatRate = it.vatRatePercent, lotNumber = it.lotNumber, expiryDate = it.expiryDate,
                    )
                },
            )
            // Remember the user's own assignments for this seller (exact description match only).
            for (item in doc.items) {
                val pid = item.productId ?: continue
                products.upsertAlias(ProductAliasEntity(sellerId = seller.id, aliasKey = ProductMatching.aliasKey(item.description), productId = pid))
                item.itemCode?.let { code ->
                    products.upsertAlias(ProductAliasEntity(sellerId = seller.id, aliasKey = ProductMatching.codeKey(code), productId = pid))
                }
            }
            sellers.deleteUnused()
            id
        }

    suspend fun deleteDocument(id: Long) {
        val doc = documents.documentById(id) ?: return
        db.withTransaction {
            documents.deleteDocument(id) // line items cascade
            sellers.deleteUnused()
        }
        files.delete(doc.filePath)
    }

    /** Stores the supplier's VAT number, letterhead words and the OCR's spelling of their name. */
    private fun learnSeller(seller: SellerEntity, l: SellerLearning, countLayout: Boolean) {
        var vat = SellerProfiles.supplierVatNumber(l.documentText, l.ownVatNumber)
        if (vat != null) {
            val other = sellers.byVatNumber(vat)
            if (other != null && other.id != seller.id) {
                sellers.clearVat(vat)
                onSharedVatNumber(vat)
                vat = null
            }
        }
        val profile = if (countLayout) {
            SellerProfiles.mergeProfile(seller.headerProfile, SellerProfiles.headerTokens(l.documentText))
        } else {
            seller.headerProfile
        }
        val keep = seller.vatNumber?.takeIf { it != l.ownVatNumber }
        sellers.updateLearning(seller.id, keep ?: vat, profile)
        val alias = DuplicateDetector.normalizeSeller(l.ocrSellerName)
        if (alias != null && alias != seller.normalizedName) {
            sellers.upsertAlias(SellerAliasEntity(aliasKey = alias, sellerId = seller.id))
        }
    }

    /** Recognises the supplier of a new scan from what was learned on earlier documents. */
    suspend fun identifySeller(
        documentText: String,
        ocrSellerName: String?,
        ownVatNumber: String?,
        ocrSellerReliable: Boolean = false,
    ): SellerRecognition? {
        val all = sellers.allOnce()
        if (all.isEmpty()) return null
        val aliases = sellers.allAliases().groupBy({ it.sellerId }, { it.aliasKey })
        val candidates = all.map {
            SellerCandidate(it.id, it.name, it.vatNumber, SellerProfiles.parseProfile(it.headerProfile), aliases[it.id].orEmpty().toSet())
        }
        val result = SellerProfiles.identifyDetailed(candidates, documentText, ocrSellerName, ownVatNumber, ocrSellerReliable)
        result.suspectVatNumber?.let { vat ->
            // A clearly printed company name contradicts the VAT match: that number was learned by mistake
            // (it is the operator's, printed as the customer). Forget it for every supplier.
            forgetVatNumber(vat)
            onSharedVatNumber(vat)
        }
        val match = result.match ?: return null
        val bases = sellers.recentVatBases(match.sellerId)
        val usual = bases.distinct().singleOrNull()?.takeIf { it != VatBasis.UNKNOWN && bases.size >= 2 }
        return SellerRecognition(match, usual, sellers.documentCount(match.sellerId))
    }

    /** Removes a VAT number from all suppliers (used when it turns out to be the operator's own). */
    suspend fun forgetVatNumber(vat: String) = withContext(Dispatchers.IO) {
        if (vat.isNotBlank()) sellers.clearVat(vat.filter(Char::isDigit))
    }

    private fun findOrCreateSeller(name: String): SellerEntity {
        val normalized = normalizeSeller(name)
        return sellers.findByNormalized(normalized) ?: run {
            val entity = SellerEntity(name = name.trim(), normalizedName = normalized)
            entity.copy(id = sellers.insert(entity))
        }
    }

    /** Pre-fills products the user already assigned for this seller + description. */
    suspend fun rememberedProduct(sellerName: String, description: String, itemCode: String? = null): Long? {
        val seller = sellers.findByNormalizedSuspend(normalizeSeller(sellerName)) ?: return null
        // The supplier's article code first: it survives small misreadings of the description.
        itemCode?.let { code -> products.findAlias(seller.id, ProductMatching.codeKey(code))?.let { return it } }
        return products.findAlias(seller.id, ProductMatching.aliasKey(description))
    }

    suspend fun cleanupOrphanFiles() = withContext(Dispatchers.IO) {
        files.deleteOrphans(documents.allFilePaths().toSet())
    }

    // ------------------------------------------------------------ sellers

    fun sellers() = sellers.all()
    fun sellerStats() = sellers.stats()

    // ------------------------------------------------------------ products

    fun products() = products.all()
    suspend fun productsOnce() = products.allOnce()
    fun observeProduct(id: Long) = products.observe(id)
    fun purchasesForProduct(id: Long) = documents.purchasesForProduct(id)
    fun aliasesForProduct(id: Long) = products.aliasesForProduct(id)
    fun conversionsForProduct(id: Long) = products.conversionsForProduct(id)

    suspend fun createProduct(name: String): ProductEntity {
        val clean = name.trim()
        require(clean.isNotEmpty())
        val normalized = ProductMatching.aliasKey(clean)
        products.findByNormalized(normalized)?.let { throw ProductNameTakenException(it) }
        val entity = ProductEntity(name = clean, normalizedName = normalized, createdAt = System.currentTimeMillis())
        return entity.copy(id = products.insert(entity))
    }

    suspend fun renameProduct(id: Long, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty())
        val normalized = ProductMatching.aliasKey(clean)
        val other = products.findByNormalized(normalized)
        if (other != null && other.id != id) throw ProductNameTakenException(other)
        products.rename(id, clean, normalized)
    }

    suspend fun deleteProduct(id: Long) = products.delete(id) // line items keep their data, product_id -> NULL

    suspend fun deleteAlias(id: Long) = products.deleteAlias(id)

    suspend fun addConversion(productId: Long, from: String, to: String, factor: BigDecimal) {
        products.insertConversion(UnitConversionEntity(productId = productId, fromUnit = from, toUnit = to, factor = factor))
    }

    suspend fun deleteConversion(id: Long) = products.deleteConversion(id)

    /** Unassigned line items grouped by seller + exact description, for bulk assignment. */
    fun unassignedGroups(): Flow<List<UnassignedGroup>> = products.unassigned().map { rows ->
        rows.groupBy { it.sellerId to ProductMatching.aliasKey(it.originalDescription) }
            .map { (key, g) -> UnassignedGroup(key.first, g.first().sellerName, g.first().originalDescription, key.second, g.map { it.lineItemId }) }
            .sortedWith(compareByDescending<UnassignedGroup> { it.lineItemIds.size }.thenBy { it.sellerName })
    }

    suspend fun assignGroup(group: UnassignedGroup, productId: Long) = db.withTransaction {
        products.assign(group.lineItemIds, productId)
        products.upsertAliasSuspend(ProductAliasEntity(sellerId = group.sellerId, aliasKey = group.aliasKey, productId = productId))
    }

    fun productSummaries(): Flow<List<ProductWithSummary>> =
        combine(products.all(), documents.allPurchases(), products.allConversions()) { prods, purchases, conversions ->
            val byProduct = purchases.filter { it.productId != null }.groupBy { it.productId!! }
            val convByProduct = conversions.groupBy { it.productId }
            prods.map { p ->
                val rows = byProduct[p.id].orEmpty()
                ProductWithSummary(p, summarize(rows, convByProduct[p.id].orEmpty()), rows.size)
            }
        }

    fun summarize(rows: List<PurchaseRow>, conversions: List<UnitConversionEntity>): CostSummary =
        CostCalculator.summarize(
            rows.map {
                PurchaseRecord(it.lineItemId, it.documentId, it.documentDate, it.sellerName, it.quantity, it.unit, it.unitPrice, it.lineTotalCents, it.vatBasis, it.lotNumber)
            },
            conversions.mapNotNull { c -> runCatching { UnitConversion(c.fromUnit, c.toUnit, c.factor) }.getOrNull() },
        )

    // ------------------------------------------------------------ reports

    fun reportDocuments(): Flow<List<ReportDocument>> = documents.reportRows().map { rows ->
        rows.map { ReportDocument(it.id, it.sellerName, it.documentDate, it.documentNumber, it.totalCents, it.itemCount) }
    }

    suspend fun purchaseExportRows(): List<PurchaseExportRow> = documents.allPurchasesOnce().map {
        PurchaseExportRow(
            it.documentDate, it.sellerName, it.documentNumber, it.documentId, it.originalDescription, it.productName,
            it.quantity, it.unit, it.unitPrice, it.lineTotalCents, it.vatBasis, it.lotNumber,
        )
    }

    companion object {
        fun normalizeSeller(name: String): String =
            DuplicateDetector.normalizeSeller(name) ?: name.trim().lowercase()
    }
}
