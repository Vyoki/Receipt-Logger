package com.kitchenreceipts.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kitchenreceipts.core.VatBasis
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.LocalDate

// ---------------------------------------------------------------- query rows

data class DocumentListRow(
    val id: Long,
    val sellerId: Long,
    val sellerName: String,
    val documentDate: LocalDate?,
    val documentNumber: String?,
    val totalCents: Long?,
    val currency: String?,
    val mimeType: String,
    val itemCount: Int,
)

data class DocumentWithSeller(
    @Embedded val document: DocumentEntity,
    @ColumnInfo(name = "seller_name") val sellerName: String,
)

data class LineItemRow(
    @Embedded val item: LineItemEntity,
    @ColumnInfo(name = "product_name") val productName: String?,
)

data class FingerprintRow(
    val id: Long,
    val sellerName: String,
    val documentNumber: String?,
    val documentDate: LocalDate?,
    val totalCents: Long?,
    val fileSha256: String,
)

data class ReportRow(
    val id: Long,
    val sellerName: String,
    val documentDate: LocalDate?,
    val documentNumber: String?,
    val totalCents: Long?,
    val itemCount: Int,
)

data class PurchaseRow(
    val lineItemId: Long,
    val documentId: Long,
    val productId: Long?,
    val productName: String?,
    val documentDate: LocalDate?,
    val sellerName: String,
    val documentNumber: String?,
    val originalDescription: String,
    val quantity: BigDecimal?,
    val unit: String?,
    val unitPrice: BigDecimal?,
    val lineTotalCents: Long?,
    val vatBasis: VatBasis,
    val lotNumber: String?,
    val currency: String?,
    val productCategory: String? = null,
)

data class SellerStatsRow(
    val id: Long,
    val name: String,
    val documentCount: Int,
    val totalCents: Long?,
    val lastDate: LocalDate?,
)

data class SellerAliasRow(val aliasKey: String, val sellerId: Long)

data class UnassignedRow(
    val lineItemId: Long,
    val documentId: Long,
    val sellerId: Long,
    val sellerName: String,
    val originalDescription: String,
)

data class ProductAliasKeyRow(val productId: Long, val aliasKey: String)

data class AliasRow(
    val id: Long,
    val sellerName: String,
    val aliasKey: String,
)

// ---------------------------------------------------------------- DAOs

private const val LIST_COLUMNS = """
    d.id AS id, d.seller_id AS sellerId, s.name AS sellerName, d.document_date AS documentDate,
    d.document_number AS documentNumber, d.total_cents AS totalCents, d.currency AS currency,
    d.mime_type AS mimeType,
    (SELECT COUNT(*) FROM line_items li WHERE li.document_id = d.id) AS itemCount
"""

private const val PURCHASE_SELECT = """
    SELECT li.id AS lineItemId, li.document_id AS documentId, li.product_id AS productId, p.name AS productName,
           d.document_date AS documentDate, s.name AS sellerName, d.document_number AS documentNumber,
           li.original_description AS originalDescription, li.quantity AS quantity, li.unit AS unit,
           li.unit_price AS unitPrice, li.line_total_cents AS lineTotalCents, d.vat_basis AS vatBasis,
           li.lot_number AS lotNumber, d.currency AS currency, p.category AS productCategory
    FROM line_items li
    JOIN documents d ON d.id = li.document_id
    JOIN sellers s ON s.id = d.seller_id
    LEFT JOIN products p ON p.id = li.product_id
    WHERE d.covered_by IS NULL AND IFNULL(d.kind, '') != 'CREDIT_NOTE'
"""

@Dao
interface DocumentDao {

    @Insert fun insertDocument(doc: DocumentEntity): Long

    @Update fun updateDocument(doc: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    fun deleteDocument(id: Long)

    @Insert fun insertItems(items: List<LineItemEntity>)

    @Query("DELETE FROM line_items WHERE document_id = :documentId")
    fun deleteItemsForDocument(documentId: Long)

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun documentById(id: Long): DocumentEntity?

    @Query("SELECT d.*, s.name AS seller_name FROM documents d JOIN sellers s ON s.id = d.seller_id WHERE d.id = :id")
    fun observeDocument(id: Long): Flow<DocumentWithSeller?>

    @Query(
        "SELECT li.*, p.name AS product_name FROM line_items li LEFT JOIN products p ON p.id = li.product_id " +
            "WHERE li.document_id = :documentId ORDER BY li.position",
    )
    fun observeItems(documentId: Long): Flow<List<LineItemRow>>

    @Query(
        "SELECT li.*, p.name AS product_name FROM line_items li LEFT JOIN products p ON p.id = li.product_id " +
            "WHERE li.document_id = :documentId ORDER BY li.position",
    )
    suspend fun itemsOnce(documentId: Long): List<LineItemRow>

    @Query("SELECT $LIST_COLUMNS FROM documents d JOIN sellers s ON s.id = d.seller_id ORDER BY d.created_at DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<DocumentListRow>>

    @Query(
        """
        SELECT $LIST_COLUMNS FROM documents d JOIN sellers s ON s.id = d.seller_id
        WHERE (:sellerId IS NULL OR d.seller_id = :sellerId)
          AND (:fromDay IS NULL OR d.document_date >= :fromDay)
          AND (:toDay IS NULL OR d.document_date <= :toDay)
          AND (:query = ''
               OR s.name LIKE '%' || :query || '%'
               OR (:normQuery != '' AND s.normalized_name LIKE '%' || :normQuery || '%')
               OR IFNULL(d.document_number, '') LIKE '%' || :query || '%'
               OR EXISTS (SELECT 1 FROM line_items li WHERE li.document_id = d.id
                          AND (li.original_description LIKE '%' || :query || '%'
                               OR IFNULL(li.lot_number, '') LIKE '%' || :query || '%')))
        ORDER BY (d.document_date IS NULL), d.document_date DESC, d.id DESC
        """,
    )
    fun search(query: String, normQuery: String, sellerId: Long?, fromDay: Long?, toDay: Long?): Flow<List<DocumentListRow>>

    @Query("SELECT DISTINCT document_date FROM documents WHERE document_date IS NOT NULL")
    fun documentDates(): Flow<List<LocalDate>>

    @Query(
        "SELECT d.id AS id, s.name AS sellerName, d.document_number AS documentNumber, d.document_date AS documentDate, " +
            "d.total_cents AS totalCents, d.file_sha256 AS fileSha256 FROM documents d JOIN sellers s ON s.id = d.seller_id",
    )
    suspend fun fingerprints(): List<FingerprintRow>

    @Query(
        "SELECT d.id AS id, s.name AS sellerName, d.document_date AS documentDate, d.document_number AS documentNumber, " +
            // A credit note is money back; a delivery note charged on an invoice is counted on the invoice.
            "CASE WHEN d.kind = 'CREDIT_NOTE' THEN -d.total_cents ELSE d.total_cents END AS totalCents, " +
            "(SELECT COUNT(*) FROM line_items li WHERE li.document_id = d.id) AS itemCount " +
            "FROM documents d JOIN sellers s ON s.id = d.seller_id WHERE d.covered_by IS NULL",
    )
    fun reportRows(): Flow<List<ReportRow>>

    @Query("SELECT file_path FROM documents")
    suspend fun allFilePaths(): List<String>

    /** Everything, for the office copy. */
    @Query("SELECT * FROM documents ORDER BY (document_date IS NULL), document_date, id")
    suspend fun allDocumentsOnce(): List<DocumentEntity>

    @Query("SELECT * FROM line_items ORDER BY document_id, position")
    suspend fun allItemsOnce(): List<LineItemEntity>

    @Query("$PURCHASE_SELECT ORDER BY d.document_date DESC, li.document_id DESC, li.position")
    fun allPurchases(): Flow<List<PurchaseRow>>

    @Query("$PURCHASE_SELECT ORDER BY d.document_date DESC, li.document_id DESC, li.position")
    suspend fun allPurchasesOnce(): List<PurchaseRow>

    /** The purchases of a few products, in the same order as [allPurchasesOnce] (at most 900 ids per call). */
    @Query("$PURCHASE_SELECT AND li.product_id IN (:productIds) ORDER BY d.document_date DESC, li.document_id DESC, li.position")
    suspend fun purchasesOfProductsOnce(productIds: List<Long>): List<PurchaseRow>

    @Query("SELECT DISTINCT product_id FROM line_items WHERE document_id = :documentId AND product_id IS NOT NULL")
    suspend fun productIdsOfDocument(documentId: Long): List<Long>

    @Query("$PURCHASE_SELECT AND li.product_id = :productId ORDER BY (d.document_date IS NULL), d.document_date DESC, li.id DESC")
    fun purchasesForProduct(productId: Long): Flow<List<PurchaseRow>>
}

@Dao
interface SellerDao {

    @Query("SELECT * FROM sellers WHERE normalized_name = :normalized LIMIT 1")
    fun findByNormalized(normalized: String): SellerEntity?

    @Query("SELECT * FROM sellers WHERE normalized_name = :normalized LIMIT 1")
    suspend fun findByNormalizedSuspend(normalized: String): SellerEntity?

    @Insert fun insert(seller: SellerEntity): Long

    @Query("SELECT * FROM sellers ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<SellerEntity>>

    @Query(
        """
        SELECT s.id AS id, s.name AS name, COUNT(d.id) AS documentCount,
               SUM(CASE WHEN d.covered_by IS NOT NULL THEN 0 WHEN d.kind = 'CREDIT_NOTE' THEN -d.total_cents ELSE d.total_cents END) AS totalCents,
               MAX(d.document_date) AS lastDate
        FROM sellers s LEFT JOIN documents d ON d.seller_id = s.id
        GROUP BY s.id ORDER BY s.name COLLATE NOCASE
        """,
    )
    fun stats(): Flow<List<SellerStatsRow>>

    @Query("DELETE FROM sellers WHERE id NOT IN (SELECT seller_id FROM documents)")
    fun deleteUnused()

    @Query("SELECT * FROM sellers")
    suspend fun allOnce(): List<SellerEntity>

    @Query("SELECT * FROM sellers WHERE id = :id")
    fun byId(id: Long): SellerEntity?

    @Query("SELECT * FROM sellers WHERE vat_number = :vat LIMIT 1")
    fun byVatNumber(vat: String): SellerEntity?

    @Query("UPDATE sellers SET vat_number = NULL WHERE vat_number = :vat")
    fun clearVat(vat: String)

    @Query("UPDATE sellers SET vat_number = :vat, header_profile = :profile WHERE id = :id")
    fun updateLearning(id: Long, vat: String?, profile: String?)

    @Query("SELECT alias_key AS aliasKey, seller_id AS sellerId FROM seller_aliases")
    suspend fun allAliases(): List<SellerAliasRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAlias(alias: SellerAliasEntity)

    /** VAT basis of this supplier's most recent documents (most recent first). */
    @Query("SELECT vat_basis FROM documents WHERE seller_id = :sellerId ORDER BY created_at DESC LIMIT 5")
    suspend fun recentVatBases(sellerId: Long): List<VatBasis>

    @Query("SELECT COUNT(*) FROM documents WHERE seller_id = :sellerId")
    suspend fun documentCount(sellerId: Long): Int
}

@Dao
interface ProductDao {

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE")
    suspend fun allOnce(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :id")
    fun observe(id: Long): Flow<ProductEntity?>

    @Query("SELECT * FROM products WHERE normalized_name = :normalized LIMIT 1")
    suspend fun findByNormalized(normalized: String): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(product: ProductEntity): Long

    @Query("UPDATE products SET name = :name, normalized_name = :normalized WHERE id = :id")
    suspend fun rename(id: Long, name: String, normalized: String)

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT product_id FROM product_aliases WHERE seller_id = :sellerId AND alias_key = :aliasKey LIMIT 1")
    suspend fun findAlias(sellerId: Long, aliasKey: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAlias(alias: ProductAliasEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAliasSuspend(alias: ProductAliasEntity)

    @Query(
        "SELECT a.id AS id, s.name AS sellerName, a.alias_key AS aliasKey FROM product_aliases a " +
            "JOIN sellers s ON s.id = a.seller_id WHERE a.product_id = :productId ORDER BY s.name, a.alias_key",
    )
    fun aliasesForProduct(productId: Long): Flow<List<AliasRow>>

    @Query("DELETE FROM product_aliases WHERE id = :id")
    suspend fun deleteAlias(id: Long)

    @Query("SELECT * FROM unit_conversions WHERE product_id = :productId ORDER BY from_unit, to_unit")
    fun conversionsForProduct(productId: Long): Flow<List<UnitConversionEntity>>

    @Query("SELECT * FROM unit_conversions")
    fun allConversions(): Flow<List<UnitConversionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversion(c: UnitConversionEntity)

    /** Adds a conversion only if the product has none for that pair of units (the operator's own ones win). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertConversionIfAbsent(c: UnitConversionEntity): Long

    @Query("SELECT COUNT(*) FROM unit_conversions WHERE product_id = :productId AND from_unit = :fromUnit")
    fun conversionCountFrom(productId: Long, fromUnit: String): Int

    @Query("DELETE FROM unit_conversions WHERE id = :id")
    suspend fun deleteConversion(id: Long)

    @Query(
        "SELECT li.id AS lineItemId, li.document_id AS documentId, d.seller_id AS sellerId, s.name AS sellerName, " +
            "li.original_description AS originalDescription FROM line_items li " +
            "JOIN documents d ON d.id = li.document_id JOIN sellers s ON s.id = d.seller_id " +
            "WHERE li.product_id IS NULL ORDER BY s.name, li.original_description",
    )
    fun unassigned(): Flow<List<UnassignedRow>>

    @Query("UPDATE line_items SET product_id = :productId WHERE id IN (:lineItemIds)")
    suspend fun assign(lineItemIds: List<Long>, productId: Long?)

    @Query("SELECT * FROM products WHERE normalized_name = :normalized LIMIT 1")
    fun findByNormalizedBlocking(normalized: String): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertBlocking(product: ProductEntity): Long

    /** Every description (and "#code") already linked to a product, for recognising products in new scans. */
    @Query("SELECT product_id AS productId, alias_key AS aliasKey FROM product_aliases")
    suspend fun allAliasKeys(): List<ProductAliasKeyRow>

    @Query("UPDATE products SET category = :category WHERE id = :id")
    suspend fun setCategory(id: Long, category: String?)

    // ---- merging two products (operator's choice): everything of [from] moves to [into]
    @Query("UPDATE line_items SET product_id = :into WHERE product_id = :from")
    suspend fun moveLineItems(from: Long, into: Long)

    @Query("UPDATE OR REPLACE product_aliases SET product_id = :into WHERE product_id = :from")
    suspend fun moveAliases(from: Long, into: Long)

    @Query("UPDATE OR IGNORE unit_conversions SET product_id = :into WHERE product_id = :from")
    suspend fun moveConversions(from: Long, into: Long)

    // ---- product groups (v6)
    @Query("SELECT * FROM product_families ORDER BY name COLLATE NOCASE")
    fun families(): Flow<List<ProductFamilyEntity>>

    @Query("SELECT * FROM product_families ORDER BY name COLLATE NOCASE")
    suspend fun familiesOnce(): List<ProductFamilyEntity>

    @Query("SELECT * FROM product_families WHERE id = :id")
    fun observeFamily(id: Long): Flow<ProductFamilyEntity?>

    @Query("SELECT * FROM product_families WHERE normalized_name = :normalized LIMIT 1")
    suspend fun findFamilyByNormalized(normalized: String): ProductFamilyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFamily(family: ProductFamilyEntity): Long

    @Query("UPDATE product_families SET name = :name, normalized_name = :normalized WHERE id = :id")
    suspend fun renameFamily(id: Long, name: String, normalized: String)

    @Query("DELETE FROM product_families WHERE id = :id")
    suspend fun deleteFamily(id: Long)

    @Query("UPDATE products SET family_id = NULL WHERE family_id = :familyId")
    suspend fun clearFamily(familyId: Long)

    @Query("UPDATE products SET family_id = :familyId, family_dismissed = 0 WHERE id IN (:productIds)")
    suspend fun setFamily(productIds: List<Long>, familyId: Long?)

    @Query("UPDATE products SET family_id = NULL, family_dismissed = 1 WHERE id IN (:productIds)")
    suspend fun dismissFamily(productIds: List<Long>)

    @Query("UPDATE products SET brand = :brand WHERE id = :id")
    suspend fun setBrand(id: Long, brand: String?)

    @Query("SELECT * FROM products WHERE family_id = :familyId ORDER BY name COLLATE NOCASE")
    fun productsInFamily(familyId: Long): Flow<List<ProductEntity>>
}

// ---------------------------------------------------------------- checks (v8)

data class MatchDocRow(
    val id: Long,
    val sellerId: Long,
    val kind: String?,
    val documentNumber: String?,
    val documentDate: LocalDate?,
    val ddtRefs: String?,
    val coveredBy: Long?,
)

data class KindlessRow(val id: Long, val ocrText: String?, val documentNumber: String?, val mimeType: String)

data class CompareLineRow(
    val lineItemId: Long,
    val documentId: Long,
    val productId: Long?,
    val originalDescription: String,
    val quantity: BigDecimal?,
    val unit: String?,
    val lineTotalCents: Long?,
    val unitPrice: BigDecimal?,
)

data class CheckedLineRow(
    val lineItemId: Long,
    val documentId: Long,
    val sellerId: Long,
    val sellerName: String,
    val documentNumber: String?,
    val productId: Long?,
    val productName: String?,
    val originalDescription: String,
    val documentDate: LocalDate?,
    val quantity: BigDecimal?,
    val unit: String?,
    val unitPrice: BigDecimal?,
    val lineTotalCents: Long?,
    val vatBasis: VatBasis,
)

data class LotLineRow(
    val lineItemId: Long,
    val documentId: Long,
    val sellerName: String,
    val documentNumber: String?,
    val documentDate: LocalDate?,
    val kind: String?,
    val productId: Long?,
    val productName: String?,
    val originalDescription: String,
    val quantity: BigDecimal?,
    val unit: String?,
    val lotNumber: String?,
    val expiryDate: LocalDate?,
)

data class AgreedPriceRow(
    @Embedded val price: AgreedPriceEntity,
    @ColumnInfo(name = "seller_name") val sellerName: String?,
    @ColumnInfo(name = "product_name") val productName: String,
)

data class CreditRow(
    @Embedded val credit: CreditEntity,
    @ColumnInfo(name = "seller_name") val sellerName: String,
    @ColumnInfo(name = "document_number") val documentNumber: String?,
    @ColumnInfo(name = "document_date") val documentDate: LocalDate?,
)

private const val LOT_SELECT = """
    SELECT li.id AS lineItemId, li.document_id AS documentId, s.name AS sellerName, d.document_number AS documentNumber,
           d.document_date AS documentDate, d.kind AS kind, li.product_id AS productId, p.name AS productName,
           li.original_description AS originalDescription, li.quantity AS quantity, li.unit AS unit,
           li.lot_number AS lotNumber, li.expiry_date AS expiryDate
    FROM line_items li
    JOIN documents d ON d.id = li.document_id
    JOIN sellers s ON s.id = d.seller_id
    LEFT JOIN products p ON p.id = li.product_id
"""

@Dao
interface ChecksDao {

    @Query(
        "SELECT id, seller_id AS sellerId, kind, document_number AS documentNumber, document_date AS documentDate, " +
            "ddt_refs AS ddtRefs, covered_by AS coveredBy FROM documents",
    )
    suspend fun matchDocs(): List<MatchDocRow>

    @Query(
        "SELECT id, seller_id AS sellerId, kind, document_number AS documentNumber, document_date AS documentDate, " +
            "ddt_refs AS ddtRefs, covered_by AS coveredBy FROM documents",
    )
    fun matchDocsFlow(): Flow<List<MatchDocRow>>

    /** Documents saved before kinds were read (or whose kind could not be read: kind = '?' after one try). */
    @Query("SELECT id, ocr_text AS ocrText, document_number AS documentNumber, mime_type AS mimeType FROM documents WHERE kind IS NULL")
    suspend fun kindless(): List<KindlessRow>

    @Query("UPDATE documents SET kind = :kind, ddt_refs = :refs WHERE id = :id")
    suspend fun setKindAndRefs(id: Long, kind: String?, refs: String?)

    @Query("UPDATE documents SET kind = :kind WHERE id = :id")
    suspend fun setKind(id: Long, kind: String?)

    @Query("UPDATE documents SET covered_by = :invoiceId WHERE id = :id")
    suspend fun setCoveredBy(id: Long, invoiceId: Long?)

    @Query(
        "SELECT id AS lineItemId, document_id AS documentId, product_id AS productId, original_description AS originalDescription, " +
            "quantity, unit, line_total_cents AS lineTotalCents, unit_price AS unitPrice FROM line_items WHERE document_id IN (:documentIds) ORDER BY document_id, position",
    )
    suspend fun compareLines(documentIds: List<Long>): List<CompareLineRow>

    /** Lines of documents that count as purchases, linked to a product: checked against agreed prices. */
    @Query(
        """
        SELECT li.id AS lineItemId, li.document_id AS documentId, d.seller_id AS sellerId, s.name AS sellerName,
               d.document_number AS documentNumber, li.product_id AS productId, p.name AS productName,
               li.original_description AS originalDescription, d.document_date AS documentDate, li.quantity AS quantity,
               li.unit AS unit, li.unit_price AS unitPrice, li.line_total_cents AS lineTotalCents, d.vat_basis AS vatBasis
        FROM line_items li
        JOIN documents d ON d.id = li.document_id
        JOIN sellers s ON s.id = d.seller_id
        JOIN products p ON p.id = li.product_id
        WHERE IFNULL(d.kind, '') != 'CREDIT_NOTE' AND d.covered_by IS NULL AND li.product_id IN (SELECT product_id FROM agreed_prices)
        """,
    )
    fun linesWithAgreedPrices(): Flow<List<CheckedLineRow>>

    @Query("$LOT_SELECT WHERE li.expiry_date IS NOT NULL AND li.expiry_date BETWEEN :fromDay AND :toDay")
    fun expiring(fromDay: LocalDate, toDay: LocalDate): Flow<List<LotLineRow>>

    @Query("$LOT_SELECT WHERE li.lot_number IS NOT NULL OR li.expiry_date IS NOT NULL ORDER BY (d.document_date IS NULL), d.document_date DESC, li.id DESC")
    fun linesWithLots(): Flow<List<LotLineRow>>

    @Query("$LOT_SELECT WHERE li.product_id = :productId ORDER BY (d.document_date IS NULL), d.document_date DESC, li.id DESC")
    fun linesOfProduct(productId: Long): Flow<List<LotLineRow>>

    // ---- agreed prices

    @Query(
        "SELECT a.*, s.name AS seller_name, p.name AS product_name FROM agreed_prices a JOIN products p ON p.id = a.product_id " +
            "LEFT JOIN sellers s ON s.id = a.seller_id ORDER BY p.name COLLATE NOCASE",
    )
    fun agreedPrices(): Flow<List<AgreedPriceRow>>

    @Query(
        "SELECT a.*, s.name AS seller_name, p.name AS product_name FROM agreed_prices a JOIN products p ON p.id = a.product_id " +
            "LEFT JOIN sellers s ON s.id = a.seller_id WHERE a.product_id = :productId",
    )
    fun agreedPricesFor(productId: Long): Flow<List<AgreedPriceRow>>

    @Insert suspend fun insertAgreedPrice(p: AgreedPriceEntity): Long

    @Query("DELETE FROM agreed_prices WHERE product_id = :productId AND ((:sellerId IS NULL AND seller_id IS NULL) OR seller_id = :sellerId) AND vat_basis = :basis")
    suspend fun deleteAgreedPriceFor(productId: Long, sellerId: Long?, basis: VatBasis)

    @Query("DELETE FROM agreed_prices WHERE id = :id")
    suspend fun deleteAgreedPrice(id: Long)

    // ---- credits

    @Query(
        "SELECT c.*, s.name AS seller_name, d.document_number AS document_number, d.document_date AS document_date FROM credits c " +
            "JOIN sellers s ON s.id = c.seller_id LEFT JOIN documents d ON d.id = c.document_id " +
            "WHERE c.closed_at IS NULL ORDER BY c.created_at",
    )
    fun openCredits(): Flow<List<CreditRow>>

    @Insert suspend fun insertCredit(c: CreditEntity): Long

    @Query("UPDATE credits SET closed_at = :at WHERE id = :id")
    suspend fun closeCredit(id: Long, at: Long?)

    @Query("DELETE FROM credits WHERE id = :id")
    suspend fun deleteCredit(id: Long)

    /** Credit notes saved after a date, per supplier: they may settle open credits. */
    @Query(
        "SELECT d.id AS id, d.seller_id AS sellerId, s.name AS sellerName, d.document_date AS documentDate, d.document_number AS documentNumber, " +
            "d.total_cents AS totalCents, d.currency AS currency, d.mime_type AS mimeType, 0 AS itemCount " +
            "FROM documents d JOIN sellers s ON s.id = d.seller_id WHERE d.kind = 'CREDIT_NOTE' ORDER BY d.created_at DESC",
    )
    fun creditNotes(): Flow<List<DocumentListRow>>

    // ---- dismissed notices

    @Query("SELECT notice FROM dismissed")
    fun dismissed(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun dismiss(d: DismissedEntity)

    @Query("DELETE FROM dismissed WHERE notice = :notice")
    suspend fun undismiss(notice: String)
}

// ---------------------------------------------------------------- food cost and orders (v9)

data class PricedRow(
    val lineItemId: Long,
    val productId: Long?,
    val productName: String?,
    val productCategory: String?,
    val originalDescription: String,
    val documentDate: LocalDate?,
    val sellerName: String,
    val quantity: BigDecimal?,
    val unit: String?,
    val unitPrice: BigDecimal?,
    val lineTotalCents: Long?,
    val vatBasis: VatBasis,
    val vatRate: BigDecimal?,
)

data class BoughtRow(
    val productId: Long?,
    val name: String,
    val documentId: Long,
    val documentDate: LocalDate?,
    val quantity: BigDecimal?,
    val unit: String?,
)

data class SellerLastRow(val id: Long, val name: String, val lastDate: LocalDate?, val documents: Int)

@Dao
interface FoodDao {

    @Query("SELECT * FROM recipes ORDER BY name COLLATE NOCASE")
    fun recipes(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun recipe(id: Long): RecipeEntity?

    @Query("SELECT * FROM recipes")
    suspend fun recipesOnce(): List<RecipeEntity>

    @Query("SELECT * FROM recipe_items")
    suspend fun allItemsOnce(): List<RecipeItemEntity>

    @Query("SELECT * FROM recipe_items ORDER BY recipe_id, position")
    fun allItems(): Flow<List<RecipeItemEntity>>

    @Query("SELECT * FROM recipe_items WHERE recipe_id = :recipeId ORDER BY position")
    suspend fun items(recipeId: Long): List<RecipeItemEntity>

    @Insert suspend fun insertRecipe(r: RecipeEntity): Long
    @Update suspend fun updateRecipe(r: RecipeEntity)

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun deleteRecipe(id: Long)

    @Query("DELETE FROM recipe_items WHERE recipe_id = :recipeId")
    suspend fun deleteItems(recipeId: Long)

    @Insert suspend fun insertItems(items: List<RecipeItemEntity>)

    /** Purchases that count (not a delivery note charged on an invoice, not a credit note), with their VAT rate. */
    @Query(
        """
        SELECT li.id AS lineItemId, li.product_id AS productId, p.name AS productName, p.category AS productCategory,
               li.original_description AS originalDescription, d.document_date AS documentDate, s.name AS sellerName,
               li.quantity AS quantity, li.unit AS unit, li.unit_price AS unitPrice, li.line_total_cents AS lineTotalCents,
               d.vat_basis AS vatBasis, li.vat_rate AS vatRate
        FROM line_items li
        JOIN documents d ON d.id = li.document_id
        JOIN sellers s ON s.id = d.seller_id
        LEFT JOIN products p ON p.id = li.product_id
        WHERE d.covered_by IS NULL AND IFNULL(d.kind, '') != 'CREDIT_NOTE'
        """,
    )
    fun pricedPurchases(): Flow<List<PricedRow>>

    /**
     * What a supplier delivered, delivery by delivery: delivery notes, and documents that cover none (an invoice
     * charging several delivery notes would count them twice).
     */
    @Query(
        """
        SELECT li.product_id AS productId, COALESCE(p.name, li.original_description) AS name, li.document_id AS documentId,
               d.document_date AS documentDate, li.quantity AS quantity, li.unit AS unit
        FROM line_items li
        JOIN documents d ON d.id = li.document_id
        LEFT JOIN products p ON p.id = li.product_id
        WHERE d.seller_id = :sellerId AND IFNULL(d.kind, '') != 'CREDIT_NOTE'
          AND NOT EXISTS (SELECT 1 FROM documents x WHERE x.covered_by = d.id)
        """,
    )
    suspend fun boughtFrom(sellerId: Long): List<BoughtRow>

    @Query(
        """
        SELECT s.id AS id, s.name AS name, MAX(d.document_date) AS lastDate, COUNT(d.id) AS documents
        FROM sellers s JOIN documents d ON d.seller_id = s.id
        GROUP BY s.id ORDER BY (MAX(d.document_date) IS NULL), MAX(d.document_date) DESC
        """,
    )
    fun sellersByLastDelivery(): Flow<List<SellerLastRow>>

    @Query("SELECT * FROM revenue ORDER BY month DESC")
    fun revenue(): Flow<List<RevenueEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRevenue(r: RevenueEntity)

    @Query("DELETE FROM revenue WHERE month = :month")
    suspend fun deleteRevenue(month: String)
}
