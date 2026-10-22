package com.kitchenreceipts.app.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kitchenreceipts.app.KitchenReceiptsApp
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.ui.theme.Motion
import com.kitchenreceipts.app.ui.theme.Palette
import com.kitchenreceipts.core.ScrollbarMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The fast scroller of long lists (design D1): a thumb on the right edge, dragged to move through the list at once.
 * Where it sits and where a drag lands come from core ScrollbarMath (counted in items, not estimated pixels).
 * Hand-built because this Compose version has no draggable scrollbar; the approach is like Now in Android's
 * scrollbar (Apache-2.0), no code copied.
 *
 * Touch: only the thumb is touchable, never the track. The thumb shares every touch with what lies under it, so a
 * tap on it still presses the ✓ or delete button below; only a drag that starts on the thumb is taken over, after
 * the touch slop, so the button lets go and the list does not also scroll by itself (see ThumbDragNode).
 */

object FastScrollbarDefaults {
    /**
     * The one switch left for the device check. true: the thumb shares touches with the rows under it (always
     * draggable). false, the fallback: the thumb takes touches only while it is active (just scrolled or dragged),
     * and lists keep [EndGutter] free at their end so no button sits under it.
     */
    const val SHARE_INPUT: Boolean = true

    /** End padding a list adds to its own, so its rows stay clear of the thumb (0 while touches are shared). */
    val EndGutter: Dp = if (SHARE_INPUT) 0.dp else 32.dp
}

private val ThumbLength = 56.dp
private val IdleWidth = 6.dp
private val ActiveWidth = 8.dp
private val HitWidth = 48.dp
private val HitLength = max(48f, ThumbLength.value).dp
private val EdgeMargin = 4.dp
// The bubble and the hint sit 16dp left of the thumb.
private val BesideThumb = EdgeMargin + ActiveWidth + 16.dp
private const val ACTIVE_AFTER_MS = 2500L
private const val HINT_MS = 6000L

// Idle: grey at about 70% on black, so the thumb is never invisible.
private val IdleColor = Palette.TextDim.copy(alpha = 0.7f)

/**
 * A fast scroller over a [LazyColumn][androidx.compose.foundation.lazy.LazyColumn] that uses [state]. Put it in the
 * Box that holds the list, after the list: it fills the Box and shows its thumb only when the list is 4 or more
 * screens long.
 *
 * [label] names the item at an index for the bubble shown while dragging and for TalkBack (null: "n / N"); it is
 * called often, so it must be quick. [topInset] and [bottomInset] keep the thumb clear of bars over the list.
 * [onGrab] runs when a drag of the thumb starts, [onRelease] when the finger lets go. [actions] are added to the
 * TalkBack actions Top and End.
 */
@Composable
fun BoxScope.FastScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    label: ((Int) -> String?)? = null,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    onGrab: () -> Unit = {},
    actions: List<CustomAccessibilityAction> = emptyList(),
    onRelease: () -> Unit = {},
) {
    // The list is read only here (derived) and while laying out the thumb, so scrolling recomposes nothing else.
    val metrics = remember(state) { derivedStateOf { listMetrics(state) } }
    val scrolling by remember(state) { derivedStateOf { state.isScrollInProgress } }
    // Items differ in height (a big card, short rows): a list is judged long from the most items ever seen on one
    // screen for this item count, so the thumb does not come and go while scrolling.
    var seen by remember(state) { mutableStateOf(0 to 0f) }
    LaunchedEffect(state) {
        snapshotFlow { metrics.value.let { it.total to it.onScreen } }.collect { (total, onScreen) ->
            seen = if (seen.first == total) total to max(seen.second, onScreen) else total to onScreen
        }
    }
    val shown by remember(state) {
        derivedStateOf {
            val m = metrics.value
            val most = if (seen.first == m.total) max(seen.second, m.onScreen) else m.onScreen
            (m.canBack || m.canForward) && ScrollbarMath.isLong(m.total, most)
        }
    }
    // What TalkBack reports: the first item on screen, or the last one at the end.
    val position by remember(state) {
        derivedStateOf { metrics.value.let { if (!it.canForward && it.canBack) it.total - 1 else it.first } }
    }
    val total by remember(state) { derivedStateOf { metrics.value.total } }

    // Drag targets go to the list one at a time; a newer one replaces any not yet applied. No animation.
    val jumps = remember(state) { Channel<Pair<Int, Int>>(Channel.CONFLATED) }
    LaunchedEffect(state, jumps) {
        jumps.applyEach { (index, offset) -> state.scrollToItem(index, offset) }
    }

    val context = LocalContext.current
    val currentLabel by rememberUpdatedState(label)
    val textAt: (Int) -> String = { i -> currentLabel?.invoke(i) ?: context.getString(R.string.scroll_position, i + 1, max(1, total)) }
    ScrollbarFrame(
        modifier = modifier,
        shown = shown,
        fraction = {
            val m = metrics.value
            ScrollbarMath.fraction(m.first, m.firstPart, m.total, m.onScreen, m.canBack, m.canForward)
        },
        scrolling = scrolling,
        position = position,
        lastPosition = max(0, total - 1),
        stateText = textAt(position),
        textAt = textAt,
        dragTo = { f ->
            val m = metrics.value
            val (index, part) = ScrollbarMath.target(f, m.total, m.onScreen)
            val size = m.items.firstOrNull { it.index == index }?.size?.toFloat() ?: m.itemPx
            jumps.trySend(index to (part * size).roundToInt())
            index
        },
        moveTo = { i -> jumps.trySend(i to 0) },
        topInset = topInset,
        bottomInset = bottomInset,
        onGrab = onGrab,
        actions = actions,
        onRelease = onRelease,
    )
}

/**
 * The same fast scroller for a plain scrolling Column ([Modifier.verticalScroll][androidx.compose.foundation.verticalScroll]
 * with [state]). [label] gets the place from 0 (top) to 1 (end); null: a percentage.
 */
@Composable
fun BoxScope.FastScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
    label: ((Float) -> String?)? = null,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    onGrab: () -> Unit = {},
    actions: List<CustomAccessibilityAction> = emptyList(),
) {
    // maxValue is Int.MAX_VALUE until the content is first measured.
    val measured = { state.maxValue in 1 until Int.MAX_VALUE && state.viewportSize > 0 }
    val fraction = { if (measured()) state.value / state.maxValue.toFloat() else 0f }
    val scrolling by remember(state) { derivedStateOf { state.isScrollInProgress } }
    val shown by remember(state) {
        derivedStateOf { measured() && ScrollbarMath.isLong(state.maxValue + state.viewportSize, state.viewportSize.toFloat()) }
    }
    val percent by remember(state) { derivedStateOf { (fraction() * 100).roundToInt() } }
    val jumps = remember(state) { Channel<Int>(Channel.CONFLATED) }
    LaunchedEffect(state, jumps) {
        jumps.applyEach { px -> state.scrollTo(px) }
    }
    val context = LocalContext.current
    val currentLabel by rememberUpdatedState(label)
    val textAt: (Int) -> String = { p -> currentLabel?.invoke(p / 100f) ?: context.getString(R.string.scroll_percent, p) }
    val scrollTo: (Float) -> Unit = { f -> if (measured()) jumps.trySend((f * state.maxValue).roundToInt()) }
    ScrollbarFrame(
        modifier = modifier,
        shown = shown,
        fraction = fraction,
        scrolling = scrolling,
        position = percent,
        lastPosition = 100,
        stateText = textAt(percent),
        textAt = textAt,
        dragTo = { f -> scrollTo(f); (f * 100).roundToInt() },
        moveTo = { p -> scrollTo(p / 100f) },
        topInset = topInset,
        bottomInset = bottomInset,
        onGrab = onGrab,
        actions = actions,
    )
}

/**
 * Applies each jump in turn. A scroll the list refuses because the finger's own scroll still holds it (a higher
 * priority) ends in a CancellationException: that jump is skipped and the next one is tried, instead of ending the
 * loop for good. A real cancellation (the screen closed) still ends it.
 */
private suspend fun <T> ReceiveChannel<T>.applyEach(apply: suspend (T) -> Unit) {
    for (jump in this) {
        try {
            apply(jump)
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }
}

/** The app's settings, or null where there is no app container (a preview). */
private fun settingsOf(context: android.content.Context) =
    (context.applicationContext as? KitchenReceiptsApp)?.container?.settings

/** "ABC S.r.l." → "A": the letter a list sorted by name shows in the scroller's bubble. */
fun scrollInitial(name: String?): String? = name?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString()

/** The list's place, worked out from what is laid out now. */
private class ListMetrics(
    val total: Int,
    val first: Int,
    /** How much of the first item is scrolled away, 0..1. */
    val firstPart: Float,
    /** How many items fit on the screen (parts count). */
    val onScreen: Float,
    val canBack: Boolean,
    val canForward: Boolean,
    val items: List<LazyListItemInfo>,
    /** Average height of the items on screen. */
    val itemPx: Float,
)

private fun listMetrics(state: LazyListState): ListMetrics {
    val info = state.layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty() || info.totalItemsCount == 0) {
        return ListMetrics(info.totalItemsCount, 0, 0f, 0f, false, false, emptyList(), 0f)
    }
    val start = info.viewportStartOffset
    val end = info.viewportEndOffset
    var onScreen = 0f
    var px = 0f
    for (item in items) {
        val size = max(1, item.size)
        onScreen += (min(end, item.offset + size) - max(start, item.offset)).coerceAtLeast(0) / size.toFloat()
        px += size
    }
    val first = state.firstVisibleItemIndex
    val firstSize = items.firstOrNull { it.index == first }?.size ?: 0
    val part = if (firstSize > 0) state.firstVisibleItemScrollOffset / firstSize.toFloat() else 0f
    return ListMetrics(info.totalItemsCount, first, part, onScreen, state.canScrollBackward, state.canScrollForward, items, px / items.size)
}

/**
 * The thumb, the bubble and the one-time hint, for any scrolling container. Positions run from 0 to [lastPosition]
 * (an item index, or a percentage). [fraction] is read only while laying out the thumb. [dragTo] moves the content
 * for a thumb at 0..1 and returns the position reached; [moveTo] moves to a position (TalkBack).
 */
@Composable
private fun BoxScope.ScrollbarFrame(
    modifier: Modifier,
    shown: Boolean,
    fraction: () -> Float,
    scrolling: Boolean,
    position: Int,
    lastPosition: Int,
    stateText: String,
    textAt: (Int) -> String,
    dragTo: (Float) -> Int,
    moveTo: (Int) -> Unit,
    topInset: Dp,
    bottomInset: Dp,
    onGrab: () -> Unit,
    actions: List<CustomAccessibilityAction>,
    onRelease: () -> Unit = {},
) {
    val view = LocalView.current
    val thumbPx = with(LocalDensity.current) { ThumbLength.toPx() }
    var trackPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var dragPosition by remember { mutableIntStateOf(0) }
    val thumbTop: () -> Float = {
        if (dragging) dragTop else ScrollbarMath.thumbTop(fraction(), trackPx.toFloat(), thumbPx)
    }

    // Active while scrolling or dragging, and for a while after: wider, green, and (fallback) touchable.
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) {
            active = true
        } else {
            delay(ACTIVE_AFTER_MS)
            active = false
        }
    }

    // The first long list ever shown on this phone says once what the thumb is for.
    var hint by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        // Like other settings, a failure to read or write it only means no hint.
        val shownBefore = try { settingsOf(context)?.scrollHintShown ?: true } catch (_: Exception) { true }
        if (shownBefore) return@LaunchedEffect
        try { settingsOf(context)?.scrollHintShown = true } catch (_: Exception) { return@LaunchedEffect }
        hint = true
        delay(HINT_MS)
        hint = false
    }

    val currentFraction by rememberUpdatedState(fraction)
    val currentDragTo by rememberUpdatedState(dragTo)
    val currentTextAt by rememberUpdatedState(textAt)
    val currentOnGrab by rememberUpdatedState(onGrab)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentThumbPx by rememberUpdatedState(thumbPx)
    val drag = remember {
        var lastText: String? = null
        fun moveBy(dy: Float) {
            dragTop = (dragTop + dy).coerceIn(0f, max(0f, trackPx - currentThumbPx))
            val p = currentDragTo(ScrollbarMath.fractionAt(dragTop, trackPx.toFloat(), currentThumbPx))
            dragPosition = p
            val text = currentTextAt(p)
            if (lastText != null && text != lastText) tick(view)
            lastText = text
        }
        ThumbDrag(
            onStart = { dy ->
                dragTop = ScrollbarMath.thumbTop(currentFraction(), trackPx.toFloat(), currentThumbPx)
                dragging = true
                hint = false
                lastText = null
                currentOnGrab()
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                moveBy(dy)
            },
            onDrag = ::moveBy,
            onEnd = {
                dragging = false
                currentOnRelease()
            },
        )
    }

    val desc = stringResource(R.string.scrollbar_desc)
    val top = stringResource(R.string.jump_top)
    val end = stringResource(R.string.jump_end)
    val width by animateDpAsState(if (active) ActiveWidth else IdleWidth, Motion.glide(), label = "thumbWidth")
    val color by animateColorAsState(if (active) Palette.PhthaloBright else IdleColor, tween(250), label = "thumbColor")

    Box(modifier.matchParentSize().padding(top = topInset, bottom = bottomInset).onSizeChanged { trackPx = it.height }) {
        AnimatedVisibility(
            visible = shown || dragging, // never taken away under the finger
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbTop().roundToInt()) },
            enter = fadeIn(Motion.enter()),
            exit = fadeOut(Motion.exit()),
        ) {
            Box(
                Modifier
                    .size(HitWidth, HitLength)
                    // The edge's back swipe is turned off only over the thumb, and only while it is in use.
                    .then(if (active) Modifier.systemGestureExclusion() else Modifier)
                    .then(
                        if (FastScrollbarDefaults.SHARE_INPUT || active) ThumbDragElement(FastScrollbarDefaults.SHARE_INPUT, drag)
                        else Modifier,
                    )
                    .semantics {
                        contentDescription = desc
                        stateDescription = stateText
                        progressBarRangeInfo = ProgressBarRangeInfo(position.toFloat(), 0f..max(1, lastPosition).toFloat())
                        setProgress { v -> moveTo(v.roundToInt().coerceIn(0, lastPosition)); true }
                        customActions = listOf(
                            CustomAccessibilityAction(top) { moveTo(0); true },
                            CustomAccessibilityAction(end) { moveTo(lastPosition); true },
                        ) + actions
                    },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .padding(end = EdgeMargin)
                        .size(width, ThumbLength)
                        .background(color, RoundedCornerShape(50))
                        .drawBehind {
                            // A grip mark: three short dark lines across the middle.
                            val line = 1.dp.toPx()
                            val gap = 4.dp.toPx()
                            val inset = 1.5.dp.toPx()
                            for (k in -1..1) {
                                val y = size.height / 2 + k * gap
                                drawLine(GripColor, Offset(inset, y), Offset(size.width - inset, y), line)
                            }
                        },
                )
            }
        }

        var bubbleHeight by remember { mutableIntStateOf(0) }
        AnimatedVisibility(
            visible = dragging,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = BesideThumb)
                .offset { IntOffset(0, besideThumb(thumbTop(), thumbPx, bubbleHeight, trackPx)) },
            enter = fadeIn(Motion.enter()) + scaleIn(Motion.enter(), initialScale = 0.92f),
            exit = fadeOut(Motion.exit()),
        ) {
            Box(
                Modifier
                    .onSizeChanged { bubbleHeight = it.height }
                    .heightIn(min = 56.dp)
                    .widthIn(min = 56.dp, max = 280.dp)
                    .background(Palette.PanelRaised, Pill)
                    .border(1.dp, Palette.PhthaloBorder, Pill)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    // Only for the eyes while dragging: TalkBack reads the same text on the thumb.
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    textAt(dragPosition),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        var hintHeight by remember { mutableIntStateOf(0) }
        AnimatedVisibility(
            visible = shown && hint && !dragging,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = BesideThumb)
                .offset { IntOffset(0, besideThumb(thumbTop(), thumbPx, hintHeight, trackPx)) },
            enter = fadeIn(Motion.enter()) + scaleIn(Motion.enter(), initialScale = 0.92f),
            exit = fadeOut(Motion.exit()),
        ) {
            Row(
                Modifier
                    .onSizeChanged { hintHeight = it.height }
                    .heightIn(min = 48.dp)
                    .widthIn(max = 280.dp)
                    .background(Palette.Phthalo, Pill)
                    .border(1.dp, Palette.PhthaloBorder, Pill)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    // A tip for touch: TalkBack users move the list with the thumb's own actions.
                    .clearAndSetSemantics { },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.scrollbar_hint), style = MaterialTheme.typography.bodyLarge, color = Palette.OnPhthalo)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Palette.OnPhthalo)
            }
        }
    }
}

private val Pill = RoundedCornerShape(28.dp)
private val GripColor = Color.Black.copy(alpha = 0.5f)

/** Top of a pill [height] tall centred on the thumb, kept inside the track. */
private fun besideThumb(thumbTop: Float, thumbPx: Float, height: Int, trackPx: Int): Int =
    (thumbTop + thumbPx / 2 - height / 2f).coerceIn(0f, max(0f, (trackPx - height).toFloat())).roundToInt()

/** A light tick each time the bubble's text changes. */
private fun tick(view: View) {
    val kind = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
    else HapticFeedbackConstants.CLOCK_TICK
    view.performHapticFeedback(kind)
}

/** What a drag of the thumb does: it starts (with the distance moved so far), moves by dy, ends. */
private class ThumbDrag(
    val onStart: (Float) -> Unit,
    val onDrag: (Float) -> Unit,
    val onEnd: () -> Unit,
)

private data class ThumbDragElement(val share: Boolean, val drag: ThumbDrag) : ModifierNodeElement<ThumbDragNode>() {
    override fun create() = ThumbDragNode(share, drag)
    override fun update(node: ThumbDragNode) {
        node.share = share
        node.drag = drag
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "fastScrollThumb"
    }
}

/**
 * The thumb's touch handling. It sees every touch first (the Initial pass) and lets it go on to the list and the
 * row under the thumb: a tap there still presses that row's button. Only once a touch that started on the thumb
 * has moved past the touch slop, mostly up or down, does it take the touch over (consumes it), so the button
 * cancels its press and the list does not scroll by itself; the thumb then follows the finger.
 */
private class ThumbDragNode(var share: Boolean, var drag: ThumbDrag) :
    Modifier.Node(), PointerInputModifierNode, CompositionLocalConsumerModifierNode {

    private var pointer: PointerId? = null
    private var dragging = false
    private var moved = Offset.Zero

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        val id = pointer
        if (id == null) {
            // A new touch on the thumb (one finger only); not consumed, so a tap goes on to what is under it.
            val down = pointerEvent.changes.singleOrNull()?.takeIf { it.changedToDownIgnoreConsumed() } ?: return
            pointer = down.id
            moved = Offset.Zero
            dragging = false
            return
        }
        val change = pointerEvent.changes.firstOrNull { it.id == id }
        if (change == null || !change.pressed) {
            if (dragging) {
                change?.consume()
                drag.onEnd()
            }
            reset()
            return
        }
        if (!dragging) {
            // Something before the thumb took this touch: leave it alone.
            if (change.isConsumed) { reset(); return }
            moved += change.positionChange()
            if (moved.getDistance() <= currentValueOf(LocalViewConfiguration).touchSlop) return
            // Sideways (a back swipe, a row's own gesture): not a scroll, leave it alone.
            if (abs(moved.y) < abs(moved.x)) { reset(); return }
            dragging = true
            change.consume()
            drag.onStart(moved.y)
            return
        }
        val dy = change.positionChange().y
        change.consume()
        if (dy != 0f) drag.onDrag(dy)
    }

    override fun onCancelPointerInput() {
        if (dragging) drag.onEnd()
        reset()
    }

    override fun onDetach() {
        if (dragging) drag.onEnd()
        reset()
    }

    override fun sharePointerInputWithSiblings(): Boolean = share

    private fun reset() {
        pointer = null
        dragging = false
        moved = Offset.Zero
    }
}
