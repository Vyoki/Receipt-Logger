package com.kitchenreceipts.app.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.FoodRepository
import com.kitchenreceipts.app.data.RevenueEntity
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.appViewModel
import com.kitchenreceipts.app.ui.categoryLabel
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.ClickCard
import com.kitchenreceipts.app.ui.components.ConfirmDialog
import com.kitchenreceipts.app.ui.components.EmptyState
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.app.ui.fmtMoney
import com.kitchenreceipts.app.ui.fmtMonth
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.FoodCost
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.RecipeCost
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class FoodCostViewModel(food: FoodRepository, overhead: BigDecimal) : ViewModel() {
    val overhead = MutableStateFlow(overhead)
    val dishes: StateFlow<List<RecipeCost>?> = this.overhead.flatMapLatest { food.recipeCosts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val months: StateFlow<List<FoodCost.Month>> = food.months().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val revenue: StateFlow<List<RevenueEntity>> = food.revenue().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/** € with two decimals from a BigDecimal amount. */
fun euros(v: BigDecimal?): String = v?.let { fmtMoney(it.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()) } ?: "—"

@Composable
fun FoodCostScreen(onBack: () -> Unit, onOpenDish: (Long) -> Unit) {
    val c = appContainer()
    val vm = appViewModel { FoodCostViewModel(it.food, it.settings.overheadPercent) }
    val dishes by vm.dishes.collectAsStateWithLifecycle()
    val months by vm.months.collectAsStateWithLifecycle()
    val revenue by vm.revenue.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(ItalianNumbers.formatDecimal(c.settings.foodCostTarget)) }
    var overhead by remember { mutableStateOf(ItalianNumbers.formatDecimal(c.settings.overheadPercent)) }
    var editMonth by remember { mutableStateOf<YearMonth?>(null) }
    val targetValue = ItalianNumbers.parse(target)?.takeIf { it.signum() > 0 } ?: FoodCost.DEFAULT_TARGET

    AppScaffold(title = stringResource(R.string.food_title), onBack = onBack) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("intro") { Text(stringResource(R.string.food_intro), style = MaterialTheme.typography.bodySmall, color = Palette.Orange) }
            item("settings") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { v -> target = v; ItalianNumbers.parse(v)?.takeIf { it.signum() > 0 }?.let { c.settings.foodCostTarget = it } },
                        label = { Text(stringResource(R.string.food_target)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = overhead,
                        onValueChange = { v ->
                            overhead = v
                            val p = ItalianNumbers.parse(v)?.takeIf { it.signum() >= 0 } ?: BigDecimal.ZERO
                            c.settings.overheadPercent = p
                            vm.overhead.value = p
                        },
                        label = { Text(stringResource(R.string.food_overhead)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                }
            }
            item("ohHint") { Text(stringResource(R.string.food_overhead_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }

            item("dishT") { SectionTitle(stringResource(R.string.food_dishes)) }
            val list = dishes
            if (list != null && list.isEmpty()) item("noDish") { EmptyState(stringResource(R.string.food_no_dishes)) }
            // Highest food cost first: those are the dishes to look at.
            items(list.orEmpty().sortedByDescending { it.foodCostPercent ?: BigDecimal.ZERO }, key = { "d" + it.recipe.id }) { d ->
                DishRow(d, targetValue) { onOpenDish(d.recipe.id) }
            }
            item("add") { BigButton(stringResource(R.string.food_add_dish), Icons.Filled.Add, primary = false, onClick = { onOpenDish(0L) }) }

            item("monT") { SectionTitle(stringResource(R.string.food_months)) }
            item("monH") { Text(stringResource(R.string.food_months_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Orange) }
            items(months, key = { "m" + it.month }) { m -> MonthRow(m, targetValue) { editMonth = m.month } }
        }
    }

    editMonth?.let { m ->
        val existing = revenue.firstOrNull { it.month == m.toString() }
        var amount by remember(m) { mutableStateOf(existing?.let { ItalianNumbers.centsToEditText(it.cents) } ?: "") }
        var withVat by remember(m) { mutableStateOf(existing?.includesVat ?: true) }
        ConfirmDialog(
            title = stringResource(R.string.food_revenue_title, fmtMonth(m)),
            text = "",
            confirmLabel = stringResource(R.string.food_save),
            onConfirm = {
                editMonth = null
                scope.launch { c.food.setRevenue(m, ItalianNumbers.parseCents(amount), withVat, existing?.vatRate ?: BigDecimal.TEN) }
            },
            onDismiss = { editMonth = null },
            dismissLabel = stringResource(R.string.cancel),
            body = {
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it }, label = { Text(stringResource(R.string.food_revenue_amount)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.food_revenue_with_vat), modifier = Modifier.weight(1f))
                    Switch(checked = withVat, onCheckedChange = { withVat = it })
                }
            },
        )
    }
}

@Composable
private fun DishRow(d: RecipeCost, target: BigDecimal, onClick: () -> Unit) {
    ClickCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.recipe.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.food_cost_line, euros(d.foodCost), d.recipe.salePriceCents?.let { fmtMoney(it) } ?: "—"),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                )
                val missing = d.ingredients.count { it.problem != null }
                if (missing > 0) Text(stringResource(R.string.food_incomplete, missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            d.foodCostPercent?.let { p ->
                Text(
                    stringResource(R.string.food_percent, ItalianNumbers.formatDecimal(p, maxScale = 1)),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    color = if (p > target) Palette.Orange else Palette.Ok,
                )
            }
        }
    }
}

@Composable
private fun MonthRow(m: FoodCost.Month, target: BigDecimal, onClick: () -> Unit) {
    ClickCard(onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmtMonth(m.month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                m.percent?.let { p ->
                    Text(
                        stringResource(R.string.food_percent, ItalianNumbers.formatDecimal(p, maxScale = 1)),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        color = if (p > target) Palette.Orange else Palette.Ok,
                    )
                }
            }
            Text(stringResource(R.string.food_bought, fmtMoney(m.foodAndDrinkCents)), style = MaterialTheme.typography.bodyMedium)
            Text(
                m.revenueNetCents?.let { stringResource(R.string.food_revenue, fmtMoney(it)) } ?: stringResource(R.string.food_no_revenue),
                style = MaterialTheme.typography.bodyMedium,
                color = if (m.revenueNetCents == null) Palette.Orange else MaterialTheme.colorScheme.onSurface,
            )
            m.byCategory.entries.filter { it.value != 0L }.sortedByDescending { it.value }.forEach { (cat, cents) ->
                Row {
                    Text(categoryLabel(cat), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.weight(1f))
                    Text(fmtMoney(cents), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
            }
            if (m.uncertainCents > 0) Text(stringResource(R.string.food_uncertain, fmtMoney(m.uncertainCents)), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
        }
    }
}
