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
import java.io.ByteArrayOutputStream
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
    /** [older]: of the [skipped], those read before this version kept the size of the pictures they were read on. */
    data class Result(val uri: Uri?, val exported: Int, val skipped: Int, val older: Int = 0)

    /** Why a document is left out. */
    private enum class Skip { OLDER_READING, OTHER }
    private class Skipped(val why: Skip) : Exception()

    suspend fun export(ownVat: String?, onProgress: (done: Int, of: Int) -> Unit = { _, _ -> }): Result = withContext(Dispatchers.IO) {
        val sellers = db.sellerDao().allOnce().associateBy { it.id }
        val docs = db.documentDao().allDocumentsOnce()
        val lines = db.documentDao().allItemsOnce().groupBy { it.documentId }
        val out = File(File(context.cacheDir, "shared").apply { mkdirs() }, "kitchen-receipts-training.zip")
        var exported = 0
        var skipped = 0
        var older = 0
        try {
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                for ((i, d) in docs.withIndex()) {
                    ensureActive()
                    onProgress(i + 1, docs.size)
                    val entries = try {
                        entries(d, sellers, lines[d.id].orEmpty(), ownVat)
                    } catch (s: Skipped) {
                        skipped++
                        if (s.why == Skip.OLDER_READING) older++
                        continue
                    }
                    // A document goes in whole or not at all.
                    for ((name, bytes) in entries) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                    exported++
                }
                ensureActive()
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write(README.toByteArray())
                zip.closeEntry()
            }
            ensureActive()
        } catch (t: Throwable) {
            // Cancelled or failed: no half-made file of real documents stays on the phone.
            out.delete()
            throw t
        }
        if (exported == 0) { out.delete(); return@withContext Result(null, 0, skipped, older) }
        Result(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out), exported, skipped, older)
    }

    /**
     * The files of one document (pictures, readings, truth); [Skipped] when it cannot teach the model: an e-invoice, a
     * reading of only some of its pages, a reading saved without the size of the pictures it was made on, a page now
     * drawn with another shape or with positions that do not fit it, arithmetic that does not prove it, or totals not
     * found where they were read.
     */
    private suspend fun entries(
        d: com.kitchenreceipts.app.data.DocumentEntity,
        sellers: Map<Long, com.kitchenreceipts.app.data.SellerEntity>,
        items: List<com.kitchenreceipts.app.data.LineItemEntity>,
        ownVat: String?,
    ): List<Pair<String, ByteArray>> {
        fun skip(why: Skip = Skip.OTHER): Nothing = throw Skipped(why)
        if (d.mimeType == FileStore.MIME_XML) skip()
        val pages = readings.load(d.id) ?: skip()
        val sizes0 = readings.sizes(d.id) ?: skip(Skip.OLDER_READING)
        if (pages.all { it.isEmpty() } || pages.size != d.pageCount || sizes0.size != pages.size) skip()
        val seller = sellers[d.sellerId]
        val doc = TrainingPackage.Doc(
            seller?.name, seller?.vatNumber, d.documentNumber, d.documentDate, d.subtotalCents, d.vatCents, d.totalCents,
            items.sortedBy { it.position }.map { l ->
                TrainingPackage.Line(
                    description = l.originalDescription, packages = l.packages, quantity = l.quantity, unit = l.unit,
                    price = l.unitPrice, amountCents = l.lineTotalCents, vatRate = l.vatRate, lot = l.lotNumber, discount = l.discount,
                    // A discount or charge line: an amount alone ("Sconto 4%", "Spese di trasporto", "Bollo").
                    adjustment = l.quantity == null && l.unitPrice == null &&
                        (Adjustments.isAdjustment(l.originalDescription, l.lineTotalCents, l.quantity) || (l.lineTotalCents ?: 0) < 0),
                )
            },
        )
        if (TrainingPackage.whyNot(doc) != null || TrainingPackage.whyNotReading(doc, pages) != null) skip()
        val dir = "doc${d.id}"
        val out = mutableListOf<Pair<String, ByteArray>>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        val read = mutableListOf<List<com.kitchenreceipts.core.OcrLine>>()
        try {
            for (p in pages.indices) {
                // The page as it is read; the OCR positions are scaled to it from the picture they were made on (a
                // second reading works on a smaller, enhanced picture). Drawn with another shape (the app's way of
                // straightening photos changed since): the positions would point elsewhere.
                val bmp = renderer.renderForReading(d.filePath, d.mimeType, p, ImportProcessor.OCR_LONG_SIDE)
                try {
                    val (w0, h0) = sizes0[p]
                    val shape = bmp.width.toDouble() / bmp.height
                    if (kotlin.math.abs(shape - w0.toDouble() / h0) > shape * 0.01) skip()
                    val lines = TrainingPackage.scaled(pages[p], bmp.width.toDouble() / w0)
                    if (!TrainingPackage.fits(lines, bmp.width, bmp.height)) skip()
                    sizes += bmp.width to bmp.height
                    read += lines
                    out += "$dir/page${suffix(p)}.jpg" to ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                    out += "$dir/reading${suffix(p)}.txt" to TrainingPackage.reading(lines).toByteArray()
                } finally {
                    bmp.recycle()
                }
            }
            out += "$dir/truth.json" to TrainingPackage.truth(doc, read, sizes, ownVat?.ifBlank { null }).toByteArray()
            return out
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Skipped) {
            throw e
        } catch (e: Exception) {
            skip()
        }
    }

    /** A package made earlier is still on the phone. */
    fun exists(): Boolean = File(File(context.cacheDir, "shared"), "kitchen-receipts-training.zip").exists()

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
