package com.kitchenreceipts.core

import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** One cell as read from a sheet: its text, and its number when the sheet stored a number. */
data class SheetCell(val text: String, val number: BigDecimal? = null) {
    fun decimal(): BigDecimal? = number ?: ItalianNumbers.parse(text.replace("%", "").trim())
    val blank: Boolean get() = text.isBlank() && number == null
}

data class ImportedIngredient(
    val name: String,
    val quantity: BigDecimal,
    val unit: String,
    val wastePercent: BigDecimal,
    val price: BigDecimal?,
    val priceUnit: String?,
)

data class ImportedDish(
    val name: String,
    /** antipasti, primi, secondi, contorni, dolci, bevande, altro (null = not given). */
    val course: String?,
    val salePriceCents: Long?,
    val priceIncludesVat: Boolean,
    val vatRatePercent: BigDecimal,
    val portions: BigDecimal,
    val ingredients: List<ImportedIngredient>,
    /** Ingredient rows left out because they had no quantity or an unknown unit. */
    val skipped: List<String>,
)

data class MenuImport(val dishes: List<ImportedDish>, val problems: List<String>)

/**
 * Dishes from a spreadsheet the kitchen keeps: one row per ingredient, the dish name on each row (or only on the
 * first, the rows below belong to it). Columns are found by their heading in Italian or English, in any order:
 * Piatto/Dish, Portata/Course, Prezzo menu/Menu price, IVA inclusa/Includes VAT, IVA %/VAT %, Porzioni/Portions,
 * Ingrediente/Ingredient, Quantità/Quantity, Unità/Unit, Scarto %/Waste %, Prezzo ingrediente/Ingredient price,
 * Prezzo per/Price per. Other columns (notes, formulas) are ignored. Reads .xlsx (the values Excel saved) and CSV.
 */
object MenuSheet {

    class NotAMenu(why: String) : Exception(why)

    private enum class Col { DISH, COURSE, PRICE, VAT_INCL, VAT, PORTIONS, INGREDIENT, QTY, UNIT, WASTE, ING_PRICE, PER }

    private fun norm(s: String) = s.lowercase().replace('à', 'a').replace('è', 'e').replace('é', 'e').replace('ù', 'u')
        .replace(Regex("[^a-z%]+"), " ").trim()

    private fun colOf(header: String): Col? {
        val h = norm(header)
        if (h.isEmpty()) return null
        return when {
            h.startsWith("piatto") || h == "dish" || h.startsWith("dish ") || h == "nome piatto" -> Col.DISH
            h.startsWith("portata") || h.startsWith("course") || h.startsWith("categoria") || h.startsWith("category") -> Col.COURSE
            h.startsWith("prezzo ingrediente") || h.startsWith("ingredient price") || h.startsWith("prezzo unitario") || h.startsWith("unit price") -> Col.ING_PRICE
            h.startsWith("prezzo per") || h.startsWith("price per") || h == "per" -> Col.PER
            h.startsWith("prezzo") || h.startsWith("price") || h.startsWith("menu price") -> Col.PRICE
            h.startsWith("iva inclusa") || h.startsWith("includes vat") || h.startsWith("vat included") || h.startsWith("ivato") -> Col.VAT_INCL
            h.startsWith("iva") || h.startsWith("vat") -> Col.VAT
            h.startsWith("porzion") || h.startsWith("portion") || h.startsWith("dosi") -> Col.PORTIONS
            h.startsWith("ingredient") -> Col.INGREDIENT
            h.startsWith("quantit") || h.startsWith("qty") || h.startsWith("qta") || h.startsWith("quantity") -> Col.QTY
            h.startsWith("unit") || h == "um" || h == "u m" -> Col.UNIT
            h.startsWith("scarto") || h.startsWith("waste") || h.startsWith("calo") -> Col.WASTE
            else -> null
        }
    }

    private val COURSES = listOf(
        "antipasti" to listOf("antipast", "starter", "appetizer", "entree"),
        "primi" to listOf("prim", "pasta", "first"),
        "secondi" to listOf("second", "main", "grill", "brace", "carne", "meat"),
        "contorni" to listOf("contorn", "side"),
        "dolci" to listOf("dolc", "dessert", "sweet"),
        "bevande" to listOf("bevand", "drink", "vin", "wine"),
        "altro" to listOf("altr", "other", "coperto", "cover"),
    )

    fun courseKey(s: String): String? {
        val n = norm(s)
        if (n.isEmpty()) return null
        return COURSES.firstOrNull { (_, words) -> words.any { n.startsWith(it) } }?.first ?: "altro"
    }

    private fun yes(s: String): Boolean? {
        val n = norm(s)
        return when {
            n.isEmpty() -> null
            n.startsWith("s") || n.startsWith("y") || n == "x" || n == "1" || n.startsWith("v") || n.startsWith("t") -> true
            n.startsWith("n") || n == "0" || n.startsWith("f") -> false
            else -> null
        }
    }

    /** Reads the rows of a sheet into dishes. */
    fun parse(rows: List<List<SheetCell>>): MenuImport {
        // The heading row: the first with both a dish and an ingredient column.
        var headerAt = -1
        var cols: Map<Col, Int> = emptyMap()
        for ((i, row) in rows.withIndex().take(30)) {
            val m = LinkedHashMap<Col, Int>()
            row.forEachIndexed { j, c -> colOf(c.text)?.let { k -> m.putIfAbsent(k, j) } }
            if (Col.DISH in m && Col.INGREDIENT in m) { headerAt = i; cols = m; break }
        }
        if (headerAt < 0) throw NotAMenu("No 'Piatto' / 'Ingrediente' heading found")
        fun cell(row: List<SheetCell>, c: Col): SheetCell? = cols[c]?.let { row.getOrNull(it) }
        fun text(row: List<SheetCell>, c: Col) = cell(row, c)?.text?.trim().orEmpty()

        class Building(val name: String) {
            var course: String? = null
            var price: Long? = null
            var vatIncl: Boolean? = null
            var vat: BigDecimal? = null
            var portions: BigDecimal? = null
            val ings = ArrayList<ImportedIngredient>()
            val skipped = ArrayList<String>()
        }
        val dishes = LinkedHashMap<String, Building>()
        val problems = ArrayList<String>()
        var current: Building? = null
        for (r in headerAt + 1 until rows.size) {
            val row = rows[r]
            if (row.all { it.blank }) continue
            val name = text(row, Col.DISH)
            val b = if (name.isNotEmpty()) dishes.getOrPut(ProductMatching.aliasKey(name)) { Building(name) } else current
            if (b == null) { problems += "Row ${r + 1}: no dish"; continue }
            current = b
            if (b.course == null) text(row, Col.COURSE).takeIf { it.isNotEmpty() }?.let { b.course = courseKey(it) }
            if (b.price == null) cell(row, Col.PRICE)?.decimal()?.let { b.price = ItalianNumbers.toCents(it) }
            if (b.vatIncl == null) yes(text(row, Col.VAT_INCL))?.let { b.vatIncl = it }
            if (b.vat == null) cell(row, Col.VAT)?.decimal()?.let { b.vat = percent(it) }
            if (b.portions == null) cell(row, Col.PORTIONS)?.decimal()?.takeIf { it.signum() > 0 }?.let { b.portions = it }
            val ing = text(row, Col.INGREDIENT)
            if (ing.isEmpty()) continue
            val q = cell(row, Col.QTY)?.decimal()?.takeIf { it.signum() > 0 }
            val unit = Units.normalize(text(row, Col.UNIT))
            if (q == null || unit == null) { b.skipped += ing; continue }
            val price = cell(row, Col.ING_PRICE)?.decimal()?.takeIf { it.signum() > 0 }
            val per = Units.normalize(text(row, Col.PER)) ?: unit.takeIf { price != null }
            b.ings += ImportedIngredient(ing, q, unit, cell(row, Col.WASTE)?.decimal()?.let(::percent)?.takeIf { it.signum() >= 0 } ?: BigDecimal.ZERO, price, per)
        }
        if (dishes.isEmpty()) throw NotAMenu("No dishes below the heading")
        return MenuImport(
            dishes.values.map { b ->
                ImportedDish(b.name, b.course, b.price, b.vatIncl ?: true, b.vat ?: BigDecimal.TEN, b.portions ?: BigDecimal.ONE, b.ings, b.skipped)
            },
            problems,
        )
    }

    /** 10, "10%" or 0,10 (a percentage cell) all mean ten percent. */
    private fun percent(v: BigDecimal): BigDecimal = if (v.signum() > 0 && v < BigDecimal.ONE) v.movePointRight(2).stripTrailingZeros() else v

    // ------------------------------------------------------------------ CSV

    /** Rows of a CSV file: ';' or ',' or tab separated (whichever the heading uses), quotes allowed. */
    fun csvRows(text: String): List<List<SheetCell>> {
        val clean = text.removePrefix("﻿")
        val firstLine = clean.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val sep = listOf(';', '\t', ',').maxByOrNull { c -> firstLine.count { it == c } } ?: ';'
        val rows = ArrayList<List<SheetCell>>()
        var row = ArrayList<SheetCell>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() { row += SheetCell(cur.toString()); cur.clear() }
        while (i < clean.length) {
            val ch = clean[i]
            when {
                quoted && ch == '"' && i + 1 < clean.length && clean[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == sep -> endCell()
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < clean.length && clean[i + 1] == '\n') i++
                    endCell(); rows += row; row = ArrayList()
                }
                else -> cur.append(ch)
            }
            i++
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) { endCell(); rows += row }
        return rows
    }

    // ------------------------------------------------------------------ XLSX

    /**
     * Rows of an .xlsx file: the sheet named Piatti / Dishes / Menu, else the first sheet with a dish heading.
     * Values as Excel saved them (a formula gives its last result).
     */
    fun xlsxRows(bytes: ByteArray): List<List<SheetCell>> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || !e.name.endsWith(".xml") && !e.name.endsWith(".rels")) continue
                if (e.name.startsWith("xl/") && (e.name.contains("sheet") || e.name.contains("sharedStrings") || e.name.endsWith("workbook.xml") || e.name.endsWith(".rels"))) {
                    val b = z.readBytes()
                    if (b.size > 30_000_000) throw NotAMenu("Sheet too large")
                    parts[e.name] = b
                }
            }
        }
        val workbook = parts["xl/workbook.xml"]?.let(::xml) ?: throw NotAMenu("Not an Excel file")
        val rels = parts["xl/_rels/workbook.xml.rels"]?.let(::xml)
        val relTarget = HashMap<String, String>()
        rels?.getElementsByTagNameNS("*", "Relationship")?.let { l ->
            for (i in 0 until l.length) {
                val e = l.item(i) as Element
                val t = e.getAttribute("Target").removePrefix("/").let { if (it.startsWith("xl/")) it else "xl/$it" }
                relTarget[e.getAttribute("Id")] = t
            }
        }
        val sheets = ArrayList<Pair<String, String>>() // name -> part
        workbook.getElementsByTagNameNS("*", "sheet").let { l ->
            for (i in 0 until l.length) {
                val e = l.item(i) as Element
                val rid = e.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id").ifEmpty { e.getAttribute("r:id") }
                val part = relTarget[rid] ?: "xl/worksheets/sheet${i + 1}.xml"
                sheets += e.getAttribute("name") to part
            }
        }
        val strings = parts["xl/sharedStrings.xml"]?.let { b ->
            val d = xml(b)
            val l = d.getElementsByTagNameNS("*", "si")
            (0 until l.length).map { i -> textOf(l.item(i) as Element) }
        }.orEmpty()
        val preferred = sheets.sortedBy { (n, _) -> if (norm(n).let { it.startsWith("piatti") || it.startsWith("dishes") || it.startsWith("menu") }) 0 else 1 }
        for ((_, part) in preferred) {
            val rows = parts[part]?.let { sheetRows(xml(it), strings) } ?: continue
            if (rows.take(30).any { r -> r.any { colOf(it.text) == Col.DISH } && r.any { colOf(it.text) == Col.INGREDIENT } }) return rows
        }
        throw NotAMenu("No sheet with 'Piatto' and 'Ingrediente' columns")
    }

    private fun sheetRows(d: org.w3c.dom.Document, strings: List<String>): List<List<SheetCell>> {
        val out = ArrayList<List<SheetCell>>()
        val rows = d.getElementsByTagNameNS("*", "row")
        for (i in 0 until rows.length) {
            val r = rows.item(i) as Element
            val index = r.getAttribute("r").toIntOrNull()?.minus(1) ?: out.size
            while (out.size < index) out += emptyList<SheetCell>()
            val cells = ArrayList<SheetCell>()
            val cl = r.getElementsByTagNameNS("*", "c")
            for (j in 0 until cl.length) {
                val c = cl.item(j) as Element
                val col = colIndex(c.getAttribute("r")) ?: cells.size
                while (cells.size < col) cells += SheetCell("")
                val t = c.getAttribute("t")
                val v = c.getElementsByTagNameNS("*", "v").item(0)?.textContent
                cells += when (t) {
                    "s" -> SheetCell(v?.toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty())
                    "inlineStr" -> SheetCell(textOf(c))
                    "str", "e" -> SheetCell(v.orEmpty())
                    "b" -> SheetCell(if (v == "1") "TRUE" else "FALSE")
                    else -> {
                        val n = v?.let { runCatching { BigDecimal(it) }.getOrNull() }
                        SheetCell(n?.stripTrailingZeros()?.toPlainString() ?: v.orEmpty(), n)
                    }
                }
            }
            out += cells
        }
        return out
    }

    private fun colIndex(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return null
        return letters.fold(0) { a, ch -> a * 26 + (ch - 'A' + 1) } - 1
    }

    private fun textOf(e: Element): String {
        val ts = e.getElementsByTagNameNS("*", "t")
        return (0 until ts.length).joinToString("") { ts.item(it).textContent }
    }

    private fun xml(b: ByteArray): org.w3c.dom.Document {
        if (String(b, Charsets.ISO_8859_1).contains("<!DOCTYPE", ignoreCase = true)) throw NotAMenu("Unexpected DOCTYPE")
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.isExpandEntityReferences = false }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(b))
    }

    /** Reads a file the operator picked: .xlsx (a zip) or CSV text. */
    fun read(bytes: ByteArray): MenuImport =
        parse(if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) xlsxRows(bytes) else csvRows(String(bytes, Charsets.UTF_8)))
}

/** A dish already in the app, as needed to update it from a sheet. */
data class ExistingDish(val id: Long, val name: String, val ingredients: List<Pair<String, Long?>>)

data class PlannedIngredient(val ingredient: ImportedIngredient, val productId: Long?, val keptLink: Boolean)
data class PlannedDish(val dish: ImportedDish, val existingId: Long?, val ingredients: List<PlannedIngredient>)

/** What an import will do, shown before anything changes. */
data class MenuPlan(val dishes: List<PlannedDish>, val notInFile: List<ExistingDish>) {
    val added: Int get() = dishes.count { it.existingId == null }
    val updated: Int get() = dishes.count { it.existingId != null }
    val linked: Int get() = dishes.sumOf { d -> d.ingredients.count { it.productId != null } }
    val typedOnly: Int get() = dishes.sumOf { d -> d.ingredients.count { it.productId == null && it.ingredient.price != null } }
    val noPrice: Int get() = dishes.sumOf { d -> d.ingredients.count { it.productId == null && it.ingredient.price == null } }
    val skipped: Int get() = dishes.sumOf { it.dish.skipped.size }
    val withoutIngredients: Int get() = dishes.count { it.ingredients.isEmpty() }
}

/**
 * Updates the app's dishes from a sheet. Dishes are matched by name. An ingredient keeps the product the
 * operator linked it to in the app (same dish, same ingredient name); otherwise it is linked only when exactly
 * one product carries all its words, so "Pecorino" finds "Pecorino romano DOP" but not when there are two
 * pecorini. Unlinked ingredients use the price written in the sheet.
 */
object MenuImporter {

    private val STOP = setOf("di", "da", "del", "della", "al", "alla", "con", "per", "e", "in", "the", "and", "of")

    private fun words(s: String) = ProductMatching.aliasKey(s).split(' ').filter { it.length > 1 && it !in STOP && !it.all(Char::isDigit) }

    /** The one product this ingredient name means, or null when none or more than one could be meant. */
    fun link(name: String, products: List<ProductRef>): Long? {
        val key = ProductMatching.aliasKey(name)
        if (key.isEmpty()) return null
        products.filter { ProductMatching.aliasKey(it.name) == key }.let { if (it.size == 1) return it[0].id; if (it.size > 1) return null }
        val w = words(name)
        if (w.isEmpty()) return null
        val hits = products.filter { p ->
            val pw = words(p.name)
            w.all { a -> pw.any { b -> a == b || (a.length >= 4 && b.startsWith(a)) } }
        }
        return hits.singleOrNull()?.id
    }

    fun plan(menu: MenuImport, existing: List<ExistingDish>, products: List<ProductRef>): MenuPlan {
        val byKey = existing.associateBy { ProductMatching.aliasKey(it.name) }
        val seen = HashSet<Long>()
        val planned = menu.dishes.map { d ->
            val old = byKey[ProductMatching.aliasKey(d.name)]
            old?.let { seen += it.id }
            val oldLinks = old?.ingredients.orEmpty().filter { it.second != null }.associate { ProductMatching.aliasKey(it.first) to it.second!! }
            PlannedDish(
                d, old?.id,
                d.ingredients.map { i ->
                    val kept = oldLinks[ProductMatching.aliasKey(i.name)]
                    PlannedIngredient(i, kept ?: link(i.name, products), kept != null)
                },
            )
        }
        return MenuPlan(planned, existing.filter { it.id !in seen })
    }
}
