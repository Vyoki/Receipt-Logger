package com.kitchenreceipts.app.ui.bundle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtMoney
import com.kitchenreceipts.app.ui.supplierCategoryLabel
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.BundlePlan
import com.kitchenreceipts.core.BundleWizard
import com.kitchenreceipts.core.InvoiceOutcome
import com.kitchenreceipts.core.PlannedInvoice
import com.kitchenreceipts.core.SupplierCategory

/** One supplier of the file's list: its name as read (null: not read), its category, and its invoices in page order. */
internal class SupplierGroup(val key: String, val name: String?, val category: SupplierCategory?, val invoices: List<PlannedInvoice>)

/**
 * The invoices of the file being read (or read), grouped by supplier in name order, the ones whose supplier was not
 * read last. Left-out invoices (credit notes, already saved, left out by the operator) are not listed: the Found step
 * counted them. [pricesOnly]: only the invoices whose prices changed.
 */
internal fun groupsOf(plan: BundlePlan, pricesOnly: Boolean): List<SupplierGroup> =
    plan.invoices.filter { it.outcome != InvoiceOutcome.LEFT_OUT && (!pricesOnly || it.pricesChanged) }
        .groupBy { if (it.isInvoice) BundleWizard.supplierKey(it) else BundleWizard.NO_SUPPLIER }
        .map { (key, list) ->
            val choice = list.firstNotNullOfOrNull { plan.supplierOf(it) }
            val name = list.firstNotNullOfOrNull { it.supplierName?.ifBlank { null } } ?: choice?.name
            SupplierGroup(key, name.takeIf { key != BundleWizard.NO_SUPPLIER }, SupplierCategory.fromKey(choice?.category), list.sortedBy { it.first })
        }
        .sortedWith(compareBy<SupplierGroup> { it.name == null }.thenBy { it.name?.lowercase() })

/**
 * The file's page while it is read and once it is done: [head] (progress, counts, buttons), then the list of its
 * invoices grouped by supplier. A row opens the invoice's check, or the saved document; one that could not be read
 * can be read again.
 */
@Composable
internal fun FileList(
    plan: BundlePlan,
    pricesOnly: Boolean,
    readingJob: String?,
    rechecking: Set<String>,
    onShowAll: () -> Unit,
    onReview: (String) -> Unit,
    onOpenDocument: (Long) -> Unit,
    onRetry: (String) -> Unit,
    head: @Composable () -> Unit,
) {
    val groups = groupsOf(plan, pricesOnly)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
    ) {
        item("head") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 16.dp)) { head() }
        }
        item("listTitle") {
            Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                Heading(stringResource(if (pricesOnly) R.string.bundle_prices_only else R.string.bundle_list_title))
                if (pricesOnly) {
                    TextButton(onClick = onShowAll, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.bundle_show_all)) }
                }
            }
        }
        invoiceItems(groups, readingJob, rechecking, onReview, onOpenDocument, onRetry)
    }
}

private fun LazyListScope.invoiceItems(
    groups: List<SupplierGroup>,
    readingJob: String?,
    rechecking: Set<String>,
    onReview: (String) -> Unit,
    onOpenDocument: (Long) -> Unit,
    onRetry: (String) -> Unit,
) {
    for (g in groups) {
        item("sup-" + g.key) { GroupHeader(g) }
        items(g.invoices, key = { "inv-" + it.first + "-" + it.last }) { inv ->
            InvoiceRow(inv, reading = inv.jobId != null && inv.jobId == readingJob, rechecking = inv.jobId in rechecking, onReview, onOpenDocument, onRetry)
            HorizontalDivider(color = Palette.Hairline)
        }
    }
}

/** The supplier's name (a heading, for TalkBack's "next heading"), with its count and category. */
@Composable
private fun GroupHeader(g: SupplierGroup) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp)) {
        Text(
            g.name ?: stringResource(R.string.bundle_no_supplier),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Palette.PhthaloBright,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        val count = pluralStringResource(R.plurals.bundle_invoices, g.invoices.size, g.invoices.size)
        Text(
            listOfNotNull(count, g.category?.let { supplierCategoryLabel(it) }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextDim,
        )
    }
}

private class RowLook(val icon: ImageVector, val tint: Color, val text: String)

/** One invoice: what it prints (number, date, total) and what became of it, with a symbol. */
@Composable
private fun InvoiceRow(
    inv: PlannedInvoice,
    reading: Boolean,
    rechecking: Boolean,
    onReview: (String) -> Unit,
    onOpenDocument: (Long) -> Unit,
    onRetry: (String) -> Unit,
) {
    val look = when (inv.outcome) {
        InvoiceOutcome.WAITING, InvoiceOutcome.QUEUED ->
            if (reading) RowLook(Icons.Filled.HourglassTop, Palette.PhthaloBright, stringResource(R.string.job_reading))
            else RowLook(Icons.Filled.HourglassEmpty, Palette.TextDim, stringResource(R.string.bundle_status_waiting))
        InvoiceOutcome.READY -> RowLook(Icons.Filled.RateReview, Palette.Orange, stringResource(R.string.bundle_status_ready))
        InvoiceOutcome.CHECK_LATER -> RowLook(Icons.Filled.Schedule, Palette.Orange, stringResource(R.string.bundle_status_later))
        InvoiceOutcome.SAVED_BY_APP -> RowLook(Icons.Filled.CheckCircle, Palette.Ok, stringResource(R.string.bundle_status_saved_app))
        InvoiceOutcome.SAVED_BY_YOU -> RowLook(Icons.Filled.CheckCircle, Palette.Ok, stringResource(R.string.bundle_status_saved_you))
        InvoiceOutcome.STOPPED -> RowLook(Icons.Filled.PauseCircle, Palette.TextDim, stringResource(R.string.bundle_status_stopped))
        InvoiceOutcome.FAILED -> RowLook(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error, stringResource(R.string.bundle_status_failed))
        InvoiceOutcome.LEFT_OUT -> RowLook(Icons.Filled.PauseCircle, Palette.TextDim, stringResource(R.string.tag_left_out))
    }
    val job = inv.jobId
    val doc = inv.documentId
    // To check: its check (not while the app checks it again); saved: the document.
    val open: (() -> Unit)? = when {
        (inv.outcome == InvoiceOutcome.READY || inv.outcome == InvoiceOutcome.CHECK_LATER) && job != null && !rechecking -> { { onReview(job) } }
        (inv.outcome == InvoiceOutcome.SAVED_BY_APP || inv.outcome == InvoiceOutcome.SAVED_BY_YOU) && doc != null -> { { onOpenDocument(doc) } }
        else -> null
    }
    val what = buildList {
        inv.number?.let { add(stringResource(R.string.doc_number_short, it)) }
        inv.date?.let { add(fmtDate(it)) }
        inv.printedTotalCents?.let { add(fmtMoney(it)) }
        if (isEmpty()) add(if (inv.first == inv.last) stringResource(R.string.bundle_page, inv.first + 1) else stringResource(R.string.bundle_pages, inv.first + 1, inv.last + 1))
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .then(if (open != null) Modifier.clickable(role = Role.Button, onClick = open) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(look.icon, contentDescription = null, tint = look.tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(what, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(look.text, style = MaterialTheme.typography.bodySmall, color = look.tint)
        }
        when {
            inv.outcome == InvoiceOutcome.FAILED && job != null ->
                TextButton(onClick = { onRetry(job) }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.job_retry)) }
            open != null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.TextDim)
        }
    }
}
