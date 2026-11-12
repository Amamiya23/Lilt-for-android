package com.lilt.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lilt.app.AppState
import com.lilt.app.LiltViewModel
import com.lilt.app.data.Song
import com.lilt.app.playback.PlaybackSnapshot
import com.lilt.app.ui.theme.Radix
import kotlinx.coroutines.launch

/** 0 is fully open, 1 fully closed; the page beneath reads it too, to recede while the player covers it. */
typealias PlayerPosition = Animatable<Float, AnimationVector1D>

// Critically damped, so the sheet settles without bouncing off the screen edges.
private val OpenSpring = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)
private val CloseSpring = spring<Float>(dampingRatio = 1f, stiffness = 520f)

/** The page beneath the player sinks back a little and rounds its corners as the player rises. */
fun Modifier.recedeBehindPlayer(position: PlayerPosition): Modifier = graphicsLayer {
    val covered = 1f - position.value
    if (covered <= 0f) return@graphicsLayer
    scaleX = 1f - .06f * covered
    scaleY = scaleX
    shape = RoundedCornerShape(24.dp.toPx() * covered)
    clip = true
}

/** One position drives opening, direct manipulation, cancellation and dismissal; a release hands its velocity on. */
@Composable
fun PlayerOverlay(state: AppState, player: PlaybackSnapshot, vm: LiltViewModel, position: PlayerPosition, onMore: (Song) -> Unit) {
    val scope = rememberCoroutineScope()
    var releaseVelocity by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.playerOpen) {
        val velocity = releaseVelocity
        releaseVelocity = 0f
        position.animateTo(if (state.playerOpen) 0f else 1f, if (state.playerOpen) OpenSpring else CloseSpring, velocity)
    }
    // Reading the position only in layer and draw blocks keeps the frames of the slide free of recomposition.
    val visible by remember { derivedStateOf { position.value < 1f } }
    if (!state.playerOpen && !visible) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = with(LocalDensity.current) { maxHeight.toPx() }
        val flingThreshold = with(LocalDensity.current) { 900.dp.toPx() }
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Radix.Black.copy(alpha = .4f * (1f - position.value))) })
        PlayerScreen(state, player, vm, onMore, remember(height) {
            Modifier.graphicsLayer {
                translationY = position.value * height
                val radius = 28.dp.toPx() * (position.value / .12f).coerceIn(0f, 1f)
                shape = RoundedCornerShape(topStart = radius, topEnd = radius)
                clip = true
            }
        }, onDrag = { delta ->
            scope.launch { position.snapTo((position.value + delta / height).coerceIn(0f, 1f)) }
        }, onDragStopped = { velocity ->
            val dismiss = when {
                velocity > flingThreshold -> true
                velocity < -flingThreshold -> false
                else -> position.value > .16f
            }
            if (dismiss) { releaseVelocity = velocity / height; vm.showPlayer(false) }
            else scope.launch { position.animateTo(0f, OpenSpring, velocity / height) }
        })
    }
}
