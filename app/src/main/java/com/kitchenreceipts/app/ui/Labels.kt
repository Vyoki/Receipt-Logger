package com.kitchenreceipts.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.kitchenreceipts.app.R
import com.kitchenreceipts.core.Category
import com.kitchenreceipts.core.SupplierCategory
import com.kitchenreceipts.core.DuplicateReason
import com.kitchenreceipts.core.Period
import com.kitchenreceipts.core.PeriodKind
import com.kitchenreceipts.core.ReviewReason
import java.time.temporal.IsoFields
import com.kitchenreceipts.core.ErrorCode
import com.kitchenreceipts.core.ExclusionReason
import com.kitchenreceipts.core.ItalianDates
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.ParseWarning
import com.kitchenreceipts.core.VatBasis
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun warningText(w: ParseWarning): String = stringResource(
    when (w) {
        ParseWarning.NO_ITEMS_FOUND -> R.string.warn_no_items
        ParseWarning.LINE_TOTAL_MISMATCH -> R.string.warn_line_total_mismatch
        ParseWarning.ITEMS_SUM_MISMATCH -> R.string.warn_items_sum_mismatch
        ParseWarning.TOTALS_INCONSISTENT -> R.string.warn_totals_inconsistent
        ParseWarning.LOT_LOOKS_LIKE_DATE -> R.string.warn_lot_looks_like_date
        ParseWarning.MULTIPLE_TOTALS -> R.string.warn_multiple_totals
        ParseWarning.VAT_GROUP_MISMATCH -> R.string.warn_vat_group_mismatch
        ParseWarning.OTHER_BUYER -> R.string.warn_other_buyer
        ParseWarning.DUPLICATE_PAGE -> R.string.warn_duplicate_page
        ParseWarning.PAGES_MISSING -> R.string.warn_pages_missing
        ParseWarning.CHECKED_BY_TOTALS -> R.string.warn_checked_by_totals
    },
)

@Composable
fun errorText(c: ErrorCode): String = stringResource(
    when (c) {
        ErrorCode.REQUIRED -> R.string.err_required
        ErrorCode.INVALID_NUMBER -> R.string.err_invalid_number
        ErrorCode.INVALID_DATE -> R.string.err_invalid_date
        ErrorCode.MUST_NOT_BE_ZERO -> R.string.err_not_zero
        ErrorCode.MUST_BE_POSITIVE -> R.string.err_positive
        ErrorCode.OUT_OF_RANGE -> R.string.err_out_of_range
        ErrorCode.TOO_LONG -> R.string.err_too_long
        ErrorCode.INVALID_CURRENCY -> R.string.err_currency
        ErrorCode.AMBIGUOUS_NUMBER -> R.string.err_ambiguous_number
    },
)

@Composable
fun vatBasisLabel(v: VatBasis): String = stringResource(
    when (v) {
        VatBasis.INCLUSIVE -> R.string.vat_inclusive
        VatBasis.EXCLUSIVE -> R.string.vat_exclusive
        VatBasis.UNKNOWN -> R.string.vat_unknown
    },
)

@Composable
fun duplicateReasonText(r: DuplicateReason): String = stringResource(
    when (r) {
        DuplicateReason.SAME_FILE -> R.string.dup_same_file
        DuplicateReason.SAME_SELLER_AND_NUMBER -> R.string.dup_same_number
        DuplicateReason.SAME_SELLER_DATE_TOTAL -> R.string.dup_same_seller_date_total
        DuplicateReason.SAME_DATE_AND_TOTAL -> R.string.dup_same_date_total
    },
)

@Composable
fun exclusionText(r: ExclusionReason): String = stringResource(
    when (r) {
        ExclusionReason.MISSING_QUANTITY -> R.string.excl_missing_qty
        ExclusionReason.MISSING_TOTAL -> R.string.excl_missing_total
        ExclusionReason.MISSING_UNIT -> R.string.excl_missing_unit
        ExclusionReason.ZERO_QUANTITY -> R.string.excl_zero_qty
    },
)

fun fmtDate(d: LocalDate?): String = d?.let(ItalianDates::format) ?: "—"
fun fmtMonth(m: YearMonth?): String = m?.let(ItalianDates::formatMonth) ?: "—"
fun fmtMoney(cents: Long?, currency: String? = "EUR"): String = cents?.let { ItalianNumbers.formatMoney(it, currency) } ?: "—"
fun fmtDecimal(v: BigDecimal?, maxScale: Int = 3): String = v?.let { ItalianNumbers.formatDecimal(it, maxScale = maxScale) } ?: "—"

@Composable
fun categoryLabel(c: Category): String = stringResource(
    when (c) {
        Category.FRUIT_VEG -> R.string.cat_fruit_veg
        Category.MEAT -> R.string.cat_meat
        Category.FISH -> R.string.cat_fish
        Category.CURED_MEATS -> R.string.cat_cured_meats
        Category.DAIRY_EGGS -> R.string.cat_dairy_eggs
        Category.BAKERY -> R.string.cat_bakery
        Category.DRY_GOODS -> R.string.cat_dry_goods
        Category.FROZEN -> R.string.cat_frozen
        Category.BEVERAGES -> R.string.cat_beverages
        Category.CLEANING -> R.string.cat_cleaning
        Category.DISPOSABLES -> R.string.cat_disposables
        Category.COPERTI -> R.string.cat_coperti
        Category.OTHER -> R.string.cat_other
    },
)

/** What a supplier sells, in the operator's words; null = "Not sorted yet". */
@Composable
fun supplierCategoryLabel(c: SupplierCategory?): String = stringResource(supplierCategoryLabelRes(c))

/** The same label as a resource, for code that is not composable (the fast scroller's bubble). */
fun supplierCategoryLabelRes(c: SupplierCategory?): Int = when (c) {
    SupplierCategory.FOOD -> R.string.supcat_food
    SupplierCategory.DRINKS -> R.string.supcat_drinks
    SupplierCategory.SUPPLIES -> R.string.supcat_supplies
    SupplierCategory.COPERTI -> R.string.supcat_coperti
    SupplierCategory.UTILITIES -> R.string.supcat_utilities
    SupplierCategory.FUEL -> R.string.supcat_fuel
    SupplierCategory.EQUIPMENT -> R.string.supcat_equipment
    SupplierCategory.OTHER -> R.string.supcat_other
    null -> R.string.supcat_none
}

/** Two or three example words under a category's name ("meat, fish, fruit & veg…"). */
@Composable
fun supplierCategoryHint(c: SupplierCategory): String = stringResource(
    when (c) {
        SupplierCategory.FOOD -> R.string.supcat_food_ex
        SupplierCategory.DRINKS -> R.string.supcat_drinks_ex
        SupplierCategory.SUPPLIES -> R.string.supcat_supplies_ex
        SupplierCategory.COPERTI -> R.string.supcat_coperti_ex
        SupplierCategory.UTILITIES -> R.string.supcat_utilities_ex
        SupplierCategory.FUEL -> R.string.supcat_fuel_ex
        SupplierCategory.EQUIPMENT -> R.string.supcat_equipment_ex
        SupplierCategory.OTHER -> R.string.supcat_other_ex
    },
)

/** One line-drawn symbol per supplier category (a question mark when not sorted yet). The label says the name. */
fun supplierCategoryIcon(c: SupplierCategory?): ImageVector = when (c) {
    SupplierCategory.FOOD -> Icons.Outlined.Restaurant
    SupplierCategory.DRINKS -> Icons.Outlined.LocalBar
    SupplierCategory.SUPPLIES -> Icons.Outlined.CleaningServices
    SupplierCategory.COPERTI -> Icons.Outlined.TableRestaurant
    SupplierCategory.UTILITIES -> Icons.Outlined.Bolt
    SupplierCategory.FUEL -> Icons.Outlined.LocalGasStation
    SupplierCategory.EQUIPMENT -> Icons.Outlined.Handyman
    SupplierCategory.OTHER -> Icons.Outlined.Payments
    null -> Icons.Outlined.QuestionMark
}

@Composable
fun reviewReasonText(r: ReviewReason): String = stringResource(
    when (r) {
        ReviewReason.SELLER_MISSING -> R.string.reason_seller
        ReviewReason.DATE_MISSING -> R.string.reason_date
        ReviewReason.TOTAL_MISSING -> R.string.reason_total
        ReviewReason.NO_ITEMS -> R.string.reason_no_items
        ReviewReason.UNCERTAIN_VALUES -> R.string.reason_uncertain
        ReviewReason.INCOMPLETE_ITEMS -> R.string.reason_incomplete
        ReviewReason.SUM_MISMATCH -> R.string.reason_sum
        ReviewReason.VAT_BASIS_UNKNOWN -> R.string.reason_vat_basis
        ReviewReason.VAT_GROUP_MISMATCH -> R.string.reason_vat_group
        ReviewReason.LOTS_MISSING -> R.string.reason_lots_missing
        ReviewReason.OTHER_BUYER -> R.string.reason_other_buyer
        ReviewReason.UNUSUAL_PRICE -> R.string.reason_unusual_price
        ReviewReason.DUPLICATE_PAGE -> R.string.reason_duplicate_page
        ReviewReason.PAGES_MISSING -> R.string.reason_pages_missing
        ReviewReason.CHECKED_BY_TOTALS -> R.string.reason_checked_by_totals
        ReviewReason.BUNDLE_CHECK -> R.string.bundle_hold_reason
    },
)

@Composable
fun periodKindLabel(k: PeriodKind): String = stringResource(
    when (k) {
        PeriodKind.WEEK -> R.string.period_week
        PeriodKind.MONTH -> R.string.period_month
        PeriodKind.QUARTER -> R.string.period_quarter
        PeriodKind.YEAR -> R.string.period_year
    },
)

@Composable
fun periodLabel(p: Period): String = when (p.kind) {
    PeriodKind.WEEK -> stringResource(R.string.week_label, p.start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), fmtDate(p.start), fmtDate(p.end))
    PeriodKind.MONTH -> fmtMonth(YearMonth.from(p.start))
    PeriodKind.QUARTER -> stringResource(R.string.quarter_label, p.start.get(IsoFields.QUARTER_OF_YEAR), p.start.year)
    PeriodKind.YEAR -> p.start.year.toString()
}

/** "Riga 4, importo: 12,50 · IA 125,00". */
@Composable
fun aiDisagreementText(d: com.kitchenreceipts.core.AiDisagreement): String {
    val what = stringResource(
        when (d.field) {
            "name" -> R.string.ai_field_name
            "quantity" -> R.string.ai_field_quantity
            "amount" -> R.string.ai_field_amount
            "supplier" -> R.string.ai_field_supplier
            "number" -> R.string.ai_field_number
            "date" -> R.string.ai_field_date
            "subtotal" -> R.string.ai_field_subtotal
            "vat" -> R.string.ai_field_vat
            "total" -> R.string.ai_field_total
            else -> R.string.ai_field_value
        },
    )
    val where = d.line?.let { stringResource(R.string.ai_field_line, it, what) } ?: what
    return stringResource(R.string.ai_disagreement, where, d.ours, d.ai)
}

/** The source under a field to check, with the AI's mark ("⚠ AI read …") in the operator's language. */
@Composable
fun sourceText(source: String?): String =
    source?.replace(com.kitchenreceipts.core.AiReader.DISAGREE, stringResource(R.string.ai_read_mark)).orEmpty()
