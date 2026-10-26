package com.kitchenreceipts.core

import java.security.MessageDigest

/**
 * What a supplier sells the restaurant, chosen by the operator once per supplier; null means "not sorted yet". [key]
 * is what is stored. It is separate from the product [Category].
 *
 * [goods]: the supplier's lines are kitchen goods. The others (electricity, fuel, repairs, other expenses) are kept,
 * read and totalled exactly as today; only where their products are shown changes.
 *
 * [COPERTI]: what the dining room's tables use for the guests (laundry, table linen, napkins, candles). Tracked as
 * products like goods (prices, usual orders), but never counted in food cost (see [FoodCost.months]).
 */
enum class SupplierCategory(val key: String, val goods: Boolean) {
    FOOD("food", true),
    DRINKS("drinks", true),
    SUPPLIES("supplies", true),
    COPERTI("coperti", true),
    UTILITIES("utilities", false),
    FUEL("fuel", false),
    EQUIPMENT("equipment", false),
    OTHER("other", false),
    ;

    companion object {
        fun fromKey(key: String?): SupplierCategory? = entries.firstOrNull { it.key == key }

        /** Keys of the categories whose lines are no kitchen goods. */
        val NOT_GOODS_KEYS: List<String> = entries.filter { !it.goods }.map { it.key }
    }
}

/**
 * A suggestion for a supplier's category from its invoices, by general signals only (the charges of an electricity or
 * gas bill, fuel in litres, the kind of products). Only shown as "Suggested": nothing is applied without a tap. It never
 * suggests repairs or other expenses.
 *
 * The phone remembers the operator's choice under [memoryKeys]: the VAT number, and the name only as an unreadable code.
 */
object SupplierCategories {

    /**
     * The signs found on a page or a supplier's pages. [utility] and [fuel] are bit sets (one bit per kind of sign, so
     * two pages with the same sign count it once); [kinds] counts product rows by the kind of product they name
     * (Category.OTHER for rows of no known kind).
     */
    data class Hints(val utility: Int = 0, val fuel: Int = 0, val kinds: Map<Category, Int> = emptyMap(), val coperti: Int = 0) {
        operator fun plus(o: Hints) = Hints(
            utility or o.utility, fuel or o.fuel,
            (kinds.keys + o.kinds.keys).associateWith { (kinds[it] ?: 0) + (o.kinds[it] ?: 0) },
            coperti or o.coperti,
        )
    }

    /** kWh / Smc / kvarh, POD / PDR, accisa, oneri di sistema, dispacciamento, quota fissa (with potenza on the page). */
    private val UTILITY_SIGNS = listOf(
        rx("(?i)\\b(kwh|smc|kvarh)\\b"), rx("(?i)\\b(pod|pdr)\\b"), rx("(?i)\\baccis[ae]\\b"),
        rx("(?i)\\boneri\\s+(generali\\s+)?di\\s+sistema\\b"), rx("(?i)\\bdispacciamento\\b"),
    )
    private val FIXED_FEE = rx("(?i)\\bquota\\s+fissa\\b")
    private val POWER = rx("(?i)\\bpotenza\\b")
    private val FUEL_WORD = rx("(?i)\\b(gasolio|benzina|gpl|metano|carburant[ei]|rifornimento)\\b")
    /** Litres next to a number ("40,00 LT", "LT 40", "12L"); never the L of "S.R.L.". */
    private val LITRES = rx("(?i)(\\d\\s*(lt|litri|l)(?![\\p{L}'])|(?<![\\p{L}'.])(lt|litri|l)\\.?\\s*\\d)")
    /** The code kind fuel is invoiced with: "( CARB )". */
    private val FUEL_CODE_KIND = rx("(?i)\\(\\s*carb\\s*\\)")
    /** A combined-nomenclature code for mineral oils (2710xxxx) or fuel gases (2711xxxx), in the code column. */
    private val FUEL_NOMENCLATURE = rx("^\\s*271[01]\\d{4}\\b")
    /** A product row: words and an amount at the end ("PANCETTA TESA 12,50 9,20 kg 10,00 115,00"). */
    private val PRODUCT_LINE = rx("\\p{L}{3}.*\\d,\\d{2}\\s*$")
    /** Rows of the foot, not items: a payment (the standard MPnn codes) or a VAT summary row. */
    private val FOOT_ROW = rx("(?i)^\\s*(MP\\d{2}\\b|[IDS]\\s*\\(\\s*esigibilit|esigibilit|riepiloghi)")
    /** A name printed letter by letter ("S A P O N E"): four or more single letters. */
    private val SPACED = rx("(?<![\\p{L}\\p{N}])\\p{L}(?: \\p{L}){3,}(?![\\p{L}\\p{N}])")
    /**
     * A laundry or a linen hire: "Lavanderia", "lavaggio tovaglie", "noleggio biancheria", "tovagliato". Not a cleaning
     * product for laundries ("detersivo per lavanderia").
     */
    private val COPERTI_SIGNS = listOf(
        rx("(?i)(?<!\\bper\\s)(?<!\\bda\\s)\\blavanderi[ae]\\b"),
        rx("(?i)\\b(lavaggio|lavaggi|noleggio|nolo)\\s+(di\\s+|della\\s+|delle\\s+)?(tovagli\\p{L}*|biancheri\\p{L}*|coprimacchi\\p{L}*|tovagliat\\p{L}*)"),
        rx("(?i)\\b(biancheria\\s+da\\s+tavola|tovagliato|coprimacchi\\p{L}*)\\b"),
    )
    private val FOOD = setOf(
        Category.FRUIT_VEG, Category.MEAT, Category.FISH, Category.CURED_MEATS, Category.DAIRY_EGGS, Category.BAKERY,
        Category.DRY_GOODS, Category.FROZEN,
    )

    /** The signs in some rows of text (one row: a product's words and numbers). */
    fun hints(rows: List<String>): Hints {
        var utility = 0
        for ((bit, sign) in UTILITY_SIGNS.withIndex()) if (rows.any { sign.containsMatchIn(it) }) utility = utility or (1 shl bit)
        if (rows.any { FIXED_FEE.containsMatchIn(it) } && rows.any { POWER.containsMatchIn(it) }) utility = utility or (1 shl UTILITY_SIGNS.size)
        var fuel = 0
        for (r in rows) {
            if (FUEL_CODE_KIND.containsMatchIn(r)) fuel = fuel or 1
            if (FUEL_NOMENCLATURE.containsMatchIn(r) && LITRES.containsMatchIn(r)) fuel = fuel or 2
            if (FUEL_WORD.containsMatchIn(r) && LITRES.containsMatchIn(r)) fuel = fuel or 4
        }
        val joined = rows.map { r -> SPACED.replace(r) { m -> m.value.replace(" ", "") } }
        val kinds = joined
            .filter { PRODUCT_LINE.containsMatchIn(it) && !FOOT_ROW.containsMatchIn(it) }
            .map { Categories.guess(it) }
            .groupingBy { it }.eachCount()
        var coperti = 0
        for ((bit, sign) in COPERTI_SIGNS.withIndex()) if (joined.any { sign.containsMatchIn(it) }) coperti = coperti or (1 shl bit)
        return Hints(utility, fuel, kinds, coperti)
    }

    /**
     * Utilities on two or more different signs; otherwise fuel (before the product guesser, which reads some fuel
     * abbreviations as food); otherwise a laundry or linen hire (before the guesser, which reads table linen as
     * disposables); otherwise the kind of 60% or more of the product rows; otherwise nothing.
     */
    fun suggest(h: Hints): SupplierCategory? {
        if (Integer.bitCount(h.utility) >= 2) return SupplierCategory.UTILITIES
        if (h.fuel != 0) return SupplierCategory.FUEL
        if (h.coperti != 0) return SupplierCategory.COPERTI
        // Every product row counts, the ones of no known kind too: a hardware supplier with two misread rows is no food one.
        val total = h.kinds.values.sum()
        if (h.kinds.filterKeys { it != Category.OTHER }.values.sum() < 2) return null
        fun atLeast60(set: Set<Category>) = h.kinds.filterKeys { it in set }.values.sum() * 100 >= 60 * total
        return when {
            atLeast60(setOf(Category.BEVERAGES)) -> SupplierCategory.DRINKS
            atLeast60(setOf(Category.CLEANING, Category.DISPOSABLES)) -> SupplierCategory.SUPPLIES
            atLeast60(FOOD) -> SupplierCategory.FOOD
            else -> null
        }
    }

    /** The suggestion for the pages of one supplier's invoices. */
    fun suggest(pages: List<InvoiceBundle.PageSignals>): SupplierCategory? = suggest(pages.fold(Hints()) { a, p -> a + p.hints })

    /** The suggestion from a document's lines as text. */
    fun suggestFromLines(lines: List<String>): SupplierCategory? = suggest(hints(lines))

    /**
     * Where the choice is remembered: "vat:<11 digits>" when the VAT number's check digit is right, then "name#" and the
     * SHA-256 of the normalised name (accents, case and legal form do not matter). No readable name is ever stored.
     */
    fun memoryKeys(vat: String?, name: String?): List<String> = buildList {
        vatKey(vat)?.let { add(it) }
        DuplicateDetector.normalizeSeller(name)?.let { add(NAME_PREFIX + sha256(it)) }
    }

    /** "vat:<11 digits>" for a valid Italian VAT number (an "IT" prefix and spaces allowed), else null. */
    fun vatKey(vat: String?): String? {
        val v = vat?.replace(rx("\\s+"), "")?.uppercase()?.removePrefix("IT") ?: return null
        return if (SellerProfiles.isValidPartitaIva(v)) VAT_PREFIX + v else null
    }

    const val VAT_PREFIX = "vat:"
    const val NAME_PREFIX = "name#"

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
