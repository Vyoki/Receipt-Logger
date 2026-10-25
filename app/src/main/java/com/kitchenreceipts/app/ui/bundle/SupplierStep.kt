package com.kitchenreceipts.app.ui.bundle

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.components.Annotation
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.CategoryPickerSheet
import com.kitchenreceipts.app.ui.components.CategoryTag
import com.kitchenreceipts.app.ui.components.CategoryTiles
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtMoney
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.BundlePlan
import com.kitchenreceipts.core.BundleWizard
import com.kitchenreceipts.core.SupplierCategory
import com.kitchenreceipts.core.SupplierChoice
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Step "Suppliers": one new supplier per screen ("Supplier 3 of 6"), with what the file holds from it as printed, and
 * what it mostly sells, as big tiles. A tap saves the choice and goes on to the next supplier; "Decide later" and
 * "Leave these invoices out" too. A suggestion or the choice of last time is only shown: it still needs a tap (or the
 * one "Use the suggestion" tap). Suppliers whose category the phone knows by VAT number are folded at the bottom.
 */
@Composable
internal fun SupplierStep(plan: BundlePlan, at: Int, vm: BundleViewModel) {
    val news = BundleWizard.newSuppliers(plan)
    val choice = news.getOrNull(at) ?: return
    val group = BundleWizard.groupBySupplier(plan).firstOrNull { it.supplier.key == choice.key }
    val suggestable = BundleWizard.suggestable(plan).size
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // One explicit tap for every supplier that has a suggestion; each one still shows here after.
        if (suggestable > 0) {
            BigButton(pluralStringResource(R.plurals.bundle_use_suggestions, suggestable, suggestable), null, onClick = vm::useSuggestions, primary = false)
        }
        Text(stringResource(R.string.bundle_supplier_of, at + 1, news.size), style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
        Text(
            choice.name ?: stringResource(R.string.bundle_flag_no_supplier),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        choice.vat?.let { Text(stringResource(R.string.bundle_vat, it), style = MaterialTheme.typography.bodyLarge) }
        if (group != null) {
            Text(
                stringResource(
                    R.string.bundle_supplier_line,
                    pluralStringResource(R.plurals.bundle_invoices, group.invoices, group.invoices),
                    dateRange(group.first, group.last),
                    printedSum(group.provenCents, group.notProven, group.invoices),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            // Credit notes apart: never added to or taken from the invoices.
            if (group.creditNotes > 0) {
                Text(
                    pluralStringResource(
                        R.plurals.bundle_credit_sum, group.creditNotes, group.creditNotes,
                        printedSum(group.creditNoteCents, group.creditNotesNotProven, group.creditNotes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextDim,
                )
            }
        }
        if (!choice.include) Annotation(stringResource(R.string.tag_left_out))
        Text(stringResource(R.string.bundle_question), style = MaterialTheme.typography.titleMedium)
        CategoryTiles(
            selected = SupplierCategory.fromKey(choice.category),
            onPick = { vm.pick(choice.key, it) },
            suggested = SupplierCategory.fromKey(choice.suggestion),
            chosenBefore = SupplierCategory.fromKey(choice.lastTime),
            onDecideLater = { vm.decideLater(choice.key) },
            decidedLater = choice.category == SupplierChoice.LATER,
        )
        TextButton(onClick = { vm.leaveOut(choice.key) }, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(stringResource(R.string.bundle_leave_out_supplier))
        }
        KnownSuppliers(plan, vm)
        Text(stringResource(R.string.bundle_remember_line), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
    }
}

/**
 * "17 suppliers already sorted ✓": folded; opened, each with its category. A tap on one changes its category (for
 * this file, and remembered when the invoices go to be read).
 */
@Composable
private fun KnownSuppliers(plan: BundlePlan, vm: BundleViewModel) {
    val known = BundleWizard.knownSuppliers(plan)
    if (known.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    var changing by rememberSaveable { mutableStateOf<String?>(null) }
    ClickCard(onClick = { open = !open }, modifier = Modifier.animateContentSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.bundle_known_sorted, known.size, known.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            if (open) known.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.supcat_change)) { changing = s.key },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.name ?: s.vat ?: "—", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    SupplierCategory.fromKey(s.category)?.let { CategoryTag(it) }
                }
            }
        }
    }
    val s = changing?.let { key -> known.firstOrNull { it.key == key } }
    if (s != null) {
        CategoryPickerSheet(
            title = s.name ?: s.vat ?: "—",
            selected = SupplierCategory.fromKey(s.category),
            onPick = { c -> vm.changeKnown(s.key, c); changing = null },
            onDismiss = { changing = null },
            note = stringResource(R.string.supcat_change_seller_note),
        )
    }
}

private val SHORT = DateTimeFormatter.ofPattern("dd/MM")

/** "01/09–28/09" (the year when they differ), one date when the same, "—" when none was read. */
internal fun dateRange(first: LocalDate?, last: LocalDate?): String = when {
    first == null || last == null -> fmtDate(first ?: last)
    first == last -> fmtDate(first)
    first.year == last.year -> "${first.format(SHORT)}–${last.format(SHORT)}"
    else -> "${fmtDate(first)}–${fmtDate(last)}"
}

/**
 * "€ 1.234,56 as printed" of [count] documents, "+ 1 not found" when some totals were not found or not proven, and
 * "not found" when none was.
 */
@Composable
internal fun printedSum(provenCents: Long, notProven: Int, count: Int): String {
    if (notProven >= count) return stringResource(R.string.bundle_not_found)
    val sum = stringResource(R.string.bundle_as_printed, fmtMoney(provenCents))
    return if (notProven == 0) sum else pluralStringResource(R.plurals.bundle_plus_not_found, notProven, sum, notProven)
}
