package com.kitchenreceipts.app.ui.bundle

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.components.Annotation
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.OutlinedButton
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtMoney
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.BundlePlan
import com.kitchenreceipts.core.BundleWizard
import com.kitchenreceipts.core.InvoiceBundle
import com.kitchenreceipts.core.PlannedInvoice

/**
 * "Check where each invoice starts": every invoice of the file in page order, with small pictures of its pages. The
 * ones that need a look are open and outlined in orange; the others open with a tap. "These pages belong to the
 * invoice before" joins an invoice to the one above it; "A new invoice starts here" under a page starts a new invoice
 * there. Everything else about the invoices around is worked out again from the pages.
 */
@Composable
internal fun PageCheck(plan: BundlePlan, vm: BundleViewModel, onSeePages: (Int) -> Unit) {
    val invoices = plan.invoices
    val need = invoices.count(BundleWizard::needsLook)
    // Starts at the first one to look at, with the invoice before it in view (it may be the one to join).
    val firstLook = invoices.indexOfFirst(BundleWizard::needsLook)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = firstLook.coerceAtLeast(0))
    var opened by remember { mutableStateOf(emptySet<Int>()) }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Heading(stringResource(R.string.bundle_check_starts))
                if (need > 0) Text(pluralStringResource(R.plurals.bundle_need_look, need, need), style = MaterialTheme.typography.titleMedium, color = Palette.Orange)
            }
        }
        // Keyed by the first page: an invoice joined or split away leaves, the others keep their place.
        itemsIndexed(invoices, key = { _, inv -> inv.first }) { i, inv ->
            val look = BundleWizard.needsLook(inv)
            InvoiceCard(
                index = i, inv = inv, look = look, open = look || inv.first in opened, vm = vm, onSeePages = onSeePages,
                onToggle = { opened = if (inv.first in opened) opened - inv.first else opened + inv.first },
            )
        }
    }
}

@Composable
private fun InvoiceCard(
    index: Int,
    inv: PlannedInvoice,
    look: Boolean,
    open: Boolean,
    vm: BundleViewModel,
    onSeePages: (Int) -> Unit,
    onToggle: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Palette.Panel, contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(if (look) 1.5.dp else 1.dp, if (look) Palette.Orange else Palette.Hairline),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !look, onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(pagesLabel(inv), style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
                    Text(
                        when {
                            !inv.isInvoice -> pluralStringResource(R.plurals.bundle_unknown_pages, inv.pageCount, inv.pageCount)
                            else -> inv.supplierName ?: stringResource(R.string.bundle_flag_no_supplier)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val detail = listOfNotNull(inv.number, inv.date?.let(::fmtDate), inv.printedTotalCents?.let { fmtMoney(it) }).joinToString(" · ")
                    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium)
                }
                if (!look) Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            tags(inv).forEach { Text(it, style = MaterialTheme.typography.labelLarge, color = Palette.TextDim) }
            (inv.flags - InvoiceBundle.Flag.OTHER_BUYER).forEach { Annotation(flagText(it, inv)) }
            if (open) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items((inv.first..inv.last).toList(), key = { it }) { page ->
                        PageThumb(vm, page, canSplit = page > inv.first, onSplit = { vm.split(page) }, onOpen = { onSeePages(page) })
                    }
                }
                if (index > 0) {
                    OutlinedButton(onClick = { vm.join(inv.first) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.bundle_belongs_before), textAlign = TextAlign.Center)
                    }
                }
                // A credit note is never imported: nothing to tick.
                if (!inv.creditNote) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .toggleable(value = inv.include, role = Role.Checkbox, onValueChange = { vm.setIncluded(inv.first, it) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = inv.include, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.bundle_include), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (look) BigButton(stringResource(R.string.bundle_looks_right), null, onClick = { vm.keep(inv.first) }, primary = false)
                TextButton(onClick = { onSeePages(inv.first) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.bundle_see_pages)) }
            }
        }
    }
}

/** A small picture of one page (it opens the viewer there), and under it "A new invoice starts here". */
@Composable
private fun PageThumb(vm: BundleViewModel, page: Int, canSplit: Boolean, onSplit: () -> Unit, onOpen: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, page) { value = vm.thumb(page) }
    val label = stringResource(R.string.bundle_page, page + 1)
    Column(Modifier.width(160.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(160.dp, 226.dp).clip(MaterialTheme.shapes.small).background(Color(0xFF303030))
                .clickable(onClickLabel = stringResource(R.string.bundle_see_pages), onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            val b = bitmap
            if (b != null) {
                Image(b.asImageBitmap(), contentDescription = label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (canSplit) {
            OutlinedButton(onClick = onSplit, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.bundle_new_starts_here), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun pagesLabel(inv: PlannedInvoice): String =
    if (inv.first == inv.last) stringResource(R.string.bundle_page, inv.first + 1)
    else stringResource(R.string.bundle_pages, inv.first + 1, inv.last + 1)

/** Why an invoice is (or may be) left out, or was saved before. */
@Composable
private fun tags(inv: PlannedInvoice): List<String> = buildList {
    if (inv.creditNote) add(stringResource(R.string.tag_credit_note))
    if (inv.alreadySavedId != null) add(stringResource(R.string.tag_already_saved))
    if (inv.maybeSavedId != null) add(stringResource(R.string.bundle_maybe_saved))
    if (!inv.include && !inv.creditNote && inv.alreadySavedId == null) add(stringResource(R.string.tag_left_out))
}

@Composable
private fun flagText(flag: InvoiceBundle.Flag, inv: PlannedInvoice): String = when (flag) {
    InvoiceBundle.Flag.NO_TOTAL -> stringResource(R.string.bundle_flag_no_total)
    InvoiceBundle.Flag.SEVERAL_IN_ONE -> stringResource(R.string.bundle_flag_several)
    InvoiceBundle.Flag.OVER_PAGE_LIMIT -> stringResource(R.string.bundle_flag_long, inv.pageCount)
    InvoiceBundle.Flag.NO_SUPPLIER -> stringResource(R.string.bundle_flag_no_supplier)
    InvoiceBundle.Flag.START_UNSURE -> stringResource(R.string.bundle_flag_start_unsure)
    // Pages with no text of their own, or anything else: the general doubt about where it is cut.
    InvoiceBundle.Flag.NO_TEXT, InvoiceBundle.Flag.OTHER_BUYER -> stringResource(R.string.bundle_hold_reason)
}
