@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lilt.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilt.app.*
import com.lilt.app.data.*
import com.lilt.app.playback.PlaybackSnapshot
import com.lilt.app.ui.theme.Radix
import kotlinx.coroutines.launch
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

private val Tabs = listOf(
    Triple(Destination.Home, Glyph.Home, "首页"),
    Triple(Destination.Search, Glyph.Search, "搜索"),
    Triple(Destination.Library, Glyph.User, "我的")
)

@Composable
fun LiltApp(vm: LiltViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var moreSong by remember { mutableStateOf<Song?>(null) }
    var pendingDownload by remember { mutableStateOf<Song?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val downloadPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingDownload?.let(vm::download)
        else vm.notify("需要存储权限才能保存到设备音乐目录")
        pendingDownload = null
    }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(state.playerOpen || state.destination != Destination.Home) { vm.back() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 700.dp
            val standalone = state.destination == Destination.Settings || state.destination == Destination.Login
            val showMini = player.current != null || state.preview
            Row(Modifier.fillMaxSize()) {
                if (expanded && !standalone) {
                    NavigationRail(Modifier.statusBarsPadding(), containerColor = MaterialTheme.colorScheme.surface,
                        header = {
                            AppIcon(Glyph.Music, Modifier.padding(vertical = 24.dp).size(28.dp), MaterialTheme.colorScheme.primary)
                        }) {
                        Tabs.forEach { (destination, glyph, title) ->
                            NavigationRailItem(state.topLevel == destination, { vm.navigate(destination) },
                                icon = { AppIcon(glyph) }, label = { Text(title) })
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().then(if (state.destination == Destination.Login) Modifier else Modifier.statusBarsPadding())) {
                    val bottomPadding = if (standalone) 24.dp else if (expanded) (if (showMini) 104.dp else 32.dp)
                        else if (showMini) 168.dp else 100.dp
                    Box(Modifier.widthIn(max = if (expanded) 880.dp else 700.dp).fillMaxSize().align(Alignment.TopCenter)) {
                        when (state.destination) {
                            Destination.Home -> HomeScreen(state, profile, vm, bottomPadding, { moreSong = it })
                            Destination.Search -> SearchScreen(state, vm, bottomPadding, { moreSong = it })
                            Destination.Library -> LibraryScreen(state, profile, vm, bottomPadding)
                            Destination.Settings -> SettingsScreen(state, profile, vm)
                            Destination.Login -> LoginScreen(state, vm)
                            Destination.Playlist -> PlaylistScreen(state, vm, bottomPadding, { moreSong = it })
                            Destination.Playlists -> PlaylistCollectionScreen(state, vm, bottomPadding)
                            Destination.Local -> LocalScreen(state, vm, bottomPadding, { moreSong = it })
                        }
                    }
                    if (!standalone) Column(Modifier.align(Alignment.BottomCenter)
                        .then(if (expanded) Modifier.widthIn(max = 760.dp).padding(horizontal = 24.dp) else Modifier).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (showMini) MiniPlayer(player, vm.currentSong(), state.preview,
                            Modifier.padding(horizontal = 12.dp), { vm.showPlayer(true) }, vm.playback::toggle, vm.playback::next)
                        if (!expanded) BottomNavigation(state.topLevel, vm::navigate)
                        else Spacer(Modifier.navigationBarsPadding().height(16.dp))
                    }
                }
            }
            AnimatedVisibility(state.playerOpen, enter = slideInVertically(tween(330)) { it / 3 } + fadeIn(tween(260)),
                exit = slideOutVertically(tween(260)) { it / 3 } + fadeOut(tween(230))) {
                PlayerScreen(state, player, vm) { moreSong = it }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = if (state.playerOpen || standalone) 16.dp else 88.dp, start = 16.dp, end = 16.dp))
        }
    }
    moreSong?.let { song ->
        ModalBottomSheet({ moreSong = null }, containerColor = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.cover, Modifier.size(56.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(horizontal = 24.dp)) {
                SettingRow(Glyph.Music, "播放", onClick = { vm.play(listOf(song)); moreSong = null })
                SettingRow(Glyph.Heart, if (song.mid in state.liked) "取消喜欢" else "喜欢", onClick = { vm.toggleLike(song); moreSong = null })
                SettingRow(Glyph.Microphone, "查看歌手", song.artist, {
                    vm.showPlayer(false); vm.search(song.artist, 1); moreSong = null
                })
                SettingRow(Glyph.Share, "分享歌曲", onClick = {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                        "${song.title} - ${song.artist}\nhttps://y.qq.com/n/ryqq/songDetail/${song.mid}")
                    context.startActivity(Intent.createChooser(share, "分享歌曲"))
                    moreSong = null
                })
                if (!song.mid.startsWith("content:")) SettingRow(Glyph.Download, "下载", onClick = {
                    if (Build.VERSION.SDK_INT <= 28 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        pendingDownload = song
                        downloadPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else vm.download(song)
                    moreSong = null
                })
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun BottomNavigation(destination: Destination, onNavigate: (Destination) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))) {
        Row(Modifier.fillMaxWidth().selectableGroup().navigationBarsPadding().padding(top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
            Tabs.forEach { (tab, icon, title) ->
                val selected = destination == tab
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .selectable(selected, role = Role.Tab, onClick = { onNavigate(tab) })
                    .padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(56.dp, 30.dp).background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center) {
                        AppIcon(icon, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(title, style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(player: PlaybackSnapshot, previewSong: Song?, preview: Boolean, modifier: Modifier,
                       onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    Surface(modifier.fillMaxWidth().height(64.dp).shadow(12.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))) {
        Box {
            Row(Modifier.fillMaxSize().clickable(onClick = onOpen).padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(player.current?.artwork ?: previewSong?.cover, Modifier.size(44.dp), 12.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(player.current?.title ?: previewSong?.title.orEmpty(), style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (player.error != null) "暂时无法播放" else player.current?.artist ?: previewSong?.artist.orEmpty(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (player.isBuffering) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                else TransportButton(if (player.isPlaying) Transport.Pause else Transport.Play, if (player.isPlaying) "暂停" else "播放",
                    26.dp, onClick = onToggle)
                TransportButton(Transport.Next, "下一首", 27.dp, onClick = onNext)
            }
            LinearProgressIndicator(progress = { if (preview) .24f else player.progress },
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp).fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent, drawStopIndicator = {})
        }
    }
}
