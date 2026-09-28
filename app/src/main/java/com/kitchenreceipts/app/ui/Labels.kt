package com.kitchenreceipts.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kitchenreceipts.app.R
import com.kitchenreceipts.core.DuplicateReason
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
