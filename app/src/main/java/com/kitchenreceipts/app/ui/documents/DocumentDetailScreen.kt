package com.kitchenreceipts.app.ui.documents

import com.kitchenreceipts.app.ui.components.Panel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.OpenInNew
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
    val vm = appViewModel(key = "doc-$documentId") { DocumentDetailViewModel(it.repository, documentId, it.log) }
    val docResult by vm.document.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val priceChanges by vm.priceChanges.collectAsStateWithLifecycle()
    val files = appContainer().fileStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askDelete by remember { mutableStateOf(false) }
    val noViewer = stringResource(R.string.no_viewer_app)

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
                LazyColumn(
                    modifier = Modifier.padding(padding),
                    contentPadding = PaddingValues(16.dp),
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
                                    Icon(Icons.Filled.OpenInNew, contentDescription = null)
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
                    item("itemsTitle") { SectionTitle(stringResource(R.string.line_items_count, items.size)) }
                    if (items.isEmpty()) item("noItems") { EmptyState(stringResource(R.string.no_line_items)) }
                    items(items, key = { it.item.id }) { row -> SavedItemCard(row, d.currency, onOpenProduct) }
                    d.ocrText?.takeIf { it.isNotBlank() }?.let { t -> item("recognised") { RecognisedTextCard(t) } }
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

@Composable
private fun SavedItemCard(row: LineItemRow, currency: String?, onOpenProduct: (Long) -> Unit) {
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
                if (it.lotNumber != null) Text(stringResource(R.string.lot_value, it.lotNumber)) else MissingValue(stringResource(R.string.lot_not_recorded))
                it.expiryDate?.let { e -> Text(stringResource(R.string.expiry_value, fmtDate(e))) }
                it.vatRate?.let { v -> Text("IVA ${fmtDecimal(v)}%") }
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
