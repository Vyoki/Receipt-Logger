package com.kitchenreceipts.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.ui.theme.Palette

/*
 * The app's buttons, used instead of Material's own (same names and parameters, so screens just import these):
 * phthalo green with a lighter green border for the main action, black with the green border for the others.
 */

private val Padding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

/** Main action: phthalo green, lighter green border. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(1.5.dp, if (enabled) Palette.PhthaloBorder else Palette.Hairline),
    colors = ButtonDefaults.buttonColors(
        containerColor = Palette.Phthalo,
        contentColor = Palette.OnPhthalo,
        disabledContainerColor = Palette.Panel,
        disabledContentColor = Palette.TextDim,
    ),
    contentPadding = Padding,
    content = content,
)

/** Second-rank action: same green, a little quieter. */
@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(1.dp, if (enabled) Palette.PhthaloBorder else Palette.Hairline),
    colors = ButtonDefaults.buttonColors(
        containerColor = Palette.PhthaloPressed,
        contentColor = Palette.OnPhthalo,
        disabledContainerColor = Palette.Panel,
        disabledContentColor = Palette.TextDim,
    ),
    contentPadding = Padding,
    content = content,
)

/** Other actions: black with the green border and green text. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(1.dp, if (enabled) Palette.PhthaloBorder else Palette.Hairline),
    colors = ButtonDefaults.outlinedButtonColors(
        containerColor = Palette.Black,
        contentColor = Palette.PhthaloBright,
        disabledContentColor = Palette.TextDim,
    ),
    contentPadding = Padding,
    content = content,
)
