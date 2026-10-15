package com.kitchenreceipts.app.diagnostics

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.kitchenreceipts.app.data.AppDatabase
import com.kitchenreceipts.app.files.FileStore
import com.kitchenreceipts.app.files.PageRenderer
import com.kitchenreceipts.app.ocr.ImportProcessor
import com.kitchenreceipts.core.Adjustments
import com.kitchenreceipts.core.TrainingPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The saved documents as training examples for the app's own AI (see core TrainingPackage, tools/training): each
 * page as the camera's reading saw it (the photo flattened as for reading, and the OCR lines with their positions)
 * and the document as the operator checked and saved it. Only documents whose arithmetic proves them, read on this
 * phone (an e-invoice has no photo to learn from).
 *
 * Written to one zip in the app's cache and handed to the share sheet by the operator: nothing leaves the phone
 * otherwise. It holds real documents, for the operator's own training (their Google Drive and Colab).
 */
class TrainingExport(
    private val context: Context,
    private val db: AppDatabase,
    private val readings: ReadingArchive,
    private val renderer: PageRenderer,
) {
    data class Result(val uri: Uri?, val exported: Int, val skipped: Int)

    suspend fun export(ownVat: String?, onProgress: (done: Int, of: Int) -> Unit = { _, _ -> }): Result = withContext(Dispatchers.IO) {
        val sellers = db.sellerDao().allOnce().associateBy { it.id }
        val docs = db.documentDao().allDocumentsOnce()
        val lines = db.documentDao().allItemsOnce().groupBy { it.documentId }
        val out = File(File(context.cacheDir, "shared").apply { mkdirs() }, "kitchen-receipts-training.zip")
        var exported = 0
        var skipped = 0
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for ((i, d) in docs.withIndex()) {
                ensureActive()
                onProgress(i, docs.size)
                val pages = readings.load(d.id)
                if (d.mimeType == FileStore.MIME_XML || pages == null || pages.all { it.isEmpty() } || pages.size > d.pageCount) { skipped++; continue }
                val seller = sellers[d.sellerId]
                val doc = TrainingPackage.Doc(
                    seller?.name, seller?.vatNumber, d.documentNumber, d.documentDate, d.subtotalCents, d.vatCents, d.totalCents,
                    lines[d.id].orEmpty().sortedBy { it.position }.map { l ->
                        TrainingPackage.Line(
                            description = l.originalDescription, packages = l.packages, quantity = l.quantity, unit = l.unit,
                            price = l.unitPrice, amountCents = l.lineTotalCents, vatRate = l.vatRate, lot = l.lotNumber, discount = l.discount,
                            // A discount or charge line: an amount alone ("Sconto 4%", "Spese di trasporto").
                            adjustment = l.quantity == null && l.unitPrice == null && (Adjustments.isDiscount(l.originalDescription) || (l.lineTotalCents ?: 0) < 0),
                        )
                    },
                )
                if (TrainingPackage.whyNot(doc) != null) { skipped++; continue }
                val dir = "doc${d.id}"
                val sizes = mutableListOf<Pair<Int, Int>>()
                val ok = runCatching {
                    for (p in pages.indices) {
                        // The page as it was read: the OCR positions refer to this picture.
                        val bmp = renderer.renderForReading(d.filePath, d.mimeType, p, ImportProcessor.OCR_LONG_SIDE)
                        try {
                            sizes += bmp.width to bmp.height
                            zip.putNextEntry(ZipEntry("$dir/page${suffix(p)}.jpg"))
                            bmp.compress(Bitmap.CompressFormat.JPEG, 90, zip)
                            zip.closeEntry()
                        } finally {
                            bmp.recycle()
                        }
                        zip.putNextEntry(ZipEntry("$dir/reading${suffix(p)}.txt"))
                        zip.write(TrainingPackage.reading(pages[p]).toByteArray())
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("$dir/truth.json"))
                    zip.write(TrainingPackage.truth(doc, pages, sizes, ownVat?.ifBlank { null }).toByteArray())
                    zip.closeEntry()
                }.isSuccess
                if (ok) exported++ else skipped++
            }
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(README.toByteArray())
            zip.closeEntry()
        }
        onProgress(docs.size, docs.size)
        if (exported == 0) { out.delete(); return@withContext Result(null, 0, skipped) }
        Result(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out), exported, skipped)
    }

    /** Deletes the last package from the phone's cache (it holds real documents). */
    fun forget() { File(File(context.cacheDir, "shared"), "kitchen-receipts-training.zip").delete() }

    private fun suffix(p: Int) = if (p == 0) "" else (p + 1).toString()

    private companion object {
        const val README = """Kitchen Receipts - training examples

Real documents saved in the app, one folder each: page.jpg (page2.jpg...) as the app read it, reading.txt
(reading2.txt...) what the camera read with positions, truth.json the document as checked and saved.
Only documents whose lines and totals add up to the cent are included.

These are REAL documents (suppliers, prices, VAT numbers). Keep them private: your own Google Drive and Colab only.
Never put them in a public place or in the app's source code.

Use: in tools/training/train_colab.ipynb, upload this zip at the step "Your own documents".
"""
    }
}
