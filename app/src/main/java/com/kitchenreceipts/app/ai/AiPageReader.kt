package com.kitchenreceipts.app.ai

import android.graphics.Bitmap
import com.kitchenreceipts.core.AiImagePlan
import com.kitchenreceipts.core.AiReader
import com.kitchenreceipts.core.OcrLine
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
    suspend fun read(
        page: Bitmap,
        lines: List<OcrLine>,
        linesImageWidth: Int,
        ocrText: String,
        options: ParseOptions,
        onProgress: (stage: Int, count: Int) -> Unit,
    ): AiPageResult {
        val started = System.currentTimeMillis()
        val (rgb, w, h) = withContext(Dispatchers.Default) { prepare(page, lines, linesImageWidth) }
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
        return AiPageResult(parsed, text, error ?: if (parsed == null) "unreadable answer" else null, "${w}x$h ${out[2]}", System.currentTimeMillis() - started)
    }

    /** What the phone's processor offers the AI (for the troubleshooting report): e.g. "NEON = 1 | DOTPROD = 1 | ...". */
    val systemInfo: String by lazy {
        runCatching { NativeAi.nativeSystemInfo() }.getOrDefault("")
            .split('|').map { it.trim() }.filter { it.endsWith("= 1") || it.startsWith("CPU") }.joinToString(" ").take(200)
    }

    /**
     * Answers one small question: [boxes] of the page (in the coordinates of the OCR [lines], measured on an image
     * [linesImageWidth] wide) are cut out, scaled so the print is readable and stacked top to bottom.
     */
    suspend fun readRegions(
        page: Bitmap,
        lines: List<OcrLine>,
        linesImageWidth: Int,
        boxes: List<com.kitchenreceipts.core.PageBox>,
        instruction: String,
        grammar: String,
        onProgress: (stage: Int, count: Int) -> Unit,
    ): AiPageResult {
        val started = System.currentTimeMillis()
        val (rgb, w, h) = withContext(Dispatchers.Default) { stack(page, lines, linesImageWidth, boxes) }
        val out = coroutineScope {
            val watcher = launch { try { awaitCancellation() } finally { NativeAi.nativeCancel(handle) } }
            val ctx = coroutineContext
            val r = withContext(Dispatchers.Default) {
                NativeAi.nativeGenerate(handle, rgb, w, h, instruction, grammar, REGION_MAX_TOKENS, REGION_N_CTX) { stage, count ->
                    onProgress(stage, count); ctx.isActive
                }
            }
            watcher.cancel()
            r
        }
        val error = out[1].ifEmpty { null }
        return AiPageResult(null, out[0], error, "${w}x$h ${out[2]}", System.currentTimeMillis() - started)
    }

    override fun close() = NativeAi.nativeFree(handle)

    companion object {
        const val MAX_TOKENS = 3500
        const val N_CTX = 8192

        /** Loads the model (a few seconds). Throws with a readable message if it cannot. */
        suspend fun open(store: AiModelStore, nativeLibDir: String): AiPageReader = withContext(Dispatchers.Default) {
            check(NativeAi.available) { "The AI reader is not available on this phone" }
            check(store.installed) { "No AI model installed" }
            val cores = Runtime.getRuntime().availableProcessors()
            // Writing the answer is limited by memory speed: the big cores are enough. Reading the image and the
            // prompt is pure computation: every core helps.
            val threads = (cores - 2).coerceIn(2, 6)
            val batchThreads = cores.coerceIn(2, 8)
            val err = arrayOfNulls<String>(1)
            val h = NativeAi.nativeLoad(nativeLibDir, store.modelFile.absolutePath, store.mmprojFile.absolutePath, threads, batchThreads, err)
            if (h == 0L) throw IllegalStateException(err[0] ?: "The AI model could not be loaded")
            AiPageReader(h)
        }

        /**
         * Cuts the photo to the area with text and scales it so the print is about [AiImagePlan.TARGET_TEXT_PX] tall:
         * the model then sees fewer image pieces and reads faster. [lines] are the OCR boxes, measured on an image
         * [linesImageWidth] pixels wide.
         */
        fun prepare(page: Bitmap, lines: List<OcrLine>, linesImageWidth: Int): Triple<ByteArray, Int, Int> {
            val k = if (linesImageWidth > 0) page.width.toDouble() / linesImageWidth else 1.0
            val scaled = if (k == 1.0) lines else lines.map {
                it.copy(left = (it.left * k).toInt(), top = (it.top * k).toInt(), right = (it.right * k).toInt(), bottom = (it.bottom * k).toInt())
            }
            val plan = AiImagePlan.plan(scaled, page.width, page.height)
            val cropped = if (plan.width == page.width && plan.height == page.height) page
            else Bitmap.createBitmap(page, plan.left, plan.top, plan.width, plan.height)
            val out = if (plan.scale < 1.0) Bitmap.createScaledBitmap(cropped, plan.outWidth, plan.outHeight, true) else cropped
            if (cropped !== page && cropped !== out) cropped.recycle()
            val result = toRgb(out, Int.MAX_VALUE)
            if (out !== page) out.recycle()
            return result
        }

        const val REGION_MAX_TOKENS = 400
        const val REGION_N_CTX = 4096

        /** Cuts [boxes] out of the page, scales them like [prepare] would and stacks them with a white gap. */
        fun stack(page: Bitmap, lines: List<OcrLine>, linesImageWidth: Int, boxes: List<com.kitchenreceipts.core.PageBox>): Triple<ByteArray, Int, Int> {
            val k = if (linesImageWidth > 0) page.width.toDouble() / linesImageWidth else 1.0
            val scaledLines = if (k == 1.0) lines else lines.map {
                it.copy(left = (it.left * k).toInt(), top = (it.top * k).toInt(), right = (it.right * k).toInt(), bottom = (it.bottom * k).toInt())
            }
            val scale = AiImagePlan.plan(scaledLines, page.width, page.height).scale
            val pieces = boxes.map { b ->
                val l = (b.left * k).toInt().coerceIn(0, page.width - 1)
                val t = (b.top * k).toInt().coerceIn(0, page.height - 1)
                val r = (b.right * k).toInt().coerceIn(l + 1, page.width)
                val bt = (b.bottom * k).toInt().coerceIn(t + 1, page.height)
                val crop = Bitmap.createBitmap(page, l, t, r - l, bt - t)
                // Never wider than the model's usual image, never scaled up.
                val s = minOf(scale, AiImagePlan.MAX_SIDE.toDouble() / crop.width, 1.0)
                if (s < 1.0) Bitmap.createScaledBitmap(crop, maxOf(1, (crop.width * s).toInt()), maxOf(1, (crop.height * s).toInt()), true).also { crop.recycle() } else crop
            }
            val gap = 8
            val w = pieces.maxOf { it.width }
            val h = pieces.sumOf { it.height } + gap * (pieces.size - 1)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            c.drawColor(android.graphics.Color.WHITE)
            var y = 0f
            for (p in pieces) {
                c.drawBitmap(p, 0f, y, null)
                y += p.height + gap
                p.recycle()
            }
            val rgb = toRgb(out, Int.MAX_VALUE)
            out.recycle()
            return rgb
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
