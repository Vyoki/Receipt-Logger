package com.kitchenreceipts.app.ui.food

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.LoadingBox
import com.kitchenreceipts.app.ui.components.Panel
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtDecimal
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.OrderSuggestion
import com.kitchenreceipts.core.Reorder
import java.time.LocalDate

/** Suppliers, most recent delivery first; tapping one opens its usual order. */
@Composable
fun OrderScreen(onBack: () -> Unit) {
    val c = appContainer()
    val suppliers by remember { c.food.suppliers() }.collectAsState(initial = null as List<com.kitchenreceipts.app.data.SellerLastRow>?)
    var chosen by remember { mutableStateOf<Pair<Long, String>?>(null) }

    val pick = chosen
    if (pick != null) {
        OrderForSupplier(pick.first, pick.second, onBack = { chosen = null })
        return
    }
    AppScaffold(title = stringResource(R.string.order_title), onBack = onBack) { padding ->
        val list = suppliers
        if (list == null) { LoadingBox(Modifier.padding(padding)); return@AppScaffold }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(stringResource(R.string.order_pick_supplier), style = MaterialTheme.typography.titleMedium) }
            if (list.isEmpty()) item { EmptyState(stringResource(R.string.no_documents_yet)) }
            items(list, key = { it.id }) { s ->
                ClickCard(onClick = { chosen = s.id to s.name }) {
                    Column {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.order_last, fmtDate(s.lastDate)), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderForSupplier(sellerId: Long, sellerName: String, onBack: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    var lines by remember(sellerId) { mutableStateOf<List<OrderSuggestion>?>(null) }
    val qty = remember(sellerId) { mutableStateMapOf<Int, String>() }
    var note by remember(sellerId) { mutableStateOf("") }
    LaunchedEffect(sellerId) {
        val s = c.food.usualOrder(sellerId)
        s.forEachIndexed { i, o -> qty[i] = ItalianNumbers.formatDecimal(o.usualQuantity, maxScale = 3) }
        lines = s
    }
    androidx.activity.compose.BackHandler(onBack = onBack)
    val heading = stringResource(R.string.order_heading, sellerName, fmtDate(LocalDate.now()))

    AppScaffold(title = sellerName, onBack = onBack) { padding ->
        val list = lines
        if (list == null) { LoadingBox(Modifier.padding(padding)); return@AppScaffold }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.order_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Orange) }
            if (list.isEmpty()) item { EmptyState(stringResource(R.string.order_none)) }
            list.forEachIndexed { i, o ->
                item(key = "o$i") {
                    Panel {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(o.name, style = MaterialTheme.typography.titleMedium)
                                val usual = fmtDecimal(o.usualQuantity) + " " + o.unit
                                val every = o.everyDays
                                Text(
                                    if (every != null) stringResource(R.string.order_usual, usual, every.toInt(), fmtDate(o.lastDate))
                                    else stringResource(R.string.order_usual_once, usual, fmtDate(o.lastDate)),
                                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                                )
                            }
                            OutlinedTextField(
                                value = qty[i] ?: "", onValueChange = { qty[i] = it }, singleLine = true,
                                suffix = { Text(o.unit) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(120.dp),
                            )
                        }
                    }
                }
            }
            if (list.isNotEmpty()) {
                item {
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(stringResource(R.string.order_note)) }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    BigButton(stringResource(R.string.order_send), Icons.AutoMirrored.Filled.Send, onClick = {
                        val chosenLines = list.mapIndexedNotNull { i, o ->
                            val q = ItalianNumbers.parse(qty[i])?.takeIf { it.signum() > 0 } ?: return@mapIndexedNotNull null
                            o.name to (ItalianNumbers.formatDecimal(q, maxScale = 3) + " " + o.unit)
                        }
                        val text = Reorder.message(heading, chosenLines, note)
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, heading)
                        context.startActivity(Intent.createChooser(send, null))
                        c.log.event("ORDER_SHARED", "lines" to chosenLines.size)
                    })
                }
            }
        }
    }
}
