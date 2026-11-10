@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lilt.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilt.app.*
import com.lilt.app.data.*
import com.lilt.app.playback.PlaybackSnapshot
import com.lilt.app.ui.theme.lilt

private val Tabs = listOf(
    Triple(Destination.Home, Glyph.Home, "首页"),
    Triple(Destination.Search, Glyph.Search, "搜索"),
    Triple(Destination.Library, Glyph.User, "我的")
)

/** The status bar height: pages scroll beneath it, under a strip of glass. */
val LocalTopInset = staticCompositionLocalOf { 0.dp }

private sealed interface SongMenu {
    data class Actions(val song: Song) : SongMenu
    data class Artists(val song: Song) : SongMenu
    data class AddTo(val song: Song) : SongMenu
    data object Quality : SongMenu
    data object Sleep : SongMenu
}

@Composable
fun LiltApp(vm: LiltViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf<SongMenu?>(null) }
    var pendingDownload by remember { mutableStateOf<Song?>(null) }
    val context = LocalContext.current
    val downloadPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingDownload?.let(vm::download)
        else vm.notify("需要存储权限才能保存到设备音乐目录")
        pendingDownload = null
    }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // The search keyboard must not stay over the player.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(state.playerOpen) { if (state.playerOpen) focus.clearFocus() }
    BackHandler(state.playerOpen || state.destination != Destination.Home) { vm.back() }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val onMore: (Song) -> Unit = { menu = SongMenu.Actions(it) }
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
                                icon = { AppIcon(glyph) }, label = { Text(title) },
                                colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val bottomPadding = if (standalone) 24.dp else if (expanded) (if (showMini) 104.dp else 32.dp)
                        else if (showMini) 168.dp else 100.dp
                    CompositionLocalProvider(LocalTopInset provides topInset) {
                        Box(Modifier.widthIn(max = if (expanded) 880.dp else 700.dp).fillMaxSize().align(Alignment.TopCenter)) {
                            when (state.destination) {
                                Destination.Home -> HomeScreen(state, profile, vm, bottomPadding, onMore)
                                Destination.Search -> SearchScreen(state, vm, bottomPadding, onMore)
                                Destination.Library -> LibraryScreen(state, profile, vm, bottomPadding)
                                Destination.Settings -> SettingsScreen(state, profile, vm)
                                Destination.Login -> LoginScreen(state, vm)
                                Destination.Playlist -> PlaylistScreen(state, vm, bottomPadding, onMore)
                                Destination.Playlists -> PlaylistCollectionScreen(state, vm, bottomPadding)
                                Destination.Albums -> AlbumCollectionScreen(state, vm, bottomPadding)
                                Destination.Local -> LocalScreen(state, vm, bottomPadding, onMore)
                            }
                        }
                    }
                    // Pages scroll under the status bar; the design covers it with glass.
                    if (state.destination != Destination.Login && state.destination != Destination.Playlist) Box(Modifier.fillMaxWidth().height(topInset)
                        .background(MaterialTheme.lilt.glass.copy(alpha = .985f)))
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
                PlayerScreen(state, player, vm, onMore)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = if (state.playerOpen || standalone) 16.dp else 88.dp, start = 16.dp, end = 16.dp))
        }
    }
    val dark = state.playerOpen
    when (val current = menu) {
        is SongMenu.Actions -> {
            val song = current.song
            SongSheet(song, fromPlayer = state.playerOpen, liked = song.mid in state.liked,
                qualityLabel = qualityLabel(state.quality), sleepLabel = sleepLabel(state.sleepAt),
                onDismiss = { menu = null },
                onPlayNext = { vm.playNext(song); menu = null },
                onAddToPlaylist = {
                    if (profile == null) { menu = null; vm.showPlayer(false); vm.navigate(Destination.Login) }
                    else menu = SongMenu.AddTo(song)
                },
                onDownload = {
                    menu = null
                    if (Build.VERSION.SDK_INT <= 28 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        pendingDownload = song
                        downloadPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else vm.download(song)
                },
                onShare = {
                    menu = null
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                        "${song.title} - ${song.artist}\nhttps://y.qq.com/n/ryqq/songDetail/${song.mid}")
                    context.startActivity(Intent.createChooser(share, "分享歌曲"))
                },
                onArtist = {
                    val artists = song.artists.filter { it.mid.isNotBlank() }
                    when {
                        artists.size > 1 -> menu = SongMenu.Artists(song.copy(artists = artists))
                        artists.size == 1 -> { menu = null; vm.showPlayer(false); vm.openArtist(artists.first()) }
                        else -> { menu = null; vm.showPlayer(false); vm.search(song.artist, 1) }
                    }
                },
                onAlbum = song.albumMid.takeIf { it.isNotBlank() }?.let { mid ->
                    { menu = null; vm.showPlayer(false); vm.openAlbum(Album(mid, 0, song.album, song.artist, song.cover)) }
                },
                onLike = { vm.toggleLike(song); menu = null },
                onQuality = { menu = SongMenu.Quality },
                onSleep = { menu = SongMenu.Sleep })
        }
        is SongMenu.Artists -> ArtistPickerSheet(current.song, dark, { menu = null }, { menu = SongMenu.Actions(current.song) }) {
            menu = null; vm.showPlayer(false); vm.openArtist(it)
        }
        is SongMenu.AddTo -> PlaylistPickerSheet(state.library.filter { it.dirId in 1 until 200 }, dark, { menu = null }) {
            vm.addToPlaylist(current.song, it); menu = null
        }
        SongMenu.Quality -> ChoiceSheet("在线播放音质", QualityOptions, state.quality, { menu = null }, dark) {
            vm.setQuality(it); menu = null; vm.notify("将在下一首歌曲生效")
        }
        SongMenu.Sleep -> ChoiceSheet("睡眠定时", SleepOptions, "", { menu = null }, dark) {
            vm.setSleepTimer(it.toInt()); menu = null
        }
        null -> Unit
    }
}

private val SleepOptions = listOf("0" to "关闭", "15" to "15 分钟", "30" to "30 分钟", "45" to "45 分钟", "60" to "60 分钟", "90" to "90 分钟")

private fun sleepLabel(at: Long): String {
    if (at <= 0) return "关闭"
    val minutes = ((at - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)
    return "$minutes 分钟后"
}

@Composable
private fun BottomNavigation(destination: Destination, onNavigate: (Destination) -> Unit) {
    val edge = MaterialTheme.lilt.glassEdge
    Box(Modifier.fillMaxWidth().background(MaterialTheme.lilt.glass.copy(alpha = .985f)).drawBehind {
        drawLine(edge, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx())
    }) {
        Row(Modifier.fillMaxWidth().selectableGroup().navigationBarsPadding().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround) {
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
                    Text(title, fontSize = 11.sp, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(player: PlaybackSnapshot, previewSong: Song?, preview: Boolean, modifier: Modifier,
                       onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val shadow = MaterialTheme.lilt.shadow
    Box(modifier.fillMaxWidth().height(60.dp)
        .shadow(18.dp, shape, ambientColor = shadow.copy(alpha = .10f), spotColor = shadow.copy(alpha = .14f))
        .clip(shape).background(MaterialTheme.lilt.glass.copy(alpha = .985f)).border(1.dp, MaterialTheme.lilt.glassEdge, shape)) {
        Row(Modifier.fillMaxSize().clickable(onClick = onOpen).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(player.current?.artwork ?: previewSong?.cover, Modifier.size(44.dp), 12.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(player.current?.title ?: previewSong?.title.orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (player.error != null) "暂时无法播放" else player.current?.artist ?: previewSong?.artist.orEmpty(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (player.isBuffering) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            else TransportButton(if (player.isPlaying) Transport.Pause else Transport.Play, if (player.isPlaying) "暂停" else "播放",
                26.dp, onClick = onToggle)
            TransportButton(Transport.Next, "下一首", 26.dp, onClick = onNext)
        }
        LinearProgressIndicator(progress = { if (preview) .24f else player.progress },
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp).fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent, drawStopIndicator = {})
    }
}

/** A page's top bar spacing below the glass status bar. */
@Composable
fun topPadding(extra: Dp): Dp = LocalTopInset.current + extra
