package com.kitchenreceipts.app.ui.products

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import com.kitchenreceipts.app.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.kitchenreceipts.app.ui.components.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.ProductEntity
import com.kitchenreceipts.app.data.ProductFamilyEntity
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.ConfirmDialog
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.LoadingBox
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.theme.LocalStatusColors
import com.kitchenreceipts.app.ui.vatBasisLabel
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.ProductFamilies
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ suggestion card (Products screen)

/** "Passata di pomodoro: 3 products — Group them / No". Nothing is grouped until the operator taps. */
@Composable
fun FamilySuggestionCard(
    suggestion: ProductFamilies.Suggestion,
    names: Map<Long, String>,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (suggestion.existingFamilyId != null) stringResource(R.string.family_add_to, suggestion.name)
                else stringResource(R.string.family_new_suggested, suggestion.name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            suggestion.productIds.take(6).forEach { id -> Text("• " + (names[id] ?: "?"), style = MaterialTheme.typography.bodyMedium) }
            if (suggestion.productIds.size > 6) Text("…", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = onAccept, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.family_accept)) }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.family_refuse)) }
            }
        }
    }
}

// ------------------------------------------------------------------ choosing a product's group

/** Pick an existing group, type a new one, or take the product out of its group. */
@Composable
fun FamilyPickerDialog(
    productName: String,
    current: ProductFamilyEntity?,
    families: List<ProductFamilyEntity>,
    onPick: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val suggested = remember(productName) { ProductFamilies.genericName(productName) }
    var text by remember { mutableStateOf(current?.name ?: suggested ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.family)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.family_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.family_name)) })
                if (suggested != null && families.none { ProductFamilies.key(it.name) == ProductFamilies.key(suggested) }) {
                    TextButton(onClick = { text = suggested }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.family_suggested, suggested), modifier = Modifier.fillMaxWidth())
                    }
                }
                if (families.isNotEmpty()) {
                    HorizontalDivider()
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(families, key = { it.id }) { f ->
                            TextButton(onClick = { onPick(f.name) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(f.name, modifier = Modifier.fillMaxWidth(), fontWeight = if (f.id == current?.id) FontWeight.Bold else null)
                            }
                        }
                    }
                }
                if (current != null) {
                    TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.family_remove), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------ group screen

class FamilyViewModel(private val repo: ReceiptRepository, private val id: Long) : ViewModel() {
    val family: StateFlow<ProductFamilyEntity?> = repo.observeFamily(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val members: StateFlow<List<ProductEntity>?> = repo.productsInFamily(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val prices: StateFlow<List<ProductFamilies.VariantPrice>?> = repo.familyComparison(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun rename(name: String, onTaken: () -> Unit) = viewModelScope.launch {
        try { repo.renameFamily(id, name) } catch (_: IllegalArgumentException) { onTaken() }
    }
    fun delete(done: () -> Unit) = viewModelScope.launch { repo.deleteFamily(id); done() }
    fun remove(productId: Long) = viewModelScope.launch { repo.dismissFamilySuggestion(listOf(productId)) }
}

/**
 * One group: the latest price of each of its products from each supplier, cheapest per kg/l first, then the
 * products that belong to it. Products are never merged: each keeps its own purchases.
 */
@Composable
fun FamilyScreen(familyId: Long, onBack: () -> Unit, onOpenProduct: (Long) -> Unit) {
    val vm = appViewModel(key = "family-$familyId") { FamilyViewModel(it.repository, familyId) }
    val family by vm.family.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val prices by vm.prices.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var taken by remember { mutableStateOf(false) }

    AppScaffold(
        title = family?.name ?: stringResource(R.string.family),
        onBack = onBack,
        actions = {
            if (family != null) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.rename)) }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
            }
        },
    ) { padding ->
        val list = members
        val rows = prices
        when {
            list == null || rows == null -> LoadingBox(Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { SectionTitle(stringResource(R.string.family_prices)) }
                item {
                    Text(stringResource(R.string.family_prices_hint), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
                }
                if (rows.isEmpty()) item { EmptyState(stringResource(R.string.no_purchases)) }
                items(rows, key = { "v${it.productId}-${it.sellerName}" }) { r -> VariantPriceCard(r) { onOpenProduct(r.productId) } }

                item { SectionTitle(pluralStringResource(R.plurals.family_products, list.size, list.size)) }
                items(list, key = { "m${it.id}" }) { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ClickCard(onClick = { onOpenProduct(p.id) }, modifier = Modifier.weight(1f)) {
                            Column {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                p.brand?.let { Text(stringResource(R.string.brand_value, it), style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                        IconButton(onClick = { vm.remove(p.id) }) { Icon(Icons.Filled.Delete, stringResource(R.string.family_remove)) }
                    }
                }
                item { Text(stringResource(R.string.family_add_hint), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange) }
            }
        }
    }

    if (renaming && family != null) {
        NameDialog(stringResource(R.string.rename), family!!.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            vm.rename(name) { taken = true }
        }
    }
    if (taken) {
        AlertDialog(
            onDismissRequest = { taken = false },
            text = { Text(stringResource(R.string.family_name_taken)) },
            confirmButton = { TextButton(onClick = { taken = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.family_delete_title),
            text = stringResource(R.string.family_delete_text),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { deleting = false; vm.delete(onBack) },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun VariantPriceCard(r: ProductFamilies.VariantPrice, onClick: () -> Unit) {
    val status = LocalStatusColors.current
    ClickCard(onClick = onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.productName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (r.cheapest) {
                    Text(
                        stringResource(R.string.family_cheapest),
                        color = status.ok,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            val perBase = r.perBase
            val main = if (perBase != null) {
                "${ItalianNumbers.formatDecimal(perBase, minScale = 2, maxScale = 2)} €/${r.baseUnit}"
            } else {
                "${ItalianNumbers.formatDecimal(r.paid, minScale = 2, maxScale = 4)} €/${r.paidUnit}"
            }
            Text(main, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (r.perBase != null && r.baseUnit != r.paidUnit) {
                Text(
                    stringResource(R.string.family_paid, ItalianNumbers.formatDecimal(r.paid, minScale = 2, maxScale = 4), r.paidUnit),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (r.perBase == null) {
                Text(stringResource(R.string.family_size_unknown), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
            }
            val who = listOfNotNull(r.brand?.let { stringResource(R.string.brand_value, it) }, r.sellerName, fmtDate(r.date)).joinToString(" · ")
            Text(who, style = MaterialTheme.typography.bodyMedium)
            Text(vatBasisLabel(r.vatBasis), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
