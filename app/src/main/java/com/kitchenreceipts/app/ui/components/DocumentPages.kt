package com.kitchenreceipts.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.appContainer
import com.kitchenreceipts.app.ui.theme.Palette
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private sealed interface PageState {
    data object Loading : PageState
    data class Ready(val bitmap: Bitmap) : PageState
    data class Failed(val message: String) : PageState
}

/**
 * Shows the original document (photo or every page of a PDF), swipe between pages, from [initialPage] (from 0).
 * [zoomable] enables pinch-to-zoom and double-tap to reset (full-screen viewer); a long file there also gets a page
 * bar (Previous, Next, a page slider and "Go to page"), so page 120 of 344 is a few taps away, not 119 swipes.
 */
@Composable
fun DocumentPages(
    relativePath: String,
    mimeType: String,
    pageCount: Int,
    modifier: Modifier = Modifier,
    zoomable: Boolean = false,
    onClick: (() -> Unit)? = null,
    initialPage: Int = 0,
) {
    val renderer = appContainer().pageRenderer
    val pages = pageCount.coerceAtLeast(1)
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, pages - 1)) { pages }
    val pageBar = zoomable && pageCount > PAGE_BAR_FROM
    Column(modifier.clipToBounds().background(Color(0xFF303030))) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx().toInt() }.coerceIn(400, 2000)
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = true) { page ->
                val res = androidx.compose.ui.platform.LocalContext.current.resources
                val state by produceState<PageState>(PageState.Loading, relativePath, page, widthPx) {
                    value = try {
                        PageState.Ready(renderer.renderPage(relativePath, mimeType, page, if (zoomable) widthPx * 2 else widthPx))
                    } catch (e: Exception) {
                        PageState.Failed(com.kitchenreceipts.app.ui.friendlyError(res, e))
                    }
                }
                Box(
                    Modifier.fillMaxSize().let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
                    contentAlignment = Alignment.Center,
                ) {
                    when (val s = state) {
                        PageState.Loading -> CircularProgressIndicator()
                        is PageState.Failed -> Text(stringResource(R.string.page_render_failed, s.message), color = Color.White, modifier = Modifier.padding(16.dp))
                        is PageState.Ready -> if (zoomable) ZoomableImage(s.bitmap) else Image(
                            bitmap = s.bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.original_page, page + 1),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            if (pageCount > 1 && !pageBar) {
                Text(
                    "${pager.currentPage + 1} / $pageCount",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp)
                        .background(Color(0x99000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        if (pageBar) PageBar(pager, pageCount)
    }
}

@OptIn(ExperimentalFoundationApi::class) // transformable(canPan = …) is still experimental
@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
            // Only capture drags while zoomed, so the pager can still swipe between pages.
            .transformable(state, canPan = { scale > 1f })
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

/** From this many pages on, the full-screen viewer has the page bar. */
private const val PAGE_BAR_FROM = 5

/**
 * The page bar of a long file: Previous and Next (56dp), a slider over all the pages with a "p. N" bubble while it is
 * dragged, and "Go to page" (a page number, from 1). Each of them turns the pager to that page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageBar(pager: PagerState, pageCount: Int) {
    val scope = rememberCoroutineScope()
    val source = remember { MutableInteractionSource() }
    val dragging by source.collectIsDraggedAsState()
    val pressed by source.collectIsPressedAsState()
    // Where the slider is while the finger is on it; the pager follows when it is let go.
    var held by remember { mutableStateOf<Float?>(null) }
    var asking by remember { mutableStateOf(false) }
    val current = pager.currentPage
    val shown = held ?: current.toFloat()
    val position = "${current + 1} / $pageCount"
    fun go(page: Int) {
        scope.launch { pager.scrollToPage(page.coerceIn(0, pageCount - 1)) }
    }
    Column(Modifier.fillMaxWidth().background(Palette.Black).padding(horizontal = 4.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(current - 1) }, enabled = current > 0, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.viewer_prev))
            }
            Slider(
                value = shown,
                onValueChange = { v ->
                    // Moved without a finger (TalkBack, a key): the page turns at once.
                    if (!dragging && !pressed) {
                        held = null
                        go(v.roundToInt())
                    } else {
                        held = v
                    }
                },
                onValueChangeFinished = {
                    held?.let { go(it.roundToInt()) }
                    held = null
                },
                valueRange = 0f..(pageCount - 1).toFloat(),
                interactionSource = source,
                thumb = {
                    Box(contentAlignment = Alignment.Center) {
                        SliderDefaults.Thumb(interactionSource = source)
                        if (held != null) PageBubble(stringResource(R.string.scroll_page, shown.roundToInt() + 1))
                    }
                },
                modifier = Modifier.weight(1f).semantics { stateDescription = position },
            )
            IconButton(onClick = { go(current + 1) }, enabled = current < pageCount - 1, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.viewer_next))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(position, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 16.dp))
            OutlinedButton(onClick = { asking = true }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.viewer_go_to_page))
            }
        }
    }
    if (asking) GoToPage(pageCount, onGo = { asking = false; go(it - 1) }, onDismiss = { asking = false })
}

/** "p. 120" above the slider's thumb; it takes no room of its own, so the thumb stays where it is. */
@Composable
private fun PageBubble(text: String) {
    Text(
        text,
        color = Palette.OnPhthalo,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .layout { m, _ ->
                val p = m.measure(Constraints())
                layout(0, 0) { p.place(-p.width / 2, -p.height - 24.dp.roundToPx()) }
            }
            .background(Palette.Phthalo, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** "Go to page": a page number from 1 to [pageCount]; any other number says so. */
@Composable
private fun GoToPage(pageCount: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val n = text.toIntOrNull()
    val valid = n != null && n in 1..pageCount
    val focus = remember { FocusRequester() }
    ConfirmDialog(
        title = stringResource(R.string.viewer_go_to_page),
        text = "",
        confirmLabel = stringResource(R.string.viewer_go),
        onConfirm = { if (n != null && valid) onGo(n) },
        onDismiss = onDismiss,
        confirmEnabled = valid,
        body = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(R.string.viewer_page_number, pageCount)) },
                isError = text.isNotEmpty() && !valid,
                supportingText = if (text.isNotEmpty() && !valid) {
                    { Text(stringResource(R.string.viewer_page_out_of_range, pageCount)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (n != null && valid) onGo(n) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        },
    )
}
