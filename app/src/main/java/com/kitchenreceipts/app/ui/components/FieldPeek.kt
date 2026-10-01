package com.kitchenreceipts.app.ui.components

import android.graphics.Bitmap
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.theme.LocalStatusColors

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

/** Floating card over the review screen: what the photo shows for the field being checked. Tap to enlarge. */
@Composable
fun FieldPeekCard(label: String, img: PeekImage, onEnlarge: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (img.exact) R.string.peek_title else R.string.peek_title_row, label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
            }
            PeekPicture(img, Modifier.fillMaxWidth().heightIn(max = 140.dp).clickable(onClick = onEnlarge))
            Text(stringResource(R.string.peek_hint), style = MaterialTheme.typography.bodySmall, color = com.kitchenreceipts.app.ui.theme.Palette.Orange)
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
