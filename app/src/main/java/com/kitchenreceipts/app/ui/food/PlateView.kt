package com.kitchenreceipts.app.ui.food

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.fmtDate
import com.kitchenreceipts.app.ui.fmtMoney
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.FoodCost
import com.kitchenreceipts.core.IngredientCost
import com.kitchenreceipts.core.ItalianNumbers
import com.kitchenreceipts.core.RecipeCost
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * What one plate earns, in the words an owner uses: the money the customer pays, split into VAT, ingredients, staff
 * and utilities, and what is left; then per kilo of the main ingredient, and what the last price changes did.
 */
@Composable
fun PlateSummary(c: RecipeCost, target: BigDecimal, overheadPercent: BigDecimal) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val split = FoodCost.split(c)
        when {
            // A dish with no ingredients yet earns nothing worth saying.
            c.recipe.ingredients.isEmpty() ->
                Text(stringResource(R.string.food_no_ingredients), style = MaterialTheme.typography.bodyMedium, color = Palette.Orange)
            split == null -> {
                Text(stringResource(R.string.food_cost_plate, euros(c.foodCost)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.food_type_price), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
            else -> {
                // Every amount to the cent, the parts adding up to what the customer pays.
                val earns = split.earnsCents
                Text(
                    stringResource(if (earns >= 0) R.string.food_earns_plate else R.string.food_loses_plate, fmtMoney(kotlin.math.abs(earns))),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    color = if (earns >= 0) Palette.Ok else Palette.Error,
                )
                Text(stringResource(R.string.food_split_title, fmtMoney(split.paysCents)), style = MaterialTheme.typography.bodyMedium)
                MoneyBar(split)
                Legend(Palette.HairlineStrong, stringResource(R.string.food_split_vat, fmtMoney(split.vatCents)))
                Legend(Palette.Orange, stringResource(R.string.food_split_ingredients, fmtMoney(split.foodCents)))
                if (split.overheadsCents > 0) Legend(Palette.OrangeDim, stringResource(R.string.food_split_overheads, fmtMoney(split.overheadsCents)))
                Legend(if (earns >= 0) Palette.Ok else Palette.Error, stringResource(R.string.food_split_earns, fmtMoney(earns)))
                if (overheadPercent.signum() == 0) Text(stringResource(R.string.food_before_overheads), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        }
        if (c.recipe.ingredients.isNotEmpty()) c.perKilo?.let { k ->
            val per = stringResource(if (k.unit == "kg") R.string.food_unit_kg else R.string.food_unit_l)
            Text(
                k.earns?.let { stringResource(R.string.food_per_kilo, per, k.ingredient, ItalianNumbers.formatDecimal(k.plates, maxScale = 1), euros(it)) }
                    ?: stringResource(R.string.food_per_kilo_plates, per, k.ingredient, ItalianNumbers.formatDecimal(k.plates, maxScale = 1)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        c.foodCostPercent?.takeIf { c.recipe.ingredients.isNotEmpty() }?.let { p ->
            Text(
                stringResource(R.string.food_percent_line, ItalianNumbers.formatDecimal(p, maxScale = 1), ItalianNumbers.formatDecimal(target)),
                style = MaterialTheme.typography.bodySmall, color = if (p > target) Palette.Orange else Palette.TextDim,
            )
        }
        c.changePerPortion?.takeIf { it.setScale(2, RoundingMode.HALF_UP).signum() != 0 }?.let { ch ->
            Text(
                stringResource(if (ch.signum() > 0) R.string.food_change_up else R.string.food_change_down, euros(ch.abs())),
                style = MaterialTheme.typography.bodySmall, color = if (ch.signum() > 0) Palette.Orange else Palette.Ok,
            )
        }
    }
}

/** The customer's money as one bar: VAT, ingredients, staff and utilities, what is left. */
@Composable
private fun MoneyBar(s: FoodCost.Split) {
    val parts = listOf(
        s.vatCents to Palette.HairlineStrong,
        s.foodCents to Palette.Orange,
        s.overheadsCents to Palette.OrangeDim,
        s.earnsCents to Palette.Ok,
    ).filter { it.first > 0 }
    if (parts.isEmpty()) return
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Palette.Panel)) {
        parts.forEach { (v, color) -> Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(color)) }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

/** A price per unit as printed on invoices: two decimals, or three when the third counts ("0,890 €"); a small price per
 * gram or millilitre keeps its digits ("0,0124 €"). */
fun unitPrice(v: BigDecimal): String {
    if (v.signum() > 0 && v < BigDecimal("0.10")) return ItalianNumbers.formatDecimal(significant(v, 3), minScale = 2, maxScale = 8) + " €"
    val three = v.setScale(3, RoundingMode.HALF_UP)
    return if (three.compareTo(three.setScale(2, RoundingMode.HALF_UP)) == 0) euros(v) else ItalianNumbers.formatDecimal(three, maxScale = 3) + " €"
}

/** "0,25 kg" as 250 g, "0,04 l" as 40 ml: kitchen quantities as the kitchen says them, never a positive amount as 0. */
fun kitchenQuantity(q: BigDecimal, unit: String): String {
    val small = q < BigDecimal.ONE && q.signum() > 0
    return when {
        unit == "kg" && small -> ItalianNumbers.formatDecimal(significant(q.movePointRight(3), 4), maxScale = 8) + " g"
        unit == "l" && small -> ItalianNumbers.formatDecimal(significant(q.movePointRight(3), 4), maxScale = 8) + " ml"
        else -> ItalianNumbers.formatDecimal(significant(q, 4), maxScale = 8) + " " + unit
    }
}

/** [v] to [digits] significant digits, written without an exponent. */
private fun significant(v: BigDecimal, digits: Int): BigDecimal {
    if (v.signum() == 0) return v
    val r = v.round(MathContext(digits, RoundingMode.HALF_UP))
    return if (r.scale() < 0) r.setScale(0) else r
}

/**
 * An ingredient's cost with its working: "250 g × 17,00 €/kg = 4,25 €", where the price comes from, and what the
 * last price change did to one plate.
 */
@Composable
fun IngredientMath(line: IngredientCost) {
    val p = line.price ?: return
    val bought = line.boughtPerPortion ?: return
    val cost = line.cost ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.recipe_line_math, kitchenQuantity(bought, p.unit), unitPrice(p.price), p.unit, euros(cost)),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (line.ingredient.wastePercent.signum() > 0) {
            Text(stringResource(R.string.recipe_waste_included, ItalianNumbers.formatDecimal(line.ingredient.wastePercent)), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        Text(
            if (p.manual) stringResource(R.string.recipe_price_typed)
            else listOfNotNull(p.sellerName, p.date?.let(::fmtDate)).joinToString(" · ") + if (p.vatUnknown) " · " + stringResource(R.string.recipe_vat_unknown) else "",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
        )
        val prev = p.previous
        val ch = line.changePerPortion
        if (prev != null && ch != null && prev.price.compareTo(p.price) != 0) {
            Text(
                stringResource(
                    if (ch.signum() > 0) R.string.recipe_change_up else R.string.recipe_change_down,
                    unitPrice(prev.price), prev.unit, fmtDate(prev.date), euros(ch.abs()),
                ),
                style = MaterialTheme.typography.bodySmall, color = if (ch.signum() > 0) Palette.Orange else Palette.Ok,
            )
        }
    }
}
