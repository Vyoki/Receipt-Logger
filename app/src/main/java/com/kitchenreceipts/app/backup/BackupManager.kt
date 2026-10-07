package com.kitchenreceipts.app.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.kitchenreceipts.app.AppContainer
import com.kitchenreceipts.app.data.AppDatabase
import com.kitchenreceipts.app.settings.AppSettings
import com.kitchenreceipts.core.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.LocalDateTime
import kotlin.system.exitProcess

/**
 * Backup and restore of everything the app keeps on this phone: the database, the original photos and PDFs, the
 * OCR readings, what the app learned from the operator, the documents corrected by hand, and the settings.
 * Not included: the AI model and the knowledge pack (downloaded again), the log, and documents still being read.
 *
 * The file is written where the operator chooses (a folder, Drive, a USB stick…) and is locked with a password
 * (see core Backup). Nothing is sent anywhere by the app itself.
 */
class BackupManager(private val c: AppContainer) {

    private val context: Context get() = c.appContext
    private val files: File get() = context.filesDir

    data class Made(val documents: Int, val files: Int, val bytes: Long)

    data class Inside(val created: String, val documents: Int, val business: String, val appVersion: String)

    class Busy : Exception("Documents are being read")
    class TooNew : Exception("The backup was made by a newer version of the app")

    /** Writes a backup to [uri] (from the system "Save as" picker). */
    suspend fun create(uri: Uri, password: CharArray): Made = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "backup-db").apply { deleteRecursively(); mkdirs() }
        try {
            val dbFiles = copyDatabase(tmp)
            val docs = countDocuments()
            val entries = ArrayList<Backup.Entry>()
            dbFiles.forEach { f -> entries += Backup.Entry("$DB_DIR/${f.name}") { FileInputStream(f) } }
            val settingsText = Backup.writeProperties(settingsSnapshot())
            entries += Backup.Entry(SETTINGS) { settingsText.byteInputStream() }
            KEPT.forEach { name -> collect(File(files, name), name, entries) }
            val manifest = linkedMapOf(
                "format" to "1",
                "db_version" to AppDatabase.VERSION.toString(),
                "app_version" to com.kitchenreceipts.app.ocr.appVersion,
                "created" to LocalDateTime.now().withNano(0).toString(),
                "documents" to docs.toString(),
                "files" to entries.size.toString(),
                "business" to c.settings.ownBusinessName,
            )
            var bytes = 0L
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write the backup file")
            CountingStream(BufferedOutputStream(out, 256 * 1024)) { bytes = it }.use { s ->
                Backup.write(s, password, manifest, entries.asSequence())
            }
            c.settings.lastBackupAt = System.currentTimeMillis()
            c.log.event("BACKUP_MADE", "documents" to docs, "files" to entries.size, "bytes" to bytes)
            Made(docs, entries.size, bytes)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * Reads the backup at [uri] into a staging folder and checks it completely (password, every chunk, the database).
     * Nothing on the phone is changed. Returns what is inside; then [replaceAndRestart] puts it in place.
     */
    suspend fun prepare(uri: Uri, password: CharArray): Inside = withContext(Dispatchers.IO) {
        if (c.importQueue.busy || c.readingChecker.running.value) throw Busy()
        val stage = File(files, STAGE).apply { deleteRecursively(); mkdirs() }
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the backup file")
            val manifest = input.buffered(256 * 1024).use { s ->
                Backup.read(s, password, onManifest = { m ->
                    if (m["format"] != "1") throw TooNew()
                    if ((m["db_version"]?.toIntOrNull() ?: Int.MAX_VALUE) > AppDatabase.VERSION) throw TooNew()
                }) { name, data ->
                    val target = File(stage, name)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { data.copyTo(it, 64 * 1024) }
                }
            }
            checkDatabase(File(stage, "$DB_DIR/${AppDatabase.NAME}"))
            Inside(
                created = manifest["created"].orEmpty(),
                documents = manifest["documents"]?.toIntOrNull() ?: 0,
                business = manifest["business"].orEmpty(),
                appVersion = manifest["app_version"].orEmpty(),
            )
        } catch (e: Exception) {
            stage.deleteRecursively()
            throw e
        }
    }

    /** Forgets a prepared backup the operator decided not to restore. */
    fun discard() { File(files, STAGE).deleteRecursively() }

    /**
     * Puts the prepared backup in place of what is on the phone and restarts the app. What was on the phone is kept
     * in a side folder until the restored data has opened once, and put back if anything fails half-way.
     */
    fun replaceAndRestart() {
        val stage = File(files, STAGE)
        check(File(stage, "$DB_DIR/${AppDatabase.NAME}").isFile) { "Nothing prepared" }
        if (c.importQueue.busy || c.readingChecker.running.value) throw Busy()
        val previous = File(files, PREVIOUS).apply { deleteRecursively(); mkdirs() }
        val dbPath = context.getDatabasePath(AppDatabase.NAME)
        val moved = ArrayList<Pair<File, File>>() // original place -> side folder
        c.log.event("RESTORE_START")
        runCatching { c.database.close() }
        try {
            // 1. Move what is on the phone aside.
            DB_SUFFIXES.forEach { s -> moveAside(File(dbPath.path + s), File(previous, "$DB_DIR/${dbPath.name}$s"), moved) }
            KEPT.forEach { name -> moveAside(File(files, name), File(previous, name), moved) }
            // 2. Put the backup in place.
            dbPath.parentFile?.mkdirs()
            File(stage, DB_DIR).listFiles()?.forEach { f -> move(f, File(dbPath.parentFile, f.name)) }
            KEPT.forEach { name -> File(stage, name).takeIf { it.exists() }?.let { move(it, File(files, name)) } }
            File(stage, SETTINGS).takeIf { it.isFile }?.let { applySettings(Backup.readProperties(it.readText())) }
            stage.deleteRecursively()
            File(files, RESTORED_FLAG).writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
            c.log.error("restore", e)
            // Put everything back as it was.
            dbPath.parentFile?.listFiles { f -> f.name.startsWith(dbPath.name) }?.forEach { it.delete() }
            KEPT.forEach { name -> File(files, name).deleteRecursively() }
            moved.forEach { (orig, side) -> runCatching { move(side, orig) } }
            previous.deleteRecursively()
            restart()
            throw e
        }
        restart()
    }

    /** At start-up after a restore: the restored database opened, so the old data set aside can go. */
    fun afterStart() {
        val flag = File(files, RESTORED_FLAG)
        if (!flag.exists()) {
            File(files, STAGE).deleteRecursively() // a restore prepared but never confirmed
            return
        }
        runCatching { countDocuments() }
            .onSuccess { n ->
                File(files, PREVIOUS).deleteRecursively()
                flag.delete()
                c.log.event("RESTORE_DONE", "documents" to n)
            }
            .onFailure { c.log.error("restoreCheck", it) }
    }

    private fun restart() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch != null) {
            context.startActivity(Intent.makeRestartActivityTask(launch.component))
        }
        exitProcess(0)
    }

    // ------------------------------------------------------------------ database

    /**
     * A consistent copy of the database: inside a write transaction nothing else can change it, and the main file
     * plus its write-ahead log together are the whole database at that moment.
     */
    private fun copyDatabase(dir: File): List<File> {
        val db = c.database.openHelper.writableDatabase
        runCatching { db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
        val main = context.getDatabasePath(AppDatabase.NAME)
        val out = ArrayList<File>()
        c.database.runInTransaction(Runnable {
            listOf("", "-wal").forEach { s ->
                val f = File(main.path + s)
                if (f.isFile && f.length() > 0) {
                    val copy = File(dir, f.name)
                    f.inputStream().use { i -> copy.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                    out += copy
                }
            }
        })
        check(out.any { it.name == main.name }) { "Database file not found" }
        return out
    }

    private fun countDocuments(): Int =
        c.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM documents").use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** The restored database must open, pass SQLite's own check, and not be from a newer app. */
    private fun checkDatabase(f: File) {
        if (!f.isFile) throw Backup.Damaged("no database")
        val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val ok = db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
            if (!ok) throw Backup.Damaged("database check failed")
            if (db.version > AppDatabase.VERSION) throw TooNew()
            db.rawQuery("SELECT COUNT(*) FROM documents", null).use { it.moveToFirst() }
        } finally {
            db.close()
        }
        // Opening it may have folded the log into the main file; a stale -shm must not travel with it.
        File(f.path + "-shm").delete()
    }

    // ------------------------------------------------------------------ files

    private fun collect(f: File, name: String, into: MutableList<Backup.Entry>) {
        when {
            f.isDirectory -> f.listFiles()?.sortedBy { it.name }?.forEach { child ->
                val n = "$name/${child.name}"
                if (Backup.safeName(n) && !child.name.endsWith(".tmp")) collect(child, n, into)
            }
            f.isFile -> into += Backup.Entry(name) { FileInputStream(f) }
        }
    }

    private fun moveAside(from: File, to: File, moved: MutableList<Pair<File, File>>) {
        if (!from.exists()) return
        move(from, to)
        moved += from to to
    }

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (to.exists()) to.deleteRecursively()
        if (!from.renameTo(to)) {
            // Different file systems (rare): copy, then delete.
            from.copyRecursively(to, overwrite = true)
            from.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ settings

    private fun settingsSnapshot(): Map<String, String> {
        val prefs = context.getSharedPreferences(AppSettings.FILE, Context.MODE_PRIVATE)
        val out = sortedMapOf<String, String>()
        prefs.all.forEach { (k, v) ->
            if (k in NOT_RESTORED) return@forEach
            when (v) {
                is String -> out["s:$k"] = v
                is Boolean -> out["b:$k"] = v.toString()
                is Int -> out["i:$k"] = v.toString()
                is Long -> out["l:$k"] = v.toString()
                is Float -> out["f:$k"] = v.toString()
            }
        }
        return out
    }

    private fun applySettings(map: Map<String, String>) {
        val e = context.getSharedPreferences(AppSettings.FILE, Context.MODE_PRIVATE).edit()
        map.forEach { (key, v) ->
            val k = key.substringAfter(':')
            if (k in NOT_RESTORED || !key.contains(':')) return@forEach
            when (key.substringBefore(':')) {
                "s" -> e.putString(k, v)
                "b" -> e.putBoolean(k, v == "true")
                "i" -> v.toIntOrNull()?.let { e.putInt(k, it) }
                "l" -> v.toLongOrNull()?.let { e.putLong(k, it) }
                "f" -> v.toFloatOrNull()?.let { e.putFloat(k, it) }
            }
        }
        e.commit()
    }

    private class CountingStream(private val inner: java.io.OutputStream, private val report: (Long) -> Unit) : java.io.OutputStream() {
        private var n = 0L
        override fun write(b: Int) { inner.write(b); n++ }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); n += len }
        override fun flush() = inner.flush()
        override fun close() { inner.close(); report(n) }
    }

    companion object {
        private const val DB_DIR = "database"
        private const val SETTINGS = "settings.properties"
        private const val STAGE = "restore-staging"
        private const val PREVIOUS = "restore-previous"
        private const val RESTORED_FLAG = "restore-done.flag"
        private val DB_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

        /** Folders and files under the app's storage that travel with the backup. */
        private val KEPT = listOf("documents", "readings", "problems", "learning.json")

        /** Settings that belong to this phone or this app version, not to the data. */
        private val NOT_RESTORED = setOf("reading_check_version", "knowledge_checked_at", "last_backup_at")
    }
}
