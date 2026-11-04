package com.kitchenreceipts.app.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.theme.LocalStatusColors
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.PeekView

/**
 * A field asked to be shown on the photo: its [label], the row it was read from, its value, and the row of its line
 * ([rowHint], for a value of a line that is not printed there, such as a missing quantity).
 */
data class PeekRequest(val label: String, val source: String?, val value: String, val rowHint: String? = null)

/** Set by the review screen when the document has its photo or PDF: a field's photo button opens it there. */
val LocalFieldPeek = staticCompositionLocalOf<((PeekRequest) -> Unit)?> { null }

/**
 * A page of the document for the photo viewer, in the pixels of [bitmap]. [mark]: the value (or its row) to outline,
 * null when its place was not found (the whole page is shown); [area]: what to show first around it.
 */
class PeekImage(
    val bitmap: Bitmap,
    val page: Int,
    val pageCount: Int,
    val mark: PeekView.Box?,
    val area: PeekView.Box?,
    val lineHeight: Float,
    val exact: Boolean,
)

/**
 * TEMPORARY (item q2b): the KR-IME log of a review screen with a photo. Lines are kept in memory from the screen's start
 * and written to screens/kr-ime.txt (next to the test's screenshots) from its first photo tap on, for 30 s.
 */
object KrIme {
    private val buffer = ArrayList<String>()
    private var file: java.io.File? = null
    private var stopAt = Long.MAX_VALUE
    @Synchronized fun begin() {
        buffer.clear()
        file = null
        stopAt = Long.MAX_VALUE
    }
    @Synchronized fun log(s: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now > stopAt) return
        val line = "$now $s"
        android.util.Log.i("KR-IME", line)
        val f = file
        if (f == null) {
            buffer += line
            if (buffer.size > 300) buffer.removeAt(0)
        } else {
            runCatching { f.appendText(line + "\n") }
        }
    }
    @Synchronized fun photoTapped(filesDir: java.io.File) {
        if (file != null) return
        val f = java.io.File(filesDir, "screens/kr-ime.txt").apply { parentFile?.mkdirs() }
        runCatching { f.writeText(buffer.joinToString("") { it + "\n" }) }
        buffer.clear()
        file = f
        stopAt = android.os.SystemClock.uptimeMillis() + 30_000
    }
}

/** "See on the photo": under a value to check (the replacements' row), and in a field being edited (its icon). */
@Composable
fun PeekChip(onClick: () -> Unit) {
    androidx.compose.material3.AssistChip(
        onClick = onClick,
        label = { Text(stringResource(R.string.peek_button), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.ImageSearch, contentDescription = null, tint = Palette.PhthaloBright) },
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

@Composable
fun PeekIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Outlined.ImageSearch, contentDescription = stringResource(R.string.peek_button), tint = Palette.PhthaloBright)
    }
}

/**
 * Full screen: the page of the document with the value outlined, shown first large around the value (its row and the
 * rows above and below), and never stretched. Buttons to zoom in and out and to see the whole page (pinching and
 * dragging work too). [img] null while the page is drawn; [failed]: it cannot be drawn ([onOpenOriginal] then).
 * [onPage]: another page of the document, whole (offered when the value's place was not found).
 *
 * Drawn over the screen in the screen's own window (the caller puts it last, above the form), not in a dialog: a
 * dialog is a second window, and the field being edited kept its focus and the keyboard in the first one, which took
 * the first Back (the viewer stayed open). The caller shows it once no field is being edited and the keyboard is gone
 * (waiting a moment at most). It takes no focus itself: asking for it gave the screen's view the focus back, and the
 * keyboard came up over the photo with no field being edited (CI 189). Back closes it; taps never reach the form
 * under it.
 */
@Composable
fun FieldPhotoViewer(
    label: String,
    img: PeekImage?,
    failed: Boolean,
    onPage: (Int) -> Unit,
    onOpenOriginal: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Registered after the screen's own Back: while the viewer is open, Back closes it.
    BackHandler(onBack = { KrIme.log("viewer: Back"); onDismiss() }) // TEMPORARY log (item q2b)
    androidx.compose.runtime.DisposableEffect(Unit) { KrIme.log("viewer shown"); onDispose { KrIme.log("viewer gone") } } // TEMPORARY (item q2b)
    Surface(
        Modifier.fillMaxSize(),
        // A Surface takes every touch on it: nothing reaches the form under the viewer.
        color = Palette.Black,
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val title = when {
                    img == null || img.mark == null -> stringResource(R.string.peek_title_page, label)
                    img.exact -> stringResource(R.string.peek_title, label)
                    else -> stringResource(R.string.peek_title_row, label)
                }
                Text(
                    title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = { KrIme.log("viewer: Close tapped"); onDismiss() }, modifier = Modifier.size(56.dp)) { // TEMPORARY log (item q2b)
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                }
            }
            when {
                img != null -> PageView(label, img, onPage, Modifier.weight(1f))
                failed -> Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.peek_failed), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = onOpenOriginal, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.peek_open_original)) }
                }
                else -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun PageView(label: String, img: PeekImage, onPage: (Int) -> Unit, modifier: Modifier) {
    val image = remember(img.bitmap) { img.bitmap.asImageBitmap() }
    val outline = LocalStatusColors.current.uncertainBorder
    val density = LocalDensity.current
    // One line of print about 26dp tall on the screen: read without zooming.
    val readable = if (img.lineHeight > 0f) with(density) { 26.dp.toPx() } / img.lineHeight else 0f
    val strokePx = with(density) { 3.dp.toPx() }
    val description = stringResource(R.string.peek_picture, label)
    Column(modifier) {
        if (img.mark == null) Note(stringResource(R.string.peek_whole_page))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val frame = PeekView.Frame(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), img.bitmap.width.toFloat(), img.bitmap.height.toFloat())
            fun first(): PeekView =
                if (img.mark != null && img.area != null) PeekView.focus(frame, img.area, img.mark, readable) else PeekView.whole(frame)
            var view by remember(img, frame) { mutableStateOf(first()) }
            var wholeShown by remember(img) { mutableStateOf(img.mark == null) }
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(img, frame) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            view = PeekView.pan(PeekView.zoom(view, zoom, centroid.x, centroid.y, frame), pan.x, pan.y, frame)
                        }
                    }
                    .semantics { contentDescription = description },
            ) {
                val v = view
                drawImage(
                    image,
                    dstOffset = IntOffset(v.x.toInt(), v.y.toInt()),
                    dstSize = IntSize((img.bitmap.width * v.scale).toInt(), (img.bitmap.height * v.scale).toInt()),
                    filterQuality = FilterQuality.Medium,
                )
                img.mark?.let { m ->
                    val b = PeekView.onScreen(v, m)
                    val pad = strokePx * 2
                    drawRect(
                        outline, topLeft = Offset(b.left - pad, b.top - pad), size = Size(b.width + 2 * pad, b.height + 2 * pad),
                        style = Stroke(width = strokePx),
                    )
                }
            }
            // The buttons, for zooming without pinching: big, over the bottom of the picture.
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Palette.Black.copy(alpha = 0.85f)).padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
            ) {
                val cx = frame.viewW / 2
                val cy = frame.viewH / 2
                if (img.mark == null && img.pageCount > 1) {
                    IconButton(onClick = { onPage(img.page - 1) }, enabled = img.page > 0, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.viewer_prev))
                    }
                    Text(stringResource(R.string.peek_page, img.page + 1, img.pageCount), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { onPage(img.page + 1) }, enabled = img.page < img.pageCount - 1, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.viewer_next))
                    }
                }
                IconButton(onClick = { view = PeekView.zoom(view, 1f / ZOOM_STEP, cx, cy, frame) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.ZoomOut, contentDescription = stringResource(R.string.peek_zoom_out))
                }
                IconButton(onClick = { KrIme.log("viewer: zoom in tapped"); view = PeekView.zoom(view, ZOOM_STEP, cx, cy, frame) }, modifier = Modifier.size(56.dp)) { // TEMPORARY log (item q2b)
                    Icon(Icons.Outlined.ZoomIn, contentDescription = stringResource(R.string.peek_zoom_in))
                }
                if (img.mark != null) {
                    // The whole page, and back to the value.
                    TextButton(
                        onClick = { view = if (wholeShown) first() else PeekView.whole(frame); wholeShown = !wholeShown },
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) {
                        Icon(if (wholeShown) Icons.Outlined.CenterFocusStrong else Icons.Outlined.FitScreen, contentDescription = null)
                        Text(
                            stringResource(if (wholeShown) R.string.peek_back_to_value else R.string.peek_whole),
                            modifier = Modifier.padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Each tap on the zoom buttons. */
private const val ZOOM_STEP = 1.6f
