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
import androidx.compose.runtime.saveable.rememberSaveable
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

/** The ingredients as typed, kept across a screen rotation (Android rebuilds the screen). */
private val IngredientsSaver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<IngredientUi>, ArrayList<String?>>(
    save = { list -> list.map { arrayListOf(it.productId?.toString(), it.productName, it.name, it.quantity, it.unit, it.waste, it.manualPrice, it.manualUnit) } },
    restore = { rows ->
        mutableStateListOf<IngredientUi>().apply {
            rows.forEach { r -> add(IngredientUi(r[0]?.toLongOrNull(), r[1], r[2].orEmpty(), r[3].orEmpty(), r[4].orEmpty(), r[5].orEmpty(), r[6].orEmpty(), r[7].orEmpty())) }
        }
    },
)

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
    // Kept across a rotation: a dish half typed is not lost when the phone is turned.
    var loaded by rememberSaveable { mutableStateOf(recipeId == 0L) }
    var name by rememberSaveable { mutableStateOf("") }
    var course by rememberSaveable { mutableStateOf<String?>(null) }
    var price by rememberSaveable { mutableStateOf("") }
    var withVat by rememberSaveable { mutableStateOf(true) }
    var vat by rememberSaveable { mutableStateOf("10") }
    var portions by rememberSaveable { mutableStateOf("1") }
    val ingredients = rememberSaveable(saver = IngredientsSaver) { mutableStateListOf<IngredientUi>() }
    var picking by remember { mutableStateOf<IngredientUi?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var createdAt by rememberSaveable { mutableStateOf(0L) }
    var saving by remember { mutableStateOf(false) }
    /** Set by a save that was refused: the rows to fix are then shown in red. */
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var initial by rememberSaveable { mutableStateOf<String?>(null) }
    val nameNeeded = stringResource(R.string.recipe_name_needed)
    val quantityNeeded = stringResource(R.string.recipe_quantity_needed)
    val saveFailed = stringResource(R.string.recipe_save_failed)

    /** Everything typed, to tell whether leaving would lose something. */
    fun typed(): String = listOf(name, course, price, withVat, vat, portions).joinToString("|") + ingredients.joinToString("|") {
        listOf(it.productId, it.name, it.quantity, it.unit, it.waste, it.manualPrice, it.manualUnit).joinToString(",")
    }

    LaunchedEffect(recipeId) {
        if (loaded) { if (initial == null) initial = typed(); return@LaunchedEffect }
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
        initial = typed()
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
        if (saving) return
        if (name.isBlank()) { error = nameNeeded; return }
        // An ingredient without a quantity is never dropped quietly: the dish would cost less than it does.
        if (ingredients.any { dec(it.quantity)?.signum() != 1 }) { showErrors = true; error = quantityNeeded; return }
        val now = System.currentTimeMillis()
        val r = current()
        val draft = RecipeDraft(
            RecipeEntity(recipeId, name.trim(), course, r.salePriceCents, withVat, r.vatRatePercent, r.portions, if (createdAt > 0) createdAt else now, now),
            ingredients.map { i ->
                RecipeItemEntity(0, recipeId, 0, i.productId, (i.productName ?: i.name).trim(), dec(i.quantity)!!, i.unit, dec(i.waste) ?: BigDecimal.ZERO, dec(i.manualPrice), i.manualUnit.takeIf { dec(i.manualPrice) != null })
            },
        )
        saving = true
        scope.launch {
            try {
                c.food.save(draft)
                c.log.event("RECIPE_SAVED", "ingredients" to draft.items.size)
                initial = typed()
                onBack()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                c.log.error("recipeSave", e)
                error = saveFailed
            } finally {
                saving = false
            }
        }
    }

    // Leaving with something typed and not saved asks first.
    val leave: () -> Unit = { if (loaded && initial != null && typed() != initial) askLeave = true else onBack() }
    androidx.activity.compose.BackHandler(enabled = loaded && initial != null && typed() != initial) { askLeave = true }
    if (askLeave) {
        ConfirmDialog(
            title = stringResource(R.string.recipe_leave_title),
            text = stringResource(R.string.recipe_leave_text),
            confirmLabel = stringResource(R.string.discard),
            onConfirm = { askLeave = false; onBack() },
            onDismiss = { askLeave = false },
            dismissLabel = stringResource(R.string.keep_editing),
        )
    }

    AppScaffold(
        title = stringResource(R.string.recipe_title),
        onBack = leave,
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
                    quantityMissing = showErrors && dec(ing.quantity)?.signum() != 1,
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
            BigButton(stringResource(R.string.recipe_save), Icons.Filled.Save, enabled = !saving, onClick = ::save)
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
private fun IngredientEditor(ing: IngredientUi, line: IngredientCost?, quantityMissing: Boolean, onPick: () -> Unit, onRemove: () -> Unit) {
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
                    isError = quantityMissing,
                    supportingText = if (quantityMissing) { { Text(stringResource(R.string.recipe_quantity_missing)) } } else null,
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
            // Without a purchase price: the operator may type one. (No line at all while the quantity is missing.)
            if (p == null || p.manual) {
                if (p == null && line != null) Text(stringResource(R.string.recipe_manual_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Orange)
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
