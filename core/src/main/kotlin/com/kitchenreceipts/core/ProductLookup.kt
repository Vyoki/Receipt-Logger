package com.kitchenreceipts.core

/**
 * What may be searched online about a product, and how an answer becomes suggestions. Only product words or a
 * barcode ever go out: no amounts, prices, dates, document numbers, VAT numbers or company names. Answers only
 * suggest product fields (name, brand, pack size, category) for the operator to accept; document values never change.
 */
object ProductLookup {

    /** One answer from a public product database. */
    data class Suggestion(val name: String, val brand: String?, val packSize: String?, val category: Category?, val barcode: String?)

    /** A barcode worth looking up: EAN-13 / EAN-8 / UPC-A with a valid check digit. */
    fun barcode(code: String?): String? {
        val d = code?.filter { !it.isWhitespace() } ?: return null
        if (!d.all(Char::isDigit) || d.length !in setOf(8, 12, 13)) return null
        val digits = d.map { it - '0' }
        val body = digits.dropLast(1).reversed()
        val sum = body.withIndex().sumOf { (i, v) -> if (i % 2 == 0) v * 3 else v }
        return d.takeIf { (10 - sum % 10) % 10 == digits.last() }
    }

    private val COMPANY = ReceiptParser.COMPANY_SUFFIX
    private val NOT_A_WORD = Regex("(?i)^(?:[0-9.,x×*/%+-]+|\\d+[a-z]{1,3}|kg|gr?|lt?|ml|cl|pz|nr|conf|cf|ct|crt|bt|kgm|pce|pcs|x\\d+)$")

    /**
     * The words searched for a product description: letters only, no quantities, codes, sizes or company forms,
     * at most six words. Null when nothing searchable is left (then nothing is sent).
     */
    fun query(description: String?): String? {
        if (description.isNullOrBlank()) return null
        // A company name is never sent: with a legal form ("ABC S.r.l."), only the words before the last number in
        // front of it are kept (the product, before its size); without such a number nothing is searched.
        val text = COMPANY.find(description)?.let { m ->
            val before = description.substring(0, m.range.first)
            val lastNumber = Regex("\\S*\\d\\S*").findAll(before).lastOrNull() ?: return null
            before.substring(0, lastNumber.range.first)
        } ?: description
        val words = text.split(Regex("[\\s,;:()\\[\\]\"'/]+"))
            .map { it.trim('.', '-', '*') }
            .filter { w -> w.length >= 2 && w.any(Char::isLetter) && !NOT_A_WORD.matches(w) && w.count(Char::isDigit) * 2 < w.length }
            .take(6)
        return words.takeIf { it.isNotEmpty() }?.joinToString(" ")?.lowercase()
    }

    /** The app's category for a product database's category tags ("en:dairies", "en:frozen-foods", …); null if unclear. */
    fun category(tags: List<String>): Category? {
        val t = tags.map { it.substringAfter(':').lowercase() }
        fun any(vararg keys: String) = t.any { tag -> keys.any { tag.contains(it) } }
        return when {
            any("frozen") -> Category.FROZEN
            any("hams", "salami", "sausages", "prepared-meats", "cured") -> Category.CURED_MEATS
            any("fishes", "seafood", "fish") -> Category.FISH
            any("meats", "poultry", "beef", "pork") -> Category.MEAT
            any("dairies", "cheeses", "milks", "yogurts", "eggs", "butters", "creams") -> Category.DAIRY_EGGS
            any("breads", "bakery", "pastries", "biscuits") -> Category.BAKERY
            any("beverages", "waters", "wines", "beers", "juices", "sodas", "coffees") -> Category.BEVERAGES
            any("fruits", "vegetables", "fresh-plant") -> Category.FRUIT_VEG
            any("pastas", "cereals", "flours", "rices", "legumes", "oils", "sauces", "canned", "condiments", "sugars", "spices") -> Category.DRY_GOODS
            any("cleaning", "detergents", "household") -> Category.CLEANING
            else -> null
        }
    }
}
