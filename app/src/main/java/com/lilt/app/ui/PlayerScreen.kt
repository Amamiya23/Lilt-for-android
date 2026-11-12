@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.lilt.app.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilt.app.*
import com.lilt.app.data.Song
import com.lilt.app.playback.PlaybackSnapshot
import com.lilt.app.ui.theme.Radix
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Soft = Color.White.copy(alpha = .65f)
private val Disc = Color.White.copy(alpha = .15f)

@Composable
fun PlayerScreen(state: AppState, player: PlaybackSnapshot, vm: LiltViewModel, onMore: (Song) -> Unit,
                 modifier: Modifier = Modifier, onDrag: (Float) -> Unit = {}, onDragStopped: (Float) -> Unit = {}) {
    val song = vm.currentSong()
    val title = player.current?.title ?: song?.title ?: "还没有播放音乐"
    val artist = player.current?.artist ?: song?.artist.orEmpty()
    val album = player.current?.album?.takeIf { it.isNotBlank() } ?: song?.album.orEmpty()
    val cover = player.current?.artwork ?: song?.cover.orEmpty()
    var showQuality by remember { mutableStateOf(false) }
    val tint by animateFloatAsState(when (state.playerPanel) { PlayerPanel.Artwork -> 0f; PlayerPanel.Lyrics -> .5f; PlayerPanel.Queue -> 1f },
        label = "playerTint")
    Box(modifier.fillMaxSize().background(Radix.PlayerBase)) {
        // Android 12+ can render the blurred cover directly as the backdrop.
        if (cover.isNotBlank()) coil.compose.AsyncImage(cover, null, Modifier.fillMaxSize().blur(90.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Radix.PlayerTint.copy(alpha = .25f + .2f * tint), Radix.PlayerShade.copy(alpha = .6f + .1f * tint)))))
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                val wide = maxWidth > maxHeight
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    Grabber(onDrag, onDragStopped) { vm.showPlayer(false) }
                    // Reserve the footer before measuring artwork; short displays can scroll the content.
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val bodyHeight = maxHeight
                    if (wide) WidePlayerContent(state, player, vm, title, artist, album, cover, song, onMore,
                        Modifier.fillMaxSize()) { showQuality = true }
                    else SharedTransitionLayout(Modifier.fillMaxSize()) {
                    // Artwork, title and controls carry across panels; everything else crossfades beneath them.
                    AnimatedContent(state.playerPanel, Modifier.fillMaxSize(), transitionSpec = {
                        fadeIn(tween(260, delayMillis = 60)) togetherWith fadeOut(tween(160)) using null
                    }, label = "playerPanel") { panel ->
                    CompositionLocalProvider(LocalPanelTransition provides PanelTransition(this@SharedTransitionLayout, this)) {
                    Column(Modifier.fillMaxSize()) { when (panel) {
                        PlayerPanel.Artwork -> {
                            val compact = bodyHeight < 600.dp
                            ArtworkPanel(bodyHeight, compact, {
                                PlayingArtwork(cover, title, player.isPlaying || player.isBuffering || state.preview)
                            }) {
                                Column {
                                    TrackHeading(title, listOf(artist, album).filter { it.isNotBlank() }.joinToString(" — "),
                                        song?.mid in state.liked, state.likeBusy, { vm.toggleLike() }, { song?.let(onMore) })
                                    PlayerProgress(player, state.preview, vm.playback::seekTo, Modifier.padding(top = if (compact) 16.dp else 24.dp),
                                        qualityChip(state.quality)) { showQuality = true }
                                    TransportControls(player, vm, prevNext = 40.dp, pause = 54.dp,
                                        Modifier.padding(top = if (compact) 20.dp else 28.dp, bottom = 20.dp))
                                }
                            }
                        }
                        PlayerPanel.Lyrics -> {
                            CompactHeading(title, artist, cover, { vm.setPlayerPanel(PlayerPanel.Artwork) }, song?.mid in state.liked, { vm.toggleLike() }, { song?.let(onMore) })
                            LyricsContent(state, player, vm, Modifier.weight(1f))
                            PlayerProgress(player, state.preview, vm.playback::seekTo, Modifier.padding(top = 16.dp))
                            TransportControls(player, vm, prevNext = 36.dp, pause = 46.dp, Modifier.padding(vertical = 18.dp))
                        }
                        PlayerPanel.Queue -> {
                            CompactHeading(title, artist, cover, { vm.setPlayerPanel(PlayerPanel.Artwork) })
                            QueueModes(state, player, vm)
                            QueueHeader(state, player, vm, Modifier.padding(top = 24.dp, bottom = 8.dp))
                            QueueContent(player, vm, Modifier.weight(1f))
                            TransportControls(player, vm, prevNext = 34.dp, pause = 44.dp, Modifier.padding(vertical = 6.dp))
                        }
                    } }
                    }
                    }
                    }
                    }
                    PanelActions(state, vm, Modifier.height(if (wide) 56.dp else 72.dp))
                }
            }
        }
    }
    if (showQuality) ChoiceSheet("在线播放音质", QualityOptions, state.quality, { showQuality = false }, dark = true) {
        vm.setQuality(it); showQuality = false; vm.notify("将在下一首歌曲生效")
    }
}

val QualityOptions = listOf("standard" to "标准", "hq" to "高品质 HQ", "lossless" to "无损 SQ")
private fun qualityChip(quality: String) = when (quality) { "hq" -> "HQ"; "lossless" -> "SQ"; else -> "标准" }

@Composable
private fun Grabber(onDrag: (Float) -> Unit, onDragStopped: (Float) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp)
        .draggable(rememberDraggableState(onDrag), Orientation.Vertical, onDragStopped = { onDragStopped(it) }),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(80.dp, 48.dp).clip(RoundedCornerShape(12.dp)).quietClickable(onClick = onClose)
            .semantics { contentDescription = "收起播放页" }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp, 5.dp).background(Color.White.copy(alpha = .35f), CircleShape))
        }
    }
}

@Composable
private fun WidePlayerContent(state: AppState, player: PlaybackSnapshot, vm: LiltViewModel,
                              title: String, artist: String, album: String, cover: String, song: Song?,
                              onMore: (Song) -> Unit, modifier: Modifier, onQuality: () -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val size = min(maxWidth.value, maxHeight.value).coerceAtLeast(0f).dp
                PlayingArtwork(cover, title, player.isPlaying || player.isBuffering || state.preview, Modifier.size(size))
            }
            if (state.playerPanel != PlayerPanel.Artwork) {
                PlayerProgress(player, state.preview, vm.playback::seekTo)
                TransportControls(player, vm, prevNext = 34.dp, pause = 44.dp)
            }
        }
        Column(Modifier.weight(1.15f).fillMaxHeight()) {
            when (state.playerPanel) {
                PlayerPanel.Artwork -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center) {
                    TrackHeading(title, listOf(artist, album).filter { it.isNotBlank() }.joinToString(" — "),
                        song?.mid in state.liked, state.likeBusy, { vm.toggleLike() }, { song?.let(onMore) })
                    PlayerProgress(player, state.preview, vm.playback::seekTo, Modifier.padding(top = 16.dp),
                        qualityChip(state.quality), onQuality)
                    TransportControls(player, vm, prevNext = 36.dp, pause = 46.dp)
                }
                PlayerPanel.Lyrics -> {
                    CompactHeading(title, artist, cover, { vm.setPlayerPanel(PlayerPanel.Artwork) }, song?.mid in state.liked, { vm.toggleLike() }, { song?.let(onMore) })
                    LyricsContent(state, player, vm, Modifier.weight(1f))
                }
                PlayerPanel.Queue -> {
                    QueueModes(state, player, vm)
                    QueueHeader(state, player, vm, Modifier.padding(top = 16.dp, bottom = 6.dp))
                    QueueContent(player, vm, Modifier.weight(1f))
                }
            }
        }
    }
}

/** The panel switch's scopes, so pieces present on both sides can travel instead of fading. */
private class PanelTransition(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)
private val LocalPanelTransition = compositionLocalOf<PanelTransition?> { null }
private val PanelBounds = BoundsTransform { _, _ ->
    spring(dampingRatio = .9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = Rect.VisibilityThreshold)
}

/** The artwork, drawn once at the animated bounds and above the other travelling pieces. */
@Composable
private fun Modifier.sharedPanelElement(key: String): Modifier {
    val transition = LocalPanelTransition.current ?: return this
    return with(transition.shared) {
        sharedElement(rememberSharedContentState(key), transition.visibility, boundsTransform = PanelBounds, zIndexInOverlay = 1f)
    }
}

/**
 * Counterparts that differ only in size: the incoming one alone is drawn, scaled from the outgoing bounds to its own,
 * so it grows or shrinks in place instead of two copies crossfading into a double image.
 */
@Composable
private fun Modifier.sharedPanelBounds(key: String, alignment: Alignment = Alignment.CenterStart): Modifier {
    val transition = LocalPanelTransition.current ?: return this
    return with(transition.shared) {
        sharedBounds(rememberSharedContentState(key), transition.visibility, enter = EnterTransition.None,
            exit = fadeOut(snap()), boundsTransform = PanelBounds,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Fit, alignment))
    }
}

/** The artwork takes the height the details leave, up to the width the progress bar spans; short displays scroll. */
@Composable
private fun ArtworkPanel(height: Dp, compact: Boolean, artwork: @Composable () -> Unit, details: @Composable () -> Unit) {
    val top = if (compact) 8.dp else 20.dp
    val gap = if (compact) 24.dp else 36.dp
    Layout({ artwork(); details() }, Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { (art, info), constraints ->
        val width = constraints.maxWidth
        val below = info.measure(Constraints(minWidth = width, maxWidth = width))
        val side = min(width, height.roundToPx() - top.roundToPx() - gap.roundToPx() - below.height)
            .coerceAtLeast(140.dp.roundToPx())
        val image = art.measure(Constraints.fixed(side, side))
        val content = top.roundToPx() + side + gap.roundToPx() + below.height
        val total = max(content, height.roundToPx())
        layout(width, total) {
            val y = top.roundToPx() + (total - content) / 2
            image.place((width - side) / 2, y)
            below.place(0, y + side + gap.roundToPx())
        }
    }
}

/** Full size while music plays; paused, the cover settles back, its shadow easing with it. */
@Composable
private fun PlayingArtwork(cover: String, title: String, playing: Boolean, modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(if (playing) 1f else .8f,
        spring(dampingRatio = .82f, stiffness = Spring.StiffnessLow), label = "artworkScale")
    val shape = RoundedCornerShape(16.dp)
    // Scaled in layout rather than in a layer, so a panel transition starts from the size actually shown.
    Artwork(cover, modifier.fillMaxSize().layout { measurable, constraints ->
        val width = (constraints.maxWidth * scale).roundToInt()
        val height = (constraints.maxHeight * scale).roundToInt()
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place((constraints.maxWidth - width) / 2, (constraints.maxHeight - height) / 2)
        }
    }.sharedPanelElement("artwork").graphicsLayer {
        shadowElevation = (6f + 18f * ((scale - .8f) / .2f).coerceIn(0f, 1f)).dp.toPx()
        this.shape = shape
        ambientShadowColor = Color.Black.copy(alpha = .4f)
        spotShadowColor = Color.Black.copy(alpha = .4f)
    }, 16.dp, "$title · 专辑封面")
}

@Composable
private fun TrackHeading(title: String, subtitle: String, liked: Boolean, likeBusy: Boolean,
                         onLike: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, Modifier.sharedPanelBounds("title"), fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 18.sp, lineHeight = 25.sp, color = Color.White.copy(alpha = .65f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LikeMore(liked, likeBusy, onLike, onMore)
    }
}

/** The 32 discs of the design, kept inside 44 touch targets. */
@Composable
private fun LikeMore(liked: Boolean, likeBusy: Boolean, onLike: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.sharedPanelBounds("likeMore"), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        CircleAction(Glyph.Heart, if (liked) "取消喜欢" else "喜欢", Disc, Modifier.size(44.dp), 32.dp, 18.dp,
            if (liked) Radix.Like else Color.White, enabled = !likeBusy, onClick = onLike)
        CircleAction(Glyph.More, "更多歌曲操作", Disc, Modifier.size(44.dp), 32.dp, 18.dp, Color.White, onClick = onMore)
    }
}

@Composable
/** Cover and title together lead back to the artwork panel. */
private fun CompactHeading(title: String, artist: String, cover: String, onArtwork: () -> Unit, liked: Boolean = false,
                           onLike: (() -> Unit)? = null, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).padding(end = 8.dp).clip(RoundedCornerShape(10.dp)).quietClickable(role = Role.Button, onClickLabel = "显示封面",
            onClick = onArtwork), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Artwork(cover, Modifier.size(60.dp).sharedPanelElement("artwork").shadow(10.dp, RoundedCornerShape(8.dp), ambientColor = Color.Black.copy(alpha = .25f),
                spotColor = Color.Black.copy(alpha = .25f)), 8.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, Modifier.sharedPanelBounds("title"), fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artist, fontSize = 15.sp, lineHeight = 21.sp, color = Color.White.copy(alpha = .65f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onLike != null && onMore != null) LikeMore(liked, false, onLike, onMore)
    }
}

@Composable
private fun PlayerProgress(player: PlaybackSnapshot, preview: Boolean, onSeek: (Long) -> Unit, modifier: Modifier = Modifier,
                           quality: String? = null, onQuality: () -> Unit = {}) {
    var seeking by remember { mutableStateOf(false) }
    var seek by remember { mutableFloatStateOf(0f) }
    val duration = if (preview) 269000L else player.durationMs
    val position = if (preview) 62000L else player.positionMs
    Column(modifier.fillMaxWidth()) {
        Slider(value = if (seeking) seek else if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { seeking = true; seek = it },
            onValueChangeFinished = { onSeek((seek * duration).toLong()); seeking = false },
            enabled = duration > 0, modifier = Modifier.fillMaxWidth().height(24.dp)
                .semantics { contentDescription = "播放进度" },
            thumb = {},
            track = { slider ->
                SliderDefaults.Track(slider, modifier = Modifier.height(7.dp),
                    colors = SliderDefaults.colors(activeTrackColor = Color.White.copy(alpha = .9f),
                        inactiveTrackColor = Color.White.copy(alpha = .2f),
                        disabledActiveTrackColor = Color.White.copy(alpha = .9f),
                        disabledInactiveTrackColor = Color.White.copy(alpha = .2f)),
                    thumbTrackGapSize = 0.dp, drawStopIndicator = null)
            })
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val shown = if (seeking) (seek * duration).toLong() else position
            Text(formatTime(shown), Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = .6f))
            if (quality != null) Box(Modifier.sizeIn(minWidth = 56.dp, minHeight = 48.dp).clip(RoundedCornerShape(8.dp))
                .quietClickable(onClick = onQuality).semantics { contentDescription = "在线播放音质：$quality" },
                contentAlignment = Alignment.Center) {
                Row(Modifier.background(Color.White.copy(alpha = .12f), RoundedCornerShape(5.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                AppIcon(Glyph.Adjust, Modifier.size(12.dp), Color.White.copy(alpha = .8f))
                Text(quality, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .5.sp, color = Color.White.copy(alpha = .8f))
                }
            }
            Text("-${formatTime(duration - shown)}", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = .6f), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun TransportControls(player: PlaybackSnapshot, vm: LiltViewModel, prevNext: Dp, pause: Dp,
                              modifier: Modifier = Modifier) {
    Column(modifier.sharedPanelBounds("transport", Alignment.Center).fillMaxWidth()) {
        // Thirds, so the panel buttons below can share the previous and next columns whatever the sizes.
        ThirdsRow(Modifier.height(pause + 18.dp), {
            TransportButton(Transport.Previous, "上一首", prevNext, Color.White, Modifier.size(prevNext + 24.dp), onClick = vm.playback::previous)
        }, {
            if (player.isBuffering) Box(Modifier.size(pause + 18.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(30.dp), color = Color.White, strokeWidth = 2.dp)
            } else TransportButton(if (player.showsPause) Transport.Pause else Transport.Play,
                if (player.showsPause) "暂停" else if (player.error != null) "重试播放" else "播放",
                pause, Color.White, Modifier.size(pause + 18.dp), onClick = vm.playback::toggle)
        }) {
            TransportButton(Transport.Next, "下一首", prevNext, Color.White, Modifier.size(prevNext + 24.dp), onClick = vm.playback::next)
        }
        if (player.error != null) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(player.error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .85f))
            QuietTextButton({ vm.playback.clearError(); vm.playback.toggle() },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("重试") }
        }
    }
}

@Composable
private fun ThirdsRow(modifier: Modifier, start: @Composable () -> Unit, center: @Composable () -> Unit, end: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        listOf(start, center, end).forEach { slot -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { slot() } }
    }
}

/** Lyrics and queue sit under previous and next; the open one is lit and set on a soft disc. */
@Composable
private fun PanelActions(state: AppState, vm: LiltViewModel, modifier: Modifier) {
    @Composable
    fun action(panel: PlayerPanel, glyph: Glyph, label: String) {
        val active = state.playerPanel == panel
        val source = remember { MutableInteractionSource() }
        val lit by animateFloatAsState(if (active) 1f else 0f, tween(220), label = "panelLit")
        Box(Modifier.size(48.dp).pressFeedback(source, .9f)
            .quietClickable(interactionSource = source, role = Role.Button) {
                vm.setPlayerPanel(if (active) PlayerPanel.Artwork else panel)
            }
            .semantics { selected = active; contentDescription = label }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp).graphicsLayer { alpha = lit; scaleX = .8f + .2f * lit; scaleY = scaleX }
                .background(Color.White.copy(alpha = .18f), CircleShape))
            AppIcon(glyph, Modifier.size(24.dp), Color.White.copy(alpha = .6f + .4f * lit))
        }
    }
    ThirdsRow(modifier, { action(PlayerPanel.Lyrics, Glyph.Lyrics, "同步歌词") }, {}) {
        action(PlayerPanel.Queue, Glyph.Queue, "播放队列")
    }
}

@Composable
private fun LyricsContent(state: AppState, player: PlaybackSnapshot, vm: LiltViewModel, modifier: Modifier) {
    val lines = state.lyrics.lines
    val wordTimed = lines.any { it.words.isNotEmpty() }
    // Word highlights follow a per-frame clock; the player itself only reports every 400 ms.
    val position = produceState(if (state.preview) 62000L else player.positionMs, player, state.preview, wordTimed) {
        value = if (state.preview) 62000L else player.positionAt(SystemClock.elapsedRealtime())
        if (state.preview || !wordTimed || !player.isPlaying) return@produceState
        while (true) withFrameMillis { value = player.positionAt(SystemClock.elapsedRealtime()) }
    }
    val activeIndex by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= position.value }.coerceAtLeast(0) }
    }
    Box(modifier) {
        when {
            state.lyricsLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            }
            state.lyricsError != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("歌词加载失败", style = MaterialTheme.typography.titleMedium, color = Soft)
                QuietTextButton({ vm.loadLyrics() }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("重试") }
            }
            lines.isEmpty() -> Text("暂无歌词，静静听", Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium, color = Soft)
            else -> LyricsList(lines, activeIndex, position, vm.playback::seekTo, Modifier.fillMaxSize(),
                songKey = player.current?.id)
        }
    }
}


@Composable
private fun QueueModes(state: AppState, player: PlaybackSnapshot, vm: LiltViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        data class Mode(val glyph: Glyph, val label: String, val on: Boolean, val status: String, val action: () -> Unit)
        listOf(
            Mode(Glyph.Shuffle, "随机播放", player.shuffle, if (player.shuffle) "已开启" else "已关闭") { vm.playback.setShuffle(!player.shuffle) },
            Mode(if (player.repeatMode == 1) Glyph.RepeatOne else Glyph.Repeat, "循环模式", player.repeatMode != 0,
                when (player.repeatMode) { 1 -> "单曲循环"; 2 -> "列表循环"; else -> "顺序播放" }, vm.playback::cycleRepeat),
            Mode(Glyph.Endless, "播完继续播放私人电台", state.endless, if (state.endless) "已开启" else "已关闭") { vm.setEndless(!state.endless) },
        ).forEach { mode ->
            val fill by animateColorAsState(Color.White.copy(alpha = if (mode.on) .85f else .12f), label = "modeFill")
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).quietClickable(onClick = mode.action)
                .semantics { contentDescription = mode.label; stateDescription = mode.status }, contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(38.dp).background(fill, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    AppIcon(mode.glyph, Modifier.size(20.dp), if (mode.on) Radix.PlayerTint else Color.White)
                }
            }
        }
    }
}

@Composable
private fun QueueHeader(state: AppState, player: PlaybackSnapshot, vm: LiltViewModel, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("接下来播放", fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
            val upcoming = (player.tracks.size - player.currentIndex - 1).coerceAtLeast(0)
            Text(state.queueSource.ifBlank { "$upcoming 首歌曲" }, fontSize = 13.sp, color = Color.White.copy(alpha = .55f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("清除", Modifier.clip(RoundedCornerShape(8.dp)).quietClickable(onClick = vm.playback::clearUpcoming)
            .padding(horizontal = 8.dp, vertical = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = .7f))
    }
}

/** Upcoming songs. Long-press the handle and drag to reorder; swipe a row away to remove it. */
@Composable
private fun QueueContent(player: PlaybackSnapshot, vm: LiltViewModel, modifier: Modifier) {
    val availability by vm.state.collectAsStateWithLifecycle()
    val start = player.currentIndex + 1
    val upcoming = player.tracks.drop(start.coerceAtLeast(0))
    if (upcoming.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(if (player.tracks.isEmpty()) "队列为空" else "这是最后一首", color = Soft, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    // The lifted row is tracked by key and by its travel from the slot it left. The queue reorders live as it passes
    // other rows, which glide aside; whatever its layout index, the row stays under the finger, then settles into the gap.
    var lifted by remember { mutableStateOf<String?>(null) }
    var liftedFrom by remember { mutableIntStateOf(0) }
    var liftedAt by remember { mutableIntStateOf(0) }
    val travel = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val rowHeight = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.toPx() }
    // Keys follow the song, counting repeats, so rows keep their identity and glide when the queue changes around them.
    val keys = remember(upcoming) {
        val seen = mutableMapOf<String, Int>()
        upcoming.map { track -> "${track.id}#${seen.merge(track.id, 1, Int::plus)}" }
    }
    LazyColumn(modifier) {
        itemsIndexed(upcoming, key = { index, _ -> keys[index] }) { index, track ->
            val playable = availability.online || vm.canPlay(track)
            val queueIndex = start + index
            val key = keys[index]
            val isLifted = lifted == key
            val removeAt by rememberUpdatedState(queueIndex)
            val currentIndex by rememberUpdatedState(index)
            val lastIndex by rememberUpdatedState(upcoming.lastIndex)
            val queueStart by rememberUpdatedState(start)
            val lift by animateFloatAsState(if (isLifted) 1f else 0f, tween(180), label = "queueLift")
            val dismiss = rememberSwipeToDismissBoxState()
            // 1 while present; a dismissed row folds away before the song leaves the queue, so the rows below follow it up.
            val presence = remember { Animatable(1f) }
            var removing by remember { mutableStateOf(false) }
            LaunchedEffect(removing) {
                if (!removing) return@LaunchedEffect
                presence.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
                vm.playback.remove(removeAt)
            }
            // Removal waits for the release, so pulling the row back before lifting the finger keeps the song.
            SwipeToDismissBox(dismiss, enableDismissFromStartToEnd = false, gesturesEnabled = !removing,
                onDismiss = { removing = true },
                backgroundContent = {
                // Rows are see-through over the backdrop, so the removal tile fills only the width already uncovered.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.fillMaxHeight().padding(vertical = 4.dp).layout { measurable, constraints ->
                        val width = (-(runCatching { dismiss.requireOffset() }.getOrDefault(0f))).roundToInt()
                            .coerceIn(0, constraints.maxWidth)
                        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(width, placeable.height) { placeable.place(0, 0) }
                    }.clip(RoundedCornerShape(12.dp)).background(Radix.RubyDark9), contentAlignment = Alignment.Center) {
                        AppIcon(Glyph.Trash, Modifier.requiredSize(22.dp), Color.White)
                    }
                }
            }, modifier = Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (isLifted) null
                    else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold))
                .zIndex(if (isLifted) 1f else 0f)
                .graphicsLayer { translationY = if (isLifted) travel.value - (index - liftedFrom) * rowHeight else 0f }
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val height = (placeable.height * presence.value).roundToInt()
                    layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
                }
                .graphicsLayer { alpha = presence.value; scaleX = .96f + .04f * presence.value; scaleY = scaleX }) {
                // A lifted row rises a touch on a soft tile, so it reads as picked up while it passes over the others.
                Row(Modifier.fillMaxWidth().height(60.dp)
                    .graphicsLayer { scaleX = 1f + .03f * lift; scaleY = scaleX }
                    .background(Color.White.copy(alpha = .14f * lift), RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(10.dp)).quietClickable(enabled = playable) { vm.playback.playAt(queueIndex) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Artwork(track.artwork, Modifier.size(44.dp).graphicsLayer { alpha = if (playable) 1f else .4f }, 6.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(track.title, color = if (playable) Radix.White else Radix.SlateDark11, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, fontSize = 13.sp, color = if (playable) Radix.White.copy(alpha = .55f) else Radix.SlateDark11, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(44.dp).pointerInput(key) {
                        fun settle(target: Int) = scope.launch {
                            travel.animateTo((target - liftedFrom) * rowHeight, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow))
                            lifted = null
                        }
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                lifted = key; liftedFrom = currentIndex; liftedAt = currentIndex
                                scope.launch { travel.snapTo(0f) }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                scope.launch(start = CoroutineStart.UNDISPATCHED) { travel.snapTo(travel.value + amount.y) }
                                val hover = (liftedFrom + ((travel.value + amount.y) / rowHeight).roundToInt()).coerceIn(0, lastIndex)
                                // Moves queue in order on the player, so each one starts from where the last one left the song.
                                if (hover != liftedAt) { vm.playback.move(queueStart + liftedAt, queueStart + hover); liftedAt = hover }
                            },
                            onDragEnd = { settle(liftedAt) },
                            onDragCancel = { settle(liftedAt) })
                    }.semantics { contentDescription = "按住拖动以调整${track.title}的顺序" }, contentAlignment = Alignment.Center) {
                        AppIcon(Glyph.Drag, Modifier.size(22.dp), Color.White.copy(alpha = .4f))
                    }
                }
            }
        }
    }
}
