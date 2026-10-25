@file:OptIn(ExperimentalMaterial3Api::class)

package com.kitchenreceipts.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.supplierCategoryHint
import com.kitchenreceipts.app.ui.supplierCategoryIcon
import com.kitchenreceipts.app.ui.supplierCategoryLabel
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.app.ui.theme.pressScale
import com.kitchenreceipts.core.SupplierCategory

/**
 * What a supplier mostly sells, as big tiles in two columns: a symbol, the name and a few example words each. A tap
 * calls [onPick]. [suggested] (from the invoices) has an orange outline and [chosenBefore] (found by the name alone) a
 * caption: both still need the tap. "Decide later" is a separate full-width button, shown when [onDecideLater] is set
 * and marked when [decidedLater]. It does not scroll by itself, so it fits a dialog, a sheet or a screen.
 */
@Composable
fun CategoryTiles(
    selected: SupplierCategory?,
    onPick: (SupplierCategory) -> Unit,
    modifier: Modifier = Modifier,
    suggested: SupplierCategory? = null,
    chosenBefore: SupplierCategory? = null,
    onDecideLater: (() -> Unit)? = null,
    decidedLater: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SupplierCategory.entries.chunked(2).forEach { pair ->
                // Both tiles of a row as tall as the taller one.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { c ->
                        CategoryTile(
                            c, selected = c == selected, suggested = c == suggested, chosenBefore = c == chosenBefore,
                            onClick = { onPick(c) }, modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (onDecideLater != null) {
            OutlinedButton(
                onClick = onDecideLater,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { this.selected = decidedLater },
            ) {
                if (decidedLater) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.PhthaloBright, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.supcat_decide_later), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun CategoryTile(
    c: SupplierCategory,
    selected: Boolean,
    suggested: Boolean,
    chosenBefore: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    // Surface's own selectable keeps the ripple inside the rounded shape; the role makes it a radio button for TalkBack.
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp).pressScale(source).semantics { role = Role.RadioButton },
        interactionSource = source,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) Palette.Phthalo else Palette.Panel,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = when {
            selected -> BorderStroke(2.dp, Palette.PhthaloBright)
            suggested -> BorderStroke(2.dp, Palette.Orange)
            else -> BorderStroke(1.dp, Palette.Hairline)
        },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(supplierCategoryIcon(c), contentDescription = null, tint = Palette.PhthaloBright, modifier = Modifier.size(28.dp))
                Spacer(Modifier.weight(1f))
                if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.PhthaloBright, modifier = Modifier.size(24.dp))
            }
            // Long labels ("Manutenzione, ferramenta e attrezzature" with large text) break between syllables, not anywhere.
            Text(
                supplierCategoryLabel(c),
                style = MaterialTheme.typography.titleMedium.copy(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph),
                fontWeight = FontWeight.SemiBold,
            )
            Text(supplierCategoryHint(c), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            if (suggested) Text(stringResource(R.string.supcat_suggested), style = MaterialTheme.typography.labelLarge, color = Palette.Orange)
            if (chosenBefore) Text(stringResource(R.string.supcat_chosen_before), style = MaterialTheme.typography.labelLarge, color = Palette.PhthaloBright)
        }
    }
}

/**
 * The tiles in a sheet from the bottom of the screen: [title] (the supplier's name, a heading), the question, an
 * optional [note], then the tiles. A tap on a tile calls [onPick]; the caller closes the sheet. [extra] goes between
 * the question and the tiles (for a choice such as "Just this invoice"). [onForget] adds "Forget this supplier's
 * category". Cancel closes it without a change.
 */
@Composable
fun CategoryPickerSheet(
    title: String,
    selected: SupplierCategory?,
    onPick: (SupplierCategory) -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
    suggested: SupplierCategory? = null,
    chosenBefore: SupplierCategory? = null,
    onDecideLater: (() -> Unit)? = null,
    onForget: (() -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.supcat_question), style = MaterialTheme.typography.bodyLarge)
            note?.let { Annotation(it) }
            extra?.invoke(this)
            CategoryTiles(selected, onPick, Modifier.padding(top = 4.dp), suggested, chosenBefore, onDecideLater)
            if (onForget != null) {
                TextButton(onClick = onForget, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.supcat_forget))
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

/** A supplier's category as a chip that opens the choice ("Not sorted yet" when there is none). */
@Composable
fun CategoryPill(category: SupplierCategory?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = { Text(supplierCategoryLabel(category), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(supplierCategoryIcon(category), contentDescription = null, modifier = Modifier.size(20.dp)) },
        modifier = modifier.heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.small,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = Palette.Black,
            labelColor = if (category == null) Palette.Orange else Palette.PhthaloBright,
            leadingIconContentColor = if (category == null) Palette.Orange else Palette.PhthaloBright,
        ),
        border = BorderStroke(1.dp, if (category == null) Palette.OrangeDim else Palette.PhthaloBorder),
    )
}

/** A supplier's category as a small label under a row (not tappable). */
@Composable
fun CategoryTag(category: SupplierCategory, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(supplierCategoryIcon(category), contentDescription = null, tint = Palette.PhthaloBright, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            supplierCategoryLabel(category), style = MaterialTheme.typography.labelMedium, color = Palette.PhthaloBright,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
