package com.kitchenreceipts.app.ui.sellers

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import com.kitchenreceipts.app.ui.components.CategoryPickerSheet
import com.kitchenreceipts.app.ui.components.CategoryPill
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.FastScrollbar
import com.kitchenreceipts.app.ui.components.FastScrollbarDefaults
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.app.ui.components.scrollInitial
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtMoney
import com.kitchenreceipts.app.ui.supplierCategoryIcon
import com.kitchenreceipts.app.ui.supplierCategoryLabel
import com.kitchenreceipts.app.ui.supplierCategoryLabelRes
import com.kitchenreceipts.app.ui.theme.Motion
import com.kitchenreceipts.core.SupplierCategory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A supplier as listed: what was bought from it, and what it sells (null = not sorted yet). */
data class SellerRow(val stats: SellerStatsRow, val category: SupplierCategory?)

class SellersViewModel(private val repo: ReceiptRepository) : ViewModel() {
    val sellers: StateFlow<List<SellerRow>?> = combine(repo.sellerStats(), repo.sellerCategories()) { list, categories ->
        list.map { SellerRow(it, categories[it.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** For all of the supplier's invoices, remembered on this phone. Only grouping and to-link prompts change. */
    fun setCategory(s: SellerStatsRow, c: SupplierCategory) = viewModelScope.launch { repo.rememberCategory(s.vatNumber, s.name, c) }

    fun forgetCategory(s: SellerStatsRow) = viewModelScope.launch { repo.forgetCategory(s.vatNumber, s.name) }
}

/** One item of the list: a section's heading, or a supplier. */
sealed interface SellerEntry {
    val section: SupplierCategory?
}

data class SellerSection(override val section: SupplierCategory?, val count: Int) : SellerEntry

data class SellerItem(override val section: SupplierCategory?, val row: SellerRow) : SellerEntry

/** Filter keys: every supplier, the ones not sorted yet, or a category's key. */
const val SELLERS_ALL = "all"
const val SELLERS_NOT_SORTED = "none"

/**
 * The suppliers [filter] keeps, in sections in category order, "Not sorted" last; by name within each. While no
 * supplier is sorted there are no sections and no filter: the list is as it always was.
 */
fun sellerEntries(rows: List<SellerRow>, filter: String): List<SellerEntry> {
    if (rows.none { it.category != null }) return rows.map { SellerItem(null, it) }
    val kept = when (filter) {
        SELLERS_ALL -> rows
        SELLERS_NOT_SORTED -> rows.filter { it.category == null }
        else -> rows.filter { it.category?.key == filter }
    }
    return (SupplierCategory.entries + listOf(null)).flatMap { c ->
        val part = kept.filter { it.category == c }
        if (part.isEmpty()) emptyList() else listOf(SellerSection(c, part.size)) + part.map { SellerItem(c, it) }
    }
}

@Composable
fun SellersScreen(onBack: () -> Unit, onOpenSeller: (Long) -> Unit) {
    val vm = appViewModel { SellersViewModel(it.repository) }
    val sellers by vm.sellers.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(SELLERS_ALL) }
    var editing by remember { mutableStateOf<SellerRow?>(null) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val rows = sellers
    val grouped = rows.orEmpty().any { it.category != null }
    val entries = remember(rows, filter) { sellerEntries(rows.orEmpty(), filter) }
    // The last supplier of the chosen filter was sorted elsewhere, or none is sorted any more: back to all of them.
    val filterGone = rows != null && (!grouped || entries.isEmpty())
    LaunchedEffect(filterGone, filter) { if (filterGone && filter != SELLERS_ALL) filter = SELLERS_ALL }

    AppScaffold(title = stringResource(R.string.sellers), onBack = onBack, snackbarHostState = snackbar) { padding ->
        Box(Modifier.padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 16.dp + FastScrollbarDefaults.EndGutter),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (rows != null && rows.isEmpty()) item { EmptyState(stringResource(R.string.no_sellers)) }
                if (grouped) item(key = "filters") { SellerFilters(rows.orEmpty(), filter) { filter = it } }
                items(entries, key = { e -> if (e is SellerItem) e.row.stats.id else "h-${e.section?.key}" }) { e ->
                    Box(Modifier.animateItem(placementSpec = Motion.place)) {
                        when (e) {
                            is SellerSection -> SectionTitle(
                                stringResource(R.string.supcat_section, supplierCategoryLabel(e.section), e.count),
                                Modifier.semantics { heading() },
                            )
                            is SellerItem -> SellerCard(e.row, onOpen = { onOpenSeller(e.row.stats.id) }, onCategory = { editing = e.row })
                        }
                    }
                }
            }
            // By name, or by category and name: the bubble says the section and the first letter.
            FastScrollbar(listState, label = { i -> scrollLabel(context, entries, i - if (grouped) 1 else 0, grouped) })
        }
    }

    editing?.let { row ->
        CategoryPickerSheet(
            title = row.stats.name,
            selected = row.category,
            note = stringResource(R.string.supcat_change_seller_note),
            onPick = { c ->
                editing = null
                if (c != row.category) {
                    vm.setCategory(row.stats, c)
                    val text = context.getString(R.string.supcat_saved_seller, row.stats.name, context.getString(supplierCategoryLabelRes(c)))
                    scope.launch { snackbar.showSnackbar(text) }
                }
            },
            onDismiss = { editing = null },
            onForget = if (row.category != null) ({ editing = null; vm.forgetCategory(row.stats) }) else null,
        )
    }
}

@Composable
private fun SellerCard(row: SellerRow, onOpen: () -> Unit, onCategory: () -> Unit) {
    val s = row.stats
    val changeLabel = stringResource(R.string.supcat_change)
    ClickCard(
        onClick = onOpen,
        modifier = Modifier.semantics { customActions = listOf(CustomAccessibilityAction(changeLabel) { onCategory(); true }) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            CategoryPill(row.category, onClick = onCategory)
        }
    }
}

/** All, each category with suppliers in it, and "Not sorted (N)"; they wrap onto more rows instead of scrolling sideways. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SellerFilters(rows: List<SellerRow>, filter: String, onFilter: (String) -> Unit) {
    val counts = rows.groupingBy { it.category }.eachCount()
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = filter == SELLERS_ALL,
            onClick = { onFilter(SELLERS_ALL) },
            label = { Text(stringResource(R.string.sellers_filter_all), style = MaterialTheme.typography.titleSmall) },
            modifier = Modifier.heightIn(min = 56.dp),
        )
        SupplierCategory.entries.forEach { c ->
            val n = counts[c] ?: 0
            if (n > 0 || filter == c.key) {
                FilterChip(
                    selected = filter == c.key,
                    onClick = { onFilter(c.key) },
                    label = { Text(stringResource(R.string.supcat_section, supplierCategoryLabel(c), n), style = MaterialTheme.typography.titleSmall) },
                    leadingIcon = { Icon(supplierCategoryIcon(c), contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        }
        val notSorted = counts[null] ?: 0
        if (notSorted > 0 || filter == SELLERS_NOT_SORTED) {
            FilterChip(
                selected = filter == SELLERS_NOT_SORTED,
                onClick = { onFilter(SELLERS_NOT_SORTED) },
                label = { Text(stringResource(R.string.sellers_not_sorted_count, notSorted), style = MaterialTheme.typography.titleSmall) },
                leadingIcon = { Icon(supplierCategoryIcon(null), contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.heightIn(min = 56.dp),
            )
        }
    }
}

/** The fast scroller's bubble at entry [i]: the supplier's first letter, after its section's name when grouped. */
private fun scrollLabel(context: Context, entries: List<SellerEntry>, i: Int, grouped: Boolean): String? {
    val e = entries.getOrNull(i.coerceAtLeast(0)) ?: return null
    val section = context.getString(supplierCategoryLabelRes(e.section))
    return when (e) {
        is SellerSection -> section
        is SellerItem -> {
            val initial = scrollInitial(e.row.stats.name)
            if (!grouped) initial else listOfNotNull(section, initial).joinToString(" · ")
        }
    }
}
