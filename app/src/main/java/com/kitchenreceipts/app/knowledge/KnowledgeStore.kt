package com.kitchenreceipts.app.knowledge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.kitchenreceipts.app.diagnostics.AppLog
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.KnowledgePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The knowledge pack on this phone (see core KnowledgePack): the one bundled with the app, replaced by a newer one
 * opened from a file or downloaded. A download fetches one public file, the same for everyone: no document, no
 * question about a document, and nothing identifying the phone is sent (not even the device model).
 */
class KnowledgeStore(private val context: Context, private val settings: AppSettings, private val log: AppLog) {

    enum class Outcome { UPDATED, ALREADY_CURRENT, NOT_ALLOWED, FAILED }

    private val dir = File(context.filesDir, "knowledge").apply { mkdirs() }
    private val stored = File(dir, "pack.json")

    private val _pack = MutableStateFlow(load())
    val pack: StateFlow<KnowledgePack> = _pack.asStateFlow()

    private fun load(): KnowledgePack {
        val bundled = runCatching { context.assets.open(ASSET).use { parse(it.readCapped()) } }.getOrNull()
        val own = runCatching { stored.takeIf { it.exists() }?.let { parse(it.readText()) } }.getOrNull()
        return KnowledgePack.newer(bundled, own) ?: KnowledgePack.EMPTY
    }

    /** A pack the operator chose from a file (works in every network mode: nothing is connected). */
    suspend fun openFile(uri: Uri): Outcome = withContext(Dispatchers.IO) {
        val p = runCatching {
            context.contentResolver.openInputStream(uri)?.use { parse(it.readCapped()) }
        }.onFailure { log.error("knowledgeOpen", it) }.getOrNull() ?: return@withContext Outcome.FAILED
        keep(p, "file")
    }

    /** Downloads the public pack: only in Hybrid (on request) and Automatic. */
    suspend fun download(): Outcome = withContext(Dispatchers.IO) {
        if (settings.networkMode == AppSettings.NetworkMode.OFFLINE) return@withContext Outcome.NOT_ALLOWED
        settings.knowledgeCheckedAt = System.currentTimeMillis()
        val p = runCatching {
            val c = URL(PACK_URL).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                c.useCaches = false
                c.instanceFollowRedirects = true
                // A neutral name instead of the default, which names the phone's model and Android build.
                c.setRequestProperty("User-Agent", "KitchenReceipts")
                if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                c.inputStream.use { parse(it.readCapped()) }
            } finally {
                c.disconnect()
            }
        }.onFailure { log.error("knowledgeDownload", it) }.getOrNull() ?: return@withContext Outcome.FAILED
        if (p.version <= _pack.value.version) {
            log.event("KNOWLEDGE_CHECKED", "version" to _pack.value.version)
            return@withContext Outcome.ALREADY_CURRENT
        }
        keep(p, "download")
    }

    /** Automatic mode: about once a week, on an unmetered network (Wi-Fi) only. */
    fun autoCheckDue(): Boolean =
        settings.networkMode == AppSettings.NetworkMode.AUTOMATIC &&
            System.currentTimeMillis() - settings.knowledgeCheckedAt > WEEK_MS &&
            unmetered()

    private fun unmetered(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    private fun keep(p: KnowledgePack, from: String): Outcome {
        stored.writeText(toJson(p))
        _pack.value = p
        log.event("KNOWLEDGE_UPDATED", "from" to from, "version" to p.version, "suppliers" to p.suppliers.size)
        return Outcome.UPDATED
    }

    companion object {
        /** The public pack, from the app's own repository (the same file for everyone). */
        const val PACK_URL = "https://raw.githubusercontent.com/Vyoki/Receipt-Logger/main/knowledge/pack.json"
        const val ASSET = "knowledge-pack.json"
        private const val MAX_BYTES = 5 * 1024 * 1024
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

        private fun InputStream.readCapped(): String {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                require(out.size() <= MAX_BYTES) { "knowledge pack too large" }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        /** Reads a pack; entries that are not well formed are left out. Throws when the file is not a pack. */
        fun parse(json: String): KnowledgePack {
            val o = JSONObject(json)
            require(o.optInt("format") == KnowledgePack.FORMAT) { "unknown knowledge pack format" }
            val version = o.getString("version")
            require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(version)) { "bad pack version" }
            val arr = o.optJSONArray("suppliers")
            val suppliers = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val s = arr!!.optJSONObject(i) ?: return@mapNotNull null
                KnowledgePack.Supplier(
                    s.optString("country", "IT").uppercase(),
                    s.optString("vat").filter(Char::isLetterOrDigit).uppercase(),
                    s.optString("name").trim(),
                ).takeIf(KnowledgePack::valid)
            }
            return KnowledgePack(version, suppliers)
        }

        fun toJson(p: KnowledgePack): String = JSONObject()
            .put("format", KnowledgePack.FORMAT)
            .put("version", p.version)
            .put("suppliers", org.json.JSONArray(p.suppliers.map { JSONObject().put("country", it.country).put("vat", it.vat).put("name", it.name) }))
            .toString()
    }
}
