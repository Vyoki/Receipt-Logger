package com.kitchenreceipts.app.diagnostics

import android.content.Context
import com.kitchenreceipts.app.data.ReceiptRepository
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.ReadingCheck
import com.kitchenreceipts.core.ReceiptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every saved document is a test the operator already answered. After each app update (and on request) the saved
 * documents are read again with the new version and compared with the saved values; a document the new version reads
 * worse than the version before is listed, and kept with the corrected documents for fixing. All on the phone.
 */
class ReadingChecker(
    private val context: Context,
    private val repo: ReceiptRepository,
    private val readings: ReadingArchive,
    private val settings: AppSettings,
    private val problems: ProblemReports,
    private val log: AppLog,
) {
    data class Result(
        val version: String,
        val previousVersion: String?,
        val at: Long,
        val docs: List<ReadingCheck.Compared>,
    ) {
        val worse: List<ReadingCheck.Compared> get() = docs.filter { it.worse }
        val better: Int get() = docs.count { it.better }
        val percentRight: Int get() = docs.sumOf { it.now.of }.let { n -> if (n == 0) 100 else docs.sumOf { it.now.right } * 100 / n }
    }

    private val file = File(context.filesDir, "reading-check.json")
    private val mutex = Mutex()
    private val _last = MutableStateFlow(runCatching { load() }.getOrNull())
    val last: StateFlow<Result?> = _last.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun appVersion(): String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    /** True when this version has not yet read the saved documents again. */
    fun due(): Boolean = settings.readingCheckVersion != appVersion()

    suspend fun run(): Result? = mutex.withLock {
        _running.value = true
        try {
            withContext(Dispatchers.Default) { check() }
        } catch (e: Exception) {
            log.error("readingCheck", e)
            null
        } finally {
            _running.value = false
        }
    }

    private suspend fun check(): Result {
        val version = appVersion()
        val before = _last.value
        // Scores of the previous version: the last check if it was another version, else what that check compared with.
        val sameVersion = before?.version == version
        val previousScores: Map<Long, Int?> = before?.docs?.associate { it.documentId to (if (sameVersion) it.before else it.now.right) }.orEmpty()
        val docs = repo.readingCheckDocuments()
        readings.deleteExcept(docs.map { it.id }.toSet())
        val options = settings.parseOptions()
        val compared = docs.filter { it.mimeType != FileStore.MIME_XML }.mapNotNull { d ->
            // The reading as the camera saw it; for documents saved before readings were kept, the recognised text.
            val pages = readings.load(d.id)
            val parsed = when {
                pages != null -> runCatching { ReceiptParser.parsePages(pages, options) }.getOrNull()
                !d.ocrText.isNullOrBlank() -> runCatching { ReceiptParser.parse(d.ocrText, options) }.getOrNull()
                else -> null
            } ?: return@mapNotNull null
            val score = ReadingCheck.score(d.confirmed, parsed)
            ReadingCheck.Compared(d.id, d.label, score, previousScores[d.id])
        }
        val previous = if (sameVersion) before?.previousVersion else before?.version
        val result = Result(version, previous, System.currentTimeMillis(), compared)
        save(result)
        _last.value = result
        settings.readingCheckVersion = version
        log.event(
            "READING_CHECK", "version" to version, "documents" to compared.size, "percentRight" to result.percentRight,
            "better" to result.better, "worse" to result.worse.size,
        )
        // Kept once per version (running the check again on the same version adds nothing new).
        if (!sameVersion) for (w in result.worse) {
            problems.record(
                w.documentId, w.label,
                listOf("Reading check: version $version reads this document worse than ${result.previousVersion ?: "the version before"}") + w.now.differences,
                null,
            )
        }
        return result
    }

    private fun save(r: Result) {
        val o = JSONObject()
            .put("version", r.version).put("previous", r.previousVersion ?: JSONObject.NULL).put("at", r.at)
            .put("docs", JSONArray(r.docs.map { c ->
                JSONObject().put("id", c.documentId).put("label", c.label).put("right", c.now.right).put("of", c.now.of)
                    .put("before", c.before ?: JSONObject.NULL).put("diff", JSONArray(c.now.differences))
            }))
        file.writeText(o.toString())
    }

    private fun load(): Result? {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        val arr = o.getJSONArray("docs")
        val docs = (0 until arr.length()).map { i ->
            val x = arr.getJSONObject(i)
            val diff = x.getJSONArray("diff").let { a -> (0 until a.length()).map { a.getString(it) } }
            ReadingCheck.Compared(
                x.getLong("id"), x.getString("label"), ReadingCheck.Score(x.getInt("right"), x.getInt("of"), diff),
                if (x.isNull("before")) null else x.getInt("before"),
            )
        }
        return Result(o.getString("version"), if (o.isNull("previous")) null else o.getString("previous"), o.getLong("at"), docs)
    }
}
