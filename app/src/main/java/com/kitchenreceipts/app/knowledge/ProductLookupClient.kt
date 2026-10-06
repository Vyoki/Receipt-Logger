package com.kitchenreceipts.app.knowledge

import com.kitchenreceipts.app.diagnostics.AppLog
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.ProductLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Online product lookup in Open Food Facts (a free public product database). Allowed only when the network setting
 * is Hybrid or Automatic AND its own switch is on. Sends only the product words of [ProductLookup.query]: never amounts, prices, dates, document or VAT numbers, or company names. Answers are suggestions for
 * product fields only.
 */
class ProductLookupClient(private val settings: AppSettings, private val log: AppLog) {

    fun available(): Boolean = settings.networkMode != AppSettings.NetworkMode.OFFLINE && settings.onlineProductLookup

    /** The exact words that would be sent for this product, or null when nothing may be sent. */
    fun searchedFor(productName: String?): String? = if (available()) ProductLookup.query(productName) else null

    suspend fun lookup(productName: String?): List<ProductLookup.Suggestion>? = withContext(Dispatchers.IO) {
        val q = searchedFor(productName) ?: return@withContext null
        runCatching {
            val url = "$BASE/cgi/search.pl?search_terms=${URLEncoder.encode(q, "UTF-8")}&search_simple=1&action=process&json=1&page_size=5&fields=$FIELDS"
            val o = JSONObject(get(url))
            val products = o.optJSONArray("products")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            products.mapNotNull { p ->
                val name = p.optString("product_name_it").ifBlank { p.optString("product_name") }.trim()
                if (name.isBlank()) return@mapNotNull null
                val tags = p.optJSONArray("categories_tags")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                ProductLookup.Suggestion(
                    name = name.take(80),
                    brand = p.optString("brands").split(',').firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.take(60),
                    packSize = p.optString("quantity").trim().takeIf { it.isNotBlank() }?.take(30),
                    category = ProductLookup.category(tags),
                    barcode = p.optString("code").takeIf { it.isNotBlank() },
                )
            }.distinctBy { listOf(it.name.lowercase(), it.brand?.lowercase(), it.packSize) }.take(5)
        }.onSuccess { log.event("PRODUCT_LOOKUP", "words" to q.split(' ').size, "answers" to it.size) }
            .onFailure { log.error("productLookup", it) }
            .getOrNull()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.useCaches = false
            // Open Food Facts asks apps to name themselves; nothing about the phone is included.
            c.setRequestProperty("User-Agent", "KitchenReceipts/1.0 (github.com/Vyoki/Receipt-Logger)")
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.use { s -> s.bufferedReader().readText().also { require(it.length < 2_000_000) } }
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val BASE = "https://world.openfoodfacts.org"
        const val FIELDS = "code,product_name,product_name_it,brands,quantity,categories_tags"
    }
}
