@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lilt.app.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lilt.app.*
import com.lilt.app.data.LyricLine
import com.lilt.app.data.Song
import com.lilt.app.playback.PlaybackSnapshot
import com.lilt.app.ui.theme.Radix
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
                    val bodyWidth = maxWidth
                    if (wide) WidePlayerContent(state, player, vm, title, artist, album, cover, song, onMore,
                        Modifier.fillMaxSize()) { showQuality = true }
                    else Column(Modifier.fillMaxSize()) { when (state.playerPanel) {
                        PlayerPanel.Artwork -> {
                            val compact = bodyHeight < 600.dp
                            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
                            val fixedHeight = (if (compact) 396f else 436f) + (fontScale - 1f) * 100f
                            val art = min(bodyWidth.value, bodyHeight.value - fixedHeight).coerceAtLeast(140f)
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.Center) {
                            Spacer(Modifier.height(if (compact) 8.dp else 20.dp))
                            Artwork(cover, Modifier.align(Alignment.CenterHorizontally).size(art.dp)
                                .shadow(24.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black.copy(alpha = .35f),
                                    spotColor = Color.Black.copy(alpha = .35f)), 14.dp, "$title · 专辑封面")
                            Spacer(Modifier.height(if (compact) 24.dp else 36.dp))
                            TrackHeading(title, listOf(artist, album).filter { it.isNotBlank() }.joinToString(" — "),
                                song?.mid in state.liked, state.likeBusy, { vm.toggleLike() }, { song?.let(onMore) })
                            PlayerProgress(player, state.preview, vm.playback::seekTo, Modifier.padding(top = if (compact) 16.dp else 24.dp),
                                qualityChip(state.quality)) { showQuality = true }
                            TransportControls(player, vm, prevNext = 40.dp, pause = 54.dp,
                                Modifier.padding(top = if (compact) 20.dp else 28.dp, bottom = 20.dp))
                            }
                        }
                        PlayerPanel.Lyrics -> {
                            CompactHeading(title, artist, cover, song?.mid in state.liked, { vm.toggleLike() }, { song?.let(onMore) })
                            LyricsContent(state, player, vm, Modifier.weight(1f))
                            PlayerProgress(player, state.preview, vm.playback::seekTo, Modifier.padding(top = 16.dp))
                            TransportControls(player, vm, prevNext = 36.dp, pause = 46.dp, Modifier.padding(vertical = 18.dp))
                        }
                        PlayerPanel.Queue -> {
                            CompactHeading(title, artist, cover)
                            QueueModes(state, player, vm)
                            QueueHeader(state, player, vm, Modifier.padding(top = 24.dp, bottom = 8.dp))
                            QueueContent(player, vm, Modifier.weight(1f))
                            TransportControls(player, vm, prevNext = 34.dp, pause = 44.dp, Modifier.padding(vertical = 6.dp))
                        }
                    } }
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
                Artwork(cover, Modifier.size(size).shadow(16.dp, RoundedCornerShape(14.dp)), 14.dp, "$title · 专辑封面")
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
                    CompactHeading(title, artist, cover, song?.mid in state.liked, { vm.toggleLike() }, { song?.let(onMore) })
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

@Composable
private fun TrackHeading(title: String, subtitle: String, liked: Boolean, likeBusy: Boolean,
                         onLike: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 18.sp, lineHeight = 25.sp, color = Color.White.copy(alpha = .65f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LikeMore(liked, likeBusy, onLike, onMore)
    }
}

/** The 32 discs of the design, kept inside 44 touch targets. */
@Composable
private fun LikeMore(liked: Boolean, likeBusy: Boolean, onLike: () -> Unit, onMore: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        CircleAction(Glyph.Heart, if (liked) "取消喜欢" else "喜欢", Disc, Modifier.size(44.dp), 32.dp, 18.dp,
            if (liked) Radix.Like else Color.White, enabled = !likeBusy, onClick = onLike)
        CircleAction(Glyph.More, "更多歌曲操作", Disc, Modifier.size(44.dp), 32.dp, 18.dp, Color.White, onClick = onMore)
    }
}

@Composable
private fun CompactHeading(title: String, artist: String, cover: String, liked: Boolean = false,
                           onLike: (() -> Unit)? = null, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(cover, Modifier.size(60.dp).shadow(10.dp, RoundedCornerShape(8.dp), ambientColor = Color.Black.copy(alpha = .25f),
            spotColor = Color.Black.copy(alpha = .25f)), 8.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, fontSize = 15.sp, lineHeight = 21.sp, color = Color.White.copy(alpha = .65f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(pause + 18.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            TransportButton(Transport.Previous, "上一首", prevNext, Color.White, Modifier.size(prevNext + 24.dp), onClick = vm.playback::previous)
            if (player.isBuffering) Box(Modifier.size(pause + 18.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(30.dp), color = Color.White, strokeWidth = 2.dp)
            } else TransportButton(if (player.isPlaying) Transport.Pause else Transport.Play,
                if (player.isPlaying) "暂停" else if (player.error != null) "重试播放" else "播放",
                pause, Color.White, Modifier.size(pause + 18.dp), onClick = vm.playback::toggle)
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
private fun PanelActions(state: AppState, vm: LiltViewModel, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        listOf(Triple(PlayerPanel.Lyrics, Glyph.Lyrics, "同步歌词"), Triple(PlayerPanel.Queue, Glyph.Queue, "播放队列"))
            .forEach { (panel, glyph, label) ->
                val active = state.playerPanel == panel
                val source = remember { MutableInteractionSource() }
                val fill by animateColorAsState(if (active) Color.White.copy(alpha = .2f) else Color.White.copy(alpha = .08f), label = "panelFill")
                Box(Modifier.requiredSize(56.dp).pressFeedback(source).clip(RoundedCornerShape(18.dp))
                    .background(fill)
                    .quietClickable(interactionSource = source, role = Role.Button) {
                        vm.setPlayerPanel(if (active) PlayerPanel.Artwork else panel)
                    }
                    .semantics { selected = active; contentDescription = label }, contentAlignment = Alignment.Center) {
                    AppIcon(glyph, Modifier.size(28.dp), Color.White.copy(alpha = if (active) 1f else .85f))
                }
            }
    }
}

/** Lines dim and blur with their distance below the sung line, as in the design. */
private fun lineLook(offset: Int): Pair<Float, Float> = when {
    offset < 0 -> .35f to 0f
    offset == 0 -> 1f to 0f
    offset == 1 -> .40f to 0f
    offset == 2 -> .30f to 1.5f
    offset == 3 -> .22f to 2.5f
    else -> .15f to 3.5f
}

private val LyricStyle = TextStyle(fontSize = 28.sp, lineHeight = 1.3.em, fontWeight = FontWeight.ExtraBold,
    lineBreak = LineBreak.Simple, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))

@Composable
private fun LyricsContent(state: AppState, player: PlaybackSnapshot, vm: LiltViewModel, modifier: Modifier) {
    val listState = rememberLazyListState()
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
    // Two lines stay visible above the one being sung.
    LaunchedEffect(activeIndex, lines) {
        if (!listState.isScrollInProgress && lines.isNotEmpty()) listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
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
            else -> LazyColumn(Modifier.fillMaxSize(), listState, contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                itemsIndexed(lines, key = { index, line -> "$index-${line.timeMs}" }) { index, line ->
                    val (alpha, blur) = lineLook(index - activeIndex)
                    val shown by animateFloatAsState(alpha, label = "lyricAlpha")
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).quietClickable { vm.playback.seekTo(line.timeMs) }
                        .then(if (blur > 0f) Modifier.blur(blur.dp, BlurredEdgeTreatment.Unbounded) else Modifier)
                        .padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (index == activeIndex && line.words.isNotEmpty()) SungLine(line, position)
                        else Text(line.text, color = Color.White.copy(alpha = shown), style = LyricStyle)
                        if (line.translation.isNotBlank()) Text(line.translation, color = Color.White.copy(alpha = shown * .7f),
                            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** The line being sung: each QRC word brightens over its own duration, following seeks and pauses. */
@Composable
private fun SungLine(line: LyricLine, position: State<Long>) {
    val text = buildAnnotatedString {
        val now = position.value
        line.words.forEach { word ->
            withStyle(SpanStyle(color = lerp(LyricUnsung, Color.White, word.progress(now)))) { append(word.text) }
        }
    }
    Text(text, style = LyricStyle, modifier = Modifier.semantics { contentDescription = line.text })
}

private val LyricUnsung = Color.White.copy(alpha = .45f)

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
    val start = player.currentIndex + 1
    val upcoming = player.tracks.drop(start.coerceAtLeast(0))
    if (upcoming.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(if (player.tracks.isEmpty()) "队列为空" else "这是最后一首", color = Soft, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    var dragging by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowHeight = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.toPx() }
    LazyColumn(modifier) {
        itemsIndexed(upcoming, key = { index, track -> "${start + index}-${track.id}" }) { index, track ->
            val queueIndex = start + index
            val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = {
                if (it == SwipeToDismissBoxValue.EndToStart) vm.playback.remove(queueIndex)
                it == SwipeToDismissBoxValue.EndToStart
            })
            SwipeToDismissBox(dismiss, enableDismissFromStartToEnd = false, backgroundContent = {
                Box(Modifier.fillMaxSize().padding(end = 12.dp), contentAlignment = Alignment.CenterEnd) {
                    Text("移除", color = Soft, fontSize = 14.sp)
                }
            }, modifier = Modifier.zIndex(if (dragging == index) 1f else 0f).graphicsLayer {
                translationY = if (dragging == index) offset else 0f
            }) {
                Row(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(10.dp)).quietClickable { vm.playback.playAt(queueIndex) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Artwork(track.artwork, Modifier.size(44.dp), 6.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(track.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, fontSize = 13.sp, color = Color.White.copy(alpha = .55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(44.dp).pointerInput(queueIndex) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = index; offset = 0f },
                            onDrag = { change, amount -> change.consume(); offset += amount.y },
                            onDragEnd = {
                                val target = (index + (offset / rowHeight).roundToInt()).coerceIn(0, upcoming.lastIndex)
                                if (target != index) vm.playback.move(queueIndex, start + target)
                                dragging = -1; offset = 0f
                            },
                            onDragCancel = { dragging = -1; offset = 0f })
                    }.semantics { contentDescription = "按住拖动以调整${track.title}的顺序" }, contentAlignment = Alignment.Center) {
                        AppIcon(Glyph.Drag, Modifier.size(22.dp), Color.White.copy(alpha = .4f))
                    }
                }
            }
        }
    }
}
