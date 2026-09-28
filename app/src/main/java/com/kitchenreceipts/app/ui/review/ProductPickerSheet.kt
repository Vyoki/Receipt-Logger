@file:OptIn(ExperimentalMaterial3Api::class)

package com.kitchenreceipts.app.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.ProductEntity
import com.kitchenreceipts.core.ProductMatching
import com.kitchenreceipts.core.ProductRef

/**
 * Assigns a receipt description to a canonical product. Similar products are only *suggested*;
 * the user taps one, or creates a new product. Nothing is merged automatically.
 */
@Composable
fun ProductPickerSheet(
    description: String,
    currentProductId: Long?,
    products: List<ProductEntity>,
    onPick: (ProductEntity) -> Unit,
    onCreate: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf(ProductMatching.proposeName(description)) }
    val suggestions = remember(description, products) {
        val byId = products.associateBy { it.id }
        ProductMatching.suggest(description, products.map { ProductRef(it.id, it.name) })
            .mapNotNull { byId[it.product.id] }
    }
    val key = ProductMatching.aliasKey(query)
    val filtered = products.filter { key.isBlank() || ProductMatching.aliasKey(it.name).contains(key) }
    val exact = products.firstOrNull { ProductMatching.aliasKey(it.name) == key }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.assign_product), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.on_document, description), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search_or_new_product)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (query.isNotBlank() && exact == null) {
                TextButton(onClick = { onCreate(query.trim()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.create_product, query.trim()))
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (suggestions.isNotEmpty() && query == ProductMatching.proposeName(description)) {
                    item { Text(stringResource(R.string.similar_products), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(suggestions, key = { "s${it.id}" }) { p -> ProductRow(p, p.id == currentProductId) { onPick(p) } }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                }
                items(filtered, key = { "p${it.id}" }) { p -> ProductRow(p, p.id == currentProductId) { onPick(p) } }
                if (currentProductId != null) {
                    item {
                        TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.remove_assignment))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductRow(p: ProductEntity, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(p.name) },
        trailingContent = { if (selected) Icon(Icons.Filled.Check, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
    )
}
