package com.kitchenreceipts.core

import java.util.concurrent.ConcurrentHashMap

/**
 * A pattern written inside a function is compiled once and reused (compiling costs far more than matching, and
 * many of these run for every line or word of every document). Regex objects are immutable and thread-safe.
 */
private val COMPILED = ConcurrentHashMap<String, Regex>()

internal fun rx(pattern: String): Regex = COMPILED.getOrPut(pattern) { Regex(pattern) }

/**
 * Remembers the last [max] results of a pure function of a string (normalised names, signatures): products,
 * suppliers and descriptions repeat on every screen and every document, so most calls are answered from here.
 */
internal class Memo<V>(private val max: Int = 4096, private val compute: (String) -> V) {
    private val map = object : LinkedHashMap<String, V>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
    }

    operator fun invoke(key: String): V {
        synchronized(map) { if (map.containsKey(key)) @Suppress("UNCHECKED_CAST") return map[key] as V }
        val v = compute(key)
        synchronized(map) { map[key] = v }
        return v
    }
}
