package com.kitchenreceipts.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kitchenreceipts.core.VatBasis
import java.math.BigDecimal
import java.time.LocalDate

/*
 * Money is stored as INTEGER cents (Long). Quantities, unit prices and conversion factors are
 * stored as exact decimal TEXT (BigDecimal.toPlainString()). No REAL columns anywhere.
 */

@Entity(
    tableName = "sellers",
    indices = [Index(value = ["normalized_name"], unique = true), Index("vat_number")],
)
data class SellerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    /** v3: supplier's Partita IVA, learned from saved documents. */
    @ColumnInfo(name = "vat_number") val vatNumber: String? = null,
    /** v3: words usually printed in this supplier's letterhead, "word:count;..." (see SellerProfiles). */
    @ColumnInfo(name = "header_profile") val headerProfile: String? = null,
)

/** v3: how the OCR spelled a supplier's name before the operator corrected it. */
@Entity(
    tableName = "seller_aliases",
    foreignKeys = [ForeignKey(SellerEntity::class, ["id"], ["seller_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["alias_key"], unique = true), Index("seller_id")],
)
data class SellerAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "alias_key") val aliasKey: String,
    @ColumnInfo(name = "seller_id") val sellerId: Long,
)

@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = SellerEntity::class,
            parentColumns = ["id"],
            childColumns = ["seller_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("seller_id"), Index("document_date"), Index("file_sha256")],
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "seller_id") val sellerId: Long,
    @ColumnInfo(name = "document_date") val documentDate: LocalDate?,
    @ColumnInfo(name = "document_number") val documentNumber: String?,
    val currency: String?,
    @ColumnInfo(name = "subtotal_cents") val subtotalCents: Long?,
    @ColumnInfo(name = "vat_cents") val vatCents: Long?,
    @ColumnInfo(name = "total_cents") val totalCents: Long?,
    /** Whether the line totals / unit prices of this document include VAT. */
    @ColumnInfo(name = "vat_basis") val vatBasis: VatBasis,
    /** Path of the original image/PDF, relative to the app's private files directory. */
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "page_count") val pageCount: Int,
    @ColumnInfo(name = "file_sha256") val fileSha256: String,
    @ColumnInfo(name = "ocr_text") val ocrText: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** v8: INVOICE, DELIVERY_NOTE, RECEIPT or CREDIT_NOTE (core DocKind), read from the title; null = not known. */
    val kind: String? = null,
    /** v8: the delivery notes this document refers to, "number|yyyy-mm-dd;..." (core DeliveryRef). */
    @ColumnInfo(name = "ddt_refs") val ddtRefs: String? = null,
    /**
     * v8: for a delivery note, the invoice that names it. Its goods are charged on that invoice, so spending,
     * quantities and averages count the invoice only. Worked out by the app from the references (see DeliveryMatching).
     */
    @ColumnInfo(name = "covered_by") val coveredBy: Long? = null,
)

@Entity(
    tableName = "products",
    indices = [Index(value = ["normalized_name"], unique = true), Index("family_id")],
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** v4: inventory category key (see core Category); null = guessed from the name. */
    val category: String? = null,
    /** v6: the group this product belongs to (e.g. "Passata di pomodoro"); null = none. */
    @ColumnInfo(name = "family_id") val familyId: Long? = null,
    /** v6: brand, typed by the operator (never guessed). */
    val brand: String? = null,
    /** v6: the operator said no to the suggested group; it is not suggested again. */
    @ColumnInfo(name = "family_dismissed", defaultValue = "0") val familyDismissed: Boolean = false,
)

/**
 * v6: a group of different products of the same kind ("Passata di pomodoro": several brands and suppliers).
 * Each product keeps its own purchases and averages; the group only puts them side by side for comparing.
 */
@Entity(
    tableName = "product_families",
    indices = [Index(value = ["normalized_name"], unique = true)],
)
data class ProductFamilyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "line_items",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("document_id"), Index("product_id")],
)
data class LineItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Every line item keeps a link to the document it was read from. */
    @ColumnInfo(name = "document_id") val documentId: Long,
    val position: Int,
    @ColumnInfo(name = "original_description") val originalDescription: String,
    @ColumnInfo(name = "product_id") val productId: Long?,
    val quantity: BigDecimal?,
    val unit: String?,
    @ColumnInfo(name = "unit_price") val unitPrice: BigDecimal?,
    @ColumnInfo(name = "line_total_cents") val lineTotalCents: Long?,
    @ColumnInfo(name = "vat_rate") val vatRate: BigDecimal?,
    @ColumnInfo(name = "lot_number") val lotNumber: String?,
    @ColumnInfo(name = "expiry_date") val expiryDate: LocalDate?,
    /** v5: "colli" as printed ("5", "1x6"). */
    val packages: String? = null,
    /** v7: how much one pack holds ("500 g", "1 l"), when the quantity counts packs. */
    @ColumnInfo(name = "pack_size") val packSize: String? = null,
)

/**
 * "When seller X prints description Y, it is product Z." Created only when the user assigns a
 * product to a line item; used to pre-fill the same assignment next time. Exact match only.
 */
@Entity(
    tableName = "product_aliases",
    foreignKeys = [
        ForeignKey(SellerEntity::class, ["id"], ["seller_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ProductEntity::class, ["id"], ["product_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["seller_id", "alias_key"], unique = true), Index("product_id")],
)
data class ProductAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "seller_id") val sellerId: Long,
    @ColumnInfo(name = "alias_key") val aliasKey: String,
    @ColumnInfo(name = "product_id") val productId: Long,
)

/** Added in schema version 2. 1 [fromUnit] = [factor] [toUnit], for one product. */
@Entity(
    tableName = "unit_conversions",
    foreignKeys = [ForeignKey(ProductEntity::class, ["id"], ["product_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["product_id", "from_unit", "to_unit"], unique = true)],
)
data class UnitConversionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "from_unit") val fromUnit: String,
    @ColumnInfo(name = "to_unit") val toUnit: String,
    val factor: BigDecimal,
)

/** v8: a price agreed with a supplier (its price list) for a product; purchases above it are flagged. */
@Entity(
    tableName = "agreed_prices",
    foreignKeys = [
        ForeignKey(ProductEntity::class, ["id"], ["product_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(SellerEntity::class, ["id"], ["seller_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("product_id"), Index("seller_id")],
)
data class AgreedPriceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "product_id") val productId: Long,
    /** null = any supplier. */
    @ColumnInfo(name = "seller_id") val sellerId: Long?,
    val unit: String,
    val price: BigDecimal,
    @ColumnInfo(name = "vat_basis") val vatBasis: VatBasis,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** v8: money a supplier owes back (goods missing, returned, charged twice), until the operator marks it received. */
@Entity(
    tableName = "credits",
    foreignKeys = [
        ForeignKey(SellerEntity::class, ["id"], ["seller_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(DocumentEntity::class, ["id"], ["document_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("seller_id"), Index("document_id")],
)
data class CreditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "seller_id") val sellerId: Long,
    @ColumnInfo(name = "document_id") val documentId: Long?,
    val description: String,
    @ColumnInfo(name = "amount_cents") val amountCents: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "closed_at") val closedAt: Long? = null,
)

/** v8: notices the operator has dealt with ("checked, it's fine", "used up"): not shown again. */
@Entity(tableName = "dismissed")
data class DismissedEntity(
    @PrimaryKey val notice: String,
    val at: Long,
)
