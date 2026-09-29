package com.kitchenreceipts.core

/**
 * Stores what the OCR read (lines with boxes and word boxes, page by page) as plain text, so a document
 * that was read in the background can be re-parsed after the app is restarted without reading it again.
 * One line per OCR line: "left,top,right,bottom,angle<TAB>text<TAB>words", words as "l,t,r,b<US>text"
 * separated by <RS>; pages separated by a line holding only a form feed.
 */
object OcrLineCodec {
    private const val US = '\u001F'
    private const val RS = '\u001E'
    private const val PAGE = "\u000C"

    fun encode(pages: List<List<OcrLine>>): String = pages.joinToString("\n$PAGE\n") { lines ->
        lines.joinToString("\n") { l ->
            val words = l.words.joinToString(RS.toString()) { w -> "${w.left},${w.top},${w.right},${w.bottom}$US${clean(w.text)}" }
            "${l.left},${l.top},${l.right},${l.bottom},${l.angle}\t${clean(l.text)}\t$words"
        }
    }

    fun decode(text: String): List<List<OcrLine>> {
        if (text.isEmpty()) return emptyList()
        return text.split("\n$PAGE\n").map { page ->
            page.lines().filter { it.isNotEmpty() && it != PAGE }.mapNotNull { row ->
                val parts = row.split('\t')
                if (parts.size < 2) return@mapNotNull null
                val n = parts[0].split(',')
                if (n.size < 5) return@mapNotNull null
                val words = parts.getOrNull(2).orEmpty().split(RS).filter { it.isNotEmpty() }.mapNotNull { w ->
                    val box = w.substringBefore(US).split(',')
                    if (box.size < 4) null else OcrLine(w.substringAfter(US), box[0].toInt(), box[1].toInt(), box[2].toInt(), box[3].toInt())
                }
                OcrLine(parts[1], n[0].toInt(), n[1].toInt(), n[2].toInt(), n[3].toInt(), n[4].toFloat(), words)
            }
        }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace(US, ' ').replace(RS, ' ')
}

/**
 * How much of a photo the AI needs to see: the area that holds text (the table and grey background around the
 * paper are cut away) at a size where the print is comfortably readable. Fewer image pieces = a faster AI:
 * both "looking at the photo" and "writing" get quicker when the prompt is shorter.
 */
data class AiImagePlan(val left: Int, val top: Int, val right: Int, val bottom: Int, val scale: Double) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val outWidth: Int get() = (width * scale).toInt().coerceAtLeast(1)
    val outHeight: Int get() = (height * scale).toInt().coerceAtLeast(1)

    companion object {
        /** Height of a text line in the image given to the model, in pixels: small print stays legible. */
        const val TARGET_TEXT_PX = 22.0
        const val MAX_SIDE = 1536
        const val MIN_SIDE = 896

        fun plan(lines: List<OcrLine>, imageWidth: Int, imageHeight: Int): AiImagePlan {
            val text = lines.filter { it.text.isNotBlank() }
            if (text.size < 3) return full(imageWidth, imageHeight)
            val margin = (maxOf(imageWidth, imageHeight) * 0.02).toInt().coerceAtLeast(16)
            val l = (text.minOf { it.left } - margin).coerceAtLeast(0)
            val t = (text.minOf { it.top } - margin).coerceAtLeast(0)
            val r = (text.maxOf { it.right } + margin).coerceAtMost(imageWidth)
            val b = (text.maxOf { it.bottom } + margin).coerceAtMost(imageHeight)
            if (r - l < imageWidth / 4 || b - t < imageHeight / 8) return full(imageWidth, imageHeight)
            // Text height from lines that are clearly horizontal (a tilted line's box is taller than its text).
            val heights = text.filter { it.width > 3 * it.height && kotlin.math.abs(it.angle) < 2f }.map { it.height }.sorted()
                .ifEmpty { text.map { it.height }.sorted() }
            val textPx = heights[heights.size / 2].toDouble()
            val longSide = maxOf(r - l, b - t).toDouble()
            var scale = (TARGET_TEXT_PX / textPx).coerceAtMost(1.0)
            scale = scale.coerceAtMost(MAX_SIDE / longSide)
            scale = scale.coerceAtLeast(minOf(1.0, MIN_SIDE / longSide))
            return AiImagePlan(l, t, r, b, scale)
        }

        private fun full(w: Int, h: Int) = AiImagePlan(0, 0, w, h, minOf(1.0, MAX_SIDE.toDouble() / maxOf(w, h)))
    }
}
