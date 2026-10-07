package com.kitchenreceipts.app.data

import androidx.room.withTransaction
import com.kitchenreceipts.core.AgreedPrice
import com.kitchenreceipts.core.AgreedPrices
import com.kitchenreceipts.core.CheckedLine
import com.kitchenreceipts.core.CompareLine
import com.kitchenreceipts.core.DeliveryMatching
import com.kitchenreceipts.core.DeliveryRef
import com.kitchenreceipts.core.Difference
import com.kitchenreceipts.core.DocKind
import com.kitchenreceipts.core.DocumentKinds
import com.kitchenreceipts.core.ExpiringLine
import com.kitchenreceipts.core.Expiry
import com.kitchenreceipts.core.Lots
import com.kitchenreceipts.core.MatchDoc
import com.kitchenreceipts.core.OverCharge
import com.kitchenreceipts.core.ProductMatching
import com.kitchenreceipts.core.UnitConversion
import com.kitchenreceipts.core.VatBasis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate

/** "Unknown, already tried": a document whose title names no kind is not read again at every start. */
internal const val KIND_UNKNOWN = "?"

internal fun kindOf(s: String?): DocKind? = DocKind.entries.firstOrNull { it.name == s }

/** Kind and delivery-note references of a new document, from its text. */
internal fun readKind(text: String?, ownNumber: String?): Pair<String, String?> {
    val kind = DocumentKinds.detect(text)
    val refs = if (kind == DocKind.DELIVERY_NOTE) emptyList() else DocumentKinds.references(text, ownNumber)
    return (kind?.name ?: KIND_UNKNOWN) to DeliveryRef.encodeAll(refs)
}

/**
 * Recomputes which delivery notes are charged on which invoice (only explicit references; see DeliveryMatching)
 * and stores it where it changed. Cheap: a few columns of every document.
 */
internal suspend fun refreshCovers(dao: ChecksDao): Int {
    val rows = dao.matchDocs()
    val covers = DeliveryMatching.covers(rows.map { it.toMatch() })
    var changed = 0
    for (r in rows) {
        val want = covers[r.id]
        if (r.coveredBy != want) {
            dao.setCoveredBy(r.id, want)
            changed++
        }
    }
    return changed
}

private fun MatchDocRow.toMatch() = MatchDoc(id, sellerId, kindOf(kind), documentNumber, documentDate, DeliveryRef.decodeAll(ddtRefs))

/** An invoice and the delivery notes it is checked against. */
data class InvoiceCheck(
    val invoiceId: Long,
    val notes: List<MatchDocRow>,
    val missing: List<DeliveryRef>,
    val differences: List<Difference>,
)

/**
 * The checks on what suppliers charge: delivery notes against invoices, agreed prices, credits still owed,
 * expiry dates and lots. Everything is worked out on the phone from the saved documents.
 */
class ChecksRepository(private val db: AppDatabase) {

    private val dao = db.checksDao()
    private val products = db.productDao()

    /** Fills the kind and references of documents saved before v8 (once), then the covers. */
    suspend fun backfill(): Int = withContext(Dispatchers.IO) {
        val rows = dao.kindless()
        db.withTransaction {
            for (r in rows) {
                val (kind, refs) = readKind(r.ocrText, r.documentNumber)
                dao.setKindAndRefs(r.id, kind, refs)
            }
            refreshCovers(dao)
        }
        rows.size
    }

    suspend fun setKind(documentId: Long, kind: DocKind?) = db.withTransaction {
        dao.setKind(documentId, kind?.name ?: KIND_UNKNOWN)
        refreshCovers(dao)
    }

    fun documents(): Flow<List<MatchDocRow>> = dao.matchDocsFlow()

    /** The check of one invoice: its delivery notes, the ones it names but are not saved, and the differences. */
    suspend fun invoiceCheck(invoiceId: Long): InvoiceCheck? = withContext(Dispatchers.Default) {
        val all = dao.matchDocs()
        val inv = all.firstOrNull { it.id == invoiceId } ?: return@withContext null
        val notes = all.filter { it.coveredBy == invoiceId }
        val missing = DeliveryMatching.missing(inv.toMatch(), all.map { it.toMatch() })
        if (notes.isEmpty()) return@withContext InvoiceCheck(invoiceId, notes, missing, emptyList())
        val lines = dao.compareLines(notes.map { it.id } + invoiceId).map {
            CompareLine(it.documentId, it.lineItemId, it.productId, it.originalDescription, it.quantity, it.unit, it.lineTotalCents, it.unitPrice)
        }
        val diffs = DeliveryMatching.compare(lines.filter { it.documentId == invoiceId }, lines.filter { it.documentId != invoiceId })
        InvoiceCheck(invoiceId, notes, missing, diffs)
    }

    /** Every invoice with delivery notes, checked; for the Checks screen. */
    fun invoiceChecks(): Flow<List<InvoiceCheck>> = dao.matchDocsFlow().map { all ->
        val invoices = all.filter { inv -> all.any { it.coveredBy == inv.id } || inv.ddtRefs != null }
        invoices.mapNotNull { invoiceCheck(it.id) }.filter { it.differences.isNotEmpty() || it.missing.isNotEmpty() }
    }.flowOn(Dispatchers.Default)

    // ------------------------------------------------------------ agreed prices

    fun agreedPrices(): Flow<List<AgreedPriceRow>> = dao.agreedPrices()
    fun agreedPricesFor(productId: Long): Flow<List<AgreedPriceRow>> = dao.agreedPricesFor(productId)

    /** Sets (or replaces) the agreed price of a product with a supplier ([sellerId] null = any supplier). */
    suspend fun setAgreedPrice(productId: Long, sellerId: Long?, unit: String, price: BigDecimal, basis: VatBasis) = db.withTransaction {
        dao.deleteAgreedPriceFor(productId, sellerId, basis)
        dao.insertAgreedPrice(AgreedPriceEntity(productId = productId, sellerId = sellerId, unit = unit, price = price, vatBasis = basis, createdAt = System.currentTimeMillis()))
    }

    suspend fun deleteAgreedPrice(id: Long) = dao.deleteAgreedPrice(id)

    /** Purchases charged above the agreed price, newest first. */
    fun overCharges(): Flow<List<Pair<OverCharge, CheckedLineRow>>> =
        combine(dao.linesWithAgreedPrices(), dao.agreedPrices(), products.allConversions()) { lines, prices, conversions ->
            val conv = conversions.groupBy { it.productId }.mapValues { (_, l) -> l.mapNotNull { c -> runCatching { UnitConversion(c.fromUnit, c.toUnit, c.factor) }.getOrNull() } }
            val byId = lines.associateBy { it.lineItemId }
            AgreedPrices.overCharges(
                lines.map { CheckedLine(it.lineItemId, it.documentId, it.sellerId, it.productId, it.productName, it.originalDescription, it.documentDate, it.quantity, it.unit, it.unitPrice, it.lineTotalCents, it.vatBasis) },
                prices.map { AgreedPrice(it.price.id, it.price.productId, it.price.sellerId, it.price.unit, it.price.price, it.price.vatBasis) },
            ) { pid -> conv[pid].orEmpty() }.map { it to byId.getValue(it.line.lineItemId) }
        }.flowOn(Dispatchers.Default)

    // ------------------------------------------------------------ credits

    fun openCredits(): Flow<List<CreditRow>> = dao.openCredits()
    fun creditNotes(): Flow<List<DocumentListRow>> = dao.creditNotes()

    suspend fun addCredit(sellerId: Long, documentId: Long?, description: String, amountCents: Long?): Long =
        dao.insertCredit(CreditEntity(sellerId = sellerId, documentId = documentId, description = description.trim(), amountCents = amountCents, createdAt = System.currentTimeMillis()))

    suspend fun closeCredit(id: Long) = dao.closeCredit(id, System.currentTimeMillis())
    suspend fun reopenCredit(id: Long) = dao.closeCredit(id, null)
    suspend fun deleteCredit(id: Long) = dao.deleteCredit(id)

    // ------------------------------------------------------------ dismissed notices

    fun dismissed(): Flow<Set<String>> = dao.dismissed().map { it.toSet() }
    suspend fun dismiss(notice: String) = dao.dismiss(DismissedEntity(notice, System.currentTimeMillis()))
    suspend fun undismiss(notice: String) = dao.undismiss(notice)

    // ------------------------------------------------------------ expiry and lots

    /**
     * What expires in the next week or expired in the last two, not marked as used. The same goods on a delivery
     * note and on its invoice (same product, lot and date) show once.
     */
    fun expiring(today: LocalDate = LocalDate.now()): Flow<List<ExpiringLine>> =
        combine(dao.expiring(today.minusDays(Expiry.AFTER), today.plusDays(Expiry.AHEAD)), dismissed()) { rows, done ->
            val handled = done.filter { it.startsWith(EXPIRY) }.mapNotNull { it.removePrefix(EXPIRY).toLongOrNull() }.toSet()
            val lines = rows.distinctBy { r -> Triple(r.productId?.toString() ?: ProductMatching.aliasKey(r.originalDescription), r.lotNumber?.let(Lots::key), r.expiryDate) }
                .map { r -> ExpiringLine(r.lineItemId, r.documentId, r.productName, r.originalDescription, r.sellerName, r.lotNumber, r.expiryDate!!, r.quantity, r.unit, r.documentDate) }
            Expiry.soon(lines, today, handled)
        }.flowOn(Dispatchers.Default)

    suspend fun markExpiryHandled(lineItemId: Long) = dismiss(EXPIRY + lineItemId)
    suspend fun unmarkExpiryHandled(lineItemId: Long) = undismiss(EXPIRY + lineItemId)

    /** Lines whose lot matches what was typed, or whose product or description contains it. */
    fun searchLots(query: String): Flow<List<LotLineRow>> = dao.linesWithLots().map { rows ->
        val q = query.trim()
        if (q.length < 2) return@map emptyList()
        val words = ProductMatching.aliasKey(q)
        rows.filter { r ->
            Lots.matches(r.lotNumber, q) ||
                (words.length >= 3 && (ProductMatching.aliasKey(r.productName ?: "").contains(words) || ProductMatching.aliasKey(r.originalDescription).contains(words)))
        }
    }.flowOn(Dispatchers.Default)

    /** How many things in Checks wait for a look (not marked as fine): for the home screen. */
    fun attention(): Flow<Int> = combine(overCharges(), invoiceChecks(), dismissed()) { over, invoices, done ->
        over.count { it.first.key() !in done } + invoices.sumOf { ic -> ic.differences.count { it.key(ic.invoiceId) !in done } }
    }

    companion object {
        const val EXPIRY = "exp:"
    }
}
