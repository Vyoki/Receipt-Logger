package com.kitchenreceipts.app.ui.settings

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kitchenreceipts.app.AppContainer
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the checker model learns from (see CheckerLog): how many checked invoices are noted so far, and the file to
 * send to the operator's own Google Colab. Numbers only, no document text: no warning needed beyond saying so.
 */
@Composable
fun CheckerSection(c: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val counts by produceState<Pair<Int, Int>?>(null) { value = withContext(Dispatchers.IO) { runCatching { c.checkerLog.counts() }.getOrNull() ?: (0 to 0) } }
    var failed by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.checker_share)

    SectionTitle(stringResource(R.string.checker_title))
    Text(stringResource(R.string.checker_hint), style = MaterialTheme.typography.bodySmall)
    counts?.let { (invoices, values) ->
        Text(
            if (invoices == 0) stringResource(R.string.checker_none) else stringResource(R.string.checker_count, invoices, values),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (failed) Text(stringResource(R.string.checker_failed), style = MaterialTheme.typography.bodyMedium)
    BigButton(stringResource(R.string.checker_share), Icons.Filled.Share, primary = false, enabled = (counts?.first ?: 0) > 0, onClick = {
        failed = false
        scope.launch {
            val uri = withContext(Dispatchers.IO) { runCatching { c.checkerLog.shareable() }.onFailure { c.log.error("checkerShare", it) }.getOrNull() }
            if (uri == null) { failed = true; return@launch }
            c.log.event("CHECKER_SHARED", "invoices" to (counts?.first ?: 0))
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Kitchen Receipts – checker data")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, shareTitle))
        }
    })
}
