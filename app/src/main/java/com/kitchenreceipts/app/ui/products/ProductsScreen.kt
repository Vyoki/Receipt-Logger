package com.kitchenreceipts.app.ui.products

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import com.kitchenreceipts.core.Categories
import com.kitchenreceipts.core.Category
import com.kitchenreceipts.app.ui.components.CategoryIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.ProductEntity
import com.kitchenreceipts.app.data.ProductFamilyEntity
import com.kitchenreceipts.app.data.ProductNameTakenException
import com.kitchenreceipts.app.data.ProductWithSummary
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.data.UnassignedGroup
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.app.ui.review.ProductPickerSheet
import com.kitchenreceipts.app.ui.theme.LocalStatusColors
import com.kitchenreceipts.core.AverageCost
import com.kitchenreceipts.core.ProductFamilies
import com.kitchenreceipts.core.ItalianNumbers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProductsViewModel(private val repo: ReceiptRepository) : ViewModel() {
    val summaries: StateFlow<List<ProductWithSummary>?> = repo.productSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val unassigned: StateFlow<List<UnassignedGroup>> = repo.unassignedGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val products: StateFlow<List<ProductEntity>> = repo.products().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val families: StateFlow<List<ProductFamilyEntity>> = repo.families().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val suggestions: StateFlow<List<ProductFamilies.Suggestion>> =
        repo.familySuggestions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun acceptFamily(s: ProductFamilies.Suggestion) = viewModelScope.launch { repo.addToFamily(s.name, s.productIds) }
    fun refuseFamily(s: ProductFamilies.Suggestion) = viewModelScope.launch { repo.dismissFamilySuggestion(s.productIds) }

    fun assign(group: UnassignedGroup, product: ProductEntity) = viewModelScope.launch { repo.assignGroup(group, product.id) }

    fun createAndAssign(group: UnassignedGroup?, name: String, onTaken: (String) -> Unit) = viewModelScope.launch {
        val product = try {
            repo.createProduct(name)
        } catch (e: ProductNameTakenException) {
            if (group == null) { onTaken(e.existing.name); return@launch }
            e.existing
        } catch (e: IllegalArgumentException) {
            return@launch
        }
        if (group != null) repo.assignGroup(group, product.id)
    }
}

@Composable
fun ProductsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onOpenFamily: (Long) -> Unit) {
    val vm = appViewModel { ProductsViewModel(it.repository) }
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val unassigned by vm.unassigned.collectAsStateWithLifecycle()
    val products by vm.products.collectAsStateWithLifecycle()
    val families by vm.families.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    var assigning by remember { mutableStateOf<UnassignedGroup?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showAllUnassigned by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val takenMsg = stringResource(R.string.product_exists)

    AppScaffold(
        title = stringResource(R.string.products),
        onBack = onBack,
        snackbarHostState = snackbar,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.new_product)) })
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (unassigned.isNotEmpty()) {
                item {
                    SectionTitle(pluralStringResource(R.plurals.unassigned_title, unassigned.size, unassigned.size))
                    Text(stringResource(R.string.unassigned_hint), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
                }
                val shown = if (showAllUnassigned) unassigned else unassigned.take(5)
                items(shown, key = { "u${it.sellerId}-${it.aliasKey}" }) { g ->
                    val status = LocalStatusColors.current
                    Card(
                        onClick = { assigning = g },
                        colors = CardDefaults.cardColors(containerColor = status.missingContainer, contentColor = status.onMissing),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(g.description, style = MaterialTheme.typography.titleMedium)
                            Text(g.sellerName + " · " + pluralStringResource(R.plurals.lines_count, g.lineItemIds.size, g.lineItemIds.size), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (unassigned.size > 5 && !showAllUnassigned) {
                    item { TextButton(onClick = { showAllUnassigned = true }) { Text(stringResource(R.string.show_all, unassigned.size)) } }
                }
            }
            if (suggestions.isNotEmpty()) {
                item {
                    SectionTitle(stringResource(R.string.family_suggestions))
                    Text(stringResource(R.string.family_suggestions_hint), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
                }
                val names = products.associate { it.id to it.name }
                items(suggestions.take(5), key = { "s${it.productIds.firstOrNull()}-${it.name}" }) { s ->
                    FamilySuggestionCard(s, names, onAccept = { vm.acceptFamily(s) }, onDismiss = { vm.refuseFamily(s) })
                }
            }
            if (families.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.families)) }
                val counts = products.groupingBy { it.familyId }.eachCount()
                items(families, key = { "f${it.id}" }) { f ->
                    ClickCard(onClick = { onOpenFamily(f.id) }) {
                        Column {
                            Text(f.name, style = MaterialTheme.typography.titleMedium)
                            val n = counts[f.id] ?: 0
                            Text(pluralStringResource(R.plurals.family_products, n, n), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.products)) }
            val rows = summaries
            if (rows != null && rows.isEmpty()) item { EmptyState(stringResource(R.string.no_products)) }
            items(rows.orEmpty(), key = { it.product.id }) { p ->
                ClickCard(onClick = { onOpen(p.product.id) }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryIcon(Category.fromKey(p.product.category) ?: Categories.guess(p.product.name))
                            Spacer(Modifier.width(8.dp))
                            Text(p.product.name, style = MaterialTheme.typography.titleMedium)
                        }
                        p.product.familyId?.let { fid -> families.firstOrNull { it.id == fid } }?.let { f ->
                            Text(stringResource(R.string.family_value, f.name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        if (p.summary.averages.isEmpty()) {
                            Text(
                                if (p.purchaseCount == 0) stringResource(R.string.no_purchases) else stringResource(R.string.no_average_yet),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        p.summary.averages.take(2).forEach { a -> Text(averageLine(a), style = MaterialTheme.typography.bodyMedium) }
                        if (p.summary.averages.size > 2) Text("…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(40.dp)) }
        }
    }

    assigning?.let { g ->
        ProductPickerSheet(
            description = g.description,
            currentProductId = null,
            products = products,
            onPick = { vm.assign(g, it); assigning = null },
            onCreate = { name -> vm.createAndAssign(g, name) {}; assigning = null },
            onClear = { assigning = null },
            onDismiss = { assigning = null },
        )
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.new_product),
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                vm.createAndAssign(null, name) { scope.launch { snackbar.showSnackbar(takenMsg.format(it)) } }
            },
        )
    }
}

/** "8,0000 €/kg · IVA esclusa · 3 acquisti" */
@Composable
fun averageLine(a: AverageCost): String =
    stringResource(
        R.string.average_line,
        ItalianNumbers.formatDecimal(a.averageUnitCost, minScale = 2, maxScale = 4),
        a.unit,
        com.kitchenreceipts.app.ui.vatBasisLabel(a.vatBasis),
        pluralStringResource(R.plurals.purchases_count, a.purchaseCount, a.purchaseCount),
    )

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text(stringResource(R.string.name)) }) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
