@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package com.kitchenreceipts.app.ui.documents

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.kitchenreceipts.app.data.DocumentListRow
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.data.SellerEntity
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.CategoryTag
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.FastScrollbar
import com.kitchenreceipts.app.ui.components.FastScrollbarDefaults
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtMoney
import com.kitchenreceipts.app.ui.fmtMonth
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.SupplierCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

data class DocumentFilters(
    val query: String = "",
    val sellerId: Long? = null,
    val month: YearMonth? = null,
    val date: LocalDate? = null,
)

class DocumentsViewModel(private val repo: ReceiptRepository, initialSellerId: Long?) : ViewModel() {
    val filters = MutableStateFlow(DocumentFilters(sellerId = initialSellerId))

    val results: StateFlow<List<DocumentListRow>?> = filters.flatMapLatest { f ->
        val from = f.date ?: f.month?.atDay(1)
        val to = f.date ?: f.month?.atEndOfMonth()
        repo.searchDocuments(f.query, f.sellerId, from, to)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val sellers: StateFlow<List<SellerEntity>> = repo.sellers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** What each supplier sells, for the small label on its documents (read at display time). */
    val categories: StateFlow<Map<Long, SupplierCategory>> =
        repo.sellerCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val months: StateFlow<List<YearMonth>> = repo.documentDates()
        .map { dates -> dates.map(YearMonth::from).distinct().sortedDescending() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun update(f: (DocumentFilters) -> DocumentFilters) { filters.value = f(filters.value) }
}

@Composable
fun DocumentsScreen(initialSellerId: Long?, onBack: () -> Unit, onOpen: (Long) -> Unit, onSuppliers: (() -> Unit)? = null) {
    val vm = appViewModel(key = "docs-$initialSellerId") { DocumentsViewModel(it.repository, initialSellerId) }
    val filters by vm.filters.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val sellers by vm.sellers.collectAsStateWithLifecycle()
    val months by vm.months.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    var showDate by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    AppScaffold(
        title = stringResource(R.string.documents),
        onBack = onBack,
        actions = {
            // The suppliers with what was bought from each (it used to be a tile on the home screen).
            onSuppliers?.let { open ->
                androidx.compose.material3.TextButton(onClick = open) { Text(stringResource(R.string.sellers)) }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 16.dp + FastScrollbarDefaults.EndGutter),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = filters.query,
                        onValueChange = { q -> vm.update { it.copy(query = q) } },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (filters.query.isNotEmpty()) {
                                IconButton(onClick = { vm.update { it.copy(query = "") } }) {
                                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear))
                                }
                            }
                        },
                        placeholder = { Text(stringResource(R.string.search_documents_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val sellerName = sellers.firstOrNull { it.id == filters.sellerId }?.name
                        DropdownChip(
                            label = sellerName ?: stringResource(R.string.all_sellers),
                            selected = sellerName != null,
                            options = listOf<Pair<Long?, String>>(null to stringResource(R.string.all_sellers)) + sellers.map { it.id to it.name },
                            onSelect = { id -> vm.update { it.copy(sellerId = id) } },
                        )
                        DropdownChip(
                            label = filters.month?.let { fmtMonth(it) } ?: stringResource(R.string.all_months),
                            selected = filters.month != null,
                            options = listOf<Pair<YearMonth?, String>>(null to stringResource(R.string.all_months)) + months.map { it to fmtMonth(it) },
                            onSelect = { m -> vm.update { it.copy(month = m, date = null) } },
                        )
                        FilterChip(
                            selected = filters.date != null,
                            onClick = { showDate = true },
                            label = { Text(filters.date?.let { fmtDate(it) } ?: stringResource(R.string.any_day)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                        if (filters != DocumentFilters()) {
                            TextButton(onClick = { vm.update { DocumentFilters() } }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.clear_filters))
                            }
                        }
                    }
                }
                val rows = results
                if (rows != null && rows.isEmpty()) item { EmptyState(stringResource(R.string.no_matching_documents)) }
                items(rows.orEmpty(), key = { it.id }) { row ->
                    Box(Modifier.animateItem(placementSpec = com.kitchenreceipts.app.ui.theme.Motion.place)) {
                        DocumentListItem(row, categories[row.sellerId]) { onOpen(row.id) }
                    }
                }
            }
            FastScrollbar(listState, label = { i -> monthLabel(results, i) })
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = filters.date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val d = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    vm.update { it.copy(date = d, month = null) }
                    showDate = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.update { it.copy(date = null) }; showDate = false }) { Text(stringResource(R.string.any_day)) }
            },
        ) { DatePicker(state = state) }
    }
}

/** A document in the list, as on Home, with its supplier's category in small letters when it has one. */
@Composable
private fun DocumentListItem(row: DocumentListRow, category: SupplierCategory?, onClick: () -> Unit) {
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
                    add(pluralStringResource(R.plurals.item_count, row.itemCount, row.itemCount))
                }.joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                category?.let { CategoryTag(it, Modifier.padding(top = 2.dp)) }
            }
            Text(fmtMoney(row.totalCents, row.currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The month of the document at list position [i] (after the search field and the filters), for the scroller. */
private fun monthLabel(rows: List<DocumentListRow>?, i: Int): String? =
    rows?.getOrNull((i - LIST_HEAD).coerceAtLeast(0))?.documentDate?.let { fmtMonth(YearMonth.from(it)) }

/** Items above the documents: the search field and the filter chips. */
private const val LIST_HEAD = 2

@Composable
fun <T> DropdownChip(label: String, selected: Boolean, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.heightIn(min = 48.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { open = false; onSelect(value) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}
