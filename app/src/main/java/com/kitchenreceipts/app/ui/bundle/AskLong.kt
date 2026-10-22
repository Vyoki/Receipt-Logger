package com.kitchenreceipts.app.ui.bundle

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.Panel

/**
 * A long PDF the app can only read as one document (a scanned file, or one with no invoices it can cut apart): asked
 * before anything is queued. With the accountant tip, which the operator may share.
 */
@Composable
fun AskLong(pages: Int, onRead: () -> Unit, onDont: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.import_long_title, pages),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.import_long_body), style = MaterialTheme.typography.bodyLarge)
        BigButton(stringResource(R.string.import_long_read), null, onClick = onRead)
        BigButton(stringResource(R.string.import_dont), null, onClick = onDont, primary = false)
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.bundle_xml_tip), style = MaterialTheme.typography.bodyMedium)
                BigButton(stringResource(R.string.bundle_share_tip), Icons.Filled.Share, onClick = { shareTip(context) }, primary = false)
            }
        }
    }
}

/** The share sheet with the fixed tip text only: nothing about the file or the documents goes with it. */
fun shareTip(context: Context) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.bundle_tip_share_text))
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.bundle_share_tip)))
    } catch (_: ActivityNotFoundException) {
        // No app on the phone can share text: nothing to do.
    }
}
