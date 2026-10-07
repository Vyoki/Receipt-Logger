package com.kitchenreceipts.app.ui.checks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.AgreedPriceRow
import com.kitchenreceipts.app.data.CheckedLineRow
import com.kitchenreceipts.app.data.ChecksRepository
import com.kitchenreceipts.app.data.CreditRow
import com.kitchenreceipts.app.data.DocumentListRow
import com.kitchenreceipts.app.data.InvoiceCheck
import com.kitchenreceipts.app.data.MatchDocRow
import com.kitchenreceipts.app.data.SellerEntity
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.ConfirmDialog
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.LoadingBox
import com.kitchenreceipts.app.ui.components.OutlinedButton
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtMoney
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.app.ui.vatBasisLabel
import com.kitchenreceipts.core.Difference
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.OverCharge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChecksViewModel(checks: ChecksRepository, sellers: Flow<List<SellerEntity>>) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5000)
    val over: StateFlow<List<Pair<OverCharge, CheckedLineRow>>?> = checks.overCharges().stateIn(viewModelScope, started, null)
    val invoices: StateFlow<List<InvoiceCheck>?> = checks.invoiceChecks().stateIn(viewModelScope, started, null)
    val credits: StateFlow<List<CreditRow>> = checks.openCredits().stateIn(viewModelScope, started, emptyList())
    val creditNotes: StateFlow<List<DocumentListRow>> = checks.creditNotes().stateIn(viewModelScope, started, emptyList())
    val agreed: StateFlow<List<AgreedPriceRow>> = checks.agreedPrices().stateIn(viewModelScope, started, emptyList())
    val dismissed: StateFlow<Set<String>> = checks.dismissed().stateIn(viewModelScope, started, emptySet())
    val docs: StateFlow<List<MatchDocRow>> = checks.documents().stateIn(viewModelScope, started, emptyList())
    val sellers: StateFlow<List<SellerEntity>> = sellers.stateIn(viewModelScope, started, emptyList())
}

/** A credit about to be added: prefilled from a difference or an overcharge, or empty. */
private data class CreditDraft(val sellerId: Long?, val documentId: Long?, val text: String, val cents: Long?)

@Composable
fun ChecksScreen(onBack: () -> Unit, onOpenDocument: (Long) -> Unit, onOpenProduct: (Long) -> Unit) {
    val c = appContainer()
    val vm = appViewModel { ChecksViewModel(it.checks, it.repository.sellers()) }
    val over by vm.over.collectAsStateWithLifecycle()
    val invoices by vm.invoices.collectAsStateWithLifecycle()
    val credits by vm.credits.collectAsStateWithLifecycle()
    val creditNotes by vm.creditNotes.collectAsStateWithLifecycle()
    val agreed by vm.agreed.collectAsStateWithLifecycle()
    val dismissed by vm.dismissed.collectAsStateWithLifecycle()
    val docs by vm.docs.collectAsStateWithLifecycle()
    val sellers by vm.sellers.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showHidden by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<CreditDraft?>(null) }
    val added = stringResource(R.string.credits_added)

    val overAll = over
    val invAll = invoices
    AppScaffold(title = stringResource(R.string.checks_title), onBack = onBack, snackbarHostState = snackbar) { padding ->
        if (overAll == null || invAll == null) {
            LoadingBox(Modifier.padding(padding))
            return@AppScaffold
        }
        val overShown = overAll.filter { showHidden || it.first.key() !in dismissed }
        val invShown = invAll.mapNotNull { ic ->
            val diffs = ic.differences.filter { showHidden || it.key(ic.invoiceId) !in dismissed }
            ic.copy(differences = diffs).takeIf { diffs.isNotEmpty() || ic.missing.isNotEmpty() }
        }
        val hiddenCount = overAll.count { it.first.key() in dismissed } + invAll.sumOf { ic -> ic.differences.count { it.key(ic.invoiceId) in dismissed } }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("intro") { Text(stringResource(R.string.checks_intro), style = MaterialTheme.typography.bodySmall, color = Palette.Orange) }
            if (overShown.isEmpty() && invShown.isEmpty() && credits.isEmpty()) item("none") { EmptyState(stringResource(R.string.checks_none)) }

            // ---------------------------------------------------------------- agreed prices
            if (overShown.isNotEmpty()) {
                item("overT") { SectionTitle(stringResource(R.string.over_title)) }
                items(overShown, key = { "o" + it.first.line.lineItemId }) { (o, row) ->
                    val hidden = o.key() in dismissed
                    val creditText = overCreditText(row, o)
                    CheckCard(
                        title = (row.productName ?: row.originalDescription),
                        subtitle = row.sellerName + " · " + fmtDate(row.documentDate) + (row.documentNumber?.let { " · n. $it" } ?: ""),
                        text = stringResource(R.string.over_row, price(o.paid), o.agreed.unit, price(o.agreed.price), ItalianNumbers.formatDecimal(o.percent, maxScale = 1)),
                        amount = o.extraCents,
                        hidden = hidden,
                        onOpen = { onOpenDocument(row.documentId) },
                        onFine = { scope.launch { if (hidden) c.checks.undismiss(o.key()) else c.checks.dismiss(o.key()) } },
                        onCredit = {
                            draft = CreditDraft(row.sellerId, row.documentId, creditText, o.extraCents)
                        },
                    )
                }
            }

            // ---------------------------------------------------------------- delivery notes vs invoices
            if (invShown.isNotEmpty()) {
                item("ddtT") { SectionTitle(stringResource(R.string.ddt_title)) }
                items(invShown, key = { "i" + it.invoiceId }) { ic ->
                    val inv = docs.firstOrNull { it.id == ic.invoiceId }
                    val seller = sellers.firstOrNull { it.id == inv?.sellerId }?.name ?: ""
                    InvoiceCheckCard(
                        ic = ic,
                        heading = seller + " · " + stringResource(R.string.ddt_invoice, inv?.documentNumber ?: "—", fmtDate(inv?.documentDate)),
                        dismissed = dismissed,
                        onOpen = { onOpenDocument(ic.invoiceId) },
                        onFine = { d -> scope.launch { val k = d.key(ic.invoiceId); if (k in dismissed) c.checks.undismiss(k) else c.checks.dismiss(k) } },
                        onCredit = { d, text -> draft = CreditDraft(inv?.sellerId, ic.invoiceId, text, d.amountCents) },
                    )
                }
            }
            if (hiddenCount > 0) item("hidden") {
                Row {
                    Text(stringResource(R.string.checks_hidden, hiddenCount), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.weight(1f).padding(top = 14.dp))
                    TextButton(onClick = { showHidden = !showHidden }) { Text(stringResource(R.string.checks_show_hidden)) }
                }
            }

            // ---------------------------------------------------------------- credits
            item("credT") { SectionTitle(stringResource(R.string.credits_title)) }
            if (credits.isEmpty()) item("credNone") { Text(stringResource(R.string.credits_none), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim) }
            items(credits, key = { "c" + it.credit.id }) { cr ->
                ClickCard(onClick = { cr.credit.documentId?.let(onOpenDocument) }) {
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text(cr.sellerName, style = MaterialTheme.typography.titleMedium)
                            Text(cr.credit.description, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.credits_since, fmtDate(java.time.Instant.ofEpochMilli(cr.credit.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate())),
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                            )
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            cr.credit.amountCents?.let { Text(fmtMoney(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                            TextButton(onClick = { scope.launch { c.checks.closeCredit(cr.credit.id) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.credits_received))
                            }
                        }
                    }
                }
            }
            item("credAdd") {
                OutlinedButton(onClick = { draft = CreditDraft(null, null, "", null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    androidx.compose.material3.Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.credits_add), modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (creditNotes.isNotEmpty()) {
                item("cnT") { SectionTitle(stringResource(R.string.credits_notes_title)) }
                item("cnH") { Text(stringResource(R.string.credits_notes_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Orange) }
                items(creditNotes.take(10), key = { "cn" + it.id }) { d ->
                    ClickCard(onClick = { onOpenDocument(d.id) }) {
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text(d.sellerName, style = MaterialTheme.typography.titleMedium)
                                Text(fmtDate(d.documentDate) + (d.documentNumber?.let { " · n. $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                            }
                            Text(fmtMoney(d.totalCents, d.currency), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- agreed prices list
            item("agT") { SectionTitle(stringResource(R.string.agreed_title)) }
            if (agreed.isEmpty()) item("agNone") { Text(stringResource(R.string.agreed_none), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim) }
            items(agreed, key = { "a" + it.price.id }) { a ->
                ClickCard(onClick = { onOpenProduct(a.price.productId) }) {
                    Column {
                        Text(a.productName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.agreed_row, price(a.price.price), a.price.unit, vatBasisLabel(a.price.vatBasis)) + " · " +
                                (a.sellerName ?: stringResource(R.string.agreed_any_supplier)),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                        )
                    }
                }
            }
        }
    }

    draft?.let { d ->
        CreditDialog(
            draft = d,
            sellers = sellers,
            onDismiss = { draft = null },
            onSave = { sellerId, text, cents ->
                draft = null
                scope.launch {
                    c.checks.addCredit(sellerId, d.documentId, text, cents)
                    c.log.event("CREDIT_ADDED", "cents" to cents)
                    snackbar.showSnackbar(added)
                }
            },
        )
    }
}

/** € 8,50 (2 to 4 decimals as needed). */
fun price(v: java.math.BigDecimal): String = "€ " + ItalianNumbers.formatDecimal(v, minScale = 2, maxScale = 4)

@Composable
private fun overCreditText(row: CheckedLineRow, o: OverCharge): String =
    stringResource(R.string.over_credit_text, row.productName ?: row.originalDescription, price(o.paid), o.agreed.unit, price(o.agreed.price)) +
        (row.documentNumber?.let { " (n. $it)" } ?: "")

@Composable
fun differenceText(d: Difference): String {
    fun q(v: java.math.BigDecimal?) = v?.let { ItalianNumbers.formatDecimal(it, maxScale = 3) } ?: "—"
    val unit = d.unit ?: ""
    return when (d.kind) {
        com.kitchenreceipts.core.DifferenceKind.NOT_DELIVERED -> stringResource(R.string.ddt_not_delivered, d.description, q(d.invoiced), unit)
        com.kitchenreceipts.core.DifferenceKind.NOT_INVOICED -> stringResource(R.string.ddt_not_invoiced, d.description, q(d.delivered), unit)
        com.kitchenreceipts.core.DifferenceKind.MORE_INVOICED, com.kitchenreceipts.core.DifferenceKind.LESS_INVOICED ->
            stringResource(R.string.ddt_quantity, d.description, q(d.invoiced), unit, q(d.delivered))
        com.kitchenreceipts.core.DifferenceKind.HIGHER_PRICE ->
            stringResource(R.string.ddt_price, d.description, d.invoicedPrice?.let(::price) ?: "—", unit, d.deliveredPrice?.let(::price) ?: "—")
    }
}

@Composable
private fun CheckCard(
    title: String,
    subtitle: String,
    text: String,
    amount: Long?,
    hidden: Boolean,
    onOpen: () -> Unit,
    onFine: () -> Unit,
    onCredit: () -> Unit,
) {
    ClickCard(onClick = onOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (hidden) Palette.TextDim else MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            amount?.takeIf { it > 0 }?.let { Text(stringResource(R.string.checks_about, fmtMoney(it)), style = MaterialTheme.typography.bodyMedium, color = Palette.Orange) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onFine, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.checks_fine)) }
                TextButton(onClick = onCredit, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.checks_ask_credit)) }
            }
        }
    }
}

/** One invoice compared with its delivery notes: the differences, and the notes it names that are not saved. */
@Composable
fun InvoiceCheckCard(
    ic: InvoiceCheck,
    heading: String?,
    dismissed: Set<String>,
    onOpen: (() -> Unit)?,
    onFine: (Difference) -> Unit,
    onCredit: (Difference, String) -> Unit,
) {
    val body = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            heading?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            if (ic.notes.isNotEmpty()) {
                Text(
                    stringResource(R.string.ddt_notes_of_invoice, ic.notes.joinToString(", ") { (it.documentNumber ?: "—") + " (" + fmtDate(it.documentDate) + ")" }),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                )
                if (ic.differences.isEmpty()) Text(stringResource(R.string.ddt_all_match), style = MaterialTheme.typography.bodyMedium, color = Palette.Ok)
            }
            ic.differences.forEach { d ->
                val hidden = d.key(ic.invoiceId) in dismissed
                val text = differenceText(d)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = if (hidden) Palette.TextDim else MaterialTheme.colorScheme.onSurface)
                d.amountCents?.takeIf { it > 0 && !hidden }?.let { Text(stringResource(R.string.checks_about, fmtMoney(it)), style = MaterialTheme.typography.bodySmall, color = Palette.Orange) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onFine(d) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.checks_fine)) }
                    if (d.kind != com.kitchenreceipts.core.DifferenceKind.NOT_INVOICED && d.kind != com.kitchenreceipts.core.DifferenceKind.LESS_INVOICED) {
                        TextButton(onClick = { onCredit(d, text) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.checks_ask_credit)) }
                    }
                }
            }
            ic.missing.forEach { m ->
                Text(stringResource(R.string.ddt_missing, m.number + (m.date?.let { " (" + fmtDate(it) + ")" } ?: "")), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
            }
        }
    }
    if (onOpen != null) ClickCard(onClick = onOpen) { body() } else com.kitchenreceipts.app.ui.components.Panel { Column(Modifier.padding(16.dp)) { body() } }
}

@Composable
private fun CreditDialog(draft: CreditDraft, sellers: List<SellerEntity>, onDismiss: () -> Unit, onSave: (Long, String, Long?) -> Unit) {
    var sellerId by remember { mutableStateOf(draft.sellerId) }
    var text by remember { mutableStateOf(draft.text) }
    var amount by remember { mutableStateOf(draft.cents?.let { ItalianNumbers.formatDecimal(ItalianNumbers.centsToDecimal(it), maxScale = 2) } ?: "") }
    var query by remember { mutableStateOf("") }
    ConfirmDialog(
        title = stringResource(R.string.credits_add),
        text = "",
        confirmLabel = stringResource(R.string.credits_save),
        onConfirm = confirm@{
            val sid = sellerId ?: return@confirm
            if (text.isBlank()) return@confirm
            onSave(sid, text, ItalianNumbers.parseCents(amount))
        },
        onDismiss = onDismiss,
        body = {
            if (draft.sellerId == null) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.credits_supplier)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val matches = sellers.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }.take(6)
                Column {
                    matches.forEach { s ->
                        FilterChip(selected = sellerId == s.id, onClick = { sellerId = s.id; query = s.name }, label = { Text(s.name, maxLines = 1) })
                    }
                }
            }
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.credits_what)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = amount, onValueChange = { amount = it }, label = { Text(stringResource(R.string.credits_amount)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}
