@file:OptIn(ExperimentalMaterial3Api::class)

package com.kitchenreceipts.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.theme.LocalStatusColors
import com.kitchenreceipts.core.DraftField

@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = actions,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

/** Full-width 64dp button: easy to hit with a wet or gloved finger. */
@Composable
fun BigButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val inner: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
    val m = modifier.fillMaxWidth().heightIn(min = 64.dp)
    if (primary) Button(onClick = onClick, modifier = m, enabled = enabled, content = inner)
    else FilledTonalButton(onClick = onClick, modifier = m, enabled = enabled, content = inner)
}

@Composable
fun ClickCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator()
    }
}

@Composable
fun WarningCard(lines: List<String>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    val status = LocalStatusColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = status.uncertainContainer, contentColor = status.onUncertain),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

enum class FieldKind { TEXT, DECIMAL, DATE, CODE }

/**
 * An editable extracted value.
 * - missing: amber background and "Missing" hint; never filled automatically;
 * - uncertain: orange border, shows what was read and where, plus a big ✓ to confirm as-is;
 * - error: validation message.
 */
@Composable
fun ReviewField(
    label: String,
    field: DraftField,
    onChange: (String) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    kind: FieldKind = FieldKind.TEXT,
    error: String? = null,
    placeholder: String? = null,
    missingHint: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** false for optional fields (expiry, VAT %) whose absence is normal. */
    highlightMissing: Boolean = true,
) {
    val status = LocalStatusColors.current
    // Where this value was read on the photo, shown while the field is being checked (review screen only).
    val peek = LocalFieldPeek.current
    val currentField = androidx.compose.runtime.rememberUpdatedState(field)
    val hadFocus = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val colors = when {
        error != null -> OutlinedTextFieldDefaults.colors()
        field.uncertain -> OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = status.uncertainBorder,
            focusedBorderColor = status.uncertainBorder,
            unfocusedContainerColor = status.uncertainContainer,
            focusedContainerColor = status.uncertainContainer,
        )
        field.isMissing && highlightMissing -> OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = status.missingContainer,
        )
        else -> OutlinedTextFieldDefaults.colors()
    }
    val keyboard = when (kind) {
        FieldKind.DECIMAL -> KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
        FieldKind.DATE -> KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
        FieldKind.CODE -> KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next, autoCorrectEnabled = false)
        FieldKind.TEXT -> KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next)
    }
    val supporting: String? = when {
        error != null -> error
        field.uncertain -> stringResource(R.string.field_uncertain, field.source?.take(60) ?: "")
        field.isMissing && highlightMissing -> missingHint ?: stringResource(R.string.field_missing)
        else -> null
    }
    OutlinedTextField(
        value = field.text,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = error != null,
        keyboardOptions = keyboard,
        colors = colors,
        supportingText = supporting?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        trailingIcon = when {
            field.uncertain -> {
                {
                    IconButton(onClick = onConfirm, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.confirm_value), tint = status.ok)
                    }
                }
            }
            trailing != null -> trailing
            else -> null
        },
        modifier = modifier.fillMaxWidth().let { m ->
            if (peek == null) {
                m
            } else {
                m.onFocusChanged { f ->
                    // Only this field gaining or losing focus counts (fields also report "not focused" when first shown).
                    if (f.isFocused) {
                        hadFocus.value = true
                        peek(PeekRequest(label, currentField.value.source, currentField.value.text))
                    } else if (hadFocus.value) {
                        hadFocus.value = false
                        peek(null)
                    }
                }
            }
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.cancel),
    body: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (text.isNotEmpty()) Text(text)
                body?.invoke()
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(dismissLabel) } },
    )
}

@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier, emphasize: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun MissingValue(label: String, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = status.missingContainer, contentColor = status.onMissing),
        border = BorderStroke(0.dp, status.missingContainer),
    ) { Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelLarge) }
}
