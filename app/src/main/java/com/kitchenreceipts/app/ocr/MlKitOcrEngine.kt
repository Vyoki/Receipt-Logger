package com.kitchenreceipts.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kitchenreceipts.core.OcrLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Google ML Kit Text Recognition v2, Latin script, with the model bundled in the APK
 * (com.google.mlkit:text-recognition). Runs fully on the device: no network, no account,
 * no cost, works in airplane mode from the first launch. Adds roughly 4 MB per ABI to the APK.
 */
class MlKitOcrEngine : OcrEngine {

    override val displayName = "ML Kit (on-device)"
    override val worksOffline = true

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(page: Bitmap): List<OcrLine> {
        // Waits for ML Kit even when the reading is cancelled: the page image is freed right after this returns, and
        // ML Kit must be done with it by then. The result is converted off the main thread.
        val lines = withContext(NonCancellable) { suspendCoroutine { cont -> start(page, cont) } }
        coroutineContext.ensureActive()
        return lines
    }

    private fun start(page: Bitmap, cont: kotlin.coroutines.Continuation<List<OcrLine>>) {
        val executor = Dispatchers.Default.asExecutor()
        recognizer.process(InputImage.fromBitmap(page, 0))
            .addOnSuccessListener(executor) { text ->
                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    // Word boxes let the parser read table columns by position.
                    val words = line.elements.mapNotNull { e ->
                        val b = e.boundingBox ?: return@mapNotNull null
                        // How sure ML Kit is of the word: low on handwriting (see core Handwriting).
                        OcrLine(e.text, b.left, b.top, b.right, b.bottom, e.angle, confidence = e.confidence)
                    }
                    OcrLine(line.text, box.left, box.top, box.right, box.bottom, line.angle, words, line.confidence)
                }
                cont.resume(lines)
            }
            .addOnFailureListener(executor) { e -> cont.resumeWithException(e) }
    }
}
