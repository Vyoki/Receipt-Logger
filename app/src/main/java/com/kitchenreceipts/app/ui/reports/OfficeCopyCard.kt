package com.kitchenreceipts.app.ui.reports

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.kitchenreceipts.app.reports.OfficeCopy
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.core.OfficeExport
import kotlinx.coroutines.launch

/**
 * "Office copy for a computer": every document, product, price and lot in one file the owner opens on a Mac or PC,
 * locked with a password chosen here. The password is never stored.
 */
@Composable
fun OfficeCopyCard() {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val tooShort = stringResource(R.string.office_pw_short, OfficeExport.MIN_PASSWORD)
    val noMatch = stringResource(R.string.office_pw_mismatch)
    val failed = stringResource(R.string.office_failed)
    val noApp = stringResource(R.string.no_viewer_app)

    fun make() {
        if (busy) return
        error = when {
            password.length < OfficeExport.MIN_PASSWORD -> tooShort
            password != again -> noMatch
            else -> null
        }
        if (error != null) return
        busy = true
        val chars = password.toCharArray()
        scope.launch {
            try {
                val r = OfficeCopy.build(context.applicationContext, c.repository, c.settings.ownBusinessName, chars)
                c.log.event("OFFICE_COPY", "docs" to r.documents, "bytes" to r.file.length())
                password = ""
                again = ""
                try {
                    context.startActivity(OfficeCopy.shareIntent(context, r))
                } catch (_: ActivityNotFoundException) {
                    error = noApp
                }
            } catch (e: Exception) {
                c.log.error("officeCopy", e)
                error = failed
            } finally {
                chars.fill(' ')
                busy = false
            }
        }
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.office_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.office_hint), style = MaterialTheme.typography.bodyMedium)
            val visual = if (show) VisualTransformation.None else PasswordVisualTransformation()
            val eye = @Composable {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(if (show) R.string.office_pw_hide else R.string.office_pw_show))
                }
            }
            OutlinedTextField(
                value = password, onValueChange = { password = it; error = null }, label = { Text(stringResource(R.string.office_pw)) },
                singleLine = true, visualTransformation = visual, trailingIcon = eye,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = again, onValueChange = { again = it; error = null }, label = { Text(stringResource(R.string.office_pw_again)) },
                singleLine = true, visualTransformation = visual,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.office_pw_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BigButton(
                text = stringResource(if (busy) R.string.office_building else R.string.office_make),
                icon = Icons.Filled.Computer,
                onClick = ::make,
                enabled = !busy,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
