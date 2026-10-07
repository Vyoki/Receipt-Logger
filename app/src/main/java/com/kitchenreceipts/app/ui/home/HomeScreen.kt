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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.kitchenreceipts.app.ui.components.Button
import com.kitchenreceipts.app.ui.theme.Palette
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
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtMoney
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(repo: ReceiptRepository, checks: com.kitchenreceipts.app.data.ChecksRepository) : ViewModel() {
    val recent: StateFlow<List<DocumentListRow>?> =
        repo.recentDocuments(10).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val attention: StateFlow<Int> = checks.attention().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val expiring: StateFlow<List<com.kitchenreceipts.core.ExpiringLine>> =
        checks.expiring().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
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
    onBackup: () -> Unit = {},
    onChecks: () -> Unit = {},
    onLots: () -> Unit = {},
) {
    val vm = appViewModel { HomeViewModel(it.repository, it.checks) }
    val recent by vm.recent.collectAsStateWithLifecycle()
    val attention by vm.attention.collectAsStateWithLifecycle()
    val expiring by vm.expiring.collectAsStateWithLifecycle()

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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("jobs") { JobsSection(onReview = onReviewJob, onOpenDocument = onOpenDocument) }
            item("scan") { ScanButton(onScan) }
            if (!recent.isNullOrEmpty()) item("backup") { BackupReminder(onBackup) }
            if (expiring.isNotEmpty()) item("expiring") { ExpiringCard(expiring, onLots) }
            item("menu") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Tile(stringResource(R.string.inventory), Icons.Filled.Warehouse, onInventory, Modifier.weight(1f))
                        Tile(stringResource(R.string.products), Icons.Filled.Inventory2, onProducts, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Tile(stringResource(R.string.documents), Icons.Filled.Description, onDocuments, Modifier.weight(1f))
                        Tile(stringResource(R.string.sellers), Icons.Filled.Storefront, onSellers, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Tile(stringResource(R.string.checks_title), Icons.Filled.Verified, onChecks, Modifier.weight(1f), badge = attention)
                        Tile(stringResource(R.string.lots_title), Icons.Filled.QrCode2, onLots, Modifier.weight(1f))
                    }
                    WideTile(stringResource(R.string.monthly_reports), stringResource(R.string.rep_home_tile), Icons.Filled.BarChart, onReports)
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

/** The main action: tall phthalo green button with a lighter border. */
@Composable
private fun ScanButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 92.dp)) {
        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.scan_document), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
        }
    }
}

@Composable
private fun Tile(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Int = 0) {
    ClickCard(onClick = onClick, modifier = modifier.heightIn(min = 104.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = Palette.PhthaloBright)
                Spacer(Modifier.weight(1f))
                if (badge > 0) Text(badge.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Palette.Orange)
            }
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WideTile(label: String, note: String, icon: ImageVector, onClick: () -> Unit) {
    ClickCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = Palette.PhthaloBright)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(note, style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.TextDim)
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
                tint = Palette.PhthaloBright,
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.sellerName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = buildList {
                    add(fmtDate(row.documentDate))
                    row.documentNumber?.let { add("n. $it") }
                    add(pluralItems(row.itemCount))
                }.joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
            Text(fmtMoney(row.totalCents, row.currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun pluralItems(n: Int): String =
    androidx.compose.ui.res.pluralStringResource(R.plurals.item_count, n, n)

/** Shown when there is data and no backup for a week: losing the phone would lose everything. */
@Composable
private fun BackupReminder(onClick: () -> Unit) {
    val last = com.kitchenreceipts.app.ui.appContainer().settings.lastBackupAt
    val days = if (last <= 0L) -1L else (System.currentTimeMillis() - last) / 86_400_000L
    if (days in 0..6) return
    ClickCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(28.dp), tint = Palette.Orange)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    if (days < 0) stringResource(R.string.backup_reminder_never) else stringResource(R.string.backup_reminder, days.toInt()),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.backup_reminder_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.TextDim)
        }
    }
}

/** Use-by dates printed on the documents, in the next days: one line on the home screen. */
@Composable
private fun ExpiringCard(lines: List<com.kitchenreceipts.core.ExpiringLine>, onClick: () -> Unit) {
    val first = lines.first()
    ClickCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.EventBusy, contentDescription = null, modifier = Modifier.size(28.dp), tint = Palette.Orange)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(stringResource(R.string.expiry_home, lines.size), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.expiry_home_hint, (first.productName ?: first.description) + " · " + fmtDate(first.expiry)),
                    style = MaterialTheme.typography.bodySmall, color = Palette.Orange, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.TextDim)
        }
    }
}
