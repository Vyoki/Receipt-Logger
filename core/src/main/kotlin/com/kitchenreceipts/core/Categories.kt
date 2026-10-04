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
 * How a name is read:
 * 1. Words that decide whatever else the name says: frozen ("surgelato"), preserved ("pelati", "passata",
 *    "concentrato", "sott'olio") and phrases ("fette biscottate", "piatti mano", "torta al testo").
 * 2. Otherwise the first word that names a product wins: on a supplier's line the product comes first and the brand,
 *    pack and details follow ("PAT.SACCHI KG4X5" is potatoes in sacks, not bags; "FILETTI DI TONNO" is fish).
 * Abbreviations as suppliers print them ("BISC.", "PAT.", "CIP.", "PARM.", "INS.") count when they can only be the
 * beginning of words of one category. Keywords are word beginnings ("mozzarell" matches mozzarella/mozzarelle);
 * keywords of 4 letters or less must match the whole word (so "sale" does not match "salame").
 */
object Categories {

    /** Step 1: decide whatever the rest of the name says. Phrases of every category are added to these. */
    private val OVERRIDES: List<Pair<Category, List<String>>> = listOf(
        Category.FROZEN to listOf("surgelat", "congelat", "surg", "cong", "gelato", "gelati", "ghiaccio"),
        Category.DRY_GOODS to listOf("pelat", "passata", "concentrat", "conserv", "sottolio", "sottaceto", "sott olio"),
    )

    /** Step 2: what a word names, checked in this order for the same word. */
    private val RULES: List<Pair<Category, List<String>>> = listOf(
        Category.CLEANING to listOf(
            "detersiv", "sgrassator", "candeggin", "igienizz", "disinfett", "sapone", "brillantant", "anticalcar",
            "lavastoviglie", "lavapiatti", "spugn", "detergent", "ammorbident", "varechina", "sanificant", "piatti mano",
            "lavatric", "bucato", "lavapavimenti", "pavimenti", "alcool etilico", "alcol etilico", "alcool", "candeggina",
            "sciampagna", "fairy", "svelto", "chanteclair", "smacchiat", "deodorant", "insetticid",
        ),
        Category.DISPOSABLES to listOf(
            "carta", "tovagliol", "bicchier", "piatti", "posate", "forchett", "cucchiai", "coltell", "pellicol",
            "alluminio", "vaschett", "sacchett", "sacchi", "shopper", "guanti", "cannucc", "contenitor", "coperchi",
            "rotolo", "rotoli", "scatol", "busta", "buste", "stuzzicadent", "tovagli", "carbone", "carbonell",
            "diavolin", "accendifuoco", "accendin", "fiammifer",
        ),
        Category.BEVERAGES to listOf(
            "acqua", "vino", "vini", "birra", "birre", "bibit", "succo", "succhi", "aranciat", "limonat", "chinotto",
            "cola", "coca", "tonica", "spremut", "liquor", "grappa", "amaro", "prosecco", "spumant", "spritz",
            "aperol", "campari", "vodka", "whisky", "rum", "gin", "caffe", "tè", "the", "tisan", "bevand",
            "sciropp", "energy", "lambrusco", "chianti", "sangiovese", "montepulciano", "minerale", "frizzante",
        ),
        Category.CURED_MEATS to listOf(
            "prosciutt", "salame", "salami", "mortadell", "bresaola", "speck", "pancett", "guancial", "coppa",
            "lonza", "culatell", "nduja", "wurstel", "salumi", "porchetta", "lardo", "salamin", "cotechin", "zampone",
        ),
        Category.DAIRY_EGGS to listOf(
            "latte", "mozzarell", "fiordilatte", "burrata", "stracciatell", "ricotta", "formagg", "parmigian",
            "grana", "pecorin", "gorgonzol", "mascarpon", "stracchin", "scamorz", "provol", "fontina", "asiago",
            "taleggi", "emmental", "caciocavall", "burro", "panna", "yogurt", "uova", "uovo", "caprin", "robiola",
            "crescenza", "squacquerone", "brie", "feta", "cheddar", "philadelphia", "besciamell", "parmigiano reggiano",
            "reggiano", "caciott", "primo sale", "edamer",
        ),
        Category.FISH to listOf(
            "pesce", "salmon", "tonno", "merluzz", "baccal", "stoccafiss", "orata", "orate", "spigol", "branzin",
            "gamber", "scampi", "scampo", "calamar", "totan", "seppi", "polpo", "polpi", "cozze", "cozza", "vongol",
            "alici", "acciug", "sgombr", "pesce spada", "spada", "sarde", "sardin", "trota", "rombo", "sogliol",
            "mazzancoll", "astice", "aragost", "ostric", "frutti di mare", "surimi", "bottarg",
        ),
        Category.MEAT to listOf(
            "carne", "carni", "manzo", "bovin", "vitell", "vitellon", "maiale", "maiali", "suino", "suini", "suina",
            "pollo", "polli", "petto", "coscia", "cosce", "tacchin", "agnell", "ovino", "ovini", "coniglio", "anatra",
            "salsicc", "hamburger", "macinat", "bistecc", "filetto", "controfilett", "entrecote", "fiorentin", "costat",
            "costine", "arrosto", "spezzatin", "fegato", "trippa", "ossobuc", "scottadit", "arrost", "fesa", "girello",
            "lombat", "noce", "cinghial", "chianina", "capocoll", "filone", "polpa", "lombo", "braciol", "cappello del prete",
        ),
        Category.BAKERY to listOf(
            "pane", "pani", "panin", "focacc", "piadin", "crescia", "torta al testo", "grissin", "crackers", "cracker",
            "fette biscottate", "tramezzin", "baguette", "ciabatt", "brioche", "cornett", "croissant", "pizza",
            "sfoglia", "pangrattat", "biscott", "frollin", "wafer", "merendin", "savoiard", "pan di spagna", "taralli",
            "tarallin", "fette", "galletti", "plumcake",
        ),
        Category.FRUIT_VEG to listOf(
            "pomodor", "patat", "cipoll", "aglio", "carot", "zucchin", "zucche", "zucca", "melanzan", "peperon",
            "insalat", "lattug", "rucola", "spinac", "bietol", "cavol", "broccol", "cavolfior", "finocch", "sedano",
            "carciof", "asparag", "funghi", "fungo", "porcin", "champignon", "piselli", "fagiolin", "cetriol",
            "radicchi", "indivia", "scarola", "prezzemol", "basilico", "rosmarin", "salvia", "menta", "timo", "erba",
            "erbe", "aromi", "aromatich", "limon", "arance", "arancia", "mela", "mele", "pera", "pere", "banan",
            "fragol", "uva", "pesca", "pesche", "albicocc", "ciliegi", "anguri", "melone", "meloni", "kiwi", "ananas",
            "frutta", "verdur", "ortagg", "mirtill", "lampon", "avocado", "zenzero", "porri", "porro", "scalogn",
            "rape", "rapa", "valerian", "songino", "cicori", "gentilin", "lollo", "catalogn", "friariell", "agrumi",
            "pompelm", "mandarin", "clementin", "castagn", "cuore di sedano",
        ),
        Category.DRY_GOODS to listOf(
            "pasta", "spaghett", "penne", "rigaton", "fusill", "linguin", "tagliatell", "lasagn", "gnocch", "farina",
            "semola", "riso", "zucchero", "sale", "pepe", "spezie", "origano", "legumi", "lenticchi", "ceci",
            "fagioli", "cacao", "cioccolat", "lievito", "amido", "fecola", "pan grattato", "mais",
            "polenta", "orzo", "farro", "cous", "brodo", "dado", "maionese", "ketchup", "senape", "salsa", "sugo",
            "pesto", "miele", "marmellat", "confettur", "nutella", "crema", "frutta secca", "noci", "mandorl",
            "nocciol", "pinoli", "pistacch", "uvetta", "capperi", "olive", "tonno in scatola", "caffe in grani",
            "aceto", "balsamic", "olio", "extravergine", "evo", "bicarbonat", "peperoncino", "paprika", "cannell",
            "vaniglia", "noce moscata", "pomodori secchi", "polpa di pomodoro", "pangrattato",
        ),
    )

    /** Phrases are checked before single words, in the order of [RULES]. */
    private val PHRASES: List<Pair<Category, List<String>>> =
        OVERRIDES + RULES.map { (c, ks) -> c to ks.filter { it.contains(' ') } }

    private class Word(val text: String, val abbreviation: Boolean)

    /**
     * What the operator taught: a word of a product name and the category they chose for it, when the app had guessed
     * differently ("RITORNELLI" -> bakery). Applies to every product with that word, from any supplier, before the
     * built-in dictionary. Set by the app from what it stored on the phone.
     */
    @Volatile var learned: Map<String, Category> = emptyMap()

    fun guess(name: String): Category {
        val words = words(name)
        val text = words.joinToString(" ") { it.text }
        val plain = words.map { it.text }
        val taught = learned
        if (taught.isNotEmpty()) for (w in words) taught[w.text]?.let { return it }
        for ((category, keywords) in PHRASES) {
            for (k in keywords) if (matches(k, text, plain)) return category
        }
        for (w in words) categoryOf(w)?.let { return it }
        return Category.OTHER
    }

    /**
     * The word to learn when the operator puts [name] in [chosen] although the app guessed otherwise: the word the
     * guess was based on, or the first word of the name (the product) when nothing was recognised. Null when the
     * guess was already right.
     */
    fun wordToLearn(name: String, chosen: Category): String? {
        if (guess(name) == chosen) return null
        val words = words(name).filter { w -> w.text.count(Char::isLetter) >= 3 && Units.normalizeKnown(w.text) == null }
        return (words.firstOrNull { categoryOf(it) != null } ?: words.firstOrNull())?.text
    }

    /** True when [storedKey] is what versions before October 2026 guessed for [name] (not chosen by the operator). */
    fun wasGuessedByOldRules(name: String, storedKey: String?): Boolean =
        storedKey != null && CategoriesV1.guess(name).key == storedKey

    private fun categoryOf(w: Word): Category? {
        for ((category, keywords) in RULES) {
            for (k in keywords) if (!k.contains(' ') && matches(k, w.text, listOf(w.text))) return category
        }
        // "BISC." / "PAT." / "PARM.": the beginning of a known word, when only one category has words starting so.
        if (w.abbreviation && w.text.length >= 3 && w.text.any(Char::isLetter)) {
            val found = RULES.flatMap { (c, keywords) ->
                keywords.filter { k -> !k.contains(' ') && k.length > w.text.length && normalize(k).startsWith(w.text) }.map { c to normalize(it) }
            }
            if (found.map { it.first }.distinct().size == 1) return found.first().first
            // "LIM." could be "limoni" or "limonata": the shorter word, when the others only continue it, wins.
            val shortest = found.minByOrNull { it.second.length }
            if (shortest != null && found.all { it.second.startsWith(shortest.second) }) return shortest.first
        }
        return null
    }

    /** Words of the name; a word printed with a dot after it ("BISC.", "M.B.", or "CIP," misread) is an abbreviation. */
    private fun words(name: String): List<Word> {
        val folded = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        return Regex("([a-z0-9]+)([.,](?=[a-z\\s]|$))?").findAll(folded).map { m ->
            Word(m.groupValues[1], m.groupValues[2].isNotEmpty() && m.groupValues[1].none(Char::isDigit))
        }.toList()
    }

    /** Whether [word] is a food/supply word the app knows; two different known words are never "typos" of each other. */
    fun isKnownWord(word: String): Boolean {
        val w = normalize(word)
        if (w.length < 3) return false
        return (OVERRIDES + RULES).any { (_, keywords) -> keywords.any { k -> !k.contains(' ') && matches(k, w, listOf(w)) } }
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
