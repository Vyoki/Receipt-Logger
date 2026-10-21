package com.kitchenreceipts.core

/**
 * What a supplier sells the restaurant, chosen by the operator once per supplier. [key] is what is stored.
 *
 * [goods]: the supplier's lines are kitchen goods (products, prices to watch, lots). The others (electricity, fuel,
 * equipment, services) are expenses: their invoices are kept and totalled, but their lines never become products,
 * price alerts or lines to link. [food]: counted as food in food cost (only once the owner turns that on).
 */
enum class SupplierCategory(val key: String, val goods: Boolean, val food: Boolean) {
    FOOD("food", true, true),
    DRINKS("drinks", true, true),
    SUPPLIES("supplies", true, true),
    UTILITIES("utilities", false, false),
    FUEL("fuel", false, false),
    EQUIPMENT("equipment", false, false),
    OTHER("other", false, false),
    ;

    companion object {
        fun fromKey(key: String?): SupplierCategory? = entries.firstOrNull { it.key == key }

        /** Keys of the categories whose lines are no kitchen goods. */
        val NOT_GOODS_KEYS: List<String> = entries.filter { !it.goods }.map { it.key }
    }
}

/**
 * A suggestion for a supplier's category from its invoices' lines, by general signals only (fuel in litres, the
 * charges of an electricity or gas bill, the kind of products). Only shown as "Suggested": the operator always
 * chooses, and nothing is applied without a tap.
 */
object SupplierCategories {

    private val FUEL_WORD = rx("(?i)\\b(gasolio|benzina|gpl|metano|carburante|carburanti|adblue|diesel|rifornimento)\\b")
    private val LITRES = rx("(?i)(\\blt\\b|\\blitri\\b|\\bl\\b)")
    private val FUEL_CODE = rx("(?i)(\\(\\s*carb\\s*\\)|^\\s*271[01]\\d{4}\\b)")
    private val UTILITY_SIGNS = listOf(
        rx("(?i)\\bpod\\b"), rx("(?i)\\bpdr\\b"), rx("(?i)\\bkwh\\b"), rx("(?i)\\bsmc\\b"), rx("(?i)oneri\\s+(di\\s+sistema|generali)"),
        rx("(?i)\\baccisa\\b"), rx("(?i)dispacciamento"), rx("(?i)quota\\s+fissa"), rx("(?i)quota\\s+potenza"),
    )
    /** A product line: words and an amount at the end ("PANCETTA TESA 12,50 9,20 kg 10,00 115,00"). */
    private val PRODUCT_LINE = rx("\\p{L}{3}.*\\d,\\d{2}\\s*$")
    private val FOOD = setOf(
        Category.FRUIT_VEG, Category.MEAT, Category.FISH, Category.CURED_MEATS, Category.DAIRY_EGGS, Category.BAKERY,
        Category.DRY_GOODS, Category.FROZEN,
    )

    fun suggest(lines: List<String>): SupplierCategory? {
        if (lines.any { FUEL_CODE.containsMatchIn(it) || (FUEL_WORD.containsMatchIn(it) && LITRES.containsMatchIn(it)) }) return SupplierCategory.FUEL
        val text = lines.joinToString("\n")
        if (UTILITY_SIGNS.count { it.containsMatchIn(text) } >= 2) return SupplierCategory.UTILITIES
        val kinds = lines.filter { PRODUCT_LINE.containsMatchIn(it) }.map { Categories.guess(it) }.filter { it != Category.OTHER }
        if (kinds.size < 2) return null
        fun share(set: Set<Category>) = kinds.count { it in set } * 100 / kinds.size
        return when {
            share(setOf(Category.BEVERAGES)) >= 60 -> SupplierCategory.DRINKS
            share(setOf(Category.CLEANING, Category.DISPOSABLES)) >= 60 -> SupplierCategory.SUPPLIES
            share(FOOD) >= 60 -> SupplierCategory.FOOD
            else -> null
        }
    }
}
