package com.kitchenreceipts.app.diagnostics

import android.content.Context
import android.os.Build
import androidx.core.content.FileProvider
import com.kitchenreceipts.app.settings.AppSettings
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lightweight operation log kept only on this phone (app-private storage, never uploaded).
 *
 * - Writes happen on one low-priority background thread: the UI never waits for the log.
 * - Size is capped: at 512 KB the file rotates, keeping one previous file (about 1 MB total).
 * - Leaves the phone only when the operator taps "Share log" in Settings.
 */
class AppLog(private val context: Context, private val settings: AppSettings) {

    private val dir = File(context.filesDir, "logs").apply { mkdirs() }
    private val current = File(dir, "app-log.txt")
    private val previous = File(dir, "app-log.1.txt")
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    val includeText: Boolean get() = settings.logIncludeText

    /** One line: `2026-09-28 17:11:02.123 IMPORT_DONE pages=2 ocrMs=1840`. */
    fun event(name: String, vararg fields: Pair<String, Any?>) {
        if (!settings.logEnabled) return
        val line = buildString {
            append(time.format(Date())).append(' ').append(name)
            fields.forEach { (k, v) -> if (v != null) append(' ').append(k).append('=').append(v.toString().replace('\n', ' ')) }
        }
        write(line)
    }

    /** A multi-line block (recognised text, list of corrections), indented under the previous event. */
    fun block(title: String, lines: List<String>, isReceiptContent: Boolean = true) {
        if (!settings.logEnabled || lines.isEmpty()) return
        if (isReceiptContent && !settings.logIncludeText) return
        write(buildString {
            append("    ").append(title).append(':')
            lines.forEach { append("\n    | ").append(it) }
        })
    }

    fun error(where: String, t: Throwable) {
        event("ERROR", "where" to where, "type" to t.javaClass.simpleName, "message" to t.message)
        block("stack", stackLines(t), isReceiptContent = false)
    }

    /** Called from the crash handler: written synchronously because the process is about to die. */
    fun crashSync(t: Throwable) {
        if (!settings.logEnabled) return
        runCatching {
            current.appendText(time.format(Date()) + " CRASH type=${t.javaClass.name} message=${t.message}\n" +
                stackLines(t).joinToString("\n") { "    | $it" } + "\n")
        }
    }

    fun sizeBytes(): Long = (if (current.exists()) current.length() else 0) + (if (previous.exists()) previous.length() else 0)

    fun clear() = executor.execute {
        current.delete()
        previous.delete()
    }

    /** Builds one shareable text file (device info + both log files) and returns a content:// URI for it. */
    fun exportForSharing(): android.net.Uri {
        val out = File(File(context.cacheDir, "shared").apply { mkdirs() }, "kitchen-receipts-log.txt")
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        out.writeText(buildString {
            append("Kitchen Receipts operation log\n")
            append("App ").append(pkg.versionName).append(" · Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(") · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Exported ").append(time.format(Date())).append("\n\n")
            if (previous.exists()) append(previous.readText())
            if (current.exists()) append(current.readText())
        })
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    }

    private fun write(text: String) = executor.execute {
        runCatching {
            if (current.exists() && current.length() > MAX_BYTES) {
                previous.delete()
                current.renameTo(previous)
            }
            current.appendText(text + "\n")
        }
    }

    private fun stackLines(t: Throwable): List<String> {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().lines().filter { it.isNotBlank() }.take(25)
    }

    companion object {
        const val MAX_BYTES = 512L * 1024
    }
}
