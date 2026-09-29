package com.kitchenreceipts.app.ai

import android.graphics.Bitmap
import com.kitchenreceipts.core.AiReader
import com.kitchenreceipts.core.ParseOptions
import com.kitchenreceipts.core.ParsedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.coroutineContext

/** What happened on one page, for the log and the review screen. */
data class AiPageResult(val parsed: ParsedDocument?, val raw: String, val error: String?, val stats: String, val millis: Long)

/**
 * Runs the AI model on document pages. Load once per document ([open]), read the pages, then [close] to give
 * the memory back. Everything runs on the phone's processor; nothing leaves the phone.
 */
class AiPageReader private constructor(private val handle: Long) : Closeable {

    /**
     * Reads one page. [ocrText] is what the regular OCR read on it (the model uses it to check digits).
     * [onProgress]: stage 0 = looking at the photo, 1 = writing (count grows).
     */
    suspend fun read(page: Bitmap, ocrText: String, options: ParseOptions, onProgress: (stage: Int, count: Int) -> Unit): AiPageResult {
        val started = System.currentTimeMillis()
        val (rgb, w, h) = withContext(Dispatchers.Default) { toRgb(page, MAX_SIDE) }
        val out = coroutineScope {
            // If the import is cancelled, tell the native loop to stop (it checks between steps).
            val watcher = launch { try { awaitCancellation() } finally { NativeAi.nativeCancel(handle) } }
            val ctx = coroutineContext
            val r = withContext(Dispatchers.Default) {
                NativeAi.nativeGenerate(
                    handle, rgb, w, h, AiReader.instruction(ocrText), AiReader.GRAMMAR, MAX_TOKENS, N_CTX,
                ) { stage, count -> onProgress(stage, count); ctx.isActive }
            }
            watcher.cancel()
            r
        }
        val text = out[0]
        val error = out[1].ifEmpty { null }
        val parsed = if (error == null) AiReader.decode(text)?.let { AiReader.toParsed(it, ocrText, options) } else null
        return AiPageResult(parsed, text, error ?: if (parsed == null) "unreadable answer" else null, out[2], System.currentTimeMillis() - started)
    }

    override fun close() = NativeAi.nativeFree(handle)

    companion object {
        /** Long side of the image given to the model: enough for small print, bounded time and memory. */
        const val MAX_SIDE = 1536
        const val MAX_TOKENS = 3500
        const val N_CTX = 8192

        /** Loads the model (a few seconds). Throws with a readable message if it cannot. */
        suspend fun open(store: AiModelStore, nativeLibDir: String): AiPageReader = withContext(Dispatchers.Default) {
            check(NativeAi.available) { "The AI reader is not available on this phone" }
            check(store.installed) { "No AI model installed" }
            val cores = Runtime.getRuntime().availableProcessors()
            val threads = (cores - 2).coerceIn(2, 6)
            val err = arrayOfNulls<String>(1)
            val h = NativeAi.nativeLoad(nativeLibDir, store.modelFile.absolutePath, store.mmprojFile.absolutePath, threads, err)
            if (h == 0L) throw IllegalStateException(err[0] ?: "The AI model could not be loaded")
            AiPageReader(h)
        }

        /** Scales the page down to [maxSide] and returns packed RGB bytes. */
        fun toRgb(src: Bitmap, maxSide: Int): Triple<ByteArray, Int, Int> {
            val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            if (bmp !== src) bmp.recycle()
            val out = ByteArray(w * h * 3)
            var j = 0
            for (c in px) {
                out[j++] = (c shr 16 and 0xFF).toByte()
                out[j++] = (c shr 8 and 0xFF).toByte()
                out[j++] = (c and 0xFF).toByte()
            }
            return Triple(out, w, h)
        }
    }
}
