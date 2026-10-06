package com.kitchenreceipts.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kitchenreceipts.app.AppContainer
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.knowledge.KnowledgeStore
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.Panel
import com.kitchenreceipts.app.ui.components.SectionTitle
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Settings ▸ Network: how knowledge may come into the phone (nothing about the documents ever goes out). */
@Composable
fun NetworkSettingsSection(c: AppContainer) {
    val s = c.settings
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(s.networkMode) }
    var lookup by remember { mutableStateOf(s.onlineProductLookup) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val pack by c.knowledge.pack.collectAsStateWithLifecycle()

    val outcomeText = mapOf(
        KnowledgeStore.Outcome.UPDATED to stringResource(R.string.knowledge_updated),
        KnowledgeStore.Outcome.ALREADY_CURRENT to stringResource(R.string.knowledge_current),
        KnowledgeStore.Outcome.NOT_ALLOWED to stringResource(R.string.knowledge_offline),
        KnowledgeStore.Outcome.FAILED to stringResource(R.string.knowledge_failed),
    )
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = outcomeText[c.knowledge.openFile(uri)]
            busy = false
        }
    }

    SectionTitle(stringResource(R.string.network_title))
    Panel {
        Column(Modifier.padding(vertical = 4.dp)) {
            listOf(
                AppSettings.NetworkMode.OFFLINE to (R.string.network_offline to R.string.network_offline_hint),
                AppSettings.NetworkMode.HYBRID to (R.string.network_hybrid to R.string.network_hybrid_hint),
                AppSettings.NetworkMode.AUTOMATIC to (R.string.network_automatic to R.string.network_automatic_hint),
            ).forEach { (value, texts) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .selectable(selected = mode == value, role = Role.RadioButton, onClick = {
                            mode = value
                            s.networkMode = value
                            message = null
                            c.log.event("SETTINGS", "network" to value)
                        })
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == value, onClick = null)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(stringResource(texts.first), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(texts.second), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    Text(stringResource(R.string.network_never_out), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)

    // Knowledge pack.
    Text(
        stringResource(R.string.knowledge_status, pack.version.takeIf { it != "0000-00-00" } ?: "–", pack.suppliers.size) +
            (s.knowledgeCheckedAt.takeIf { it > 0 }?.let { " " + stringResource(R.string.knowledge_checked_at, DateFormat.getDateInstance().format(Date(it))) } ?: ""),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
    if (mode != AppSettings.NetworkMode.OFFLINE) {
        BigButton(stringResource(R.string.knowledge_download), Icons.Filled.CloudDownload, enabled = !busy, onClick = {
            scope.launch {
                busy = true
                message = outcomeText[c.knowledge.download()]
                busy = false
            }
        })
    }
    BigButton(stringResource(R.string.knowledge_open_file), Icons.Filled.FolderOpen, primary = false, enabled = !busy, onClick = {
        openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    })

    // Online product lookup: only when the app may connect, and off until the operator turns it on.
    if (mode != AppSettings.NetworkMode.OFFLINE) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected = lookup, role = Role.Switch, onClick = {
                lookup = !lookup; s.onlineProductLookup = lookup; c.log.event("SETTINGS", "productLookup" to lookup)
            }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.product_lookup), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.product_lookup_hint), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
            }
            Switch(checked = lookup, onCheckedChange = { lookup = it; s.onlineProductLookup = it; c.log.event("SETTINGS", "productLookup" to it) })
        }
    }
}

/** Settings ▸ Reading check: every saved document read again by this version and compared with what was saved. */
@Composable
fun ReadingCheckSection(c: AppContainer) {
    val scope = rememberCoroutineScope()
    val last by c.readingChecker.last.collectAsStateWithLifecycle()
    val running by c.readingChecker.running.collectAsStateWithLifecycle()
    var showWorse by remember { mutableStateOf(false) }

    SectionTitle(stringResource(R.string.reading_check_title))
    Text(stringResource(R.string.reading_check_hint), style = MaterialTheme.typography.bodySmall)
    val r = last
    if (r == null) {
        Text(stringResource(R.string.reading_check_never), style = MaterialTheme.typography.bodyMedium)
    } else {
        Panel {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.reading_check_summary, r.version, r.docs.size, r.percentRight),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.at)), style = MaterialTheme.typography.bodySmall)
                if (r.previousVersion != null) {
                    Text(
                        stringResource(R.string.reading_check_compared, r.previousVersion, r.better, r.worse.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (r.worse.isEmpty()) MaterialTheme.colorScheme.onSurface else com.kitchenreceipts.app.ui.theme.Palette.Orange,
                    )
                }
                if (r.worse.isNotEmpty()) {
                    Text(
                        stringResource(if (showWorse) R.string.reading_check_hide else R.string.reading_check_show),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.heightIn(min = 48.dp).padding(top = 12.dp)
                            .selectable(selected = showWorse, role = Role.Button, onClick = { showWorse = !showWorse }),
                    )
                    if (showWorse) r.worse.forEach { w ->
                        Text(w.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        w.now.differences.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
    if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
    BigButton(stringResource(R.string.reading_check_run), Icons.Filled.Refresh, primary = false, enabled = !running, onClick = {
        scope.launch { c.readingChecker.run() }
    })
}
