package com.kitchenreceipts.app.ui.products

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.knowledge.ProductLookupClient
import com.kitchenreceipts.app.ui.categoryLabel
import com.kitchenreceipts.app.ui.components.OutlinedButton
import com.kitchenreceipts.app.ui.components.Panel
import com.kitchenreceipts.core.ProductLookup
import kotlinx.coroutines.launch

/**
 * Suggestions from a public product database for one product. Shows the exact words that will be sent before
 * anything is sent; nothing is applied unless the operator taps "Use".
 */
@Composable
fun ProductLookupCard(client: ProductLookupClient, productName: String, onUse: (ProductLookup.Suggestion) -> Unit) {
    val scope = rememberCoroutineScope()
    val words = remember(productName) { client.searchedFor(productName) }
    var searching by remember { mutableStateOf(false) }
    var answers by remember(productName) { mutableStateOf<List<ProductLookup.Suggestion>?>(null) }
    var failed by remember(productName) { mutableStateOf(false) }

    Panel {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.lookup_title), style = MaterialTheme.typography.titleMedium)
            if (words == null) {
                Text(stringResource(R.string.lookup_nothing), style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Text(stringResource(R.string.lookup_sends, words), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (answers == null) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            searching = true
                            val a = client.lookup(productName)
                            failed = a == null
                            answers = a
                            searching = false
                        }
                    },
                    enabled = !searching,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
                ) { Text(stringResource(R.string.lookup_search)) }
            }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (failed) Text(stringResource(R.string.lookup_failed), style = MaterialTheme.typography.bodyMedium)
            answers?.let { list ->
                if (list.isEmpty()) Text(stringResource(R.string.lookup_none), style = MaterialTheme.typography.bodyMedium)
                list.forEach { s ->
                    Column(Modifier.padding(top = 10.dp)) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(s.brand, s.packSize, s.category?.let { categoryLabel(it) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (s.brand != null || s.category != null) {
                            TextButton(onClick = { onUse(s) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lookup_use)) }
                        }
                    }
                }
            }
        }
    }
}
