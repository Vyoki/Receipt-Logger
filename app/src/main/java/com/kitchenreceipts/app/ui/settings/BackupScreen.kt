package com.kitchenreceipts.app.ui.settings

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.backup.BackupManager
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.ConfirmDialog
import com.kitchenreceipts.app.ui.components.Panel
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.Backup
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

/** Backup and restore: one file with everything, locked with a password; restore replaces what is on the phone. */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var last by remember { mutableLongStateOf(c.settings.lastBackupAt) }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Restore: file picked -> password asked -> checked -> confirmed.
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePassword by remember { mutableStateOf("") }
    var restoreError by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf<BackupManager.Inside?>(null) }

    val tooShort = stringResource(R.string.office_pw_short, Backup.MIN_PASSWORD)
    val noMatch = stringResource(R.string.office_pw_mismatch)
    val failed = stringResource(R.string.backup_failed)
    val busyMsg = stringResource(R.string.backup_busy)
    val wrongPw = stringResource(R.string.backup_wrong_password)
    val notBackup = stringResource(R.string.backup_not_a_backup)
    val damaged = stringResource(R.string.backup_damaged)
    val tooNew = stringResource(R.string.backup_too_new)
    val madeFmt = stringResource(R.string.backup_made)

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        val chars = password.toCharArray()
        scope.launch {
            try {
                val made = c.backup.create(uri, chars)
                password = ""; again = ""
                last = c.settings.lastBackupAt
                snackbar.showSnackbar(String.format(madeFmt, made.documents, Formatter.formatShortFileSize(context, made.bytes)))
            } catch (e: Exception) {
                c.log.error("backup", e)
                error = failed
            } finally {
                chars.fill(' ')
                busy = false
            }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restoreUri = uri; restorePassword = ""; restoreError = null }
    }

    fun startBackup() {
        error = when {
            password.length < Backup.MIN_PASSWORD -> tooShort
            password != again -> noMatch
            else -> null
        }
        if (error == null) save.launch("kitchen-receipts-backup-${LocalDate.now()}.${Backup.EXTENSION}")
    }

    fun checkRestore() {
        val uri = restoreUri ?: return
        busy = true
        restoreError = null
        val chars = restorePassword.toCharArray()
        scope.launch {
            try {
                prepared = c.backup.prepare(uri, chars)
                restoreUri = null
            } catch (e: Exception) {
                restoreError = when (e) {
                    is Backup.WrongPassword -> wrongPw
                    is Backup.NotABackup -> notBackup
                    is Backup.Damaged -> damaged
                    is BackupManager.TooNew -> tooNew
                    is BackupManager.Busy -> busyMsg
                    else -> failed
                }
                if (e !is Backup.WrongPassword) c.log.error("restorePrepare", e)
            } finally {
                chars.fill(' ')
                busy = false
            }
        }
    }

    AppScaffold(title = stringResource(R.string.backup_title), onBack = onBack, snackbarHostState = snackbar) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (last > 0) stringResource(R.string.backup_last, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last)))
                        else stringResource(R.string.backup_never),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(stringResource(R.string.backup_what), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            SectionTitle(stringResource(R.string.backup_make_title))
            val visual = if (show) VisualTransformation.None else PasswordVisualTransformation()
            OutlinedTextField(
                value = password, onValueChange = { password = it; error = null }, label = { Text(stringResource(R.string.backup_pw)) },
                singleLine = true, visualTransformation = visual,
                trailingIcon = {
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(if (show) R.string.office_pw_hide else R.string.office_pw_show))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = again, onValueChange = { again = it; error = null }, label = { Text(stringResource(R.string.backup_pw_again)) },
                singleLine = true, visualTransformation = visual,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.backup_pw_note), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
            BigButton(stringResource(if (busy) R.string.backup_working else R.string.backup_make), Icons.Filled.Save, onClick = ::startBackup, enabled = !busy)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            SectionTitle(stringResource(R.string.backup_restore_title))
            Text(stringResource(R.string.backup_restore_hint), style = MaterialTheme.typography.bodyMedium)
            BigButton(stringResource(R.string.backup_restore), Icons.Filled.Restore, primary = false, enabled = !busy, onClick = { pick.launch(arrayOf("*/*")) })
        }
    }

    if (restoreUri != null) {
        ConfirmDialog(
            title = stringResource(R.string.backup_restore_title),
            text = stringResource(R.string.backup_restore_pw),
            confirmLabel = stringResource(if (busy) R.string.backup_working else R.string.backup_check),
            onConfirm = { if (!busy) checkRestore() },
            onDismiss = { if (!busy) { restoreUri = null; c.backup.discard() } },
            body = {
                OutlinedTextField(
                    value = restorePassword, onValueChange = { restorePassword = it; restoreError = null },
                    label = { Text(stringResource(R.string.backup_pw)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                restoreError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    prepared?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.backup_confirm_title),
            text = stringResource(R.string.backup_confirm_text, p.created.replace('T', ' '), p.documents, p.business.ifBlank { "—" }),
            confirmLabel = stringResource(R.string.backup_confirm),
            onConfirm = {
                try {
                    c.backup.replaceAndRestart()
                } catch (e: Exception) {
                    prepared = null
                    error = if (e is BackupManager.Busy) busyMsg else failed
                }
            },
            onDismiss = { prepared = null; c.backup.discard() },
        )
    }
}
