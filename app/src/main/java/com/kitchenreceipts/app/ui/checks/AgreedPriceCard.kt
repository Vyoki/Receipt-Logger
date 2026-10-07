package com.kitchenreceipts.app.ui.checks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.PurchaseRow
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.components.Panel
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.app.ui.vatBasisLabel
import com.kitchenreceipts.core.DuplicateDetector
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.Units
import com.kitchenreceipts.core.VatBasis
import kotlinx.coroutines.launch

/** The price agreed with a supplier for this product: shown, set, removed. Purchases above it appear in Checks. */
@Composable
fun AgreedPriceCard(productId: Long, purchases: List<PurchaseRow>) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val prices by remember(productId) { c.checks.agreedPricesFor(productId) }.collectAsState(initial = emptyList())
    val allSellers by remember { c.repository.sellers() }.collectAsState(initial = emptyList())
    // The suppliers this product was bought from, most recent first.
    val boughtFrom = purchases.map { DuplicateDetector.normalizeSeller(it.sellerName) }.distinct()
    val sellers = boughtFrom.mapNotNull { n -> allSellers.firstOrNull { DuplicateDetector.normalizeSeller(it.name) == n } }
    val units = (purchases.mapNotNull { p -> Units.normalize(p.unit)?.let { u -> Units.dimension(u)?.let(Units::baseUnit) ?: u } } + listOf("kg", "l", "pz")).distinct().take(5)
    val usualBasis = purchases.groupingBy { it.vatBasis }.eachCount().filterKeys { it != VatBasis.UNKNOWN }.maxByOrNull { it.value }?.key ?: VatBasis.EXCLUSIVE

    var sellerId by remember(sellers.firstOrNull()?.id) { mutableStateOf(sellers.firstOrNull()?.id) }
    var unit by remember(units.first()) { mutableStateOf(units.first()) }
    var basis by remember(usualBasis) { mutableStateOf(usualBasis) }
    var priceText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val bad = stringResource(R.string.agreed_bad_price)

    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.agreed_card_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.agreed_card_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
            prices.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.agreed_row, price(a.price.price), a.price.unit, vatBasisLabel(a.price.vatBasis)) + " · " +
                            (a.sellerName ?: stringResource(R.string.agreed_any_supplier)),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { scope.launch { c.checks.deleteAgreedPrice(a.price.id) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.agreed_remove))
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sellers.forEach { s -> FilterChip(sellerId == s.id, { sellerId = s.id }, label = { Text(s.name, maxLines = 1) }) }
                FilterChip(sellerId == null, { sellerId = null }, label = { Text(stringResource(R.string.agreed_any_supplier)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = priceText, onValueChange = { priceText = it; error = null },
                    label = { Text(stringResource(R.string.agreed_price)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.agreed_per))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                units.forEach { u -> FilterChip(unit == u, { unit = u }, label = { Text(u) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(VatBasis.EXCLUSIVE, VatBasis.INCLUSIVE).forEach { b -> FilterChip(basis == b, { basis = b }, label = { Text(vatBasisLabel(b)) }) }
            }
            com.kitchenreceipts.app.ui.components.OutlinedButton(
                onClick = click@{
                    val p = ItalianNumbers.parse(priceText)
                    if (p == null || p.signum() <= 0) { error = bad; return@click }
                    scope.launch {
                        c.checks.setAgreedPrice(productId, sellerId, unit, p, basis)
                        c.log.event("AGREED_PRICE_SET", "product" to productId)
                        priceText = ""
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.agreed_set)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
