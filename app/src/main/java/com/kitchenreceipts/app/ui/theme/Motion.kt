package com.kitchenreceipts.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * How the app moves: one soft glide everywhere (springs that settle without bouncing), so screens, cards and lists
 * feel like one piece. Change it here, not screen by screen.
 */
object Motion {
    /** Things that move a distance: cards growing, rows sliding into place. */
    fun <T> glide() = spring<T>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)

    /** Sizes (a card opening or folding). */
    val size = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold)

    /** Rows of a list sliding to their new place. */
    val place = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)

    /** Screens coming in: fast start, long gentle landing (Material's "emphasized" curve). */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun <T> enter() = tween<T>(durationMillis = 420, easing = Emphasized)
    fun <T> exit() = tween<T>(durationMillis = 220, easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f))
}

/** Sinks a little under the finger and glides back when let go: every tap answers at once. */
fun Modifier.pressScale(source: InteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.965f else 1f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Glides in the first time it is shown (fades in while rising a little); [order] makes neighbours follow one after
 * the other. Shown once per screen: coming back to the screen does not play it again.
 */
fun Modifier.glideIn(order: Int = 0): Modifier = composed {
    var played by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!played) {
            delay(order * 45L)
            progress.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessLow))
            played = true
        }
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 28.dp.toPx()
    }
}
