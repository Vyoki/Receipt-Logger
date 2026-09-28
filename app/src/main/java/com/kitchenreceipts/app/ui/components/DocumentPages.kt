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

private sealed interface PageState {
    data object Loading : PageState
    data class Ready(val bitmap: Bitmap) : PageState
    data class Failed(val message: String) : PageState
}

/**
 * Shows the original document (photo or every page of a PDF), swipe between pages.
 * [zoomable] enables pinch-to-zoom and double-tap to reset (full-screen viewer).
 */
@Composable
fun DocumentPages(
    relativePath: String,
    mimeType: String,
    pageCount: Int,
    modifier: Modifier = Modifier,
    zoomable: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val renderer = appContainer().pageRenderer
    val pager = rememberPagerState { pageCount.coerceAtLeast(1) }
    BoxWithConstraints(modifier.clipToBounds().background(Color(0xFF303030))) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx().toInt() }.coerceIn(400, 2000)
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = true) { page ->
            val state by produceState<PageState>(PageState.Loading, relativePath, page, widthPx) {
                value = try {
                    PageState.Ready(renderer.renderPage(relativePath, mimeType, page, if (zoomable) widthPx * 2 else widthPx))
                } catch (e: Exception) {
                    PageState.Failed(e.message ?: e.javaClass.simpleName)
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
        if (pageCount > 1) {
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
