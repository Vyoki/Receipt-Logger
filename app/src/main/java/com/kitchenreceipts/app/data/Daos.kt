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
            "d.total_cents AS totalCents, (SELECT COUNT(*) FROM line_items li WHERE li.document_id = d.id) AS itemCount " +
            "FROM documents d JOIN sellers s ON s.id = d.seller_id",
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
    @Query("$PURCHASE_SELECT WHERE li.product_id IN (:productIds) ORDER BY d.document_date DESC, li.document_id DESC, li.position")
    suspend fun purchasesOfProductsOnce(productIds: List<Long>): List<PurchaseRow>

    @Query("SELECT DISTINCT product_id FROM line_items WHERE document_id = :documentId AND product_id IS NOT NULL")
    suspend fun productIdsOfDocument(documentId: Long): List<Long>

    @Query("$PURCHASE_SELECT WHERE li.product_id = :productId ORDER BY (d.document_date IS NULL), d.document_date DESC, li.id DESC")
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
        SELECT s.id AS id, s.name AS name, COUNT(d.id) AS documentCount, SUM(d.total_cents) AS totalCents,
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
