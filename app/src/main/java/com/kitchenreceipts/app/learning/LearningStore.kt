package com.kitchenreceipts.app.learning

import android.content.Context
import com.kitchenreceipts.core.AiReader
import com.kitchenreceipts.core.Categories
import com.kitchenreceipts.core.Category
import com.kitchenreceipts.core.ChoiceRule
import com.kitchenreceipts.core.ProductNames
import com.kitchenreceipts.core.SupplierLayout
import com.kitchenreceipts.core.SupplierLayouts
import com.kitchenreceipts.core.SupplierMemory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the app has learned from the operator, per supplier (see core SupplierMemory): the choices made when a line
 * could be read two ways, and a few confirmed lines shown to the AI as examples. Kept in the app's private storage
 * on this phone only; excluded from backup like everything else.
 */
class LearningStore(context: Context) {

    private val file = File(context.filesDir, "learning.json")
    private val lock = Any()

    private data class Entry(
        val rules: MutableSet<ChoiceRule> = mutableSetOf(),
        var examples: List<AiReader.RowExample> = emptyList(),
        /** How the supplier prints its documents (see core SupplierLayout). */
        var layout: SupplierLayout? = null,
    )

    private val entries: MutableMap<String, Entry> by lazy { load() }

    /** Words of product names and the category the operator chose for them (all suppliers). */
    private val categoryWords: MutableMap<String, Category> by lazy { loadCategories() }

    /** Abbreviations in product names and the words the operator wrote for them ("tr" -> "tenerissimo"). */
    private val nameWords: MutableMap<String, String> by lazy { loadNames() }

    /** Puts what was learned into the category guesser and the name cleaner (call once at start). */
    fun applyCategories() = synchronized(lock) {
        Categories.learned = categoryWords.toMap()
        ProductNames.learned = nameWords.toMap()
    }

    /** The operator named a new product [finalName] for the printed line [printed]: learn its abbreviations. */
    fun learnName(printed: String, finalName: String) = synchronized(lock) {
        val pairs = ProductNames.learnFromRename(printed, finalName)
        if (pairs.isEmpty() || pairs.all { (k, v) -> nameWords[k] == v }) return@synchronized
        nameWords.putAll(pairs)
        ProductNames.learned = nameWords.toMap()
        save()
    }

    private fun loadNames(): MutableMap<String, String> {
        val o = runCatching { JSONObject(file.readText()).optJSONObject(NAMES_KEY) }.getOrNull() ?: return mutableMapOf()
        return o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotBlank() }.toMutableMap()
    }

    /** The operator chose [category] for [productName]: the word the guess hinged on now means that category. */
    fun learnCategory(productName: String, category: Category) = synchronized(lock) {
        val word = Categories.wordToLearn(productName, category) ?: return@synchronized
        categoryWords[word] = category
        Categories.learned = categoryWords.toMap()
        save()
    }

    private fun loadCategories(): MutableMap<String, Category> {
        val o = runCatching { JSONObject(file.readText()).optJSONObject(CATEGORY_KEY) }.getOrNull() ?: return mutableMapOf()
        return o.keys().asSequence().mapNotNull { k -> Category.fromKey(o.optString(k))?.let { k to it } }.toMap().toMutableMap()
    }

    fun rules(key: String?): Set<ChoiceRule> = synchronized(lock) { key?.let { entries[it]?.rules?.toSet() }.orEmpty() }

    fun examples(key: String?): List<AiReader.RowExample> = synchronized(lock) { key?.let { entries[it]?.examples }.orEmpty() }

    fun layout(key: String?): SupplierLayout? = synchronized(lock) { key?.let { entries[it]?.layout } }

    /** Adds what one more confirmed document of the supplier taught about its layout. */
    fun addLayout(key: String?, learned: SupplierLayout) = synchronized(lock) {
        if (key == null) return@synchronized
        val e = entries.getOrPut(key) { Entry() }
        e.layout = SupplierLayouts.merge(e.layout, learned)
        save()
    }

    fun addRule(key: String?, rule: ChoiceRule) = synchronized(lock) {
        if (key == null) return@synchronized
        entries.getOrPut(key) { Entry() }.rules += rule
        save()
    }

    fun addExamples(key: String?, new: List<AiReader.RowExample>) = synchronized(lock) {
        if (key == null || new.isEmpty()) return@synchronized
        val e = entries.getOrPut(key) { Entry() }
        e.examples = SupplierMemory.mergeExamples(e.examples, new)
        save()
    }

    /** Counts for Settings: suppliers, remembered choices, example lines. */
    fun stats(): Triple<Int, Int, Int> = synchronized(lock) {
        Triple(entries.size, entries.values.sumOf { it.rules.size }, entries.values.sumOf { it.examples.size })
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        categoryWords.clear()
        Categories.learned = emptyMap()
        nameWords.clear()
        ProductNames.learned = emptyMap()
        file.delete()
    }

    private fun load(): MutableMap<String, Entry> {
        val out = mutableMapOf<String, Entry>()
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return out
        for (key in root.keys()) {
            if (key == CATEGORY_KEY || key == NAMES_KEY) continue
            val o = root.optJSONObject(key) ?: continue
            val rules = o.optJSONArray("rules")?.let { a -> (0 until a.length()).mapNotNull { ChoiceRule.decode(a.optString(it)) } }.orEmpty()
            val examples = o.optJSONArray("examples")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val x = a.optJSONObject(i) ?: return@mapNotNull null
                    fun s(k: String) = if (x.isNull(k)) null else x.optString(k, "").ifEmpty { null }
                    AiReader.RowExample(
                        s("row") ?: return@mapNotNull null, s("code"), s("colli"), s("description") ?: "", s("unit"),
                        s("quantity"), s("price"), s("amount"), s("vat"),
                    )
                }
            }.orEmpty()
            out[key] = Entry(rules.toMutableSet(), examples, SupplierLayout.decode(o.optString("layout", "")))
        }
        return out
    }

    private fun save() {
        val root = JSONObject()
        entries.forEach { (key, e) ->
            val o = JSONObject()
            o.put("rules", JSONArray(e.rules.map { it.encode() }))
            o.put("examples", JSONArray(e.examples.map { x ->
                JSONObject().apply {
                    put("row", x.rowText); put("code", x.code ?: JSONObject.NULL); put("colli", x.colli ?: JSONObject.NULL)
                    put("description", x.description); put("unit", x.unit ?: JSONObject.NULL); put("quantity", x.quantity ?: JSONObject.NULL)
                    put("price", x.price ?: JSONObject.NULL); put("amount", x.amount ?: JSONObject.NULL); put("vat", x.vatRate ?: JSONObject.NULL)
                }
            }))
            e.layout?.let { o.put("layout", it.encode()) }
            root.put(key, o)
        }
        root.put(CATEGORY_KEY, JSONObject(categoryWords.mapValues { it.value.key }))
        root.put(NAMES_KEY, JSONObject(nameWords.toMap()))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    private companion object {
        /** Not a supplier key (those start with "vat:" or "name:"). */
        const val CATEGORY_KEY = "#categories"
        const val NAMES_KEY = "#names"
    }
}
