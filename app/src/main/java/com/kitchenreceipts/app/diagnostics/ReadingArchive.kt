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

    fun save(documentId: Long, pages: List<List<OcrLine>>) {
        if (pages.all { it.isEmpty() }) return
        runCatching { file(documentId).writeText(OcrLineCodec.encode(pages)) }
    }

    fun load(documentId: Long): List<List<OcrLine>>? =
        file(documentId).takeIf { it.exists() }?.let { f -> runCatching { OcrLineCodec.decode(f.readText()) }.getOrNull() }

    /** Readings of documents that no longer exist. */
    fun deleteExcept(documentIds: Set<Long>) {
        dir.listFiles()?.forEach { f ->
            val id = f.name.removePrefix("doc").removeSuffix(".ocr").toLongOrNull()
            if (id == null || id !in documentIds) f.delete()
        }
    }
}
