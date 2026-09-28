@file:OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kitchenreceipts.app.ui.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.diagnostics.AppLog
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.categoryLabel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.LoadingBox
import com.kitchenreceipts.app.ui.components.PriceChangeRow
import com.kitchenreceipts.app.ui.fmtDecimal
import com.kitchenreceipts.app.ui.fmtMoney
import com.kitchenreceipts.app.ui.periodKindLabel
import com.kitchenreceipts.app.ui.periodLabel
import com.kitchenreceipts.app.ui.vatBasisLabel
import com.kitchenreceipts.core.Category
import com.kitchenreceipts.core.InventoryLine
import com.kitchenreceipts.core.InventoryReport
import com.kitchenreceipts.core.Period
import com.kitchenreceipts.core.PeriodKind
import com.kitchenreceipts.core.PriceChange
import com.kitchenreceipts.core.ProductCandidate
import com.kitchenreceipts.core.QuantityTotal
import com.kitchenreceipts.core.SpendTotal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class InventoryViewModel(private val repo: ReceiptRepository, private val log: AppLog) : ViewModel() {
    val period = MutableStateFlow(Period.of(LocalDate.now(), PeriodKind.MONTH))

    val report: StateFlow<InventoryReport?> = period.flatMapLatest { repo.inventory(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val priceChanges: StateFlow<List<PriceChange>?> = repo.priceHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val duplicates = MutableStateFlow<List<Pair<ProductCandidate, ProductCandidate>>>(emptyList())

    init { refreshDuplicates() }

    private fun refreshDuplicates() = viewModelScope.launch {
        duplicates.value = runCatching { repo.possibleDuplicateProducts() }.getOrDefault(emptyList())
    }

    fun setKind(k: PeriodKind) { period.value = Period.of(period.value.start, k) }
    fun previous() { period.value = period.value.previous() }
    fun next() { period.value = period.value.next() }

    /** The operator decided these are the same product: [from] is merged into [into]. */
    fun merge(from: Long, into: Long) = viewModelScope.launch {
        repo.mergeProducts(from, into)
        log.event("PRODUCTS_MERGED", "from" to from, "into" to into)
        refreshDuplicates()
    }

    fun dismissDuplicate(pair: Pair<ProductCandidate, ProductCandidate>) {
        duplicates.value = duplicates.value - pair
    }
}

@Composable
fun InventoryScreen(onBack: () -> Unit, onOpenProduct: (Long) -> Unit) {
    val vm = appViewModel { InventoryViewModel(it.repository, it.log) }
    var tab by rememberSaveable { mutableStateOf(0) }
    AppScaffold(title = stringResource(R.string.inventory), onBack = onBack) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.inventory)) }, modifier = Modifier.heightIn(min = 56.dp))
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.price_changes)) }, modifier = Modifier.heightIn(min = 56.dp))
            }
            if (tab == 0) InventoryTab(vm, onOpenProduct) else PriceChangesTab(vm, onOpenProduct)
        }
    }
}

@Composable
private fun InventoryTab(vm: InventoryViewModel, onOpenProduct: (Long) -> Unit) {
    val period by vm.period.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val duplicates by vm.duplicates.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var askMerge by remember { mutableStateOf<Pair<ProductCandidate, ProductCandidate>?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("kind") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PeriodKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = period.kind == k,
                        onClick = { vm.setKind(k) },
                        shape = SegmentedButtonDefaults.itemShape(i, PeriodKind.entries.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(periodKindLabel(k), maxLines = 1) }
                }
            }
        }
        item("period") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_period))
                }
                Text(
                    periodLabel(period),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::next, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_period))
                }
            }
        }
        val r = report
        if (r == null) {
            item("loading") { LoadingBox() }
            return@LazyColumn
        }
        if (r.categories.size > 1) {
            item("filter") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.all)) }) }
                    items(r.categories, key = { it.category.key }) { c ->
                        FilterChip(
                            selected = filter == c.category.key,
                            onClick = { filter = if (filter == c.category.key) null else c.category.key },
                            label = { Text(categoryLabel(c.category)) },
                        )
                    }
                }
            }
        }
        if (duplicates.isNotEmpty()) {
            item("dups") {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.possible_duplicates_title), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.possible_duplicates_hint), style = MaterialTheme.typography.bodyMedium)
                        duplicates.take(5).forEach { pair ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${pair.first.name}  ↔  ${pair.second.name}", modifier = Modifier.weight(1f))
                                TextButton(onClick = { askMerge = pair }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.merge)) }
                                TextButton(onClick = { vm.dismissDuplicate(pair) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.not_same)) }
                            }
                        }
                    }
                }
            }
        }
        val lines = r.lines.filter { filter == null || it.category.key == filter }
        if (lines.isEmpty()) {
            item("empty") { EmptyState(stringResource(R.string.inventory_empty)) }
        }
        lines.groupBy { it.category }.forEach { (category, group) ->
            item("h-${category.key}") {
                val total = r.categories.firstOrNull { it.category == category }
                Column(Modifier.padding(top = 8.dp)) {
                    Text(categoryLabel(category), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (total != null) {
                        Text(
                            pluralStringResource(R.plurals.products_count, total.productCount, total.productCount) + " · " + spendText(total.spend),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(group, key = { line -> line.productId?.let { "p$it" } ?: "d${line.name}" }) { line ->
                InventoryRow(line, period.kind) { line.productId?.let(onOpenProduct) }
            }
        }
    }

    askMerge?.let { pair ->
        AlertDialog(
            onDismissRequest = { askMerge = null },
            title = { Text(stringResource(R.string.merge_confirm_title)) },
            text = { Text(stringResource(R.string.merge_which, pair.first.name, pair.second.name)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { vm.merge(pair.second.id, pair.first.id); askMerge = null }) {
                        Text(stringResource(R.string.keep_name, pair.first.name))
                    }
                    TextButton(onClick = { vm.merge(pair.first.id, pair.second.id); askMerge = null }) {
                        Text(stringResource(R.string.keep_name, pair.second.name))
                    }
                    TextButton(onClick = { askMerge = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}

@Composable
private fun InventoryRow(line: InventoryLine, kind: PeriodKind, onClick: () -> Unit) {
    ClickCard(onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(line.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
                Text(qtyText(line.quantities).ifEmpty { "—" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                pluralStringResource(R.plurals.purchases_count, line.purchaseCount, line.purchaseCount) + " · " + spendText(line.spend),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (line.usual.isNotEmpty()) {
                Text(
                    stringResource(R.string.usual_per_period, qtyText(line.usual), periodKindLabel(kind).lowercase()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (line.withoutQuantity > 0) {
                Text(
                    pluralStringResource(R.plurals.without_quantity, line.withoutQuantity, line.withoutQuantity),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (line.productId == null) {
                Text(stringResource(R.string.no_product_assigned), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun qtyText(q: List<QuantityTotal>): String = q.joinToString(" + ") { "${fmtDecimal(it.amount)} ${it.unit}" }

@Composable
private fun spendText(spend: List<SpendTotal>): String =
    if (spend.isEmpty()) "—" else spend.map { "${fmtMoney(it.cents)} (${vatBasisLabel(it.vatBasis)})" }.joinToString(" + ")

@Composable
private fun PriceChangesTab(vm: InventoryViewModel, onOpenProduct: (Long) -> Unit) {
    val changes by vm.priceChanges.collectAsStateWithLifecycle()
    val list = changes
    when {
        list == null -> LoadingBox()
        list.isEmpty() -> EmptyState(stringResource(R.string.no_price_changes), Modifier.padding(16.dp))
        else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text(stringResource(R.string.price_changes_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(list) { c ->
                Card { Column(Modifier.padding(horizontal = 12.dp)) { PriceChangeRow(c) { onOpenProduct(c.productId) } } }
            }
        }
    }
}
