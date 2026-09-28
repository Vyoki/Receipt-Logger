package com.kitchenreceipts.core

import java.text.Normalizer

/** Inventory categories of a restaurant's purchases. [key] is what is stored in the database. */
enum class Category(val key: String) {
    FRUIT_VEG("fruit_veg"),
    MEAT("meat"),
    FISH("fish"),
    CURED_MEATS("cured_meats"),
    DAIRY_EGGS("dairy_eggs"),
    BAKERY("bakery"),
    DRY_GOODS("dry_goods"),
    FROZEN("frozen"),
    BEVERAGES("beverages"),
    CLEANING("cleaning"),
    DISPOSABLES("disposables"),
    OTHER("other"),
    ;

    companion object {
        fun fromKey(key: String?): Category? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Guesses the category of a product from its name with an Italian keyword dictionary.
 * The operator can always change it; a guess never changes a category the operator chose.
 *
 * Keywords are word beginnings ("mozzarell" matches mozzarella/mozzarelle). Keywords of 4 letters
 * or less must match the whole word (so "sale" does not match "salame").
 * Rules are checked in order: "pomodori pelati" is a tin (dry goods) before it is a vegetable,
 * "aceto di vino" is a condiment before it is a wine, anything "surgelato" is frozen.
 */
object Categories {

    private val RULES: List<Pair<Category, List<String>>> = listOf(
        Category.FROZEN to listOf("surgelat", "congelat", "surg", "cong", "gelato", "gelati", "ghiaccio"),
        Category.CLEANING to listOf(
            "detersiv", "sgrassator", "candeggin", "igienizz", "disinfett", "sapone", "brillantant", "anticalcar",
            "lavastoviglie", "lavapiatti", "spugn", "detergent", "ammorbident", "varechina", "sanificant",
        ),
        Category.DISPOSABLES to listOf(
            "carta", "tovagliol", "bicchier", "piatti", "posate", "forchett", "cucchiai", "coltell", "pellicol",
            "alluminio", "vaschett", "sacchett", "sacchi", "shopper", "guanti", "cannucc", "contenitor", "coperchi",
            "rotolo", "rotoli", "scatol", "busta", "buste", "stuzzicadent", "tovagli",
        ),
        Category.DRY_GOODS to listOf(
            "pelat", "passata", "polpa", "concentrat", "conserv", "sottolio", "sottaceto", "aceto", "balsamic",
            "olio", "extravergine", "evo",
        ),
        Category.BEVERAGES to listOf(
            "acqua", "vino", "vini", "birra", "birre", "bibit", "succo", "succhi", "aranciat", "limonat", "chinotto",
            "cola", "coca", "tonica", "spremut", "liquor", "grappa", "amaro", "prosecco", "spumant", "spritz",
            "aperol", "campari", "vodka", "whisky", "rum", "gin", "caffe", "caffè", "tè", "tisan", "bevand",
            "sciropp", "energy", "lambrusco", "chianti", "sangiovese", "montepulciano",
        ),
        Category.CURED_MEATS to listOf(
            "prosciutt", "salame", "salami", "mortadell", "bresaola", "speck", "pancett", "guancial", "coppa",
            "lonza", "culatell", "nduja", "wurstel", "salumi", "porchetta", "lardo",
        ),
        Category.DAIRY_EGGS to listOf(
            "latte", "mozzarell", "fiordilatte", "burrata", "stracciatell", "ricotta", "formagg", "parmigian",
            "grana", "pecorin", "gorgonzol", "mascarpon", "stracchin", "scamorz", "provol", "fontina", "asiago",
            "taleggi", "emmental", "caciocavall", "burro", "panna", "yogurt", "uova", "uovo", "caprin", "robiola",
            "crescenza", "squacquerone", "brie", "feta", "cheddar", "philadelphia", "besciamell",
        ),
        Category.FISH to listOf(
            "pesce", "salmon", "tonno", "merluzz", "baccal", "stoccafiss", "orata", "orate", "spigol", "branzin",
            "gamber", "scampi", "scampo", "calamar", "totan", "seppi", "polpo", "polpi", "cozze", "cozza", "vongol",
            "alici", "acciug", "sgombr", "pesce spada", "spada", "sarde", "sardin", "trota", "rombo", "sogliol",
            "mazzancoll", "astice", "aragost", "ostric", "frutti di mare", "surimi", "bottarg",
        ),
        Category.MEAT to listOf(
            "carne", "carni", "manzo", "bovin", "vitell", "vitellon", "maial", "suin", "pollo", "polli", "petto",
            "coscia", "cosce", "tacchin", "agnell", "ovin", "coniglio", "anatra", "salsicc", "hamburger", "macinat",
            "bistecc", "filetto", "controfilett", "entrecote", "fiorentin", "costat", "costine", "arrosto", "spezzatin",
            "fegato", "trippa", "ossobuc", "scottadit", "arrost", "fesa", "girello", "lombat", "noce", "cinghial",
            "chianina", "capocoll",
        ),
        Category.BAKERY to listOf(
            "pane", "pani", "panin", "focacc", "piadin", "crescia", "torta al testo", "grissin", "crackers", "cracker",
            "fette biscottate", "tramezzin", "baguette", "ciabatt", "brioche", "cornett", "croissant", "pizza",
            "sfoglia", "pangrattat",
        ),
        Category.FRUIT_VEG to listOf(
            "pomodor", "patat", "cipoll", "aglio", "carot", "zucchin", "melanzan", "peperon", "insalat", "lattug",
            "rucola", "spinac", "bietol", "cavol", "broccol", "cavolfior", "finocch", "sedano", "carciof", "asparag",
            "funghi", "fungo", "porcin", "champignon", "zucca", "piselli", "fagiolin", "cetriol", "radicchi", "indivia",
            "scarola", "prezzemol", "basilico", "rosmarin", "salvia", "menta", "timo", "erba", "limon", "arance",
            "arancia", "mela", "mele", "pera", "pere", "banan", "fragol", "uva", "pesca", "pesche", "albicocc",
            "ciliegi", "anguri", "melone", "meloni", "kiwi", "ananas", "frutta", "verdur", "ortagg", "mirtill",
            "lampon", "avocado", "zenzero", "porri", "porro", "scalogn", "rape", "rapa", "valerian", "songino",
        ),
        Category.DRY_GOODS to listOf(
            "pasta", "spaghett", "penne", "rigaton", "fusill", "linguin", "tagliatell", "lasagn", "gnocch", "farina",
            "semola", "riso", "zucchero", "sale", "pepe", "spezie", "origano", "legumi", "lenticchi", "ceci",
            "fagioli", "biscott", "cacao", "cioccolat", "lievito", "amido", "fecola", "pan grattato", "mais",
            "polenta", "orzo", "farro", "cous", "brodo", "dado", "maionese", "ketchup", "senape", "salsa", "sugo",
            "pesto", "miele", "marmellat", "confettur", "nutella", "crema", "frutta secca", "noci", "mandorl",
            "nocciol", "pinoli", "pistacch", "uvetta", "capperi", "olive", "tonno in scatola", "caffe in grani",
        ),
    )

    fun guess(name: String): Category {
        val text = normalize(name)
        val words = text.split(' ').filter { it.isNotEmpty() }
        for ((category, keywords) in RULES) {
            for (k in keywords) if (matches(k, text, words)) return category
        }
        return Category.OTHER
    }

    /** Whether [word] is a food/supply word the app knows; two different known words are never "typos" of each other. */
    fun isKnownWord(word: String): Boolean {
        val w = normalize(word)
        if (w.length < 3) return false
        return RULES.any { (_, keywords) -> keywords.any { k -> !k.contains(' ') && matches(k, w, listOf(w)) } }
    }

    private fun matches(keyword: String, text: String, words: List<String>): Boolean {
        val k = normalize(keyword)
        if (k.contains(' ')) return (" $text ").contains(" $k ")
        return if (k.length <= 4) words.any { it == k } else words.any { it.startsWith(k) }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
