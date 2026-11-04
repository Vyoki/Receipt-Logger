package com.kitchenreceipts.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.ProductName

/**
 * A product name in a list, from its raw text as printed: the name, and its size on a smaller grey line under it
 * when there is one ("SALE MARINO GROSSO GR.1000" -> "SALE MARINO GROSSO" / "1000 g"). The article code is not
 * shown. Display only: edit fields, exports, matching and storage keep the raw text. [sizeFrom]: the text whose size
 * is shown (a linked product's name over its invoice line keeps the line's size); by default [raw] itself.
 */
@Composable
fun ProductNameText(
    raw: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    sizeFrom: String = raw,
) {
    val parts = remember(raw) { ProductName.split(raw) }
    val size = remember(raw, sizeFrom) { if (sizeFrom == raw) parts.size else ProductName.split(sizeFrom).size }
    Column(modifier) {
        Text(parts.name, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        size?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1) }
    }
}
