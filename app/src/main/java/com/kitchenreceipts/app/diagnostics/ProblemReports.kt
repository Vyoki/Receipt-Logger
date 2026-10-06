package com.kitchenreceipts.app.diagnostics

import android.content.Context
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Documents the reader got wrong, kept on the phone so they can be fixed all at once later: whenever the operator
 * changes a value before saving (or edits a saved document), the list of changes and the full reading report are
 * stored here. Nothing is sent: the operator shares them with one button when they choose.
 */
class ProblemReports(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "problems").apply { mkdirs() }
    private val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)

    /** Stores one corrected document. "confirmed" entries alone are not a problem: only real changes count. */
    fun record(documentId: Long, seller: String, corrections: List<String>, report: String?) {
        val changes = corrections.filterNot { it.contains(": confirmed ") }
        if (changes.isEmpty()) return
        runCatching {
            val f = File(dir, "${stamp.format(Date())}_doc$documentId.txt")
            f.writeText(buildString {
                append("Document ").append(documentId).append(" · ").append(seller).append('\n')
                append("What was changed before saving:\n")
                changes.forEach { append("  ").append(it).append('\n') }
                append('\n').append(report ?: "(no reading report)").append('\n')
            })
            // Keep the most recent 300.
            dir.listFiles()?.sortedBy { it.name }?.dropLast(300)?.forEach { it.delete() }
        }
    }

    fun count(): Int = dir.listFiles()?.size ?: 0

    /** The stored problems, oldest first: (file name, document id, text). */
    fun all(): List<Triple<String, Long?, String>> = dir.listFiles()?.sortedBy { it.name }?.map { f ->
        Triple(f.name, f.name.substringAfterLast("_doc").removeSuffix(".txt").toLongOrNull(), f.readText())
    }.orEmpty()

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    /** One text file with every stored problem, for the share sheet. */
    fun exportForSharing(appLog: String?): android.net.Uri {
        val out = File(File(context.cacheDir, "shared").apply { mkdirs() }, "kitchen-receipts-problems.txt")
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        out.writeText(buildString {
            append("Kitchen Receipts – documents corrected by hand\n")
            append("App ").append(pkg.versionName).append(" · Android ").append(Build.VERSION.RELEASE).append(" · ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Exported ").append(stamp.format(Date())).append(" · ").append(count()).append(" documents\n")
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                append("\n\n######## ").append(f.name).append(" ########\n").append(f.readText())
            }
            if (appLog != null) append("\n\n######## operation log ########\n").append(appLog)
        })
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    }
}
