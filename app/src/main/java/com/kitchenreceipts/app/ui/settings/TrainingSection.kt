package com.kitchenreceipts.app.ui.settings

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kitchenreceipts.app.AppContainer
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.ConfirmDialog
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.app.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * The saved documents as training examples for the app's own AI: made on the phone, handed to the share sheet by the
 * operator (their own Google Drive, for the training notebook). Real documents: said before anything is made.
 */
@Composable
fun TrainingSection(c: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    // A package made earlier (another visit to Settings) can still be deleted.
    var made by remember { mutableStateOf(c.trainingExport.exists()) }
    val shareTitle = stringResource(R.string.training_share)
    val none = stringResource(R.string.training_none)
    val failed = stringResource(R.string.training_failed)
    val doneFormat = stringResource(R.string.training_done)
    val olderFormat = stringResource(R.string.training_older)

    SectionTitle(stringResource(R.string.training_title))
    Text(stringResource(R.string.training_hint), style = MaterialTheme.typography.bodySmall)
    progress?.let { (done, of) ->
        if (of > 0) Text(stringResource(R.string.training_progress, done, of), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { if (of == 0) 0f else done.toFloat() / of }, modifier = Modifier.fillMaxWidth())
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    BigButton(stringResource(R.string.training_make), Icons.Filled.School, primary = false, enabled = progress == null, onClick = { ask = true })
    if (made) BigButton(stringResource(R.string.training_forget), Icons.Filled.Delete, primary = false, onClick = {
        c.trainingExport.forget()
        made = false
        result = null
    })

    if (ask) {
        ConfirmDialog(
            title = stringResource(R.string.training_confirm_title),
            text = stringResource(R.string.training_confirm_text),
            confirmLabel = stringResource(R.string.training_make),
            onConfirm = {
                ask = false
                result = null
                progress = 0 to 0
                scope.launch {
                    try {
                        val r = c.trainingExport.export(c.settings.ownVatNumber) { d, of -> progress = d to of }
                        c.log.event("TRAINING_EXPORTED", "documents" to r.exported, "skipped" to r.skipped)
                        val uri = r.uri
                        val olderNote = if (r.older > 0) "\n" + String.format(olderFormat, r.older) else ""
                        if (uri == null) {
                            result = none + olderNote
                        } else {
                            made = true
                            result = String.format(doneFormat, r.exported, r.skipped) + olderNote
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("application/zip")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_SUBJECT, "Kitchen Receipts – training examples")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        c.log.error("trainingExport", e)
                        result = failed
                    } finally {
                        progress = null
                    }
                }
            },
            onDismiss = { ask = false },
        )
    }
}
