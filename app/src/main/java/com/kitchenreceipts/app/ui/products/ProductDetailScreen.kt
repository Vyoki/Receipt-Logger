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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.AliasRow
import com.kitchenreceipts.app.data.ProductEntity
import com.kitchenreceipts.app.data.ProductNameTakenException
import com.kitchenreceipts.app.data.PurchaseRow
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.data.UnitConversionEntity
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.ConfirmDialog
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.KeyValue
import com.kitchenreceipts.app.ui.components.LoadingBox
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.app.ui.exclusionText
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtDecimal
import com.kitchenreceipts.app.ui.fmtMoney
import com.kitchenreceipts.app.ui.vatBasisLabel
import com.kitchenreceipts.core.CostSummary
import com.kitchenreceipts.core.Categories
import com.kitchenreceipts.core.Category
import com.kitchenreceipts.core.PriceChange
import com.kitchenreceipts.core.ProductCandidate
import com.kitchenreceipts.core.SmartMatcher
import com.kitchenreceipts.app.ui.categoryLabel
import com.kitchenreceipts.app.ui.components.PriceChangesCard
import androidx.compose.material.icons.filled.MergeType
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.Units
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

data class ProductDetail(
    val product: ProductEntity?,
    val purchases: List<PurchaseRow>,
    val summary: CostSummary,
    val conversions: List<UnitConversionEntity>,
    val aliases: List<AliasRow>,
)

class ProductDetailViewModel(private val repo: ReceiptRepository, private val id: Long) : ViewModel() {
    val detail: StateFlow<ProductDetail?> = combine(
        repo.observeProduct(id),
        repo.purchasesForProduct(id),
        repo.conversionsForProduct(id),
        repo.aliasesForProduct(id),
    ) { p, purchases, conv, aliases -> ProductDetail(p, purchases, repo.summarize(purchases, conv), conv, aliases) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun rename(name: String, onTaken: () -> Unit) = viewModelScope.launch {
        try { repo.renameProduct(id, name) } catch (_: ProductNameTakenException) { onTaken() } catch (_: IllegalArgumentException) {}
    }
    fun delete(done: () -> Unit) = viewModelScope.launch { repo.deleteProduct(id); done() }
    fun addConversion(from: String, to: String, factor: BigDecimal) = viewModelScope.launch { repo.addConversion(id, from, to, factor) }
    fun deleteConversion(cid: Long) = viewModelScope.launch { repo.deleteConversion(cid) }
    fun deleteAlias(aid: Long) = viewModelScope.launch { repo.deleteAlias(aid) }
    fun setCategory(c: Category) = viewModelScope.launch { repo.setCategory(id, c) }
    fun mergeInto(target: Long, done: () -> Unit) = viewModelScope.launch { repo.mergeProducts(id, target); done() }

    val otherProducts: StateFlow<List<ProductEntity>> = repo.products().map { list -> list.filter { it.id != id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val priceChanges: StateFlow<List<PriceChange>> = repo.priceHistory().map { list -> list.filter { it.productId == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable
fun ProductDetailScreen(productId: Long, onBack: () -> Unit, onOpenDocument: (Long) -> Unit) {
    val vm = appViewModel(key = "product-$productId") { ProductDetailViewModel(it.repository, productId) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val others by vm.otherProducts.collectAsStateWithLifecycle()
    val changes by vm.priceChanges.collectAsStateWithLifecycle()
    var choosingCategory by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var mergeTarget by remember { mutableStateOf<ProductEntity?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var addingConversion by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val takenMsg = stringResource(R.string.product_exists)

    val d = detail
    AppScaffold(
        title = d?.product?.name ?: stringResource(R.string.product),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            if (d?.product != null) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.rename)) }
                IconButton(onClick = { merging = true }) { Icon(Icons.Filled.MergeType, stringResource(R.string.merge_into)) }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
            }
        },
    ) { padding ->
        when {
            d == null -> LoadingBox(Modifier.padding(padding))
            d.product == null -> EmptyState(stringResource(R.string.product_not_found), Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item("category") {
                    val category = Category.fromKey(d.product.category) ?: Categories.guess(d.product.name)
                    ClickCard(onClick = { choosingCategory = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.category), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(categoryLabel(category), style = MaterialTheme.typography.titleMedium)
                            }
                            Text(stringResource(R.string.change), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (changes.isNotEmpty()) {
                    item("changes") { PriceChangesCard(changes.take(10)) }
                }
                item { SectionTitle(stringResource(R.string.average_cost)) }
                if (d.summary.averages.isEmpty()) {
                    item { EmptyState(stringResource(if (d.purchases.isEmpty()) R.string.no_purchases else R.string.no_average_yet)) }
                }
                items(d.summary.averages, key = { "${it.unit}-${it.vatBasis}" }) { a ->
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "${ItalianNumbers.formatDecimal(a.averageUnitCost, minScale = 2, maxScale = 4)} €/${a.unit}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(vatBasisLabel(a.vatBasis), style = MaterialTheme.typography.titleMedium)
                            KeyValue(stringResource(R.string.based_on), pluralStringResource(R.plurals.purchases_count, a.purchaseCount, a.purchaseCount))
                            KeyValue(stringResource(R.string.total_quantity), "${fmtDecimal(a.totalQuantity)} ${a.unit}")
                            KeyValue(stringResource(R.string.total_spent), fmtMoney(a.totalCents))
                            if (a.sourceUnits.size > 1 || a.sourceUnits.firstOrNull() != a.unit) {
                                Text(
                                    stringResource(R.string.converted_from, a.sourceUnits.sorted().joinToString(", ")),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (d.summary.averages.size > 1) {
                    item { Text(stringResource(R.string.separate_averages_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (d.summary.excluded.isNotEmpty()) {
                    item {
                        val reasons = d.summary.excluded.groupingBy { it.second }.eachCount()
                        Column {
                            Text(pluralStringResource(R.plurals.excluded_purchases, d.summary.excluded.size, d.summary.excluded.size), fontWeight = FontWeight.SemiBold)
                            reasons.forEach { (r, n) -> Text("• ${exclusionText(r)}: $n", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }

                item { SectionTitle(stringResource(R.string.unit_conversions)) }
                item { Text(stringResource(R.string.conversions_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(d.conversions, key = { "c${it.id}" }) { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("1 ${c.fromUnit} = ${fmtDecimal(c.factor, 6)} ${c.toUnit}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { vm.deleteConversion(c.id) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                    }
                }
                item {
                    OutlinedButton(onClick = { addingConversion = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Filled.Add, null)
                        Text(stringResource(R.string.add_conversion))
                    }
                }

                if (d.aliases.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.remembered_descriptions)) }
                    items(d.aliases, key = { "a${it.id}" }) { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("\"${a.aliasKey}\"", style = MaterialTheme.typography.bodyLarge)
                                Text(a.sellerName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.deleteAlias(a.id) }) { Icon(Icons.Filled.Delete, stringResource(R.string.forget)) }
                        }
                    }
                }

                item { SectionTitle(stringResource(R.string.price_history)) }
                if (d.purchases.isEmpty()) item { EmptyState(stringResource(R.string.no_purchases)) }
                items(d.purchases, key = { "p${it.lineItemId}" }) { p -> PurchaseCard(p) { onOpenDocument(p.documentId) } }
            }
        }
    }

    if (renaming && d?.product != null) {
        NameDialog(stringResource(R.string.rename), d.product.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            vm.rename(name) { scope.launch { snackbar.showSnackbar(takenMsg.format(name)) } }
        }
    }
    if (choosingCategory) {
        AlertDialog(
            onDismissRequest = { choosingCategory = false },
            title = { Text(stringResource(R.string.category)) },
            text = {
                LazyColumn {
                    items(Category.entries) { c ->
                        TextButton(onClick = { vm.setCategory(c); choosingCategory = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(categoryLabel(c), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingCategory = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (merging && d?.product != null) {
        var query by remember { mutableStateOf("") }
        val ranked = remember(query, others) {
            val base = if (query.isBlank()) d.product.name else query
            val scored = SmartMatcher.rank(base, others.map { ProductCandidate(it.id, it.name) }, 50).map { it.productId }
            val byId = others.associateBy { it.id }
            (scored.mapNotNull { byId[it] } + others.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }).distinct().take(30)
        }
        AlertDialog(
            onDismissRequest = { merging = false },
            title = { Text(stringResource(R.string.merge_into)) },
            text = {
                Column {
                    Text(stringResource(R.string.merge_hint, d.product.name), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.search)) })
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(ranked, key = { it.id }) { p ->
                            TextButton(onClick = { mergeTarget = p; merging = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(p.name, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { merging = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    mergeTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.merge_confirm_title),
            text = stringResource(R.string.merge_confirm_text, d?.product?.name ?: "", target.name),
            confirmLabel = stringResource(R.string.merge),
            onConfirm = { mergeTarget = null; vm.mergeInto(target.id, onBack) },
            onDismiss = { mergeTarget = null },
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.delete_product_title),
            text = stringResource(R.string.delete_product_text),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { deleting = false; vm.delete(onBack) },
            onDismiss = { deleting = false },
        )
    }
    if (addingConversion) {
        ConversionDialog(
            units = d?.purchases?.mapNotNull { it.unit }?.distinct().orEmpty(),
            onDismiss = { addingConversion = false },
            onConfirm = { from, factor, to -> addingConversion = false; vm.addConversion(from, to, factor) },
        )
    }
}

@Composable
private fun PurchaseCard(p: PurchaseRow, onClick: () -> Unit) {
    ClickCard(onClick = onClick) {
        Column {
            Row {
                Text(fmtDate(p.documentDate), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(fmtMoney(p.lineTotalCents, p.currency), fontWeight = FontWeight.SemiBold)
            }
            Text(p.sellerName, style = MaterialTheme.typography.bodyMedium)
            val qty = p.quantity?.let { "${fmtDecimal(it)} ${p.unit ?: "?"}" } ?: "—"
            // Unit price as printed; if absent, total / quantity, marked as calculated.
            val price = when {
                p.unitPrice != null -> "${fmtDecimal(p.unitPrice, 4)} €/${p.unit ?: "?"}"
                p.quantity != null && p.lineTotalCents != null && p.quantity.signum() != 0 ->
                    "${fmtDecimal(ItalianNumbers.centsToDecimal(p.lineTotalCents).divide(p.quantity, 4, RoundingMode.HALF_EVEN), 4)} €/${p.unit ?: "?"} " +
                        stringResource(R.string.calculated)
                else -> "—"
            }
            Text("$qty · $price", style = MaterialTheme.typography.bodyMedium)
            Text(
                vatBasisLabel(p.vatBasis) + " · " + (p.lotNumber?.let { stringResource(R.string.lot_value, it) } ?: stringResource(R.string.lot_not_recorded)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("\"${p.originalDescription}\"", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConversionDialog(units: List<String>, onDismiss: () -> Unit, onConfirm: (String, BigDecimal, String) -> Unit) {
    var from by remember { mutableStateOf(units.firstOrNull { Units.dimension(it) == null } ?: "conf") }
    var factor by remember { mutableStateOf("") }
    var to by remember { mutableStateOf(units.firstOrNull { it != from } ?: "pz") }
    val f = ItalianNumbers.parse(factor)
    val fromN = Units.normalize(from)
    val toN = Units.normalize(to)
    val valid = f != null && f.signum() > 0 && fromN != null && toN != null && fromN != toN
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_conversion)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.conversion_example))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("1")
                    OutlinedTextField(from, { from = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.unit)) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("=")
                    OutlinedTextField(
                        factor, { factor = it }, Modifier.weight(1f), singleLine = true,
                        label = { Text(stringResource(R.string.quantity)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(to, { to = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.unit)) })
                }
                HorizontalDivider()
            }
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onConfirm(fromN!!, f!!, toN!!) }, enabled = valid) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
