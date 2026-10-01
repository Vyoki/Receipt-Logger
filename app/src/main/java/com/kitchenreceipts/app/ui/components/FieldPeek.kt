package com.kitchenreceipts.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.theme.LocalStatusColors
import com.kitchenreceipts.app.ui.theme.Palette

/** A field of the review screen got focus ([label], the row it was read from and its value), or lost it (null). */
data class PeekRequest(val label: String, val source: String?, val value: String)

/** Set by the review screen: fields report focus so the matching part of the photo can be shown. */
val LocalFieldPeek = staticCompositionLocalOf<((PeekRequest?) -> Unit)?> { null }

/** A crop of the page photo, and where the value is in it (fractions of the crop's width and height). */
class PeekImage(val bitmap: Bitmap, val markLeft: Float, val markTop: Float, val markRight: Float, val markBottom: Float, val exact: Boolean)

/** The crop with the value outlined. */
@Composable
fun PeekPicture(img: PeekImage, modifier: Modifier = Modifier) {
    val color = LocalStatusColors.current.uncertainBorder
    Box(modifier.aspectRatio(img.bitmap.width.toFloat() / img.bitmap.height.coerceAtLeast(1))) {
        Image(img.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize()) {
            val l = img.markLeft * size.width
            val t = img.markTop * size.height
            drawRect(
                color = color,
                topLeft = Offset(l - 4f, t - 4f),
                size = Size((img.markRight - img.markLeft) * size.width + 8f, (img.markBottom - img.markTop) * size.height + 8f),
                style = Stroke(width = 5f),
            )
        }
    }
}

/**
 * Small strip over the top of the review screen: the part of the photo behind the field being checked, with the
 * value outlined. Kept low (one row of the photo) so it covers as little of the form as possible; tap it to enlarge.
 */
@Composable
fun FieldPeekCard(label: String, img: PeekImage, onEnlarge: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(if (img.exact) R.string.peek_title else R.string.peek_title_row, label)
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = MaterialTheme.shapes.small,
        color = Palette.Panel,
        border = BorderStroke(1.dp, Palette.OrangeDim),
        shadowElevation = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
            Column(
                Modifier.weight(1f).padding(vertical = 4.dp)
                    .clickable(onClickLabel = stringResource(R.string.peek_hint), onClick = onEnlarge),
            ) {
                Text(title, style = MaterialTheme.typography.labelSmall, color = Palette.Orange, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().heightIn(max = 72.dp), contentAlignment = Alignment.CenterStart) {
                    PeekPicture(img)
                }
            }
            Column {
                IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.close), modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onEnlarge, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.OpenInFull, stringResource(R.string.peek_hint), modifier = Modifier.size(18.dp), tint = Palette.PhthaloBright)
                }
            }
        }
    }
}

@Composable
fun FieldPeekDialog(label: String, img: PeekImage, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Panel(Modifier.fillMaxWidth().padding(8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(if (img.exact) R.string.peek_title else R.string.peek_title_row, label), style = MaterialTheme.typography.titleMedium)
                PeekPicture(img, Modifier.fillMaxWidth().padding(vertical = 8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text(stringResource(R.string.close)) }
            }
        }
    }
}
