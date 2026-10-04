package com.kitchenreceipts.core

import java.math.BigDecimal
import java.text.Normalizer

/**
 * Turns a product line as a supplier prints it into a readable product name, a brand and a pack size:
 * "PASTA BARI.PIPETTE RIG500 N.86 - BARILLA" -> "Pasta Barilla Pipette Rigate n.86", brand Barilla, 500 g.
 *
 * In order:
 * 1. the brand printed after the dash ("... - BARILLA") goes to the brand; a word that abbreviates it in the name
 *    ("BARI.", "LIEVITAL") becomes the brand, and a repeat of it ("DALLABONA") is dropped;
 * 2. sizes ("500", "GR500", "KG5", "LT.1", "CC.700", "GR.150", "KG.1,775") go to the pack size, not the name;
 * 3. abbreviations of the food trade are written out ("RIG" rigate, "B.AD." bovino adulto, "S/V" sottovuoto,
 *    "P.S." parzialmente scremato, "FORM.STAG." formaggio stagionato...), and what the operator taught by renaming
 *    products ([learned]) comes first;
 * 4. what is still an abbreviation the app does not know ("TR.", "ORLAN.") stays as printed and is listed in
 *    [Clean.unknown], so the name is shown for checking. Nothing is guessed.
 * The printed text is always kept elsewhere (the line's description, the supplier's article code).
 */
object ProductNames {

    data class Clean(val name: String, val brand: String?, val size: PackSizes.Size?, val unknown: List<String>)

    /** What the operator taught: an abbreviation (lower case, no dot) and the words it stands for. */
    @Volatile var learned: Map<String, String> = emptyMap()

    /** Abbreviations written out (lower case, without dots). Multi-part keys keep their slash ("s/v", "a/terra"). */
    private val ABBREVIATIONS: Map<String, String> = mapOf(
        // cuts, preparation, packaging state
        "b/a" to "bovino adulto", "bad" to "bovino adulto", "s/v" to "sottovuoto", "sv" to "sottovuoto", "c/osso" to "con osso",
        "s/osso" to "senza osso", "a/terra" to "allevate a terra", "p/pasta" to "per pasta", "c/vap" to "al vapore",
        "c/pelle" to "con pelle", "s/pelle" to "senza pelle", "s/sem" to "senza semi", "s/gl" to "senza glutine",
        "surg" to "surgelato", "cong" to "congelato", "stag" to "stagionato", "sgusc" to "sgusciati", "secc" to "secco",
        "conc" to "concentrato", "rig" to "rigate", "dec" to "decorticato", "ps" to "parzialmente scremato",
        "is" to "intero", "uht" to "UHT", "dop" to "DOP", "igp" to "IGP", "bio" to "BIO", "friz" to "frizzante",
        "nat" to "naturale", "bott" to "bottiglia", "latt" to "lattina", "conf" to "confezione",
        // products
        "form" to "formaggio", "ita" to "italiano", "pom" to "pomodoro", "rad" to "radicchio", "cip" to "cipolla",
        "pat" to "patate", "ins" to "insalata", "lim" to "limoni", "prezzem" to "prezzemolo", "cuo" to "cuore",
        "aro" to "aromi", "carc" to "carciofi", "zucch" to "zucchine", "melanz" to "melanzane", "pep" to "peperoni",
        "parm" to "parmigiano", "reg" to "reggiano", "mozz" to "mozzarella", "ric" to "ricotta", "prosc" to "prosciutto",
        "bisc" to "biscotti", "tov" to "tovaglioli", "tovagl" to "tovaglioli", "deterg" to "detergente",
        "sgrass" to "sgrassatore", "igieniz" to "igienizzante", "sup" to "superfici", "cont" to "contatto",
        "ali" to "alimenti", "sturasc" to "sturascarichi", "minera" to "minerale", "pass" to "passata", "pel" to "pelati",
        "extrav" to "extravergine", "gir" to "girasole", "m/penne" to "mezze penne", "bian" to "bianco",
        "selv" to "selvaggina", "verd" to "verdure", "fil" to "filetto", "spagh" to "spaghetti", "mezz" to "mezze",
        "farf" to "farfalle", "cav" to "cavolo", "fin" to "finocchi", "fagiol" to "fagioli",
        "cont/ali" to "contatto alimenti", "u/g" to "usa e getta", "p/c" to "precotto", "s/g" to "senza glutine",
    )

    /** Packaging words: about the box, not the product ("CRT" carton, "PT" pallet tag). Dropped from the name. */
    private val PACKAGING = setOf("crt", "cart", "ct", "conf", "pz", "pezzi")

    private val SMALL_WORDS = setOf("di", "da", "del", "della", "al", "alla", "con", "per", "e", "in", "a")

    // "GR500", "G330", "KG5", "LT.1", "ML.580", "CC.700", "GR.150", "G.150", "KG.1,775", "CL 70" (glued or dotted)
    private val SIZE = Regex("^(?i)(kg|gr|g|hg|lt|l|ml|cl|cc)\\.?(\\d+(?:[.,]\\d+)?)(x\\d+)?$")
    private val COUNT = Regex("^(?i)(?:x|p|pz)(\\d{1,4})$")
    private val CUT_NUMBER = Regex("^(?i)n\\.?(\\d{1,4})$")

    fun clean(printed: String, packSizeHint: PackSizes.Size? = null): Clean {
        var text = printed.trim().replace(Regex("\\s+"), " ")
        // A dash with nothing after it ("- .", "-.") is just the end of the supplier's name field.
        text = text.replace(Regex("\\s*-\\s*\\.?\\s*$"), "").trim()
        var brand: String? = null
        Regex("^(.*\\S)\\s+-\\s+([A-Za-z][A-Za-z'&. ]{1,30})$").find(text)?.let { m ->
            val b = m.groupValues[2].trim().trimEnd('.')
            if (b.count(Char::isLetter) >= 2 && b.split(' ').size <= 3) { brand = b; text = m.groupValues[1].trim() }
        }
        val brandKey = brand?.let { key(it).replace(" ", "") }
        var size: PackSizes.Size? = null
        val unknown = mutableListOf<String>()
        val out = mutableListOf<String>()
        val taught = learned
        val ps = pieces(text)
        var skip = 0
        for ((idx, raw) in ps.withIndex()) {
            if (skip > 0) { skip--; continue }
            val k = key(raw)
            if (k.isEmpty()) continue
            val next = ps.getOrNull(idx + 1)
            // Written in two pieces: "B." + "AD." (bovino adulto), "GR." + "500", "KG" + "1".
            if (next != null) {
                val pair = k + key(next)
                val two = taught[pair] ?: ABBREVIATIONS[pair]
                if (two != null && raw.endsWith('.')) { out += two; skip = 1; continue }
                val unit = Units.normalizeKnown(k)
                val n = ItalianNumbers.parse(next) ?: next.replace(',', '.').toBigDecimalOrNull()
                if ((unit != null && Units.dimension(unit) != null || k == "cc") && n != null && n.signum() > 0) {
                    if (size == null) size = PackSizes.Size(n.stripTrailingZeros(), if (k == "cc") "ml" else unit!!)
                    skip = 1
                    continue
                }
            }
            // Sizes go to the pack size.
            val sm = SIZE.matchEntire(raw.trimEnd('.'))
            if (sm != null) {
                val printedUnit = sm.groupValues[1].lowercase()
                // "CC.700" is cubic centimetres = ml.
                val u = if (printedUnit == "cc") "ml" else (Units.normalizeKnown(printedUnit) ?: printedUnit)
                val n = ItalianNumbers.parse(sm.groupValues[2]) ?: sm.groupValues[2].replace(',', '.').toBigDecimalOrNull()
                if (n != null && size == null) size = PackSizes.Size(n.stripTrailingZeros(), u)
                if (sm.groupValues[3].isNotEmpty()) out += sm.groupValues[3].lowercase()
                continue
            }
            // A bare number equal to the pack size ("BURRO ... GR. 500", "FUSILLI 500") repeats it.
            val hint = packSizeHint ?: size
            if (raw.all(Char::isDigit) && hint != null && BigDecimal(raw).compareTo(hint.amount) == 0) continue
            val cut = CUT_NUMBER.matchEntire(raw)
            if (cut != null) { out += "n.${cut.groupValues[1]}"; continue }
            val count = COUNT.matchEntire(raw)
            if (count != null) { out += "x${count.groupValues[1]}"; continue }
            if (k in PACKAGING) continue
            // The brand, abbreviated ("BARI." for Barilla, "LIEVITAL" for Lievitalia) or repeated ("DALLABONA").
            if (brandKey != null && k.length >= 3 && (brandKey.startsWith(k) || k == brandKey)) {
                if (out.none { key(it) == brandKey }) out += brand!!
                continue
            }
            val known = taught[k] ?: ABBREVIATIONS[k]
            when {
                known != null -> out += known
                isAbbreviation(raw) -> { out += raw.trimEnd('.'); unknown += raw }
                else -> out += raw
            }
        }
        val name = titleCase(out.joinToString(" ").replace(Regex("\\s+"), " ").trim())
        return Clean(name.ifBlank { titleCase(text) }, brand?.let { b -> if (b.count(Char::isLetter) <= 3) b.uppercase() else titleCase(b) }, if (packSizeHint != null && (Units.dimension(packSizeHint.unit) != null || size == null)) packSizeHint else size ?: packSizeHint, unknown)
    }

    /** Words of the name; "BARI.PIPETTE" and "FORM.STAG.ITA" are several words, "S/V" and "A/TERRA" one. */
    private fun pieces(text: String): List<String> =
        text.split(' ').filter { it.isNotBlank() }.flatMap { w ->
            if (SIZE.matches(w.trimEnd('.')) || CUT_NUMBER.matches(w) || w.count { it == '.' } == 0) listOf(w)
            else if (Regex("^(?i)[a-z]\\.[a-z]{1,2}\\.?$").matches(w)) listOf(w) // "B.AD.", "P.S" written as one
            else Regex("[^.]+\\.?").findAll(w).map { it.value }.toList()
        }.flatMap { w -> if (Regex("^(?i)(rig)(\\d+)$").matches(w)) listOf(w.substring(0, 3), w.substring(3)) else listOf(w) }

    /** Lower case, accents and dots removed: "B.AD." -> "bad", "S/V" -> "s/v". */
    private fun key(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace(".", "").replace("'", "").trim(',', ';', ':', '-', '(', ')')

    /** Printed as an abbreviation: a dot after letters ("TR.", "ORLAN."), or a slash form ("P/OCCHIO"). */
    private fun isAbbreviation(raw: String): Boolean =
        (raw.endsWith('.') && raw.count(Char::isLetter) in 1..7 && raw.none(Char::isDigit)) || Regex("^(?i)[a-z]{1,2}/[a-z]+$").matches(raw)

    private fun titleCase(s: String): String = s.split(' ').mapIndexed { i, w ->
        when {
            w.isEmpty() -> w
            w in setOf("DOP", "IGP", "UHT", "BIO", "IGT", "DOC", "DOCG") -> w
            i > 0 && w.lowercase() in SMALL_WORDS -> w.lowercase()
            w.startsWith("n.") || Regex("^x\\d+$").matches(w) -> w
            // "C+C", "1/16", "25X25": codes and measures stay as printed.
            w.any { !it.isLetter() && it != '.' && it != '\'' && it != '-' } -> w.uppercase()
            // "M.BIGAZZI" -> "M.Bigazzi", "VITE-POLLO" -> "Vite-Pollo"
            else -> w.lowercase().split('.').joinToString(".") { part -> part.split('-').joinToString("-") { it.replaceFirstChar { c -> c.titlecase() } } }
        }
    }.joinToString(" ")

    /**
     * What a rename by the operator teaches: each abbreviation in the printed name and the word of the new name that
     * starts with it ("TR." and "Tenerissimo" -> "tr" = "tenerissimo"). Only clear pairs; nothing else.
     */
    fun learnFromRename(printed: String, newName: String): Map<String, String> {
        val words = newName.split(' ').filter { it.count(Char::isLetter) >= 3 }
        val out = mutableMapOf<String, String>()
        for (raw in pieces(printed)) {
            if (!isAbbreviation(raw) && raw.length > 5) continue
            val k = key(raw)
            if (k.length < 2 || k in ABBREVIATIONS || k.any(Char::isDigit)) continue
            val letters = k.filter(Char::isLetter)
            // "TR." for Tenerissimo, "SGUSC." for sgusciati: same first letter, the other letters in order.
            val match = words.filter { w -> val wk = key(w); wk != letters && wk.first() == letters.first() && subsequence(letters, wk) }
            if (match.size == 1) out[k] = match.single().lowercase()
        }
        return out
    }

    private fun subsequence(a: String, b: String): Boolean {
        var i = 0
        for (ch in b) if (i < a.length && a[i] == ch) i++
        return i == a.length
    }
}
