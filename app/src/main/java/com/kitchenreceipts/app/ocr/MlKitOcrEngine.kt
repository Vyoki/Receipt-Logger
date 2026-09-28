package com.kitchenreceipts.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kitchenreceipts.core.OcrLine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google ML Kit Text Recognition v2, Latin script, with the model bundled in the APK
 * (com.google.mlkit:text-recognition). Runs fully on the device: no network, no account,
 * no cost, works in airplane mode from the first launch. Adds roughly 4 MB per ABI to the APK.
 */
class MlKitOcrEngine : OcrEngine {

    override val displayName = "ML Kit (on-device)"
    override val worksOffline = true

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(page: Bitmap): List<OcrLine> = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(page, 0))
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    // Word boxes let the parser read table columns by position.
                    val words = line.elements.mapNotNull { e ->
                        val b = e.boundingBox ?: return@mapNotNull null
                        OcrLine(e.text, b.left, b.top, b.right, b.bottom, e.angle)
                    }
                    OcrLine(line.text, box.left, box.top, box.right, box.bottom, line.angle, words)
                }
                if (cont.isActive) cont.resume(lines)
            }
            .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    }
}
