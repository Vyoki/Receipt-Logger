package com.kitchenreceipts.app.ui.settings

import com.kitchenreceipts.app.ui.components.Panel

import android.app.Activity
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import com.kitchenreceipts.app.ui.components.OutlinedButton
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.MainActivity
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.ConfirmDialog
import com.kitchenreceipts.app.ui.components.SectionTitle
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = appContainer()
    val s = c.settings
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var language by remember { mutableStateOf(s.language) }
    var ownName by remember { mutableStateOf(s.ownBusinessName) }
    var ownVat by remember { mutableStateOf(s.ownVatNumber) }
    var logEnabled by remember { mutableStateOf(s.logEnabled) }
    var logText by remember { mutableStateOf(s.logIncludeText) }
    var appLock by remember { mutableStateOf(s.appLock) }
    var secure by remember { mutableStateOf(s.blockScreenshots) }
    var autoSave by remember { mutableStateOf(s.autoSave) }
    var autoLink by remember { mutableStateOf(s.autoLinkProducts) }
    var priceAlerts by remember { mutableStateOf(s.priceAlerts) }
    var logSize by remember { mutableLongStateOf(c.log.sizeBytes()) }
    var askClear by remember { mutableStateOf(false) }
    var askClearProblems by remember { mutableStateOf(false) }
    var problemCount by remember { mutableStateOf(c.problems.count()) }
    val lockUnavailable = stringResource(R.string.app_lock_unavailable)
    val shareTitle = stringResource(R.string.share_log)

    AppScaffold(title = stringResource(R.string.settings), onBack = onBack, snackbarHostState = snackbar) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---------------------------------------------------------------- language
            SectionTitle(stringResource(R.string.language))
            Panel {
                Column(Modifier.padding(vertical = 4.dp)) {
                    listOf(
                        AppSettings.Language.SYSTEM to stringResource(R.string.language_system),
                        AppSettings.Language.ENGLISH to "English",
                        AppSettings.Language.ITALIAN to "Italiano",
                    ).forEach { (value, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(selected = language == value, role = Role.RadioButton, onClick = {
                                    if (language != value) {
                                        language = value
                                        s.language = value
                                        c.log.event("SETTINGS", "language" to value)
                                        (context as? Activity)?.recreate() // apply the new language now
                                    }
                                })
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = language == value, onClick = null)
                            Text(label, modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- own business
            SectionTitle(stringResource(R.string.your_business))
            Text(stringResource(R.string.your_business_hint), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
            OutlinedTextField(
                value = ownName,
                onValueChange = { ownName = it; s.ownBusinessName = it },
                label = { Text(stringResource(R.string.your_business_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ownVat,
                onValueChange = { v ->
                    ownVat = v.filter(Char::isDigit).take(11)
                    s.ownVatNumber = ownVat
                    // Your own number must never identify a supplier: forget it wherever it was learned.
                    if (ownVat.length == 11) scope.launch { c.repository.forgetVatNumber(ownVat) }
                },
                label = { Text(stringResource(R.string.your_vat_number)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            // ---------------------------------------------------------------- automation
            SectionTitle(stringResource(R.string.automation))
            SwitchRow(stringResource(R.string.auto_save), stringResource(R.string.auto_save_hint), autoSave) { on ->
                autoSave = on
                s.autoSave = on
                c.log.event("SETTINGS", "autoSave" to on)
            }
            SwitchRow(stringResource(R.string.auto_link), stringResource(R.string.auto_link_hint), autoLink) { on ->
                autoLink = on
                s.autoLinkProducts = on
                c.log.event("SETTINGS", "autoLink" to on)
            }
            SwitchRow(stringResource(R.string.price_alerts), stringResource(R.string.price_alerts_hint), priceAlerts) { on ->
                priceAlerts = on
                s.priceAlerts = on
                c.log.event("SETTINGS", "priceAlerts" to on)
            }

            // ---------------------------------------------------------------- what was learned
            var learned by remember { mutableStateOf(c.learning.stats()) }
            SectionTitle(stringResource(R.string.learned_title))
            Text(
                pluralStringResource(R.plurals.learned_stats, learned.first, learned.first, learned.second, learned.third) + " " +
                    stringResource(R.string.learned_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (learned.first > 0) {
                OutlinedButton(
                    onClick = { c.learning.clear(); learned = c.learning.stats(); c.log.event("SETTINGS", "learningCleared" to true) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.learned_clear)) }
            }

            // ---------------------------------------------------------------- AI reader
            AiSettingsSection(c)

            // ---------------------------------------------------------------- network (information comes in only)
            NetworkSettingsSection(c)

            // ---------------------------------------------------------------- privacy & security
            SectionTitle(stringResource(R.string.privacy_security))
            Panel {
                Text(
                    stringResource(R.string.privacy_statement),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SwitchRow(stringResource(R.string.app_lock), stringResource(R.string.app_lock_hint), appLock) { on ->
                if (on && !MainActivity.canUseAppLock(context)) {
                    scope.launch { snackbar.showSnackbar(lockUnavailable) }
                } else {
                    appLock = on
                    s.appLock = on
                    if (on) c.unlocked = true // don't lock the operator out right after enabling
                    c.log.event("SETTINGS", "appLock" to on)
                }
            }
            SwitchRow(stringResource(R.string.block_screenshots), stringResource(R.string.block_screenshots_hint), secure) { on ->
                secure = on
                s.blockScreenshots = on
                (context as? MainActivity)?.applySecureScreen()
                c.log.event("SETTINGS", "blockScreenshots" to on)
            }

            // ---------------------------------------------------------------- saved documents read again
            ReadingCheckSection(c)

            // ---------------------------------------------------------------- documents corrected by hand
            SectionTitle(stringResource(R.string.problems_title))
            Text(stringResource(R.string.problems_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.problems_count, problemCount), style = MaterialTheme.typography.bodyMedium)
            BigButton(stringResource(R.string.problems_send), Icons.Filled.Share, enabled = problemCount > 0, onClick = {
                c.log.event("PROBLEMS_SHARED", "documents" to problemCount)
                val uri = c.problems.exportForSharing(runCatching { c.log.text() }.getOrNull())
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Kitchen Receipts – documents corrected by hand")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, shareTitle))
            })
            BigButton(stringResource(R.string.problems_clear), Icons.Filled.Delete, primary = false, enabled = problemCount > 0, onClick = { askClearProblems = true })

            // ---------------------------------------------------------------- FUNCTION X (temporary, remove with functionx/)
            com.kitchenreceipts.app.functionx.FunctionXSection(c)

            // ---------------------------------------------------------------- operation log
            SectionTitle(stringResource(R.string.operation_log))
            Text(stringResource(R.string.operation_log_hint), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
            SwitchRow(stringResource(R.string.log_enabled), null, logEnabled) { on ->
                if (!on) c.log.event("SETTINGS", "log" to false)
                logEnabled = on
                s.logEnabled = on
                if (on) c.log.event("SETTINGS", "log" to true)
            }
            SwitchRow(stringResource(R.string.log_include_text), stringResource(R.string.log_include_text_hint), logText) { on ->
                logText = on
                s.logIncludeText = on
            }
            Text(
                stringResource(R.string.log_size, Formatter.formatShortFileSize(context, logSize)),
                style = MaterialTheme.typography.bodyMedium,
            )
            BigButton(stringResource(R.string.share_log), Icons.Filled.Share, onClick = {
                c.log.event("LOG_SHARED")
                val uri = c.log.exportForSharing()
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Kitchen Receipts log")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, shareTitle))
            })
            BigButton(stringResource(R.string.clear_log), Icons.Filled.Delete, primary = false, onClick = { askClear = true })
        }
    }

    if (askClearProblems) {
        ConfirmDialog(
            title = stringResource(R.string.problems_clear),
            text = stringResource(R.string.problems_clear_text),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { askClearProblems = false; c.problems.clear(); problemCount = 0 },
            onDismiss = { askClearProblems = false },
        )
    }

    if (askClear) {
        ConfirmDialog(
            title = stringResource(R.string.clear_log),
            text = stringResource(R.string.clear_log_text),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { askClear = false; c.log.clear(); logSize = 0 },
            onDismiss = { askClear = false },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected = checked, role = Role.Switch, onClick = { onChange(!checked) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
