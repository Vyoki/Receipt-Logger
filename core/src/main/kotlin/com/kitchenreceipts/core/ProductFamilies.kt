package com.kitchenreceipts.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalDate

/**
 * Product groups ("families"): different products that are the same kind of thing — tomato passata from
 * three brands, mozzarella from two suppliers — gathered under one generic Italian name so their prices can
 * be compared. Each product stays a product of its own (its purchases, averages and price changes are never
 * mixed with another's); the group only puts them side by side.
 *
 * The app only *suggests* a group, from an Italian dictionary of generic product names; the operator confirms.
 * No name is ever suggested for a product the dictionary does not recognise.
 */
object ProductFamilies {

    /**
     * Generic names, most specific first: "passata di pomodoro" before "pomodori", "tonno" before "olio"
     * (tonno sott'olio is tuna), vegetables before vinegar (cipolline al balsamico are onions).
     * Each alternative is a list of keywords that must all appear. A keyword is a word beginning
     * ("mozzarell" matches mozzarella/mozzarelle); keywords of 4 letters or less must be whole words;
     * a keyword with a space must appear as that phrase.
     */
    private val KINDS: List<Pair<String, List<List<String>>>> = listOf(
        // tomato products
        "Passata di pomodoro" to listOf(listOf("passata")),
        "Concentrato di pomodoro" to listOf(listOf("concentrat", "pomodor"), listOf("doppio concentrat"), listOf("triplo concentrat")),
        "Polpa di pomodoro" to listOf(listOf("polpa", "pomodor"), listOf("pomodor", "cubett"), listOf("pomodor", "triturat")),
        // "pelati" alone is tomatoes; "patate pelate" are potatoes.
        "Pomodori pelati" to listOf(listOf("pelat", "pomodor"), listOf("pelati")),
        "Salsa di pomodoro" to listOf(listOf("salsa", "pomodor"), listOf("sugo", "pomodor")),
        "Pomodorini" to listOf(listOf("pomodorin"), listOf("ciliegin"), listOf("datterin")),
        // dairy
        "Mozzarella di bufala" to listOf(listOf("mozzarell", "bufal"), listOf("bufal")),
        "Mozzarella" to listOf(listOf("mozzarell"), listOf("fiordilatte"), listOf("fior di latte")),
        "Burrata" to listOf(listOf("burrata")),
        "Stracciatella" to listOf(listOf("stracciatell")),
        "Ricotta" to listOf(listOf("ricotta")),
        "Mascarpone" to listOf(listOf("mascarpon")),
        "Parmigiano Reggiano" to listOf(listOf("parmigian")),
        "Grana Padano" to listOf(listOf("grana")),
        "Pecorino" to listOf(listOf("pecorin")),
        "Gorgonzola" to listOf(listOf("gorgonzol")),
        "Scamorza" to listOf(listOf("scamorz")),
        "Provola" to listOf(listOf("provol")),
        "Panna" to listOf(listOf("panna")),
        "Burro" to listOf(listOf("burro")),
        "Latte" to listOf(listOf("latte")),
        "Uova" to listOf(listOf("uova"), listOf("uovo")),
        // fish (before oil: tonno sott'olio is tuna)
        "Tonno" to listOf(listOf("tonno")),
        "Acciughe" to listOf(listOf("acciug"), listOf("alici")),
        "Salmone" to listOf(listOf("salmon")),
        "Gamberi" to listOf(listOf("gamber"), listOf("mazzancoll")),
        "Calamari" to listOf(listOf("calamar"), listOf("totan")),
        "Cozze" to listOf(listOf("cozze"), listOf("cozza")),
        "Vongole" to listOf(listOf("vongol")),
        "Baccalà" to listOf(listOf("baccal")),
        // cured meats
        "Prosciutto crudo" to listOf(listOf("prosciutt", "crudo"), listOf("crudo")),
        "Prosciutto cotto" to listOf(listOf("prosciutt", "cotto"), listOf("cotto")),
        "Mortadella" to listOf(listOf("mortadell")),
        "Salame" to listOf(listOf("salame"), listOf("salami")),
        "Guanciale" to listOf(listOf("guancial")),
        "Pancetta" to listOf(listOf("pancett")),
        "Bresaola" to listOf(listOf("bresaola")),
        "Speck" to listOf(listOf("speck")),
        // meat
        "Petto di pollo" to listOf(listOf("petto", "pollo")),
        "Pollo" to listOf(listOf("pollo"), listOf("polli")),
        "Carne macinata" to listOf(listOf("macinat")),
        "Salsiccia" to listOf(listOf("salsicc")),
        // pasta, flour, rice
        "Spaghetti" to listOf(listOf("spaghett")),
        "Penne" to listOf(listOf("penne")),
        "Rigatoni" to listOf(listOf("rigaton")),
        "Fusilli" to listOf(listOf("fusill")),
        "Linguine" to listOf(listOf("linguin")),
        "Tagliatelle" to listOf(listOf("tagliatell")),
        "Lasagne" to listOf(listOf("lasagn")),
        "Gnocchi" to listOf(listOf("gnocch")),
        "Semola" to listOf(listOf("semola")),
        "Farina" to listOf(listOf("farina")),
        "Riso" to listOf(listOf("riso")),
        "Pangrattato" to listOf(listOf("pangrattat"), listOf("pan grattato")),
        "Zucchero" to listOf(listOf("zucchero")),
        "Sale" to listOf(listOf("sale")),
        // vegetables and fruit (before vinegar)
        "Pomodori" to listOf(listOf("pomodor")),
        "Patate" to listOf(listOf("patat")),
        "Cipolle" to listOf(listOf("cipoll")),
        "Aglio" to listOf(listOf("aglio")),
        "Carote" to listOf(listOf("carot")),
        "Zucchine" to listOf(listOf("zucchin")),
        "Melanzane" to listOf(listOf("melanzan")),
        "Peperoni" to listOf(listOf("peperon")),
        "Insalata" to listOf(listOf("insalat"), listOf("lattug")),
        "Rucola" to listOf(listOf("rucola")),
        "Funghi" to listOf(listOf("funghi"), listOf("porcin"), listOf("champignon")),
        "Limoni" to listOf(listOf("limon")),
        "Basilico" to listOf(listOf("basilico")),
        "Olive" to listOf(listOf("olive")),
        "Capperi" to listOf(listOf("capperi")),
        // oil and vinegar
        "Olio extravergine di oliva" to listOf(listOf("extravergine"), listOf("evo"), listOf("olio", "extra")),
        "Olio di semi" to listOf(listOf("olio", "semi"), listOf("girasol"), listOf("olio", "arachid")),
        "Aceto balsamico" to listOf(listOf("balsamic")),
        "Aceto" to listOf(listOf("aceto")),
        // drinks
        "Acqua minerale" to listOf(listOf("acqua")),
        "Birra" to listOf(listOf("birra"), listOf("birre")),
        "Vino" to listOf(listOf("vino"), listOf("vini")),
        "Caffè" to listOf(listOf("caffe")),
        // cleaning and disposables
        "Detersivo lavastoviglie" to listOf(listOf("lavastoviglie")),
        "Detersivo piatti" to listOf(listOf("lavapiatti"), listOf("detersiv", "piatti")),
        "Sgrassatore" to listOf(listOf("sgrassator")),
        "Candeggina" to listOf(listOf("candeggin"), listOf("varechina")),
        "Detersivo" to listOf(listOf("detersiv")),
        "Tovaglioli" to listOf(listOf("tovagliol")),
        "Pellicola" to listOf(listOf("pellicol")),
        "Carta alluminio" to listOf(listOf("alluminio")),
        "Guanti" to listOf(listOf("guanti")),
    )

    /** The generic Italian name for a product, or null when the dictionary does not recognise it. */
    fun genericName(productName: String): String? {
        val text = normalize(productName)
        if (text.isEmpty()) return null
        val words = text.split(' ')
        return KINDS.firstOrNull { (_, alternatives) ->
            alternatives.any { all -> all.all { k -> matches(k, text, words) } }
        }?.first
    }

    /** Key for comparing group names: "Passata di Pomodoro" = "passata di pomodoro". */
    fun key(name: String): String = normalize(name)

    // ------------------------------------------------------------------ suggestions

    data class Member(val id: Long, val name: String, val familyId: Long?, val dismissed: Boolean)
    data class Family(val id: Long, val name: String)

    /** "Put these products in the group [name]" ([existingFamilyId] null: a new group). */
    data class Suggestion(val name: String, val existingFamilyId: Long?, val productIds: List<Long>)

    /**
     * Groups to propose: products without a group (and not refused by the operator) that share a generic name.
     * An existing group with that name takes a single product; a new group needs at least two.
     */
    fun suggestions(products: List<Member>, families: List<Family>): List<Suggestion> {
        val byKey = families.associateBy { key(it.name) }
        return products.asSequence()
            .filter { it.familyId == null && !it.dismissed }
            .mapNotNull { p -> genericName(p.name)?.let { it to p } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (name, members) ->
                val existing = byKey[key(name)]
                when {
                    existing != null -> Suggestion(existing.name, existing.id, members.map { it.id })
                    members.size >= 2 -> Suggestion(name, null, members.map { it.id })
                    else -> null
                }
            }
            .sortedWith(compareByDescending<Suggestion> { it.productIds.size }.thenBy { it.name })
    }

    // ------------------------------------------------------------------ pack sizes

    /** How much one pack holds, in kg or l. [count] > 1 for "6X400G" (six packs of 0,4 kg). */
    data class PackSize(val each: BigDecimal, val unit: String, val count: Int = 1) {
        val total: BigDecimal get() = each.multiply(BigDecimal(count))
    }

    private val SIZE = Regex("(?i)(?<![\\d.,])(\\d+(?:[.,]\\d+)?)\\s*(kg|gr|g|hg|lt|l|ml|cl)\\b\\.?")
    private val SIZE_BEFORE = Regex("(?i)\\b(kg|gr|hg|lt|ml|cl)\\.?\\s*(\\d+(?:[.,]\\d+)?)\\b")
    private val MULTI_BEFORE = Regex("(?i)\\b(\\d{1,3})\\s*[x×]\\s*(\\d+(?:[.,]\\d+)?)\\s*(kg|gr|g|hg|lt|l|ml|cl)\\b")
    private val MULTI_AFTER = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(kg|gr|g|hg|lt|l|ml|cl)\\.?\\s*[x×]\\s*(\\d{1,3})\\b")
    private val PACK_COUNT = Regex("(?i)(\\b(cf|conf|ct|crt|pz)\\.?\\s*(da\\s*)?\\d+\\b|\\(\\s*\\d+\\s*(pz|x)?\\s*\\))")

    /**
     * The pack size printed in a product name, or null when there is none or it is unclear (two different
     * sizes, or a count of packs next to a size that cannot be told apart). Never guessed.
     */
    fun packSize(name: String): PackSize? {
        MULTI_BEFORE.find(name)?.let { m -> return sized(m.groupValues[2], m.groupValues[3], m.groupValues[1].toInt()) }
        MULTI_AFTER.find(name)?.let { m -> return sized(m.groupValues[1], m.groupValues[2], m.groupValues[3].toInt()) }
        val found = SIZE.findAll(name).mapNotNull { sized(it.groupValues[1], it.groupValues[2], 1) }.toMutableList()
        SIZE_BEFORE.findAll(name).mapNotNullTo(found) { sized(it.groupValues[2], it.groupValues[1], 1) }
        val distinct = found.distinctBy { it.unit to it.each.stripTrailingZeros() }
        if (distinct.size != 1) return null
        // "CF 6 400G" or "400G (6)": how many packs the price is for is not clear.
        if (PACK_COUNT.containsMatchIn(name)) return null
        return distinct.single()
    }

    private fun sized(num: String, unitRaw: String, count: Int): PackSize? {
        if (count < 1) return null
        val n = ItalianNumbers.parse(num) ?: num.replace(',', '.').toBigDecimalOrNull() ?: return null
        if (n.signum() <= 0) return null
        val unit = Units.normalize(unitRaw) ?: return null
        val factor = Units.factorToBase(unit) ?: return null
        val dim = Units.dimension(unit) ?: return null
        return PackSize(n.multiply(factor).stripTrailingZeros(), Units.baseUnit(dim), count)
    }

    // ------------------------------------------------------------------ price comparison within a group

    data class Variant(val productId: Long, val name: String, val brand: String?)

    /** One line of the comparison: the latest purchase of one product from one supplier. */
    data class VariantPrice(
        val productId: Long,
        val productName: String,
        val brand: String?,
        val sellerName: String,
        val date: LocalDate?,
        val documentId: Long,
        /** What was paid per unit as bought (line total / quantity), and that unit. */
        val paid: BigDecimal,
        val paidUnit: String,
        /** Price per kg or l, when the size is known; null otherwise (then it is not compared). */
        val perBase: BigDecimal?,
        val baseUnit: String?,
        val vatBasis: VatBasis,
        /** The lowest price per kg/l among the lines with the same unit and VAT basis (and there are at least two). */
        val cheapest: Boolean = false,
    )

    private val MC = MathContext.DECIMAL64

    /**
     * The latest price of each product of a group from each supplier, per kg or per litre where the size is
     * known: the unit bought is kg/l (or g, ml...), a unit conversion the operator entered, or a pack size
     * printed in the name. A size printed as "6X400G" counts only when a carton/case was bought
     * (ct, cs: the whole 2,4 kg); bought by the piece it is unclear which is meant, so it is not compared.
     * Prices with and without VAT are never compared with each other.
     */
    fun compare(
        variants: List<Variant>,
        purchases: List<PricePoint>,
        conversions: Map<Long, List<UnitConversion>> = emptyMap(),
    ): List<VariantPrice> {
        val byId = variants.associateBy { it.productId }
        val latest = compareBy<PricePoint>({ it.date ?: LocalDate.MIN }, { it.documentId }, { it.lineItemId })
        val rows = purchases.filter { it.productId in byId }
            .groupBy { it.productId to DuplicateDetector.normalizeSeller(it.sellerName) }
            .mapNotNull { (_, list) -> list.filter { PriceWatch.unitCost(it) != null }.maxWithOrNull(latest) }
            .mapNotNull { p ->
                val v = byId.getValue(p.productId)
                val (unit, paid) = PriceWatch.unitCost(p) ?: return@mapNotNull null
                val base = perBase(unit, paid, v.name, conversions[p.productId].orEmpty())
                VariantPrice(
                    p.productId, v.name, v.brand, p.sellerName, p.date, p.documentId,
                    paid.setScale(4, RoundingMode.HALF_EVEN), unit,
                    base?.second?.setScale(4, RoundingMode.HALF_EVEN), base?.first, p.vatBasis,
                )
            }
        val groups = rows.filter { it.perBase != null && it.vatBasis != VatBasis.UNKNOWN }.groupBy { it.baseUnit to it.vatBasis }
        val cheapest = groups.values.filter { it.size >= 2 }.mapNotNull { g -> g.minByOrNull { it.perBase!! } }.toSet()
        return rows.map { if (it in cheapest) it.copy(cheapest = true) else it }
            .sortedWith(
                compareBy<VariantPrice>({ it.baseUnit == null }, { it.baseUnit }, { it.vatBasis }, { it.perBase })
                    .thenBy { it.productName }.thenBy { it.sellerName },
            )
    }

    /** (kg or l, price per it) for a price per [unit], or null when the size is not known. */
    private fun perBase(unit: String, price: BigDecimal, name: String, conversions: List<UnitConversion>): Pair<String, BigDecimal>? {
        if (Units.dimension(unit) != null) return unit to price // unitCost already gives kg / l
        conversions.firstOrNull { it.fromUnit == unit && Units.dimension(it.toUnit) != null }?.let { c ->
            val base = Units.baseUnit(Units.dimension(c.toUnit)!!)
            val amount = c.factor.multiply(Units.factorToBase(c.toUnit)!!)
            return base to price.divide(amount, MC)
        }
        val size = packSize(name) ?: return null
        val amount = when {
            size.count == 1 -> size.each
            unit == "ct" || unit == "cs" -> size.total
            else -> return null
        }
        return size.unit to price.divide(amount, MC)
    }

    // ------------------------------------------------------------------ helpers

    private fun matches(keyword: String, text: String, words: List<String>): Boolean {
        val parts = normalize(keyword).split(' ')
        fun word(k: String, w: String) = if (k.length <= 4) w == k else w.startsWith(k)
        if (parts.size == 1) return words.any { word(parts[0], it) }
        return (0..words.size - parts.size).any { i -> parts.indices.all { j -> word(parts[j], words[i + j]) } }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
