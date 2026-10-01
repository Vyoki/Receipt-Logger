package com.kitchenreceipts.app.learning

import android.content.Context
import com.kitchenreceipts.core.AiReader
import com.kitchenreceipts.core.ChoiceRule
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
        file.delete()
    }

    private fun load(): MutableMap<String, Entry> {
        val out = mutableMapOf<String, Entry>()
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return out
        for (key in root.keys()) {
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
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }
}
