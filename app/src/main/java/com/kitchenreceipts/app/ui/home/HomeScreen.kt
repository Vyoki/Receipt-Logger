package com.kitchenreceipts.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.DocumentListRow
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtMoney
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(repo: ReceiptRepository) : ViewModel() {
    val recent: StateFlow<List<DocumentListRow>?> =
        repo.recentDocuments(10).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
fun HomeScreen(
    onScan: () -> Unit,
    onDocuments: () -> Unit,
    onSellers: () -> Unit,
    onProducts: () -> Unit,
    onReports: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    onSettings: () -> Unit,
    onInventory: () -> Unit,
    onReviewJob: (String) -> Unit,
) {
    val vm = appViewModel { HomeViewModel(it.repository) }
    val recent by vm.recent.collectAsStateWithLifecycle()

    AppScaffold(
        title = stringResource(R.string.app_name),
        onBack = null,
        actions = {
            androidx.compose.material3.IconButton(onClick = onSettings, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("jobs") { JobsSection(onReview = onReviewJob, onOpenDocument = onOpenDocument) }
            item {
                BigButton(
                    text = stringResource(R.string.scan_document),
                    icon = Icons.Filled.CameraAlt,
                    onClick = onScan,
                    modifier = Modifier.heightIn(min = 80.dp),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(stringResource(R.string.inventory), Icons.Filled.Warehouse, onInventory, Modifier.weight(1f))
                    Tile(stringResource(R.string.rep_home_tile), Icons.AutoMirrored.Filled.Send, onReports, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(stringResource(R.string.documents), Icons.Filled.Description, onDocuments, Modifier.weight(1f))
                    Tile(stringResource(R.string.sellers), Icons.Filled.Storefront, onSellers, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(stringResource(R.string.products), Icons.Filled.Inventory2, onProducts, Modifier.weight(1f))
                    Tile(stringResource(R.string.monthly_reports), Icons.Filled.BarChart, onReports, Modifier.weight(1f))
                }
            }
            item { SectionTitle(stringResource(R.string.recent_documents)) }
            val rows = recent
            if (rows != null && rows.isEmpty()) {
                item { EmptyState(stringResource(R.string.no_documents_yet)) }
            }
            items(rows.orEmpty(), key = { it.id }) { row -> DocumentRow(row) { onOpenDocument(row.id) } }
        }
    }
}

@Composable
private fun Tile(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(onClick = onClick, modifier = modifier.heightIn(min = 96.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun DocumentRow(row: DocumentListRow, onClick: () -> Unit) {
    ClickCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (row.mimeType == FileStore.MIME_PDF) Icons.Filled.PictureAsPdf else Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.sellerName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = buildList {
                    add(fmtDate(row.documentDate))
                    row.documentNumber?.let { add("n. $it") }
                    add(pluralItems(row.itemCount))
                }.joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(fmtMoney(row.totalCents, row.currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun pluralItems(n: Int): String =
    androidx.compose.ui.res.pluralStringResource(R.plurals.item_count, n, n)
