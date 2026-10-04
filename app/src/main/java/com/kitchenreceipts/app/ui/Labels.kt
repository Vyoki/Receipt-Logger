package com.kitchenreceipts.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kitchenreceipts.app.R
import com.kitchenreceipts.core.Category
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
        Category.OTHER -> R.string.cat_other
    },
)

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
