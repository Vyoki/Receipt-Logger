package com.kitchenreceipts.app.diagnostics

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.kitchenreceipts.core.CheckerCases
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What the checker model learns from (see core CheckerCases): one line per value of every invoice the operator checked
 * and saved, numbers only: no name, number, amount or text of a document. Kept in one file on the phone and shared
 * only by the operator (Settings), for training in their own Google Colab (tools/checker).
 */
class CheckerLog(private val context: Context) {

    private val dir = File(context.filesDir, "checker")
    /** One file per version of the features: lines of another version never mix with these. */
    private val file = File(dir, "cases-v${CheckerCases.VERSION}.csv")

    /** Adds one checked invoice's values (nothing once the file holds [MAX_BYTES]: tens of thousands of invoices). */
    @Synchronized
    fun record(cases: List<CheckerCases.Case>) {
        if (cases.isEmpty()) return
        dir.mkdirs()
        if (file.length() > MAX_BYTES) return
        val header = !file.exists() || file.length() == 0L
        FileOutputStream(file, true).bufferedWriter().use { w ->
            if (header) w.appendLine(CheckerCases.header())
            for (c in cases) w.appendLine(CheckerCases.row(c))
        }
    }

    /** Invoices and values recorded so far (every version's file). */
    @Synchronized
    fun counts(): Pair<Int, Int> {
        var values = 0
        val groups = HashSet<String>()
        for (f in files()) f.useLines { lines ->
            lines.drop(1).forEach { l -> if (l.isNotBlank()) { values++; groups += l.substringBefore(',') } }
        }
        return groups.size to values
    }

    /** One zip of the files and a note, in the cache folder the share sheet reads; null when nothing is recorded. */
    @Synchronized
    fun shareable(): Uri? {
        val all = files()
        if (all.isEmpty()) return null
        val out = File(File(context.cacheDir, "shared").apply { mkdirs() }, "kitchen-receipts-checker.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (f in all) {
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(README.toByteArray())
            zip.closeEntry()
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    }

    private fun files(): List<File> = dir.listFiles { f -> f.name.startsWith("cases-v") && f.name.endsWith(".csv") }?.sortedBy { it.name }.orEmpty()

    companion object {
        const val MAX_BYTES = 40L * 1024 * 1024

        private const val README = """Kitchen Receipts - checker data

One line per value of every invoice you checked and saved: how it was read (flagged or not, where it came from,
whether the document's sums proved it) and whether you kept it or corrected it. Numbers only: no supplier, number,
amount or text of your documents.

To train the checker: open tools/checker/train_checker.ipynb in Google Colab, upload this zip, run all cells.
The model is ready for the phone only when, on invoices it did not learn from, it cleared no wrong value.
"""
    }
}
