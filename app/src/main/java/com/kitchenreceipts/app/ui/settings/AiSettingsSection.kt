package com.kitchenreceipts.app.ui.settings

import com.kitchenreceipts.app.ui.components.Panel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import com.kitchenreceipts.app.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.kitchenreceipts.app.ui.components.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.AppContainer
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ai.AiModelStore
import com.kitchenreceipts.app.ai.AiPageReader
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.app.ui.components.ConfirmDialog
import com.kitchenreceipts.app.ui.components.SectionTitle
import kotlinx.coroutines.launch
import java.util.Locale

/** Settings ▸ AI reader: install the model (via the browser, the app stays offline), choose when it runs, test it. */
@Composable
fun AiSettingsSection(c: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = c.aiModels
    var installed by remember { mutableStateOf(store.installed) }
    var name by remember { mutableStateOf(store.installedName) }
    var option by remember { mutableStateOf(AiModelStore.OPTIONS.first()) }
    var mode by remember { mutableStateOf(c.settings.aiMode) }
    var copying by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var askRemove by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val support = remember(option) { store.deviceSupport(option) }
    val noBrowser = stringResource(R.string.no_browser)
    val readyFmt = stringResource(R.string.ai_test_ok)
    val failedFmt = stringResource(R.string.ai_failed)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            copying = 0f
            message = null
            try {
                val label = store.import(uris) { done, total -> copying = if (total > 0) done.toFloat() / total else null }
                installed = true
                name = label
                c.log.event("AI_MODEL_INSTALLED", "name" to label, "bytes" to store.sizeBytes)
            } catch (e: Exception) {
                message = failedFmt.format(e.message ?: e.javaClass.simpleName)
                c.log.error("aiImport", e)
            } finally {
                copying = null
            }
        }
    }

    fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            message = noBrowser
        }
    }

    SectionTitle(stringResource(R.string.ai_reader))
    Text(stringResource(R.string.ai_reader_hint), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.ai_this_phone, String.format(Locale.ROOT, "%.0f", support.ramGb)) + " " +
                    stringResource(if (support.nativeOk && support.enoughRam) R.string.ai_phone_ok else R.string.ai_phone_weak),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (installed) {
                Text(
                    stringResource(R.string.ai_installed, name ?: "", String.format(Locale.ROOT, "%.1f", store.sizeBytes / 1e9)),
                    fontWeight = FontWeight.SemiBold,
                )
                if (!AiModelStore.isTrained(name)) {
                    // Installed before only the trained model was offered: it still works, but reads worse and slower.
                    Text(stringResource(R.string.ai_general_installed), color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
                }
                Text(stringResource(R.string.ai_when), style = MaterialTheme.typography.titleSmall)
                listOf(
                    AppSettings.AiMode.WHEN_NEEDED to R.string.ai_mode_needed,
                    AppSettings.AiMode.ALWAYS to R.string.ai_mode_always,
                    AppSettings.AiMode.OFF to R.string.ai_mode_off,
                ).forEach { (m, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(mode == m, role = Role.RadioButton, onClick = {
                            mode = m
                            c.settings.aiMode = m
                            c.log.event("SETTINGS", "aiMode" to m)
                        }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == m, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            message = null
                            scope.launch {
                                val t0 = System.currentTimeMillis()
                                message = try {
                                    val reader = AiPageReader.open(store, context.applicationInfo.nativeLibraryDir)
                                    reader.close()
                                    readyFmt.format(String.format(Locale.ROOT, "%.1f", (System.currentTimeMillis() - t0) / 1000.0))
                                } catch (e: Exception) {
                                    failedFmt.format(e.message ?: e.javaClass.simpleName)
                                }
                                c.log.event("AI_TEST", "result" to message)
                                testing = false
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(if (testing) R.string.ai_testing else R.string.ai_test)) }
                    OutlinedButton(onClick = { askRemove = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ai_remove)) }
                }
            } else {
                Text(stringResource(R.string.ai_step1), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.ai_option_trained, option.label, option.approxGb), modifier = Modifier.heightIn(min = 48.dp))
                Text(stringResource(R.string.ai_step2), style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { open(option.modelUrl) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ai_download_model))
                }
                OutlinedButton(onClick = { open(option.mmprojUrl) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ai_download_vision))
                }
                Text(stringResource(R.string.ai_step3), style = MaterialTheme.typography.titleSmall)
                Button(
                    enabled = copying == null,
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.ai_load_files)) }
                Text(stringResource(R.string.ai_download_note), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
            }
            copying?.let { p ->
                Text(stringResource(R.string.ai_copying))
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    if (askRemove) {
        ConfirmDialog(
            title = stringResource(R.string.ai_remove),
            text = stringResource(R.string.ai_remove_text),
            confirmLabel = stringResource(R.string.ai_remove),
            onConfirm = {
                askRemove = false
                store.remove()
                installed = false
                name = null
                c.log.event("AI_MODEL_REMOVED")
            },
            onDismiss = { askRemove = false },
        )
    }
}
