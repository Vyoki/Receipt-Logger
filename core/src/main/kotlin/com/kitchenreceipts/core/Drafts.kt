package com.kitchenreceipts.core

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Editable text of one field in the review screen.
 * [uncertain] = extracted with LOW confidence and not yet touched/confirmed by the user.
 * An empty [text] means the value is missing (shown as "missing", never filled in automatically).
 */
data class DraftField(val text: String = "", val uncertain: Boolean = false, val source: String? = null) {
    val isMissing: Boolean get() = text.isBlank()

    /** User edited or confirmed the value. */
    fun confirmed(newText: String = text) = copy(text = newText, uncertain = false)
}

data class LineItemDraft(
    val key: Long,
    val description: DraftField = DraftField(),
    val productId: Long? = null,
    val productName: String? = null,
    val quantity: DraftField = DraftField(),
    val unit: DraftField = DraftField(),
    val unitPrice: DraftField = DraftField(),
    val lineTotal: DraftField = DraftField(),
    val vatRate: DraftField = DraftField(),
    val lot: DraftField = DraftField(),
    val expiry: DraftField = DraftField(),
    /** "Colli": packages/cartons as printed ("5", "1x6"); kept apart from the name and the quantity. */
    val packages: DraftField = DraftField(),
    /** Supplier's article code read from the line; used to remember product assignments. */
    val itemCode: String? = null,
    /** How the product was chosen, when the app chose it (shown so the operator can see and undo it). */
    val productSource: ProductSource? = null,
    /** No product matched: a new product with this name is created when the document is saved. */
    val newProductName: String? = null,
    /** The line's numbers add up in more than one way: the operator picks (the first is the suggested one). */
    val choices: List<LineChoice> = emptyList(),
    /** How much one pack holds ("500 g", "1 l"): the quantity counts packs of this size. */
    val packSize: DraftField = DraftField(),
) {
    /** "4 × 500 g = 2 kg": the total amount the packs hold, or null when there is no size or the unit is not a count. */
    fun packTotal(): Pair<BigDecimal, String>? {
        val size = PackSizes.fromText(packSize.text)?.base ?: return null
        if (Units.normalize(unit.text).let { it != null && it != "pz" }) return null
        val q = ItalianNumbers.parse(quantity.text) ?: return null
        return q.multiply(size.first).stripTrailingZeros() to size.second
    }

    /** Takes one reading of the line's numbers as confirmed. */
    fun pick(c: LineChoice): LineItemDraft = copy(
        quantity = quantity.confirmed(ItalianNumbers.toEditText(c.quantity)),
        unitPrice = unitPrice.confirmed(ItalianNumbers.toEditText(c.unitPrice)),
        lineTotal = lineTotal.confirmed(ItalianNumbers.centsToEditText(c.lineTotalCents)),
        unit = if (c.unit != null) unit.confirmed(c.unit) else unit,
        choices = emptyList(),
    )

    val uncertainCount: Int
        get() = listOf(description, quantity, unit, unitPrice, lineTotal, vatRate, lot, expiry, packages, packSize).count { it.uncertain }

    /** qty x price, offered as a one-tap suggestion when the line total is missing. Never auto-applied. */
    fun computedTotalCents(): Long? {
        val q = ItalianNumbers.parse(quantity.text) ?: return null
        val p = ItalianNumbers.parse(unitPrice.text) ?: return null
        return ItalianNumbers.toCents(q.multiply(p))
    }
}

/** How the app linked a line to a product without asking. */
enum class ProductSource {
    /** The operator linked this supplier's description or article code before. */
    REMEMBERED,
    /** Recognised despite a different spelling, abbreviation or misreading. */
    RECOGNISED,
    /** Not bought before: a new product will be created. */
    NEW,
}

data class DocumentDraft(
    val seller: DraftField = DraftField(),
    val date: DraftField = DraftField(),
    val number: DraftField = DraftField(),
    val currency: DraftField = DraftField(),
    val subtotal: DraftField = DraftField(),
    val vat: DraftField = DraftField(),
    val total: DraftField = DraftField(),
    val vatBasis: VatBasis = VatBasis.UNKNOWN,
    val vatBasisUncertain: Boolean = false,
    val items: List<LineItemDraft> = emptyList(),
    val warnings: Set<ParseWarning> = emptySet(),
    /** False when the document prints no lots at all: an empty lot is then normal, not something to fill in. */
    val lotsPrinted: Boolean = true,
    /** VAT groups that do not add up (rate, printed taxable amount, lines' sum), to show the operator where to look. */
    val vatGroupProblems: List<VatSummary.Check> = emptyList(),
    /** The AI's double-check, if it ran. */
    val aiCheck: AiCheck? = null,
) {
    val uncertainCount: Int
        get() = listOf(seller, date, number, currency, subtotal, vat, total).count { it.uncertain } +
            (if (vatBasisUncertain) 1 else 0) + items.sumOf { it.uncertainCount }

    companion object {
        fun fromParsed(p: ParsedDocument, keyStart: Long = 1): DocumentDraft {
            fun <T> f(e: Extracted<T>?, fmt: (T) -> String) =
                if (e == null) DraftField() else DraftField(fmt(e.value), e.confidence == Confidence.LOW, e.source)
            val cents = { c: Long -> ItalianNumbers.centsToEditText(c) }
            val dec = { d: BigDecimal -> ItalianNumbers.toEditText(d) }
            return DocumentDraft(
                seller = f(p.sellerName) { it },
                date = f(p.documentDate) { ItalianDates.format(it) },
                number = f(p.documentNumber) { it },
                currency = f(p.currency) { it },
                subtotal = f(p.subtotalCents, cents),
                vat = f(p.vatCents, cents),
                total = f(p.totalCents, cents),
                vatBasis = p.vatBasis?.value ?: VatBasis.UNKNOWN,
                vatBasisUncertain = p.vatBasis?.confidence == Confidence.LOW,
                items = p.lineItems.mapIndexed { i, it ->
                    LineItemDraft(
                        key = keyStart + i,
                        description = DraftField(it.originalDescription),
                        quantity = f(it.quantity, dec),
                        unit = f(it.unit) { u -> u },
                        unitPrice = f(it.unitPrice, dec),
                        lineTotal = f(it.lineTotalCents, cents),
                        vatRate = f(it.vatRatePercent, dec),
                        lot = f(it.lotNumber) { l -> l },
                        expiry = f(it.expiryDate) { d -> ItalianDates.format(d) },
                        packages = f(it.packages) { p -> p },
                        itemCode = it.itemCode,
                        choices = it.choices,
                        packSize = f(it.packSize) { s -> s },
                    )
                },
                warnings = p.warnings,
                lotsPrinted = p.lotsPrinted,
                vatGroupProblems = p.vatChecks.filter { !it.ok },
                aiCheck = p.aiCheck,
            )
        }
    }
}

enum class ErrorCode { REQUIRED, INVALID_NUMBER, INVALID_DATE, MUST_NOT_BE_ZERO, MUST_BE_POSITIVE, OUT_OF_RANGE, TOO_LONG, INVALID_CURRENCY }

/** [field] is "seller", "date", ..., or "items[3].quantity" (index into the draft list). */
data class FieldError(val field: String, val code: ErrorCode)

data class ValidLineItem(
    val description: String,
    val productId: Long?,
    val quantity: BigDecimal?,
    val unit: String?,
    val unitPrice: BigDecimal?,
    val lineTotalCents: Long?,
    val vatRatePercent: BigDecimal?,
    val lotNumber: String?,
    val expiryDate: LocalDate?,
    val itemCode: String? = null,
    /** Create this product on save and link the line to it (only when [productId] is null). */
    val newProductName: String? = null,
    val packages: String? = null,
    /** "500 g": one pack's size (normalised, see PackSizes). */
    val packSize: String? = null,
)

data class ValidDocument(
    val sellerName: String,
    val date: LocalDate?,
    val number: String?,
    val currency: String?,
    val subtotalCents: Long?,
    val vatCents: Long?,
    val totalCents: Long?,
    val vatBasis: VatBasis,
    val items: List<ValidLineItem>,
)

sealed interface ValidationResult {
    data class Valid(val document: ValidDocument) : ValidationResult
    data class Invalid(val errors: List<FieldError>) : ValidationResult
}

object DraftValidator {

    private val CURRENCY = Regex("^[A-Z]{3}$")

    fun validate(d: DocumentDraft): ValidationResult {
        val errors = mutableListOf<FieldError>()

        val seller = d.seller.text.trim()
        if (seller.isEmpty()) errors += FieldError("seller", ErrorCode.REQUIRED)
        if (seller.length > 120) errors += FieldError("seller", ErrorCode.TOO_LONG)

        val date = optionalDate(d.date.text, "date", errors)
        val number = d.number.text.trim().ifEmpty { null }
        if ((number?.length ?: 0) > 60) errors += FieldError("number", ErrorCode.TOO_LONG)
        val currency = d.currency.text.trim().uppercase().ifEmpty { null }
        if (currency != null && !CURRENCY.matches(currency)) errors += FieldError("currency", ErrorCode.INVALID_CURRENCY)

        val subtotal = optionalCents(d.subtotal.text, "subtotal", errors)
        val vat = optionalCents(d.vat.text, "vat", errors)
        val total = optionalCents(d.total.text, "total", errors)

        val items = d.items.mapIndexed { i, it ->
            val p = "items[$i]"
            val desc = it.description.text.trim()
            if (desc.isEmpty()) errors += FieldError("$p.description", ErrorCode.REQUIRED)
            val qty = optionalDecimal(it.quantity.text, "$p.quantity", errors)
            if (qty != null && qty.signum() == 0) errors += FieldError("$p.quantity", ErrorCode.MUST_NOT_BE_ZERO)
            val unit = Units.normalize(it.unit.text)
            if ((unit?.length ?: 0) > 15) errors += FieldError("$p.unit", ErrorCode.TOO_LONG)
            val price = optionalDecimal(it.unitPrice.text, "$p.unitPrice", errors)
            if (price != null && price.signum() < 0) errors += FieldError("$p.unitPrice", ErrorCode.MUST_BE_POSITIVE)
            val lineTotal = optionalCents(it.lineTotal.text, "$p.lineTotal", errors)
            val rate = optionalDecimal(it.vatRate.text, "$p.vatRate", errors)
            if (rate != null && (rate.signum() < 0 || rate > BigDecimal(100))) errors += FieldError("$p.vatRate", ErrorCode.OUT_OF_RANGE)
            val lot = it.lot.text.trim().ifEmpty { null }
            if ((lot?.length ?: 0) > 40) errors += FieldError("$p.lot", ErrorCode.TOO_LONG)
            val expiry = optionalDate(it.expiry.text, "$p.expiry", errors)
            val packages = it.packages.text.trim().ifEmpty { null }
            if ((packages?.length ?: 0) > 20) errors += FieldError("$p.packages", ErrorCode.TOO_LONG)
            val packSize = it.packSize.text.trim().ifEmpty { null }?.let { t ->
                val s = PackSizes.fromText(t) ?: PackSizes.parse(t.split(' ').reversed()) ?: PackSizes.parse(t.split(' '))
                if (s == null) { errors += FieldError("$p.packSize", ErrorCode.INVALID_NUMBER); null } else s.text
            }
            ValidLineItem(
                desc, it.productId, qty, unit, price, lineTotal, rate, lot, expiry, it.itemCode,
                newProductName = it.newProductName?.trim()?.ifEmpty { null }?.takeIf { _ -> it.productId == null },
                packages = packages,
                packSize = packSize,
            )
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid(ValidDocument(seller, date, number, currency, subtotal, vat, total, d.vatBasis, items))
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    private fun optionalDecimal(text: String, field: String, errors: MutableList<FieldError>): BigDecimal? {
        if (text.isBlank()) return null
        val v = ItalianNumbers.parse(text)
        if (v == null) errors += FieldError(field, ErrorCode.INVALID_NUMBER)
        return v
    }

    private fun optionalCents(text: String, field: String, errors: MutableList<FieldError>): Long? {
        val v = optionalDecimal(text, field, errors) ?: return null
        if (v.stripTrailingZeros().scale() > 2) {
            errors += FieldError(field, ErrorCode.INVALID_NUMBER) // money has at most 2 decimals
            return null
        }
        if (v.abs() > BigDecimal("10000000")) {
            errors += FieldError(field, ErrorCode.OUT_OF_RANGE)
            return null
        }
        return ItalianNumbers.toCents(v)
    }

    private fun optionalDate(text: String, field: String, errors: MutableList<FieldError>): LocalDate? {
        if (text.isBlank()) return null
        val d = ItalianDates.parse(text)
        if (d == null) errors += FieldError(field, ErrorCode.INVALID_DATE)
        return d
    }
}
