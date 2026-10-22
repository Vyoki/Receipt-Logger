package com.kitchenreceipts.app.learning

import android.content.Context
import com.kitchenreceipts.core.AiReader
import com.kitchenreceipts.core.Categories
import com.kitchenreceipts.core.Category
import com.kitchenreceipts.core.ChoiceRule
import com.kitchenreceipts.core.ProductNames
import com.kitchenreceipts.core.SupplierCategory
import com.kitchenreceipts.core.SupplierLayout
import com.kitchenreceipts.core.SupplierLayouts
import com.kitchenreceipts.core.SupplierMemory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the app has learned from the operator, per supplier (see core SupplierMemory): the choices made when a line
 * could be read two ways, and a few confirmed lines shown to the AI as examples. Kept in the app's private storage
 * on this phone, and in the operator's backup file (see BackupManager).
 *
 * Also what each supplier sells (see core SupplierCategory), under the keys of SupplierCategories.memoryKeys: the VAT
 * number, and the name only as an unreadable code. It outlives the supplier's documents; Sellers has its Forget.
 */
class LearningStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "learning.json"))

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

    /** A supplier's category as the operator chose it, and when (milliseconds). */
    data class RememberedCategory(val category: SupplierCategory, val at: Long)

    /** The suppliers' categories by memory key; a new map on every change, so screens follow it. */
    private val supplierCategories: MutableStateFlow<Map<String, RememberedCategory>> by lazy { MutableStateFlow(loadSupplierCategories()) }

    /** The remembered supplier categories by key ("vat:…" or "name#…"); reads the file on first use. */
    val supplierCategoryMemory: StateFlow<Map<String, RememberedCategory>> get() = supplierCategories

    fun supplierCategory(key: String): RememberedCategory? = synchronized(lock) { supplierCategories.value[key] }

    /** The operator chose [category] for a supplier: remembered under every one of its [keys]. */
    fun rememberSupplierCategory(keys: List<String>, category: SupplierCategory, at: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (keys.isEmpty() || keys.all { supplierCategories.value[it]?.category == category }) return@synchronized
        supplierCategories.value = supplierCategories.value + keys.associateWith { RememberedCategory(category, at) }
        save()
    }

    /** "Forget this supplier's category": every one of its [keys] goes. */
    fun forgetSupplierCategory(keys: List<String>) = synchronized(lock) {
        if (keys.none { it in supplierCategories.value }) return@synchronized
        supplierCategories.value = supplierCategories.value - keys.toSet()
        save()
    }

    private fun loadSupplierCategories(): Map<String, RememberedCategory> {
        // A list, not an object: versions before it took every object at the top as a supplier's entry.
        val a = runCatching { JSONObject(file.readText()).optJSONArray(SUPPLIER_CATEGORIES_KEY) }.getOrNull() ?: return emptyMap()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("k").ifBlank { null } ?: return@mapNotNull null
            val category = SupplierCategory.fromKey(o.optString("c")) ?: return@mapNotNull null
            key to RememberedCategory(category, o.optLong("t"))
        }.toMap()
    }

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

    /** Forgets the reading choices, examples, layouts and words. Suppliers' categories stay (Sellers has their Forget). */
    fun clear() = synchronized(lock) {
        entries.clear()
        categoryWords.clear()
        Categories.learned = emptyMap()
        nameWords.clear()
        ProductNames.learned = emptyMap()
        if (supplierCategories.value.isEmpty()) file.delete() else save()
    }

    private fun load(): MutableMap<String, Entry> {
        val out = mutableMapOf<String, Entry>()
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return out
        for (key in root.keys()) {
            if (key == CATEGORY_KEY || key == NAMES_KEY || key == SUPPLIER_CATEGORIES_KEY) continue
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
        supplierCategories.value.takeIf { it.isNotEmpty() }?.let { m ->
            root.put(SUPPLIER_CATEGORIES_KEY, JSONArray(m.map { (k, v) -> JSONObject().put("k", k).put("c", v.category.key).put("t", v.at) }))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    private companion object {
        /** Not a supplier key (those start with "vat:" or "name:"). */
        const val CATEGORY_KEY = "#categories"
        const val NAMES_KEY = "#names"
        /** [{k: memory key, c: category key, t: when}] (see SupplierCategories.memoryKeys). */
        const val SUPPLIER_CATEGORIES_KEY = "supplier_categories"
    }
}
