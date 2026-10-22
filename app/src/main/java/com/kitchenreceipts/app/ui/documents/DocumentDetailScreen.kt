package com.kitchenreceipts.app.ui.documents

import com.kitchenreceipts.app.ui.components.Panel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.kitchenreceipts.app.ui.components.FastScrollbar
import com.kitchenreceipts.app.ui.components.FastScrollbarDefaults
import com.kitchenreceipts.app.ui.components.JumpChip
import com.kitchenreceipts.app.ui.components.JumpChips
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.kitchenreceipts.app.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.DocumentWithSeller
import com.kitchenreceipts.app.data.LineItemRow
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.ConfirmDialog
import com.kitchenreceipts.app.ui.components.DocumentPages
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.KeyValue
import com.kitchenreceipts.app.ui.components.LoadingBox
import com.kitchenreceipts.app.ui.components.MissingValue
import com.kitchenreceipts.app.ui.components.RecognisedTextCard
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.app.ui.components.PriceChangesCard
import com.kitchenreceipts.core.PriceChange
import kotlinx.coroutines.flow.map
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.CheckCircle
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtDecimal
import com.kitchenreceipts.app.ui.fmtMoney
import com.kitchenreceipts.app.ui.vatBasisLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DocumentDetailViewModel(
    private val repo: ReceiptRepository,
    private val id: Long,
    private val log: com.kitchenreceipts.app.diagnostics.AppLog,
    /** Removes what else the phone keeps about a deleted document (its reading, reports, unsaved edits). */
    private val forget: (Long) -> Unit = {},
) : ViewModel() {
    /** null = loading; Result with null value = deleted / not found. */
    val document: StateFlow<Result<DocumentWithSeller?>?> =
        kotlinx.coroutines.flow.flow { repo.observeDocument(id).collect { emit(Result.success(it)) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val items: StateFlow<List<LineItemRow>> = repo.observeItems(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Prices on this document that differ from the previous purchase of the same product. */
    val priceChanges: StateFlow<List<PriceChange>> = repo.observeItems(id)
        .map { runCatching { repo.priceChangesForDocument(id) }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun delete() {
        repo.deleteDocument(id)
        runCatching { forget(id) }
        log.event("DELETED", "doc" to id)
    }
}

@Composable
fun DocumentDetailScreen(
    documentId: Long,
    autoSaved: Boolean = false,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onViewOriginal: () -> Unit,
    onOpenProduct: (Long) -> Unit,
    onOpenDocument: (Long) -> Unit = {},
) {
    val vm = appViewModel(key = "doc-$documentId") { DocumentDetailViewModel(it.repository, documentId, it.log) { doc ->
        it.readings.forget(doc)
        it.problems.forget(doc)
        java.io.File(it.appContext.filesDir, "edits/$doc.json").delete()
    } }
    val docResult by vm.document.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val priceChanges by vm.priceChanges.collectAsStateWithLifecycle()
    val files = appContainer().fileStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askDelete by remember { mutableStateOf(false) }
    val noViewer = stringResource(R.string.no_viewer_app)
    val listState = rememberLazyListState()

    val doc = docResult?.getOrNull()
    AppScaffold(
        title = doc?.sellerName ?: stringResource(R.string.document),
        onBack = onBack,
        actions = {
            if (doc != null) {
                IconButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
                }
                IconButton(onClick = { askDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        },
    ) { padding ->
        when {
            docResult == null -> LoadingBox(Modifier.padding(padding))
            doc == null -> EmptyState(stringResource(R.string.document_not_found), Modifier.padding(padding))
            else -> {
                val d = doc.document
                val hasText = !d.ocrText.isNullOrBlank()
                val parts = DetailParts(autoSaved, priceChanges.isNotEmpty(), items.size)
                val jumpPages = stringResource(R.string.jump_pages)
                val jumpSummary = stringResource(R.string.jump_summary)
                val jumpLines = stringResource(R.string.jump_lines)
                val recognised = stringResource(R.string.recognised_text)
                val goTo: (Int) -> Unit = { i -> scope.launch { listState.animateScrollToItem(i) } }
                val chips = listOf(JumpChip(jumpPages, parts.pages), JumpChip(jumpSummary, parts.summary), JumpChip(jumpLines, parts.title))
                Column(Modifier.padding(padding)) {
                    // Jumps to the parts of a long document; short ones need none.
                    if (items.size >= JUMP_CHIPS_FROM_LINES) {
                        JumpChips(chips, goTo, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 16.dp + FastScrollbarDefaults.EndGutter),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (autoSaved) {
                                item("auto") {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        shape = MaterialTheme.shapes.medium,
                                    ) {
                                        Row(Modifier.fillMaxWidth().padding(12.dp)) {
                                            Icon(Icons.Filled.CheckCircle, contentDescription = null)
                                            Spacer(Modifier.width(10.dp))
                                            Text(stringResource(R.string.auto_saved_banner), style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                            if (priceChanges.isNotEmpty()) {
                                item("prices") { PriceChangesCard(priceChanges, onOpenProduct = onOpenProduct) }
                            }
                            item("pages") {
                                Panel {
                                    DocumentPages(d.filePath, d.mimeType, d.pageCount, Modifier.fillMaxWidth().height(300.dp), onClick = onViewOriginal)
                                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = onViewOriginal, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                            Icon(Icons.Filled.OpenInFull, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.full_screen))
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                val intent = Intent(Intent.ACTION_VIEW)
                                                    .setDataAndType(files.uriForSharing(d.filePath), d.mimeType)
                                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                try {
                                                    context.startActivity(Intent.createChooser(intent, null))
                                                } catch (_: ActivityNotFoundException) {
                                                    Toast.makeText(context, noViewer, Toast.LENGTH_LONG).show()
                                                }
                                            },
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(when (d.mimeType) { FileStore.MIME_PDF -> R.string.open_pdf; FileStore.MIME_XML -> R.string.open_xml; else -> R.string.open_image }))
                                        }
                                    }
                                }
                            }
                            item("summary") {
                                Panel {
                                    Column(Modifier.padding(16.dp)) {
                                        KeyValue(stringResource(R.string.seller), doc.sellerName, emphasize = true)
                                        KeyValue(stringResource(R.string.date), fmtDate(d.documentDate))
                                        KeyValue(stringResource(R.string.document_number), d.documentNumber ?: "—")
                                        KeyValue(stringResource(R.string.prices_are), vatBasisLabel(d.vatBasis))
                                        KeyValue(stringResource(R.string.subtotal), fmtMoney(d.subtotalCents, d.currency))
                                        KeyValue(stringResource(R.string.vat_amount), fmtMoney(d.vatCents, d.currency))
                                        KeyValue(stringResource(R.string.total), fmtMoney(d.totalCents, d.currency), emphasize = true)
                                    }
                                }
                            }
                            item("kind") { com.kitchenreceipts.app.ui.checks.DocumentKindAndChecks(d, onOpenDocument) }
                            item("itemsTitle") { SectionTitle(stringResource(R.string.line_items_count, items.size), Modifier.semantics { heading() }) }
                            if (items.isEmpty()) item("noItems") { EmptyState(stringResource(R.string.no_line_items)) }
                            // "Lot not recorded" only where the document prints lots (some line has one), never on a charge line.
                            val lotsPrinted = items.any { it.item.lotNumber != null }
                            items(items, key = { it.item.id }) { row -> SavedItemCard(row, d.currency, onOpenProduct, lotsPrinted) }
                            d.ocrText?.takeIf { it.isNotBlank() }?.let { t -> item("recognised") { RecognisedTextCard(t) } }
                        }
                        FastScrollbar(
                            listState,
                            label = { i ->
                                when {
                                    i < parts.summary -> jumpPages
                                    i < parts.title -> jumpSummary
                                    // No lines: the title, then "no line items".
                                    parts.lines == 0 -> if (i <= parts.title + 1 || !hasText) jumpLines else recognised
                                    i <= parts.lastLine ->
                                        context.getString(R.string.scroll_line, (i - parts.firstLine).coerceAtLeast(0) + 1, parts.lines)
                                    hasText -> recognised
                                    else -> jumpLines
                                }
                            },
                            actions = chips.map { c -> CustomAccessibilityAction(c.label) { goTo(c.index); true } },
                        )
                    }
                }
            }
        }
    }

    if (askDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_document_title),
            text = stringResource(R.string.delete_document_text),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                askDelete = false
                scope.launch {
                    vm.delete()
                    onBack()
                }
            },
            onDismiss = { askDelete = false },
        )
    }
}

/** A document with this many lines or more gets the jump chips (with the header, about 4 screens). */
private const val JUMP_CHIPS_FROM_LINES = 12

/** Where the parts of the detail list are, by index (the order of the items above). */
private class DetailParts(autoSaved: Boolean, prices: Boolean, val lines: Int) {
    val pages = (if (autoSaved) 1 else 0) + (if (prices) 1 else 0)
    val summary = pages + 1
    val title = pages + 3 // after the summary and the kind card
    val firstLine = title + 1
    val lastLine = firstLine + lines - 1
}

@Composable
private fun SavedItemCard(row: LineItemRow, currency: String?, onOpenProduct: (Long) -> Unit, lotsPrinted: Boolean = true) {
    val it = row.item
    Panel {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(it.originalDescription, style = MaterialTheme.typography.titleMedium)
            val pid = it.productId
            if (pid != null && row.productName != null) {
                AssistChip(onClick = { onOpenProduct(pid) }, label = { Text(stringResource(R.string.product_is, row.productName)) })
            } else {
                Text(stringResource(R.string.no_product_assigned), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val marks = com.kitchenreceipts.core.ItemMark.decode(it.marks)
            if (marks.isNotEmpty()) {
                Text(com.kitchenreceipts.app.ui.review.marksLine(marks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val qty = if (it.quantity != null) "${fmtDecimal(it.quantity)} ${it.unit ?: ""}".trim() else null
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOfNotNull(qty, it.unitPrice?.let { p -> "× ${fmtDecimal(p, 4)}" }, it.discount?.let { d -> stringResource(R.string.discount_value, d) })
                        .joinToString(" ").ifEmpty { "—" },
                    modifier = Modifier.weight(1f),
                )
                Text(fmtMoney(it.lineTotalCents, currency), fontWeight = FontWeight.SemiBold)
            }
            // What one unit really cost after the discount (the price used for price changes and food cost).
            if (it.discount != null) paidPerUnit(it.quantity, it.lineTotalCents)?.let { paid ->
                Text(
                    stringResource(R.string.paid_per_unit, fmtDecimal(paid, 4), it.unit ?: "?"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val charge = it.productId == null && com.kitchenreceipts.core.Adjustments.isAdjustment(it.originalDescription, it.lineTotalCents, it.quantity)
                if (it.lotNumber != null) Text(stringResource(R.string.lot_value, it.lotNumber))
                else if (lotsPrinted && !charge) MissingValue(stringResource(R.string.lot_not_recorded))
                it.expiryDate?.let { e -> Text(stringResource(R.string.expiry_value, fmtDate(e))) }
                it.vatRate?.let { v -> Text(androidx.compose.ui.res.stringResource(com.kitchenreceipts.app.R.string.vat_rate_short, fmtDecimal(v))) }
                it.packages?.let { p -> Text(stringResource(R.string.packages_value, p)) }
            }
        }
    }
}

/** Amount / quantity: the price per unit really paid. */
internal fun paidPerUnit(quantity: java.math.BigDecimal?, cents: Long?): java.math.BigDecimal? {
    if (quantity == null || cents == null || quantity.signum() == 0) return null
    return com.kitchenreceipts.core.ItalianNumbers.centsToDecimal(cents).divide(quantity, 4, java.math.RoundingMode.HALF_EVEN)
}
