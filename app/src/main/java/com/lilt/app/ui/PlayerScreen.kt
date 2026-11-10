@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lilt.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilt.app.*
import com.lilt.app.data.Song
import com.lilt.app.playback.PlaybackSnapshot
import com.lilt.app.ui.theme.Radix
import kotlin.math.min

@Composable
fun PlayerScreen(state: AppState, player: PlaybackSnapshot, vm: LiltViewModel, onMore: (Song) -> Unit) {
    val song = vm.currentSong()
    val title = player.current?.title ?: song?.title ?: "还没有播放音乐"
    val artist = player.current?.artist ?: song?.artist.orEmpty()
    val cover = player.current?.artwork ?: song?.cover.orEmpty()
    val foreground = Radix.White
    var showQuality by remember { mutableStateOf(false) }
    val backdrop = listOf(Radix.Sand11, Radix.Mauve12, Radix.Sand12)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(backdrop))) {
        if (cover.isNotBlank()) coil.compose.AsyncImage(cover, null, Modifier.fillMaxSize().blur(80.dp).alpha(.55f),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Radix.Mauve12.copy(alpha = .15f), Radix.Mauve12.copy(alpha = .6f), Radix.Sand12.copy(alpha = .8f)))))
        if (state.playerPanel == PlayerPanel.Lyrics) Box(Modifier.fillMaxSize().background(Radix.Black.copy(alpha = .4f)))
        CompositionLocalProvider(LocalContentColor provides foreground) {
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                val contentHeight = maxHeight
                val contentWidth = maxWidth
                val wide = maxWidth > maxHeight
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    Box(Modifier.fillMaxWidth().height(38.dp).pointerInput(Unit) {
                        var drag = 0f
                        detectVerticalDragGestures(onDragStart = { drag = 0f }, onVerticalDrag = { _, amount -> drag += amount },
                            onDragEnd = { if (drag > 50) vm.showPlayer(false) })
                    }, contentAlignment = Alignment.Center) {
                        IconButton({ vm.showPlayer(false) }, Modifier.width(80.dp).height(38.dp)
                            .semantics { contentDescription = "收起播放页" }) {
                            Box(Modifier.width(40.dp).height(5.dp).background(foreground.copy(alpha = .35f), CircleShape))
                        }
                    }
                    if (wide) WidePlayerContent(state, player, vm, title, artist, cover, song, onMore,
                        Modifier.fillMaxWidth().weight(1f), { showQuality = true })
                    else when (state.playerPanel) {
                        PlayerPanel.Artwork -> {
                            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                val coverSize = min(min(contentWidth.value - 48, contentHeight.value * .45f), maxHeight.value).coerceAtLeast(0f).dp
                                Artwork(cover, Modifier.size(coverSize).shadow(24.dp, RoundedCornerShape(16.dp)),
                                    16.dp, "$title · 专辑封面")
                            }
                            TrackHeading(title, "$artist${player.current?.album?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: song?.album?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()}",
                                song?.mid in state.liked, state.likeBusy, { vm.toggleLike() }, { song?.let(onMore) })
                            PlayerProgress(player, state.preview, onSeek = vm.playback::seekTo, onQuality = { showQuality = true },
                                quality = qualityLabel(state.quality), showQuality = true)
                            TransportControls(player, vm, large = true)
                            Spacer(Modifier.weight(.2f))
                        }
                        PlayerPanel.Lyrics -> {
                            CompactHeading(title, artist, cover, { vm.toggleLike() }, { song?.let(onMore) }, song?.mid in state.liked)
                            LyricsContent(state, player, vm, Modifier.weight(1f))
                            PlayerProgress(player, state.preview, onSeek = vm.playback::seekTo)
                            TransportControls(player, vm, large = false)
                        }
                        PlayerPanel.Queue -> {
                            CompactHeading(title, artist, cover, { vm.toggleLike() }, { song?.let(onMore) }, song?.mid in state.liked)
                            QueueModes(player, vm)
                            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("接下来播放", style = MaterialTheme.typography.titleMedium)
                                    Text("${player.tracks.size} 首歌曲", style = MaterialTheme.typography.bodyMedium, color = foreground.copy(alpha = .65f))
                                }
                                TextButton(vm.playback::clearUpcoming, colors = ButtonDefaults.textButtonColors(contentColor = foreground.copy(alpha = .8f))) { Text("清除") }
                            }
                            QueueContent(player, vm, Modifier.weight(1f))
                            TransportControls(player, vm, large = false)
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(if (wide) 48.dp else 64.dp), horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ vm.setPlayerPanel(if (state.playerPanel == PlayerPanel.Lyrics) PlayerPanel.Artwork else PlayerPanel.Lyrics) },
                            Modifier.size(48.dp).semantics { selected = state.playerPanel == PlayerPanel.Lyrics }
                                .background(if (state.playerPanel == PlayerPanel.Lyrics) foreground.copy(alpha = .16f) else Color.Transparent, RoundedCornerShape(14.dp))) {
                            AppIcon(Glyph.Lyrics, description = "同步歌词", tint = foreground.copy(alpha = if (state.playerPanel == PlayerPanel.Lyrics) 1f else .65f))
                        }
                        IconButton({ vm.setPlayerPanel(if (state.playerPanel == PlayerPanel.Queue) PlayerPanel.Artwork else PlayerPanel.Queue) },
                            Modifier.size(48.dp).semantics { selected = state.playerPanel == PlayerPanel.Queue }
                                .background(if (state.playerPanel == PlayerPanel.Queue) foreground.copy(alpha = .16f) else Color.Transparent, RoundedCornerShape(14.dp))) {
                            AppIcon(Glyph.Queue, description = "播放队列", tint = foreground.copy(alpha = if (state.playerPanel == PlayerPanel.Queue) 1f else .65f))
                        }
                    }
                    Spacer(Modifier.height(if (wide) 0.dp else 12.dp))
                }
            }
        }
    }
    if (showQuality) ChoiceSheet("在线播放音质", listOf("standard" to "标准", "hq" to "高品质 HQ", "lossless" to "无损 SQ"),
        state.quality, { showQuality = false }) { vm.setQuality(it); showQuality = false; vm.notify("将在下一首歌曲生效") }
}

@Composable
private fun WidePlayerContent(state: AppState, player: PlaybackSnapshot, vm: LiltViewModel,
                              title: String, artist: String, cover: String, song: Song?,
                              onMore: (Song) -> Unit, modifier: Modifier, onQuality: () -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val size = min(maxWidth.value, maxHeight.value).coerceAtLeast(0f).dp
                Artwork(cover, Modifier.size(size).shadow(16.dp, RoundedCornerShape(14.dp)), 14.dp, "$title · 专辑封面")
            }
            if (state.playerPanel != PlayerPanel.Artwork) {
                PlayerProgress(player, state.preview, vm.playback::seekTo)
                TransportControls(player, vm, large = false)
            }
        }
        Column(Modifier.weight(1.15f).fillMaxHeight()) {
            if (state.playerPanel == PlayerPanel.Artwork) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    TrackHeading(title, artist, song?.mid in state.liked, state.likeBusy,
                        { vm.toggleLike() }, { song?.let(onMore) })
                    PlayerProgress(player, state.preview, vm.playback::seekTo, onQuality, qualityLabel(state.quality),
                        showQuality = true, compact = true)
                    TransportControls(player, vm, large = false)
                }
            } else {
                CompactHeading(title, artist, cover, { vm.toggleLike() }, { song?.let(onMore) }, song?.mid in state.liked)
                if (state.playerPanel == PlayerPanel.Lyrics) LyricsContent(state, player, vm, Modifier.weight(1f))
                else {
                    QueueModes(player, vm)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("接下来播放 · ${player.tracks.size} 首", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(vm.playback::clearUpcoming,
                            colors = ButtonDefaults.textButtonColors(contentColor = Radix.White)) { Text("清除") }
                    }
                    QueueContent(player, vm, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TrackHeading(title: String, artist: String, liked: Boolean, likeBusy: Boolean,
                         onLike: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, style = MaterialTheme.typography.bodyLarge, color = Radix.White.copy(alpha = .65f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconAction(Glyph.Heart, if (liked) "取消喜欢" else "喜欢", tint = if (liked) Radix.Ruby9 else Radix.White.copy(alpha = .8f),
            enabled = !likeBusy, onClick = onLike)
        IconAction(Glyph.More, "更多歌曲操作", tint = Radix.White.copy(alpha = .8f), onClick = onMore)
    }
}

@Composable
private fun CompactHeading(title: String, artist: String, cover: String, onLike: () -> Unit, onMore: () -> Unit, liked: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(cover, Modifier.size(60.dp), 10.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, style = MaterialTheme.typography.bodyLarge, color = Radix.White.copy(alpha = .65f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconAction(Glyph.Heart, if (liked) "取消喜欢" else "喜欢", tint = if (liked) Radix.Ruby9 else Radix.White.copy(alpha = .8f), onClick = onLike)
        IconAction(Glyph.More, "更多歌曲操作", tint = Radix.White.copy(alpha = .8f), onClick = onMore)
    }
}

@Composable
private fun PlayerProgress(player: PlaybackSnapshot, preview: Boolean, onSeek: (Long) -> Unit,
                           onQuality: () -> Unit = {}, quality: String = "", showQuality: Boolean = false,
                           compact: Boolean = false) {
    var seeking by remember { mutableStateOf(false) }
    var seek by remember { mutableFloatStateOf(0f) }
    val duration = if (preview) 269000L else player.durationMs
    val position = if (preview) 62000L else player.positionMs
    Column(Modifier.fillMaxWidth().padding(top = if (showQuality) if (compact) 8.dp else 20.dp else 0.dp)) {
        Slider(value = if (seeking) seek else if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { seeking = true; seek = it },
            onValueChangeFinished = { onSeek((seek * duration).toLong()); seeking = false },
            enabled = duration > 0, modifier = Modifier.fillMaxWidth().height(48.dp)
                .semantics { contentDescription = "播放进度" },
            thumb = {},
            track = { slider ->
                SliderDefaults.Track(slider, modifier = Modifier.height(6.dp),
                    colors = SliderDefaults.colors(activeTrackColor = Radix.White.copy(alpha = .85f),
                        inactiveTrackColor = Radix.White.copy(alpha = .22f)),
                    thumbTrackGapSize = 0.dp, drawStopIndicator = null)
            })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(if (seeking) (seek * duration).toLong() else position), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = Radix.White.copy(alpha = .7f))
            if (showQuality) TextButton(onQuality, Modifier.heightIn(min = 32.dp), contentPadding = PaddingValues(horizontal = 9.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Radix.White.copy(alpha = .75f))) {
                AppIcon(Glyph.Adjust, Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp)); Text(quality, style = MaterialTheme.typography.labelSmall)
            }
            Text("-${formatTime(duration - if (seeking) (seek * duration).toLong() else position)}", Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = Radix.White.copy(alpha = .7f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun TransportControls(player: PlaybackSnapshot, vm: LiltViewModel, large: Boolean) {
    val size = if (large) 54.dp else 46.dp
    Row(Modifier.fillMaxWidth().height(if (large) 122.dp else 82.dp), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        TransportButton(Transport.Previous, "上一首", if (large) 40.dp else 34.dp, Radix.White, onClick = vm.playback::previous)
        if (player.isBuffering) Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp), color = Radix.White, strokeWidth = 2.dp)
        } else TransportButton(if (player.isPlaying) Transport.Pause else Transport.Play,
            if (player.isPlaying) "暂停" else if (player.error != null) "重试播放" else "播放",
            size, Radix.White, Modifier.size(72.dp), onClick = vm.playback::toggle)
        TransportButton(Transport.Next, "下一首", if (large) 40.dp else 34.dp, Radix.White, onClick = vm.playback::next)
    }
    if (player.error != null) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(player.error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Radix.White.copy(alpha = .85f))
        TextButton({ vm.playback.clearError(); vm.playback.toggle() },
            colors = ButtonDefaults.textButtonColors(contentColor = Radix.White)) { Text("重试") }
    }
}

@Composable
private fun LyricsContent(state: AppState, player: PlaybackSnapshot, vm: LiltViewModel, modifier: Modifier) {
    val listState = rememberLazyListState()
    val lines = state.lyrics.lines
    val position = if (state.preview) 62000L else player.positionMs
    val activeIndex = lines.indexOfLast { it.timeMs <= position }.coerceAtLeast(0)
    LaunchedEffect(activeIndex) {
        if (!listState.isScrollInProgress && lines.isNotEmpty()) listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
    }
    Box(modifier) {
        when {
            state.lyricsLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Radix.White, strokeWidth = 2.dp)
            }
            state.lyricsError != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("歌词加载失败", style = MaterialTheme.typography.titleMedium, color = Radix.White.copy(alpha = .7f))
                TextButton({ vm.loadLyrics() }, colors = ButtonDefaults.textButtonColors(contentColor = Radix.White)) { Text("重试") }
            }
            lines.isEmpty() -> Text("暂无歌词，静静听", Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium, color = Radix.White.copy(alpha = .65f))
            else -> LazyColumn(Modifier.fillMaxSize(), listState, contentPadding = PaddingValues(top = 20.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp)) {
                itemsIndexed(lines, key = { index, line -> "$index-${line.timeMs}" }) { index, line ->
                    val color by animateColorAsState(if (index == activeIndex) Radix.White else Radix.White.copy(alpha = .74f), label = "lyricColor")
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.playback.seekTo(line.timeMs) }
                        .padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(line.text, color = color, fontSize = 28.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
                        if (line.translation.isNotBlank()) Text(line.translation, color = color.copy(alpha = .7f),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueModes(player: PlaybackSnapshot, vm: LiltViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            Triple(Glyph.Shuffle, "随机播放", player.shuffle),
            Triple(if (player.repeatMode == 1) Glyph.RepeatOne else Glyph.Repeat, "切换循环模式", player.repeatMode != 0),
        ).forEachIndexed { index, (icon, label, selected) ->
            IconButton({ if (index == 0) vm.playback.setShuffle(!player.shuffle) else vm.playback.cycleRepeat() },
                Modifier.weight(1f).height(48.dp).semantics {
                    stateDescription = if (index == 0) if (player.shuffle) "已开启" else "已关闭"
                    else when (player.repeatMode) { 1 -> "单曲循环"; 2 -> "列表循环"; else -> "顺序播放" }
                }.background(Radix.White.copy(alpha = if (selected) .25f else .1f), RoundedCornerShape(12.dp))) {
                AppIcon(icon, description = label, tint = Radix.White.copy(alpha = if (selected) 1f else .65f))
            }
        }
    }
}

@Composable
private fun QueueContent(player: PlaybackSnapshot, vm: LiltViewModel, modifier: Modifier) {
    if (player.tracks.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("队列为空", color = Radix.White.copy(alpha = .65f), style = MaterialTheme.typography.bodyLarge)
        }
    } else LazyColumn(modifier) {
        itemsIndexed(player.tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.playback.playAt(index) }
                .background(if (index == player.currentIndex) Radix.White.copy(alpha = .09f) else Color.Transparent).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Artwork(track.artwork, Modifier.size(44.dp), 9.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(track.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        color = Radix.White.copy(alpha = if (index == player.currentIndex) 1f else .85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = Radix.White.copy(alpha = .55f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconAction(Glyph.Up, "将${track.title}向前移动", enabled = index > 0,
                    tint = Radix.White.copy(alpha = .65f), onClick = { vm.playback.move(index, index - 1) })
                IconAction(Glyph.Close, "从队列移除${track.title}", tint = Radix.White.copy(alpha = .65f),
                    onClick = { vm.playback.remove(index) })
            }
        }
    }
}
