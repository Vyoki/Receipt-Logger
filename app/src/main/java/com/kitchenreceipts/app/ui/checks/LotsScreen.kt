package com.kitchenreceipts.app.ui.checks

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.ChecksRepository
import com.kitchenreceipts.app.data.LotLineRow
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtDecimal
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.ExpiringLine
import com.kitchenreceipts.core.Expiry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LotsViewModel(checks: ChecksRepository) : ViewModel() {
    val query = MutableStateFlow("")
    val hits: StateFlow<List<LotLineRow>> = query.debounce(250).flatMapLatest { checks.searchLots(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val expiring: StateFlow<List<ExpiringLine>?> = checks.expiring().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

/** Lot search (for a recall or an inspection) and what expires soon. */
@Composable
fun LotsScreen(onBack: () -> Unit, onOpenDocument: (Long) -> Unit) {
    val c = appContainer()
    val vm = appViewModel { LotsViewModel(it.checks) }
    val query by vm.query.collectAsStateWithLifecycle()
    val hits by vm.hits.collectAsStateWithLifecycle()
    val expiring by vm.expiring.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subject = stringResource(R.string.lots_share_subject)
    val today = LocalDate.now()

    AppScaffold(title = stringResource(R.string.lots_title), onBack = onBack) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("search") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { vm.query.value = it },
                        label = { Text(stringResource(R.string.lots_search)) }, singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.lots_search_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
                }
            }
            if (query.trim().length >= 2) {
                if (hits.isEmpty()) item("noHits") { EmptyState(stringResource(R.string.lots_none)) }
                else {
                    item("share") {
                        val text = lotSheet(query, hits)
                        BigButton(stringResource(R.string.lots_share), Icons.Filled.Share, primary = false, onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "$subject: ${query.trim()}")
                                .putExtra(Intent.EXTRA_TEXT, text)
                            context.startActivity(Intent.createChooser(send, null))
                            c.log.event("LOTS_SHARED", "rows" to hits.size)
                        })
                    }
                    items(hits, key = { "h" + it.lineItemId }) { r -> LotRow(r) { onOpenDocument(r.documentId) } }
                }
            }

            item("expT") { SectionTitle(stringResource(R.string.expiry_title)) }
            item("expH") { Text(stringResource(R.string.expiry_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Orange) }
            val exp = expiring
            if (exp != null && exp.isEmpty()) item("expNone") { Text(stringResource(R.string.expiry_none), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim) }
            items(exp.orEmpty(), key = { "e" + it.lineItemId }) { e ->
                ClickCard(onClick = { onOpenDocument(e.documentId) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.productName ?: e.description, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(e.sellerName, e.lot?.let { stringResource(R.string.lots_lot, it) }, qty(e.quantity, e.unit)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                            )
                            val left = Expiry.daysLeft(e, today)
                            Text(
                                fmtDate(e.expiry) + " · " + when {
                                    left == 0L -> stringResource(R.string.expiry_today)
                                    left < 0 -> stringResource(R.string.expiry_ago, (-left).toInt())
                                    else -> stringResource(R.string.expiry_in, left.toInt())
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (left <= 1) MaterialTheme.colorScheme.error else Palette.Orange,
                            )
                        }
                        TextButton(onClick = { scope.launch { c.checks.markExpiryHandled(e.lineItemId) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.expiry_done))
                        }
                    }
                }
            }
        }
    }
}

private fun qty(q: java.math.BigDecimal?, unit: String?): String? = q?.let { fmtDecimal(it) + (unit?.let { u -> " $u" } ?: "") }

@Composable
private fun LotRow(r: LotLineRow, onClick: () -> Unit) {
    ClickCard(onClick = onClick) {
        Column {
            Text(r.productName ?: r.originalDescription, style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(r.lotNumber?.let { stringResource(R.string.lots_lot, it) } ?: stringResource(R.string.lots_no_lot), r.expiryDate?.let { stringResource(R.string.lots_expiry, fmtDate(it)) }, qty(r.quantity, r.unit))
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                r.sellerName + " · " + fmtDate(r.documentDate) + (r.documentNumber?.let { " · n. $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
            )
        }
    }
}

/** Plain text for the share sheet: one line per delivery, for a recall file or an inspector. */
@Composable
private fun lotSheet(query: String, rows: List<LotLineRow>): String {
    val noLot = stringResource(R.string.lots_no_lot)
    return buildString {
        append(query.trim()).append('\n')
        rows.forEach { r ->
            append(fmtDate(r.documentDate)).append(" · ").append(r.sellerName)
            r.documentNumber?.let { append(" · n. ").append(it) }
            append(" · ").append(r.productName ?: r.originalDescription)
            qty(r.quantity, r.unit)?.let { append(" · ").append(it) }
            append(" · ").append(r.lotNumber?.let { "LOT $it" } ?: noLot)
            r.expiryDate?.let { append(" · SCAD ").append(fmtDate(it)) }
            append('\n')
        }
    }
}
