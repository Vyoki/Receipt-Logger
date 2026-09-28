package com.kitchenreceipts.app.ui.sellers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.data.SellerStatsRow
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtMoney
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SellersViewModel(repo: ReceiptRepository) : ViewModel() {
    val sellers: StateFlow<List<SellerStatsRow>?> = repo.sellerStats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
fun SellersScreen(onBack: () -> Unit, onOpenSeller: (Long) -> Unit) {
    val vm = appViewModel { SellersViewModel(it.repository) }
    val sellers by vm.sellers.collectAsStateWithLifecycle()
    AppScaffold(title = stringResource(R.string.sellers), onBack = onBack) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val rows = sellers
            if (rows != null && rows.isEmpty()) item { EmptyState(stringResource(R.string.no_sellers)) }
            items(rows.orEmpty(), key = { it.id }) { s ->
                ClickCard(onClick = { onOpenSeller(s.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                pluralStringResource(R.plurals.documents_count, s.documentCount, s.documentCount) +
                                    " · " + stringResource(R.string.last_document, fmtDate(s.lastDate)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(fmtMoney(s.totalCents), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
