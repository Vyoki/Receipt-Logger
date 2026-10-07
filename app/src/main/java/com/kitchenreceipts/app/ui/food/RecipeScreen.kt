package com.kitchenreceipts.app.ui.food

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.data.RecipeDraft
import com.kitchenreceipts.app.data.RecipeEntity
import com.kitchenreceipts.app.data.RecipeItemEntity
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.components.AppScaffold
import com.kitchenreceipts.app.ui.components.BigButton
import com.kitchenreceipts.app.ui.components.ConfirmDialog
import com.kitchenreceipts.app.ui.components.KeyValue
import com.kitchenreceipts.app.ui.components.LoadingBox
import com.kitchenreceipts.app.ui.components.OutlinedButton
import com.kitchenreceipts.app.ui.components.Panel
import com.kitchenreceipts.app.ui.components.SectionTitle
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.review.ProductPickerSheet
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.CostProblem
import com.kitchenreceipts.core.FoodCost
import com.kitchenreceipts.core.IngredientCost
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.Recipe
import com.kitchenreceipts.core.RecipeIngredient
import com.kitchenreceipts.core.UnitConversion
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** One ingredient as it is being typed. */
private class IngredientUi(
    productId: Long?,
    productName: String?,
    name: String,
    quantity: String,
    unit: String,
    waste: String,
    manualPrice: String,
    manualUnit: String,
) {
    var productId by mutableStateOf(productId)
    var productName by mutableStateOf(productName)
    var name by mutableStateOf(name)
    var quantity by mutableStateOf(quantity)
    var unit by mutableStateOf(unit)
    var waste by mutableStateOf(waste)
    var manualPrice by mutableStateOf(manualPrice)
    var manualUnit by mutableStateOf(manualUnit)
}

private val UNITS = listOf("g", "kg", "ml", "l", "pz")
private val COURSES = listOf(
    "antipasti" to R.string.recipe_cat_starters, "primi" to R.string.recipe_cat_first, "secondi" to R.string.recipe_cat_second,
    "contorni" to R.string.recipe_cat_sides, "dolci" to R.string.recipe_cat_desserts, "bevande" to R.string.recipe_cat_drinks,
    "altro" to R.string.recipe_cat_other,
)

/** Adds or edits a dish ([recipeId] 0 = new): its ingredients with quantity, waste and live cost. */
@Composable
fun RecipeScreen(recipeId: Long, onBack: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val products by remember { c.repository.products() }.collectAsState(initial = emptyList())
    val prices by remember { c.food.prices() }.collectAsState(initial = emptyMap())
    val conversionRows by remember { c.database.productDao().allConversions() }.collectAsState(initial = emptyList())
    var loaded by remember { mutableStateOf(recipeId == 0L) }
    var name by remember { mutableStateOf("") }
    var course by remember { mutableStateOf<String?>(null) }
    var price by remember { mutableStateOf("") }
    var withVat by remember { mutableStateOf(true) }
    var vat by remember { mutableStateOf("10") }
    var portions by remember { mutableStateOf("1") }
    val ingredients = remember { mutableStateListOf<IngredientUi>() }
    var picking by remember { mutableStateOf<IngredientUi?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var createdAt by remember { mutableStateOf(0L) }
    val nameNeeded = stringResource(R.string.recipe_name_needed)

    LaunchedEffect(recipeId) {
        if (recipeId == 0L) return@LaunchedEffect
        val d = c.food.draft(recipeId)
        if (d != null) {
            name = d.recipe.name
            course = d.recipe.category
            price = ItalianNumbers.centsToEditText(d.recipe.salePriceCents)
            withVat = d.recipe.priceIncludesVat
            vat = ItalianNumbers.toEditText(d.recipe.vatRate)
            portions = ItalianNumbers.toEditText(d.recipe.portions)
            createdAt = d.recipe.createdAt
            val byId = c.database.productDao().allOnce().associateBy { it.id }
            ingredients.clear()
            d.items.forEach { i ->
                ingredients += IngredientUi(
                    i.productId, i.productId?.let { byId[it]?.name }, i.name, ItalianNumbers.toEditText(i.quantity), i.unit,
                    ItalianNumbers.toEditText(i.wastePercent).takeIf { it != "0" } ?: "", ItalianNumbers.toEditText(i.manualPrice), i.manualUnit ?: "kg",
                )
            }
        }
        loaded = true
    }

    fun dec(s: String): BigDecimal? = ItalianNumbers.parse(s)

    /** The dish as typed now, for the live cost. Rows without a name or quantity are left out. */
    fun current(): Recipe = Recipe(
        recipeId, name, course, ItalianNumbers.parseCents(price), withVat, dec(vat) ?: BigDecimal.TEN,
        dec(portions)?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE,
        ingredients.mapNotNull { i ->
            val q = dec(i.quantity)?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            RecipeIngredient(i.productId, i.productName ?: i.name, q, i.unit, dec(i.waste) ?: BigDecimal.ZERO, dec(i.manualPrice), i.manualUnit)
        },
    )

    val conv = conversionRows.groupBy { it.productId }.mapValues { (_, l) -> l.mapNotNull { r -> runCatching { UnitConversion(r.fromUnit, r.toUnit, r.factor) }.getOrNull() } }
    val cost = FoodCost.cost(current(), { prices[it] }, { conv[it].orEmpty() }, c.settings.overheadPercent)

    fun save() {
        if (name.isBlank()) { error = nameNeeded; return }
        val now = System.currentTimeMillis()
        val r = current()
        val draft = RecipeDraft(
            RecipeEntity(recipeId, name.trim(), course, r.salePriceCents, withVat, r.vatRatePercent, r.portions, if (createdAt > 0) createdAt else now, now),
            ingredients.mapNotNull { i ->
                val q = dec(i.quantity)?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
                RecipeItemEntity(0, recipeId, 0, i.productId, (i.productName ?: i.name).trim(), q, i.unit, dec(i.waste) ?: BigDecimal.ZERO, dec(i.manualPrice), i.manualUnit.takeIf { dec(i.manualPrice) != null })
            },
        )
        scope.launch {
            c.food.save(draft)
            c.log.event("RECIPE_SAVED", "ingredients" to draft.items.size)
            onBack()
        }
    }

    AppScaffold(
        title = stringResource(R.string.recipe_title),
        onBack = onBack,
        actions = {
            if (recipeId != 0L) IconButton(onClick = { askDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.recipe_delete)) }
        },
    ) { padding ->
        if (!loaded) { LoadingBox(Modifier.padding(padding)); return@AppScaffold }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(value = name, onValueChange = { name = it; error = null }, label = { Text(stringResource(R.string.recipe_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.recipe_category), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                COURSES.forEach { (key, label) -> FilterChip(course == key, { course = if (course == key) null else key }, label = { Text(stringResource(label)) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = price, onValueChange = { price = it }, label = { Text(stringResource(R.string.recipe_price)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = vat, onValueChange = { vat = it }, label = { Text(stringResource(R.string.recipe_vat_rate)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(96.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recipe_price_vat), modifier = Modifier.weight(1f))
                Switch(checked = withVat, onCheckedChange = { withVat = it })
            }
            OutlinedTextField(
                value = portions, onValueChange = { portions = it }, label = { Text(stringResource(R.string.recipe_portions)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )

            // ---------------------------------------------------------------- the cost, live
            Panel {
                Column(Modifier.padding(16.dp)) {
                    KeyValue(stringResource(R.string.recipe_cost_portion), euros(cost.foodCost), emphasize = true)
                    if (c.settings.overheadPercent.signum() > 0) KeyValue(stringResource(R.string.recipe_full_cost), euros(cost.fullCost))
                    cost.netPrice?.let { KeyValue(stringResource(R.string.recipe_net_price), euros(it)) }
                    cost.foodCostPercent?.let { KeyValue(stringResource(R.string.food_title), ItalianNumbers.formatDecimal(it, maxScale = 1) + " %", emphasize = true) }
                    cost.margin?.let { KeyValue(stringResource(R.string.recipe_margin), euros(it)) }
                    val target = c.settings.foodCostTarget
                    FoodCost.priceForTarget(cost, target)?.let { KeyValue(stringResource(R.string.recipe_for_target, ItalianNumbers.formatDecimal(target)), euros(it)) }
                    val missing = cost.ingredients.count { it.problem != null }
                    if (missing > 0) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.food_incomplete, missing, missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            // ---------------------------------------------------------------- ingredients
            SectionTitle(stringResource(R.string.recipe_ingredients))
            ingredients.forEachIndexed { index, ing ->
                val line = cost.ingredients.firstOrNull { it.ingredient.name == (ing.productName ?: ing.name) && it.ingredient.productId == ing.productId }
                IngredientEditor(
                    ing = ing,
                    line = line,
                    onPick = { picking = ing },
                    onRemove = { ingredients.removeAt(index) },
                )
            }
            OutlinedButton(
                onClick = { ingredients += IngredientUi(null, null, "", "", "g", "", "", "kg").also { picking = it } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.recipe_add_ingredient), modifier = Modifier.padding(start = 8.dp))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            BigButton(stringResource(R.string.recipe_save), Icons.Filled.Save, onClick = ::save)
        }
    }

    picking?.let { ing ->
        ProductPickerSheet(
            description = ing.productName ?: ing.name,
            currentProductId = ing.productId,
            products = products,
            onPick = { p -> ing.productId = p.id; ing.productName = p.name; ing.name = p.name; picking = null },
            onCreate = { text -> ing.productId = null; ing.productName = null; ing.name = text; picking = null },
            onClear = { ing.productId = null; ing.productName = null; picking = null },
            onDismiss = { if (ing.name.isBlank() && ing.productId == null) ingredients.remove(ing); picking = null },
            createLabel = R.string.recipe_free_ingredient,
        )
    }

    if (askDelete) {
        ConfirmDialog(
            title = stringResource(R.string.recipe_delete),
            text = stringResource(R.string.recipe_delete_text),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { askDelete = false; scope.launch { c.food.delete(recipeId); onBack() } },
            onDismiss = { askDelete = false },
        )
    }
}

@Composable
private fun IngredientEditor(ing: IngredientUi, line: IngredientCost?, onPick: () -> Unit, onRemove: () -> Unit) {
    Panel {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPick, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(ing.productName ?: ing.name.ifBlank { stringResource(R.string.recipe_pick_product) }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                }
                line?.cost?.let { Text(euros(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.recipe_remove_ingredient)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ing.quantity, onValueChange = { ing.quantity = it }, label = { Text(stringResource(R.string.recipe_quantity)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = ing.waste, onValueChange = { ing.waste = it }, label = { Text(stringResource(R.string.recipe_waste)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(110.dp),
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UNITS.forEach { u -> FilterChip(ing.unit == u, { ing.unit = u }, label = { Text(u) }) }
            }
            val p = line?.price
            when {
                p != null && !p.manual -> Text(
                    stringResource(R.string.recipe_price_from, euros(p.price), p.unit, listOfNotNull(p.sellerName, p.date?.let(::fmtDate)).joinToString(" ")) +
                        if (p.vatUnknown) " · " + stringResource(R.string.recipe_vat_unknown) else "",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                )
                line?.problem == CostProblem.UNITS_DONT_CONVERT && p != null ->
                    Text(stringResource(R.string.recipe_units_dont_convert, p.unit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            // Without a purchase price: the operator may type one.
            if (p == null || p.manual) {
                if (p == null) Text(stringResource(R.string.recipe_manual_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ing.manualPrice, onValueChange = { ing.manualPrice = it }, label = { Text(stringResource(R.string.recipe_manual_price)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                    listOf("kg", "l", "pz").forEach { u -> FilterChip(ing.manualUnit == u, { ing.manualUnit = u }, label = { Text(u) }) }
                }
            }
        }
    }
}
