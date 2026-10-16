package com.kitchenreceipts.app.diagnostics

import android.content.Context
import com.kitchenreceipts.core.OcrLine
import com.kitchenreceipts.core.OcrLineCodec
import java.io.File

/**
 * The OCR reading (text with positions) of every saved document, kept on the phone so that a new version of the app
 * can read the document again exactly as the camera saw it (see ReadingChecker). Photos stay where they are.
 */
class ReadingArchive(context: Context) {

    private val dir = File(context.filesDir, "readings").apply { mkdirs() }

    private fun file(documentId: Long) = File(dir, "doc$documentId.ocr")
    /** The width of each page's picture the OCR read (its positions are in that picture's pixels). */
    private fun widthsFile(documentId: Long) = File(dir, "doc$documentId.widths")

    fun save(documentId: Long, pages: List<List<OcrLine>>, widths: List<Int> = emptyList()) {
        if (pages.all { it.isEmpty() }) return
        runCatching { file(documentId).writeText(OcrLineCodec.encode(pages)) }
        if (widths.size == pages.size && widths.all { it > 0 }) runCatching { widthsFile(documentId).writeText(widths.joinToString(",")) }
    }

    /** The widths saved with the reading; null for a reading saved without them (before they were kept). */
    fun widths(documentId: Long): List<Int>? =
        widthsFile(documentId).takeIf { it.exists() }?.let { f -> runCatching { f.readText().trim().split(',').map { it.trim().toInt() } }.getOrNull() }

    fun load(documentId: Long): List<List<OcrLine>>? =
        file(documentId).takeIf { it.exists() }?.let { f -> runCatching { OcrLineCodec.decode(f.readText()) }.getOrNull() }

    /** The reading of a deleted document (its text must not outlive it, nor go into backups). */
    fun forget(documentId: Long) { file(documentId).delete(); widthsFile(documentId).delete() }

    /** Readings of documents that no longer exist. */
    fun deleteExcept(documentIds: Set<Long>) {
        dir.listFiles()?.forEach { f ->
            val id = f.name.removePrefix("doc").substringBefore('.').toLongOrNull()
            if (id == null || id !in documentIds) f.delete()
        }
    }
}
