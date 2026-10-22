package com.kitchenreceipts.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.ui.theme.Motion
import com.kitchenreceipts.app.ui.theme.Palette

/** One jump: what the chip says and the list position it goes to. */
data class JumpChip(val label: String, val index: Int)

/**
 * A row of big chips that jump to the parts of a long list: a tap calls [goTo] with the chip's index, and the screen
 * scrolls there. The chips wrap onto a second row rather than scroll sideways, and are hidden while the keyboard is
 * open, so they never take room from the field being typed in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JumpChips(chips: List<JumpChip>, goTo: (Int) -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = chips.isNotEmpty() && !WindowInsets.isImeVisible,
        modifier = modifier,
        enter = expandVertically(Motion.enter()) + fadeIn(Motion.enter()),
        exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips.forEach { chip ->
                AssistChip(
                    onClick = { goTo(chip.index) },
                    label = { Text(chip.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = AssistChipDefaults.assistChipColors(containerColor = Palette.Black, labelColor = Palette.PhthaloBright),
                    border = BorderStroke(1.dp, Palette.PhthaloBorder),
                )
            }
        }
    }
}
