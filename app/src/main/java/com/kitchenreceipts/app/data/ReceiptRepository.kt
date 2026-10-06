package com.kitchenreceipts.app.data

import androidx.room.withTransaction
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.core.OfficeExport
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.core.Categories
import com.kitchenreceipts.core.Category
import com.kitchenreceipts.core.CostCalculator
import com.kitchenreceipts.core.Inventory
import com.kitchenreceipts.core.InventoryPurchase
import com.kitchenreceipts.core.InventoryReport
import com.kitchenreceipts.core.LineItemDraft
import com.kitchenreceipts.core.Period
import com.kitchenreceipts.core.PriceChange
import com.kitchenreceipts.core.PricePoint
import com.kitchenreceipts.core.PriceWatch
import com.kitchenreceipts.core.ProductCandidate
import com.kitchenreceipts.core.ProductFamilies
import com.kitchenreceipts.core.ProductSource
import com.kitchenreceipts.core.SmartMatcher
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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
            // Lines the app could not link to an existing product become new products (one per name).
            val createdProducts = doc.items.withIndex().mapNotNull { (i, it) ->
                val name = it.newProductName?.takeIf { _ -> it.productId == null } ?: return@mapNotNull null
                i to findOrCreateProduct(name, it.newProductBrand)
            }.toMap()
            documents.insertItems(
                doc.items.mapIndexed { i, it ->
                    LineItemEntity(
                        documentId = id, position = i, originalDescription = it.description, productId = it.productId ?: createdProducts[i],
                        quantity = it.quantity, unit = it.unit, unitPrice = it.unitPrice, lineTotalCents = it.lineTotalCents,
                        vatRate = it.vatRatePercent, lotNumber = it.lotNumber, expiryDate = it.expiryDate,
                        packages = it.packages,
                        packSize = it.packSize,
                    )
                },
            )
            // The pack size printed on the invoice ("GR 500") tells how much one piece of this product holds: kept as the
            // product's conversion (1 pz = 500 g), so inventory, averages and price comparisons can work in kg or litres.
            for ((index, item) in doc.items.withIndex()) {
                val pid = item.productId ?: createdProducts[index] ?: continue
                val size = com.kitchenreceipts.core.PackSizes.fromText(item.packSize) ?: continue
                val from = com.kitchenreceipts.core.Units.normalize(item.unit) ?: continue
                if (from != "pz" || com.kitchenreceipts.core.Units.dimension(size.unit) == null) continue
                if (products.conversionCountFrom(pid, from) > 0) continue
                products.insertConversionIfAbsent(UnitConversionEntity(productId = pid, fromUnit = from, toUnit = size.unit, factor = size.amount))
            }
            // Remember the assignments for this seller (description and article code).
            for ((index, item) in doc.items.withIndex()) {
                val pid = item.productId ?: createdProducts[index] ?: continue
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

    private fun findOrCreateProduct(name: String, brand: String? = null): Long {
        val clean = name.trim()
        val normalized = ProductMatching.aliasKey(clean)
        products.findByNormalizedBlocking(normalized)?.let { return it.id }
        return products.insertBlocking(
            ProductEntity(name = clean, normalizedName = normalized, createdAt = System.currentTimeMillis(), brand = brand?.trim()?.ifEmpty { null }),
        )
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

    /** Deletes stored originals that belong to no document; [keep] = files still used elsewhere (being read). */
    suspend fun cleanupOrphanFiles(keep: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        files.deleteOrphans(documents.allFilePaths().toSet() + keep)
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

    // ------------------------------------------------------------ product groups

    fun families() = products.families()
    fun observeFamily(id: Long) = products.observeFamily(id)
    fun productsInFamily(id: Long) = products.productsInFamily(id)

    /** Groups the app proposes (the operator confirms each one; nothing is grouped by itself). */
    fun familySuggestions(): Flow<List<ProductFamilies.Suggestion>> =
        combine(products.all(), products.families()) { prods, fams ->
            ProductFamilies.suggestions(
                prods.map { ProductFamilies.Member(it.id, it.name, it.familyId, it.familyDismissed) },
                fams.map { ProductFamilies.Family(it.id, it.name) },
            )
        }.flowOn(Dispatchers.Default)

    /** Puts [productIds] in the group called [name] (created if needed); returns the group's id. */
    suspend fun addToFamily(name: String, productIds: List<Long>): Long = db.withTransaction {
        val clean = name.trim()
        require(clean.isNotEmpty())
        val key = ProductFamilies.key(clean)
        val id = products.findFamilyByNormalized(key)?.id
            ?: products.insertFamily(ProductFamilyEntity(name = clean, normalizedName = key, createdAt = System.currentTimeMillis()))
        if (productIds.isNotEmpty()) products.setFamily(productIds, id)
        id
    }

    suspend fun setProductFamily(productId: Long, familyId: Long?) = products.setFamily(listOf(productId), familyId)

    /** The operator said no: these products are not suggested for a group again (they can still be added by hand). */
    suspend fun dismissFamilySuggestion(productIds: List<Long>) = products.dismissFamily(productIds)

    suspend fun renameFamily(id: Long, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty())
        val key = ProductFamilies.key(clean)
        val other = products.findFamilyByNormalized(key)
        require(other == null || other.id == id) { "A group with this name already exists" }
        products.renameFamily(id, clean, key)
    }

    /** Deletes the group only; its products stay as they are. */
    suspend fun deleteFamily(id: Long) = db.withTransaction {
        products.clearFamily(id)
        products.deleteFamily(id)
    }

    suspend fun setBrand(productId: Long, brand: String?) = products.setBrand(productId, brand?.trim()?.ifEmpty { null })

    /** The latest price of each product of a group from each supplier, per kg or l where the size is known. */
    fun familyComparison(familyId: Long): Flow<List<ProductFamilies.VariantPrice>> =
        combine(products.productsInFamily(familyId), documents.allPurchases(), products.allConversions()) { members, rows, conversions ->
            val ids = members.map { it.id }.toSet()
            val conv = conversions.filter { it.productId in ids }.groupBy { it.productId }.mapValues { (_, list) ->
                list.mapNotNull { c -> runCatching { UnitConversion(c.fromUnit, c.toUnit, c.factor) }.getOrNull() }
            }
            ProductFamilies.compare(
                members.map { ProductFamilies.Variant(it.id, it.name, it.brand) },
                rows.filter { it.productId in ids }.map { it.toPricePoint() },
                conv,
            )
        }.flowOn(Dispatchers.Default)

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

    suspend fun setCategory(productId: Long, category: Category) = products.setCategory(productId, category.key)

    /**
     * Products whose category was stored by the old guesser (not chosen by the operator) go back to "guessed from the
     * name", so the better rules apply to them too (e.g. every biscuit in one category). Run once after the update.
     */
    suspend fun regroupGuessedCategories(): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (p in products.allOnce()) {
            if (Categories.wasGuessedByOldRules(p.name, p.category)) { products.setCategory(p.id, null); n++ }
        }
        n
    }

    /**
     * Merges [from] into [into] (the operator's choice): its purchases, remembered descriptions and unit
     * conversions move over, then [from] is deleted. Nothing is merged automatically.
     */
    suspend fun mergeProducts(from: Long, into: Long) {
        require(from != into)
        db.withTransaction {
            products.moveLineItems(from, into)
            products.moveAliases(from, into)
            products.moveConversions(from, into)
            products.delete(from)
        }
    }

    /** Existing products with their already-linked descriptions (from any supplier). */
    suspend fun productCandidates(): List<ProductCandidate> {
        val aliases = products.allAliasKeys().filterNot { it.aliasKey.startsWith("#") }.groupBy({ it.productId }, { it.aliasKey })
        return products.allOnce().map { ProductCandidate(it.id, it.name, aliases[it.id].orEmpty()) }
    }

    /**
     * Links every line of a new scan to a product without asking, where that is safe:
     * 1. what the operator linked before for this supplier (article code, then description);
     * 2. a product recognised despite spelling differences, abbreviations or misreadings;
     * 3. otherwise (if [createNew]) a new product named after the description, created on save.
     * Lines already linked are left alone.
     */
    suspend fun autoAssign(sellerName: String, items: List<LineItemDraft>, createNew: Boolean): List<LineItemDraft> {
        val candidates = productCandidates()
        val names = candidates.associate { it.id to it.name }
        return items.map { item ->
            if (item.productId != null || item.description.text.isBlank()) return@map item
            val remembered = if (sellerName.isNotBlank()) rememberedProduct(sellerName, item.description.text, item.itemCode) else null
            if (remembered != null && names.containsKey(remembered)) {
                return@map item.copy(productId = remembered, productName = names[remembered], productSource = ProductSource.REMEMBERED, newProductName = null)
            }
            val match = SmartMatcher.bestMatch(item.description.text, candidates)
            if (match != null && match.automatic) {
                return@map item.copy(productId = match.productId, productName = match.name, productSource = ProductSource.RECOGNISED, newProductName = null)
            }
            if (createNew) {
                // A readable name: brand and size moved to their fields, the trade's abbreviations written out.
                val clean = com.kitchenreceipts.core.ProductNames.clean(item.description.text, com.kitchenreceipts.core.PackSizes.fromText(item.packSize.text))
                item.copy(
                    newProductName = clean.name,
                    newProductBrand = clean.brand,
                    nameUnknown = clean.unknown,
                    packSize = clean.size?.takeIf { item.packSize.text.isBlank() }?.let { com.kitchenreceipts.core.DraftField(it.text, false, item.description.text) } ?: item.packSize,
                    productSource = ProductSource.NEW,
                )
            } else {
                item
            }
        }
    }

    /**
     * Swaps quantity and price on linked lines where the product's purchase history shows they were read the
     * wrong way round (the "quantity" is what it usually costs). The amount does not change.
     */
    suspend fun fixSwappedQuantities(items: List<LineItemDraft>): List<LineItemDraft> {
        val ids = items.mapNotNull { it.productId }.toSet()
        if (ids.isEmpty()) return items
        val last = documents.allPurchasesOnce().filter { it.productId in ids }
            .groupBy { it.productId!! }
            .mapValues { (_, rows) -> rows.mapNotNull { r -> PriceWatch.unitCost(r.toPricePoint())?.let { r.unit to it.second } } }
        return items.map { item ->
            val pid = item.productId ?: return@map item
            val q = com.kitchenreceipts.core.ItalianNumbers.parse(item.quantity.text) ?: return@map item
            val p = com.kitchenreceipts.core.ItalianNumbers.parse(item.unitPrice.text) ?: return@map item
            val unit = com.kitchenreceipts.core.Units.normalize(item.unit.text)
            val usual = last[pid].orEmpty().firstOrNull { com.kitchenreceipts.core.Units.normalize(it.first) == unit }?.second ?: return@map item
            if (PriceWatch.looksSwapped(q, p, usual)) item.copy(quantity = item.unitPrice, unitPrice = item.quantity) else item
        }
    }

    // ------------------------------------------------------------ price changes

    private fun PurchaseRow.toPricePoint() = PricePoint(
        productId!!, productName ?: originalDescription, documentId, lineItemId, documentDate, sellerName,
        quantity, unit, unitPrice, lineTotalCents, vatBasis,
    )

    /** Price changes of a document not saved yet, against everything bought before. */
    suspend fun priceChangesForDraft(
        sellerName: String,
        date: LocalDate?,
        vatBasis: VatBasis,
        items: List<LineItemDraft>,
        excludeDocumentId: Long?,
    ): List<PriceChange> {
        val linked = items.filter { it.productId != null }
        if (linked.isEmpty()) return emptyList()
        val ids = linked.mapNotNull { it.productId }.toSet()
        val history = documents.allPurchasesOnce()
            .filter { it.productId in ids && it.documentId != excludeDocumentId }
            .map { it.toPricePoint() }
        return linked.mapIndexedNotNull { i, item ->
            val point = PricePoint(
                item.productId!!, item.productName ?: item.description.text, Long.MAX_VALUE, Long.MAX_VALUE - i,
                date, sellerName, com.kitchenreceipts.core.ItalianNumbers.parse(item.quantity.text), item.unit.text.ifBlank { null },
                com.kitchenreceipts.core.ItalianNumbers.parse(item.unitPrice.text),
                com.kitchenreceipts.core.ItalianNumbers.parse(item.lineTotal.text)?.let { com.kitchenreceipts.core.ItalianNumbers.toCents(it) },
                vatBasis,
            )
            PriceWatch.compare(point, history)
        }
    }

    /** Price changes on a saved document. */
    suspend fun priceChangesForDocument(documentId: Long): List<PriceChange> {
        val all = documents.allPurchasesOnce().filter { it.productId != null }
        val ids = all.filter { it.documentId == documentId }.mapNotNull { it.productId }.toSet()
        val history = all.filter { it.productId in ids }.map { it.toPricePoint() }
        return history.filter { it.documentId == documentId }.mapNotNull { PriceWatch.compare(it, history) }
    }

    /** Every price change in the purchase history, most recent first. */
    fun priceHistory(): Flow<List<PriceChange>> = documents.allPurchases().map { rows ->
        PriceWatch.history(rows.filter { it.productId != null }.map { it.toPricePoint() })
    }.flowOn(Dispatchers.Default)

    // ------------------------------------------------------------ inventory

    fun inventory(period: Period): Flow<InventoryReport> =
        combine(documents.allPurchases(), products.allConversions()) { rows, conversions ->
            val conv = conversions.groupBy { it.productId }.mapValues { (_, list) ->
                list.mapNotNull { c -> runCatching { UnitConversion(c.fromUnit, c.toUnit, c.factor) }.getOrNull() }
            }
            val purchases = rows.map {
                val name = it.productName ?: it.originalDescription
                InventoryPurchase(
                    it.productId, name, Category.fromKey(it.productCategory) ?: Categories.guess(name),
                    it.documentDate, it.quantity, it.unit, it.lineTotalCents, it.vatBasis,
                )
            }
            Inventory.report(purchases, period, conv)
        }.flowOn(Dispatchers.Default)

    /** One saved document as the operator confirmed it, for reading it again (see ReadingChecker). */
    data class CheckDocument(
        val id: Long, val label: String, val mimeType: String, val ocrText: String?, val confirmed: com.kitchenreceipts.core.ReadingCheck.Confirmed,
    )

    suspend fun readingCheckDocuments(): List<CheckDocument> = withContext(Dispatchers.Default) {
        val names = sellers.allOnce().associate { it.id to it.name }
        val lines = documents.allItemsOnce().groupBy { it.documentId }
        documents.allDocumentsOnce().map { d ->
            CheckDocument(
                d.id,
                "${names[d.sellerId] ?: "?"} ${d.documentNumber.orEmpty()} ${d.documentDate?.let(com.kitchenreceipts.core.ItalianDates::format).orEmpty()}".trim(),
                d.mimeType, d.ocrText,
                com.kitchenreceipts.core.ReadingCheck.Confirmed(
                    d.documentDate, d.documentNumber, d.totalCents, d.subtotalCents, d.vatCents,
                    lines[d.id].orEmpty().mapNotNull { it.lineTotalCents },
                ),
            )
        }
    }

    /** Everything recorded, for the office copy (see core OfficeExport). Files (photos, PDFs, XML) are not included. */
    suspend fun officeSnapshot(business: String?): OfficeExport.Snapshot = withContext(Dispatchers.Default) {
        val lines = documents.allItemsOnce().groupBy { it.documentId }
        OfficeExport.Snapshot(
            business = business?.ifBlank { null },
            exportedAt = java.time.LocalDateTime.now(),
            sellers = sellers.allOnce().map { OfficeExport.Seller(it.id, it.name, it.vatNumber) },
            families = products.familiesOnce().map { OfficeExport.Family(it.id, it.name) },
            products = products.allOnce().map { p ->
                val chosen = com.kitchenreceipts.core.Category.fromKey(p.category)
                OfficeExport.Product(p.id, p.name, p.brand, (chosen ?: com.kitchenreceipts.core.Categories.guess(p.name)).key, chosen != null, p.familyId)
            },
            documents = documents.allDocumentsOnce().map { d ->
                OfficeExport.Document(
                    d.id, d.sellerId, d.documentDate, d.documentNumber, d.currency, d.subtotalCents, d.vatCents, d.totalCents, d.vatBasis,
                    when (d.mimeType) { FileStore.MIME_XML -> "e-invoice"; FileStore.MIME_PDF -> "pdf"; else -> "photo" },
                    lines[d.id].orEmpty().map { l ->
                        OfficeExport.Line(l.originalDescription, l.productId, l.quantity, l.unit, l.unitPrice, l.lineTotalCents, l.vatRate, l.lotNumber, l.expiryDate, l.packages, l.packSize)
                    },
                )
            },
        )
    }

    suspend fun bossReport(period: Period): com.kitchenreceipts.core.BossReport = withContext(Dispatchers.Default) {
        val rows = documents.allPurchasesOnce()
        val docs = documents.reportRows().first().map {
            ReportDocument(it.id, it.sellerName, it.documentDate, it.documentNumber, it.totalCents, it.itemCount)
        }
        val conv = products.allConversions().first().groupBy { it.productId }.mapValues { (_, list) ->
            list.mapNotNull { c -> runCatching { UnitConversion(c.fromUnit, c.toUnit, c.factor) }.getOrNull() }
        }
        val purchases = rows.map {
            val name = it.productName ?: it.originalDescription
            InventoryPurchase(it.productId, name, Category.fromKey(it.productCategory) ?: Categories.guess(name), it.documentDate, it.quantity, it.unit, it.lineTotalCents, it.vatBasis)
        }
        com.kitchenreceipts.core.BossReports.build(period, docs, purchases, rows.filter { it.productId != null }.map { it.toPricePoint() }, conv)
    }

    /** Products that look like the same thing, for the operator to merge if they agree. */
    suspend fun possibleDuplicateProducts(): List<Pair<ProductCandidate, ProductCandidate>> {
        val all = products.allOnce().map { ProductCandidate(it.id, it.name) }
        return withContext(Dispatchers.Default) { SmartMatcher.possibleDuplicates(all) }
    }

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
