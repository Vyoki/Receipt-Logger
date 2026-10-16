package com.kitchenreceipts.app.ui.food

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.ui.res.pluralStringResource
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
    var plan by remember { mutableStateOf<com.kitchenreceipts.core.MenuPlan?>(null) }
    var removeMissing by remember { mutableStateOf(false) }
    var byCost by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val badFile = stringResource(R.string.food_import_bad)
    val failed = stringResource(R.string.food_import_failed)
    val done = stringResource(R.string.food_import_done)
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open")
                }
                require(bytes.size <= 30_000_000) { "Too large" }
                val menu = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.kitchenreceipts.core.MenuSheet.read(bytes) }
                removeMissing = false
                plan = c.food.planImport(menu)
            } catch (e: com.kitchenreceipts.core.MenuSheet.NotAMenu) {
                snackbar.showSnackbar(badFile)
            } catch (e: Exception) {
                c.log.error("menuImport", e)
                snackbar.showSnackbar(failed)
            }
        }
    }
    val targetValue = ItalianNumbers.parseTyped(target)?.takeIf { it.signum() > 0 } ?: FoodCost.DEFAULT_TARGET

    AppScaffold(title = stringResource(R.string.food_title), onBack = onBack, snackbarHostState = snackbar) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("intro") { Text(stringResource(R.string.food_intro), style = MaterialTheme.typography.bodySmall, color = Palette.Orange) }
            item("settings") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { v -> target = v; ItalianNumbers.parseTyped(v)?.takeIf { it.signum() > 0 }?.let { c.settings.foodCostTarget = it } },
                        label = { Text(stringResource(R.string.food_target)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = overhead,
                        onValueChange = { v ->
                            overhead = v
                            val p = ItalianNumbers.parseTyped(v)?.takeIf { it.signum() >= 0 } ?: BigDecimal.ZERO
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
            if (!list.isNullOrEmpty()) item("sort") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(!byCost, { byCost = false }, label = { Text(stringResource(R.string.food_sort_earns)) })
                    androidx.compose.material3.FilterChip(byCost, { byCost = true }, label = { Text(stringResource(R.string.food_sort_cost)) })
                }
            }
            // What a plate earns, most first (the owner's question); or the highest food cost first (the dishes to fix).
            val order = if (byCost) compareBy<RecipeCost> { it.recipe.ingredients.isEmpty() }.thenByDescending { it.foodCostPercent ?: BigDecimal.ZERO }
            else compareBy<RecipeCost> { it.recipe.ingredients.isEmpty() }.thenBy { FoodCost.split(it) == null }
                .thenByDescending { FoodCost.split(it)?.earnsCents ?: Long.MIN_VALUE } // the amount shown, to the cent
            items(list.orEmpty().sortedWith(order), key = { "d" + it.recipe.id }) { d ->
                Box(Modifier.animateItem(placementSpec = com.kitchenreceipts.app.ui.theme.Motion.place)) { DishRow(d, targetValue) { onOpenDish(d.recipe.id) } }
            }
            item("add") { BigButton(stringResource(R.string.food_add_dish), Icons.Filled.Add, primary = false, onClick = { onOpenDish(0L) }) }
            item("import") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BigButton(stringResource(R.string.food_import), Icons.Filled.UploadFile, primary = false, onClick = { pick.launch(arrayOf("*/*")) })
                    Text(stringResource(R.string.food_import_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
            }

            item("monT") { SectionTitle(stringResource(R.string.food_months)) }
            item("monH") { Text(stringResource(R.string.food_months_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Orange) }
            items(months, key = { "m" + it.month }) { m -> Box(Modifier.animateItem(placementSpec = com.kitchenreceipts.app.ui.theme.Motion.place)) { MonthRow(m, targetValue) { editMonth = m.month } } }
        }
    }

    plan?.let { p ->
        ConfirmDialog(
            title = pluralStringResource(R.plurals.food_import_title, p.dishes.size, p.dishes.size),
            text = stringResource(R.string.food_import_summary, p.added, p.updated, p.linked, p.typedOnly, p.noPrice),
            confirmLabel = stringResource(R.string.food_import_go),
            onConfirm = {
                plan = null
                scope.launch {
                    c.food.applyImport(p, removeMissing)
                    c.log.event("MENU_IMPORTED", "dishes" to p.dishes.size, "linked" to p.linked, "removed" to (if (removeMissing) p.notInFile.size else 0))
                    snackbar.showSnackbar(done)
                }
            },
            onDismiss = { plan = null },
            body = {
                // Values of dishes already in the app that the sheet changes, each one said before anything is written.
                if (p.changes.isNotEmpty()) {
                    Text(stringResource(R.string.food_import_changes), style = MaterialTheme.typography.labelLarge)
                    p.changes.take(12).forEach { ch ->
                        val what = stringResource(
                            when (ch.field) {
                                com.kitchenreceipts.core.DishField.PRICE -> R.string.recipe_price
                                com.kitchenreceipts.core.DishField.VAT_INCLUDED -> R.string.recipe_price_vat
                                com.kitchenreceipts.core.DishField.VAT_RATE -> R.string.recipe_vat_rate
                                com.kitchenreceipts.core.DishField.PORTIONS -> R.string.recipe_portions
                            },
                        )
                        val yes = stringResource(R.string.yes); val no = stringResource(R.string.no)
                        fun shown(v: String) = when (v) { "true" -> yes; "false" -> no; else -> v }
                        Text("${ch.dish} · $what: ${shown(ch.before)} → ${shown(ch.after)}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (p.changes.size > 12) Text("…", style = MaterialTheme.typography.bodySmall)
                }
                if (p.skipped > 0) Text(stringResource(R.string.food_import_skipped, p.skipped), color = Palette.Orange)
                if (p.withoutIngredients > 0) Text(stringResource(R.string.food_import_empty, p.withoutIngredients))
                if (p.notInFile.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.food_import_remove, p.notInFile.size), modifier = Modifier.weight(1f))
                        Switch(checked = removeMissing, onCheckedChange = { removeMissing = it })
                    }
                }
            },
        )
    }

    editMonth?.let { m ->
        val existing = revenue.firstOrNull { it.month == m.toString() }
        var amount by remember(m) { mutableStateOf(existing?.let { ItalianNumbers.centsToEditText(it.cents) } ?: "") }
        var withVat by remember(m) { mutableStateOf(existing?.includesVat ?: true) }
        // An empty box removes the month's revenue, said on the button; text that is not an amount saves nothing.
        val removing = amount.isBlank() && existing != null
        ConfirmDialog(
            title = stringResource(R.string.food_revenue_title, fmtMonth(m)),
            text = "",
            confirmLabel = stringResource(if (removing) R.string.food_revenue_remove else R.string.food_save),
            confirmEnabled = removing || ItalianNumbers.parseCents(amount)?.let { it > 0 } == true,
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
                if (d.recipe.ingredients.isEmpty()) {
                    Text(stringResource(R.string.food_no_ingredients), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
                } else {
                    Text(
                        listOfNotNull(
                            d.customerPays?.let { stringResource(R.string.food_dish_sells, euros(it)) },
                            stringResource(R.string.food_dish_ingredients, euros(d.foodCost)),
                            d.foodCostPercent?.let { stringResource(R.string.food_dish_percent, ItalianNumbers.formatDecimal(it, maxScale = 1)) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = d.foodCostPercent?.takeIf { it > target }?.let { Palette.Orange } ?: Palette.TextDim,
                    )
                    d.perKilo?.earns?.let { e ->
                        val per = stringResource(if (d.perKilo!!.unit == "kg") R.string.food_unit_kg else R.string.food_unit_l)
                        val loses = e.signum() < 0
                        Text(
                            stringResource(if (loses) R.string.food_dish_per_kilo_loses else R.string.food_dish_per_kilo, per, d.perKilo!!.ingredient, euros(e.abs())),
                            style = MaterialTheme.typography.bodySmall, color = if (loses) Palette.Error else Palette.TextDim,
                        )
                    }
                }
                val missing = d.ingredients.count { it.problem != null }
                if (missing > 0) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.food_incomplete, missing, missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (d.vatUncertain) Text(stringResource(R.string.food_vat_uncertain), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
            }
            // What one plate earns: the number the owner looks for.
            // To the cent as on the dish's own screen (the parts of the price add up there).
            FoodCost.split(d)?.earnsCents?.takeIf { d.recipe.ingredients.isNotEmpty() }?.let { e ->
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        (if (e < 0) "−" else "") + fmtMoney(kotlin.math.abs(e)),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        color = if (e < 0) Palette.Error else Palette.Ok,
                    )
                    Text(stringResource(R.string.food_dish_per_plate), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
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
