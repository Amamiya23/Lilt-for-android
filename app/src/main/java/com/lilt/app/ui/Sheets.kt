@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lilt.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilt.app.data.Artist
import com.lilt.app.data.Playlist
import com.lilt.app.data.Song
import com.lilt.app.ui.theme.lilt

/** The two looks of a sheet: dark glass over the player, the app's own surfaces elsewhere. */
@Immutable
data class SheetColors(val container: Color, val content: Color, val secondary: Color, val tile: Color,
                       val divider: Color, val chevron: Color, val handle: Color, val scrim: Color)

@Composable
fun sheetColors(dark: Boolean): SheetColors = if (dark) SheetColors(
    container = com.lilt.app.ui.theme.Radix.SlateDark3, content = Color.White, secondary = Color.White.copy(alpha = .6f),
    tile = Color.White.copy(alpha = .07f), divider = Color.White.copy(alpha = .07f), chevron = Color.White.copy(alpha = .35f),
    handle = Color.White.copy(alpha = .3f), scrim = Color.Black.copy(alpha = .42f),
) else SheetColors(
    container = MaterialTheme.colorScheme.background, content = MaterialTheme.colorScheme.onSurface,
    secondary = MaterialTheme.colorScheme.onSurfaceVariant, tile = MaterialTheme.colorScheme.surface,
    divider = MaterialTheme.lilt.divider, chevron = MaterialTheme.lilt.textTertiary,
    handle = MaterialTheme.colorScheme.outline.copy(alpha = .6f), scrim = Color.Black.copy(alpha = .32f),
)

@Composable
fun LiltSheet(onDismiss: () -> Unit, dark: Boolean, content: @Composable ColumnScope.(SheetColors) -> Unit) {
    val colors = sheetColors(dark)
    ModalBottomSheet(onDismiss, containerColor = colors.container, contentColor = colors.content, scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).background(colors.handle, CircleShape)) }) {
        CompositionLocalProvider(LocalContentColor provides colors.content) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) { content(colors) }
        }
    }
}

@Composable
fun SheetGroup(colors: SheetColors, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.tile).padding(horizontal = 16.dp, vertical = 2.dp),
        content = content)
}

@Composable
fun SheetRow(colors: SheetColors, glyph: Glyph, label: String, value: String? = null, divider: Boolean = false, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).quietClickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(glyph, Modifier.size(21.dp), colors.content.copy(alpha = .8f))
            Spacer(Modifier.width(14.dp))
            Text(label, Modifier.weight(1f), fontSize = 15.sp, color = colors.content)
            if (value != null) Text(value, fontSize = 13.sp, color = colors.secondary.copy(alpha = .8f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp).padding(start = 8.dp))
            Spacer(Modifier.width(4.dp))
            AppIcon(Glyph.Right, Modifier.size(18.dp), colors.chevron)
        }
        if (divider) HorizontalDivider(thickness = 1.dp, color = colors.divider)
    }
}

private data class SheetTile(val glyph: Glyph, val label: String, val onClick: () -> Unit, val tint: Color? = null)

/** A song's actions. On the player it adds playback quality and the sleep timer; liking lives on the player itself. */
@Composable
fun SongSheet(song: Song, fromPlayer: Boolean, liked: Boolean, qualityLabel: String, sleepLabel: String,
              onDismiss: () -> Unit, onPlayNext: () -> Unit, onAddToPlaylist: () -> Unit, onDownload: () -> Unit,
              onArtist: () -> Unit, onAlbum: (() -> Unit)?, onLike: () -> Unit,
              onQuality: () -> Unit, onSleep: () -> Unit) {
    LiltSheet(onDismiss, dark = fromPlayer) { colors ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 4.dp)) {
            Artwork(song.cover, Modifier.size(52.dp), 10.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(song.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp,
                    color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val online = !song.mid.startsWith("content:")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val likeTint = if (liked) MaterialTheme.lilt.like else colors.content
            listOfNotNull(
                if (online && !fromPlayer) SheetTile(Glyph.Heart, if (liked) "已喜欢" else "喜欢", onLike, likeTint) else null,
                SheetTile(Glyph.PlayNext, "下一首播放", onPlayNext),
                if (online) SheetTile(Glyph.PlaylistAdd, "添加到歌单", onAddToPlaylist) else null,
                if (online) SheetTile(Glyph.Download, "下载", onDownload) else null,
            ).forEach { (glyph, label, action, tint) ->
                Column(Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(18.dp)).background(colors.tile).quietClickable(onClick = action),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    AppIcon(glyph, Modifier.size(22.dp), tint ?: colors.content)
                    Spacer(Modifier.height(8.dp))
                    Text(label, fontSize = 11.sp, color = colors.content.copy(alpha = .8f), maxLines = 1)
                }
            }
        }
        if (online) SheetGroup(colors) {
            SheetRow(colors, Glyph.User, "查看歌手", song.artist, divider = onAlbum != null, onClick = onArtist)
            if (onAlbum != null) SheetRow(colors, Glyph.Disc, "查看专辑", song.album, onClick = onAlbum)
        }
        if (fromPlayer) SheetGroup(colors) {
            SheetRow(colors, Glyph.Headphones, "播放音质", qualityLabel, divider = true, onClick = onQuality)
            SheetRow(colors, Glyph.Sleep, "睡眠定时", sleepLabel, onClick = onSleep)
        }
    }
}

/** A song with several credited artists asks which one to open. */
@Composable
fun ArtistPickerSheet(song: Song, dark: Boolean, onDismiss: () -> Unit, onBack: () -> Unit, onArtist: (Artist) -> Unit) {
    LiltSheet(onDismiss, dark) { colors ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircleAction(Glyph.Back, "返回", colors.tile.copy(alpha = if (dark) .14f else 1f), size = 36.dp, onClick = onBack)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("选择歌手", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("${song.title} · ${song.artists.size} 位歌手", fontSize = 12.sp, color = colors.secondary)
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.tile).padding(horizontal = 16.dp, vertical = 4.dp)) {
            song.artists.forEachIndexed { index, artist ->
                Row(Modifier.fillMaxWidth().height(64.dp).quietClickable { onArtist(artist) }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Artwork(artist.cover, Modifier.size(44.dp), 22.dp)
                    Text(artist.name, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    AppIcon(Glyph.Right, Modifier.size(18.dp), colors.chevron)
                }
                if (index < song.artists.lastIndex) HorizontalDivider(thickness = 1.dp, color = colors.divider)
            }
        }
    }
}

/** Names a playlist; QQ Music caps names at 20 characters and keeps the trimmed text. */
@Composable
fun PlaylistNameDialog(title: String, confirm: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val trimmed = name.text.trim()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = if (it.text.length > 20) it.copy(it.text.take(20)) else it }, singleLine = true,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().focusRequester(focus))
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 0.dp,
        dismissButton = { QuietTextButton(onDismiss) { Text("取消") } },
        confirmButton = {
            QuietTextButton({ onConfirm(trimmed) }, enabled = trimmed.isNotEmpty() && trimmed != initial) { Text(confirm) }
        })
}

@Composable
fun PlaylistPickerSheet(playlists: List<Playlist>, dark: Boolean, onDismiss: () -> Unit, onCreate: () -> Unit, onPick: (Playlist) -> Unit) {
    LiltSheet(onDismiss, dark) { colors ->
        Text("添加到歌单", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(18.dp)).background(colors.tile)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().height(64.dp).quietClickable(onClick = onCreate), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(colors.divider), contentAlignment = Alignment.Center) {
                    AppIcon(Glyph.Plus, Modifier.size(22.dp), colors.content)
                }
                Text("新建歌单", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            if (playlists.isNotEmpty()) HorizontalDivider(thickness = 1.dp, color = colors.divider)
            playlists.forEachIndexed { index, playlist ->
                Row(Modifier.fillMaxWidth().height(64.dp).quietClickable { onPick(playlist) }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Artwork(playlist.cover, Modifier.size(44.dp), 10.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(playlist.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${playlist.trackCount} 首", fontSize = 12.sp, color = colors.secondary)
                    }
                }
                if (index < playlists.lastIndex) HorizontalDivider(thickness = 1.dp, color = colors.divider)
            }
        }
    }
}

@Composable
fun ChoiceSheet(title: String, options: List<Pair<String, String>>, selected: String,
                onDismiss: () -> Unit, dark: Boolean = false, onSelect: (String) -> Unit) {
    LiltSheet(onDismiss, dark) { colors ->
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.tile).padding(horizontal = 16.dp, vertical = 2.dp)) {
            options.forEachIndexed { index, (id, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).quietClickable { onSelect(id) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), fontSize = 15.sp)
                    if (selected == id) AppIcon(Glyph.Check, Modifier.size(20.dp),
                        if (dark) Color.White else MaterialTheme.colorScheme.primary)
                }
                if (index < options.lastIndex) HorizontalDivider(thickness = 1.dp, color = colors.divider)
            }
        }
    }
}
