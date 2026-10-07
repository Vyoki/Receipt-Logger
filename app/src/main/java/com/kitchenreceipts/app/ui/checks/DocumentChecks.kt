package com.kitchenreceipts.app.ui.checks

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.DocumentEntity
import com.kitchenreceipts.app.data.InvoiceCheck
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.Panel
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.DocKind
import kotlinx.coroutines.launch

/**
 * On a saved document: its kind (changeable with one tap), and
 * - for a delivery note, the invoice it is charged on;
 * - for an invoice, its delivery notes and the differences with them.
 */
@Composable
fun DocumentKindAndChecks(d: DocumentEntity, onOpenDocument: (Long) -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val docs by androidx.compose.runtime.remember { c.checks.documents() }.collectAsState(initial = emptyList())
    val dismissed by androidx.compose.runtime.remember { c.checks.dismissed() }.collectAsState(initial = emptySet())
    val kind = DocKind.entries.firstOrNull { it.name == d.kind }
    val added = stringResource(R.string.credits_added)

    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.kind_title), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    DocKind.INVOICE to R.string.kind_invoice,
                    DocKind.DELIVERY_NOTE to R.string.kind_delivery_note,
                    DocKind.RECEIPT to R.string.kind_receipt,
                    DocKind.CREDIT_NOTE to R.string.kind_credit_note,
                ).forEach { (k, label) ->
                    FilterChip(
                        selected = kind == k,
                        onClick = {
                            scope.launch {
                                c.checks.setKind(d.id, if (kind == k) null else k)
                                c.log.event("KIND_SET", "doc" to d.id, "kind" to (if (kind == k) null else k))
                            }
                        },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            if (kind == null) Text(stringResource(R.string.kind_unknown), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            if (kind == DocKind.CREDIT_NOTE) Text(stringResource(R.string.kind_credit_note_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)

            if (kind == DocKind.DELIVERY_NOTE) {
                val inv = d.coveredBy?.let { id -> docs.firstOrNull { it.id == id } }
                if (inv != null) {
                    ClickCard(onClick = { onOpenDocument(inv.id) }) {
                        Text(stringResource(R.string.ddt_covered_by, inv.documentNumber ?: "—", fmtDate(inv.documentDate)), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(stringResource(R.string.ddt_not_yet), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
            }
        }
    }

    if (kind != DocKind.DELIVERY_NOTE && kind != DocKind.CREDIT_NOTE) {
        // Recomputed when any document changes (a delivery note saved later, a kind changed).
        val check by produceState<InvoiceCheck?>(null, d.id, d.updatedAt, docs) {
            value = runCatching { c.checks.invoiceCheck(d.id) }.getOrNull()
        }
        val ic = check
        if (ic != null && (ic.notes.isNotEmpty() || ic.missing.isNotEmpty())) {
            InvoiceCheckCard(
                ic = ic,
                heading = null,
                dismissed = dismissed,
                onOpen = null,
                onFine = { diff ->
                    scope.launch { val k = diff.key(ic.invoiceId); if (k in dismissed) c.checks.undismiss(k) else c.checks.dismiss(k) }
                },
                onCredit = { diff, text ->
                    scope.launch {
                        c.checks.addCredit(d.sellerId, d.id, text + (d.documentNumber?.let { " (n. $it)" } ?: ""), diff.amountCents)
                        c.log.event("CREDIT_ADDED", "cents" to diff.amountCents)
                        Toast.makeText(context, added, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
}
