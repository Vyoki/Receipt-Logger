package com.kitchenreceipts.app.ocr

import android.graphics.Bitmap
import com.kitchenreceipts.core.OcrLine

/**
 * Replaceable OCR backend. The rest of the app only sees text lines with bounding boxes, so an
 * engine can be swapped (e.g. for a server-side provider) without touching parsing or the UI.
 *
 * An online engine must NOT embed a provider API key in the app: it should call your own backend,
 * which holds the key and forwards the image. See README "Adding an online OCR provider".
 */
interface OcrEngine {
    /** Shown in the review screen ("Read by: ..."). */
    val displayName: String
    val worksOffline: Boolean
    suspend fun recognize(page: Bitmap): List<OcrLine>
}

/** Used for "Enter manually": no OCR at all. */
object NoOcrEngine : OcrEngine {
    override val displayName = "Manual entry"
    override val worksOffline = true
    override suspend fun recognize(page: Bitmap): List<OcrLine> = emptyList()
}
