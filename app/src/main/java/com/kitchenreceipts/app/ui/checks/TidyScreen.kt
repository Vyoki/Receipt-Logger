package com.kitchenreceipts.app.ui.checks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.data.TidyRow
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.ItemMark
import com.kitchenreceipts.core.Tidy
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TidyViewModel(repo: ReceiptRepository) : ViewModel() {
    val rows: StateFlow<List<TidyRow>?> = repo.tidyLines().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

/**
 * Doubts left to tidy on invoices the app saved because their money is proven (see core Tidy): a lot or a unit read
 * unsure or not read, a name that may go on over the next printed line, a new supplier's name (its VAT number checked).
 * Each is settled with one tap, or by opening the invoice.
 */
/** The units offered in one tap for a line printed without one. */
private val UNITS = listOf("kg", "pz", "l")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TidyScreen(onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val c = appContainer()
    val vm = appViewModel { TidyViewModel(it.repository) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    AppScaffold(title = stringResource(R.string.tidy_title), onBack = onBack) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("hint") { Text(stringResource(R.string.tidy_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Orange) }
            val list = rows
            if (list != null && list.isEmpty()) item("none") { EmptyState(stringResource(R.string.tidy_none)) }
            items(list.orEmpty(), key = { it.lineId }) { r ->
                Box(Modifier.animateItem(placementSpec = com.kitchenreceipts.app.ui.theme.Motion.place)) {
                    ClickCard(onClick = { onEdit(r.documentId) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(r.description, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(r.sellerName, r.documentDate?.let { fmtDate(it) }, r.documentNumber?.let { "n. $it" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                            )
                            for (m in Tidy.of(ItemMark.decode(r.marks))) {
                                fun settle(keep: Boolean, chosen: String? = null) {
                                    scope.launch {
                                        c.repository.settleTidy(r, m, keep, chosen)
                                        c.log.event("TIDY", "field" to m.code, "keep" to keep, "chosen" to (chosen != null))
                                    }
                                }
                                val read = Tidy.suggestion(m)
                                Text(
                                    when {
                                        m.code == Tidy.NAME -> stringResource(R.string.tidy_name)
                                        m.code == Tidy.SUPPLIER -> stringResource(R.string.tidy_supplier, m.meaning)
                                        m.code == Tidy.EXPIRY && read != null -> stringResource(R.string.tidy_expiry_unsure, read)
                                        m.code == Tidy.EXPIRY -> stringResource(R.string.tidy_expiry_none)
                                        m.code == Tidy.UNIT && read != null -> stringResource(R.string.tidy_unit_unsure, read)
                                        m.code == Tidy.UNIT -> stringResource(R.string.tidy_unit_none)
                                        read != null -> stringResource(R.string.tidy_lot_unsure, read)
                                        else -> stringResource(R.string.tidy_lot_none)
                                    },
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    when {
                                        m.code == Tidy.NAME || m.code == Tidy.SUPPLIER -> OutlinedButton(onClick = { settle(true) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                            Text(stringResource(R.string.tidy_name_ok))
                                        }
                                        read != null -> {
                                            OutlinedButton(onClick = { settle(true) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                                Text(stringResource(R.string.tidy_keep, read))
                                            }
                                            TextButton(onClick = { settle(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                                Text(stringResource(R.string.tidy_leave_empty))
                                            }
                                        }
                                        // No unit printed: the common ones in one tap, or left empty.
                                        m.code == Tidy.UNIT -> {
                                            for (u in UNITS) OutlinedButton(onClick = { settle(true, u) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(u) }
                                            TextButton(onClick = { settle(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                                Text(stringResource(R.string.tidy_leave_empty))
                                            }
                                        }
                                        else -> TextButton(onClick = { settle(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                            Text(stringResource(R.string.tidy_none_printed))
                                        }
                                    }
                                    TextButton(onClick = { onEdit(r.documentId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text(stringResource(R.string.tidy_open))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
