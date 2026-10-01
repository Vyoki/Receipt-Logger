package com.kitchenreceipts.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.BakeryDining
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Egg
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.KebabDining
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material.icons.outlined.LunchDining
import androidx.compose.material.icons.outlined.SetMeal
import androidx.compose.material.icons.outlined.TakeoutDining
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.Category

/** One line-drawn symbol per category, so a category is recognised at a glance. */
fun categoryIcon(c: Category): ImageVector = when (c) {
    Category.FRUIT_VEG -> Icons.Outlined.Eco
    Category.MEAT -> Icons.Outlined.KebabDining
    Category.FISH -> Icons.Outlined.SetMeal
    Category.CURED_MEATS -> Icons.Outlined.LunchDining
    Category.DAIRY_EGGS -> Icons.Outlined.Egg
    Category.BAKERY -> Icons.Outlined.BakeryDining
    Category.DRY_GOODS -> Icons.Outlined.Grain
    Category.FROZEN -> Icons.Outlined.AcUnit
    Category.BEVERAGES -> Icons.Outlined.LocalDrink
    Category.CLEANING -> Icons.Outlined.CleaningServices
    Category.DISPOSABLES -> Icons.Outlined.TakeoutDining
    Category.OTHER -> Icons.Outlined.Category
}

/** The category's symbol, plain (in chips and lists). The label next to it says the name, so no description here. */
@Composable
fun CategoryIcon(c: Category, modifier: Modifier = Modifier, size: Dp = 18.dp, tint: Color = Palette.PhthaloBright) {
    Icon(categoryIcon(c), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** The symbol in a thin green ring: section headings and the product's category card. */
@Composable
fun CategoryBadge(c: Category, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(modifier.size(size).border(1.dp, Palette.PhthaloBorder, CircleShape), contentAlignment = Alignment.Center) {
        CategoryIcon(c, size = size * 0.55f)
    }
}
