package com.lilt.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lilt.app.AppState
import com.lilt.app.LiltViewModel
import com.lilt.app.data.Song
import com.lilt.app.playback.PlaybackSnapshot
import com.lilt.app.ui.theme.Radix
import kotlinx.coroutines.launch

/** One position drives opening, direct manipulation, cancellation and dismissal. */
@Composable
fun PlayerOverlay(state: AppState, player: PlaybackSnapshot, vm: LiltViewModel, onMore: (Song) -> Unit) {
    val position = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.playerOpen) {
        position.animateTo(if (state.playerOpen) 0f else 1f,
            tween(if (state.playerOpen) 360 else 260,
                easing = if (state.playerOpen) LinearOutSlowInEasing else FastOutSlowInEasing))
    }
    if (state.playerOpen || position.value < 1f) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val height = with(LocalDensity.current) { maxHeight.toPx() }
            val flingThreshold = with(LocalDensity.current) { 900.dp.toPx() }
            Box(Modifier.fillMaxSize().background(Radix.Black.copy(alpha = .18f * (1f - position.value))))
            PlayerScreen(state, player, vm, onMore, Modifier.graphicsLayer {
                translationY = position.value * height
                val radius = (28.dp.toPx() * (position.value / .12f).coerceIn(0f, 1f))
                shape = RoundedCornerShape(topStart = radius, topEnd = radius)
                clip = true
            }, onDrag = { delta ->
                scope.launch { position.snapTo((position.value + delta / height).coerceIn(0f, 1f)) }
            }, onDragStopped = { velocity ->
                val dismiss = when {
                    velocity > flingThreshold -> true
                    velocity < -flingThreshold -> false
                    else -> position.value > .16f
                }
                if (dismiss) vm.showPlayer(false)
                else scope.launch { position.animateTo(0f, tween(220, easing = LinearOutSlowInEasing)) }
            })
        }
    }
}
