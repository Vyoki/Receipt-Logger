package com.kitchenreceipts.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.vatBasisLabel
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.PriceChange

/** Price up = red, down = green: the colour is backed by the arrow and the sign, never alone. */
@Composable
fun priceChangeColor(c: PriceChange): Color = if (c.isIncrease) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)

@Composable
fun PriceChangeRow(c: PriceChange, showProduct: Boolean = true, onClick: (() -> Unit)? = null) {
    val color = priceChangeColor(c)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (c.isIncrease) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
            contentDescription = stringResource(if (c.isIncrease) R.string.price_up else R.string.price_down),
            tint = color,
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            if (showProduct) Text(c.productName, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(
                stringResource(
                    R.string.price_change_line,
                    ItalianNumbers.formatDecimal(c.oldPrice, minScale = 2), ItalianNumbers.formatDecimal(c.newPrice, minScale = 2), c.unit,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    if (c.sameSeller) R.string.price_change_since_same else R.string.price_change_since_other,
                    fmtDate(c.oldDate), c.oldSeller,
                ) + " · " + vatBasisLabel(c.vatBasis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            (if (c.percent.signum() > 0) "+" else "") + ItalianNumbers.formatDecimal(c.percent, minScale = 1, maxScale = 1) + "%",
            color = color,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
fun PriceChangesCard(changes: List<PriceChange>, modifier: Modifier = Modifier, onOpenProduct: ((Long) -> Unit)? = null) {
    if (changes.isEmpty()) return
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.price_changes_title, changes.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            changes.forEach { c -> PriceChangeRow(c, onClick = onOpenProduct?.let { f -> { f(c.productId) } }) }
        }
    }
}
