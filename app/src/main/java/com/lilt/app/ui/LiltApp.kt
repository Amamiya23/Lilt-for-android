@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lilt.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilt.app.*
import com.lilt.app.data.*
import com.lilt.app.playback.PlaybackSnapshot
import com.lilt.app.ui.theme.Radix
import com.lilt.app.ui.theme.lilt
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

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
    data class Create(val song: Song) : SongMenu
    data class AddSelection(val songs: List<Song>) : SongMenu
    data class CreateSelection(val songs: List<Song>) : SongMenu
    data class Delete(val song: Song) : SongMenu
    data object Quality : SongMenu
    data object Sleep : SongMenu
}

@Composable
fun LiltApp(vm: LiltViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var chromeHeight by remember { mutableIntStateOf(0) }
    val tabStates = rememberSaveableStateHolder()
    val hazeState = remember { HazeState() }
    var menu by remember { mutableStateOf<SongMenu?>(null) }
    val selectableSongs = songsForSelection(state)
    val availableIds = selectableSongs.map { it.mid }.toSet()
    val selection = remember(selectionScope(state)) { SongSelectionState() }
    var deleteSelection by remember(selection) { mutableStateOf<List<Song>?>(null) }
    var removeSelection by remember(selection) { mutableStateOf<Pair<Playlist, List<Song>>?>(null) }
    val editablePlaylist = state.detail?.playlist?.takeIf {
        state.destination == Destination.Playlist && (it.dirId in 1 until 200 || it.dirId == 201)
    }
    val removableIds = if (editablePlaylist != null) availableIds else state.local.map { it.mid }.toSet()
    LaunchedEffect(selection, availableIds) { selection.reconcile(availableIds) }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // The search keyboard must not stay over the player.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(state.playerOpen, selection.active) {
        if (state.playerOpen || selection.active) focus.clearFocus()
    }
    BackHandler(state.playerOpen || state.destination != Destination.Home) { vm.back() }
    BackHandler(selection.active && !state.playerOpen) { selection.clear() }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // The floating selection actions: a 68dp card on a 12dp margin, above the navigation bar inset.
    val selectionBarHeight = 80.dp
    val onMore: (Song) -> Unit = { vm.refreshSongLike(it); menu = SongMenu.Actions(it) }
    val playerPosition = remember { Animatable(if (state.playerOpen) 0f else 1f) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Black shows only around the receding page while the player slides over it.
        BoxWithConstraints(Modifier.fillMaxSize().background(Radix.Black)) {
            val expanded = maxWidth >= 700.dp
            val standalone = state.destination == Destination.Settings || state.destination == Destination.Login
            val showMini = player.current != null || state.preview
            Row(Modifier.fillMaxSize().recedeBehindPlayer(playerPosition).background(MaterialTheme.colorScheme.background)) {
                if (expanded && !standalone) {
                    NavigationRail(Modifier.statusBarsPadding(), containerColor = MaterialTheme.colorScheme.surface,
                        header = {
                            AppIcon(Glyph.Music, Modifier.padding(vertical = 24.dp).size(28.dp), MaterialTheme.colorScheme.primary)
                        }) {
                        Tabs.forEach { (destination, glyph, title) ->
                            val source = remember { MutableInteractionSource() }
                            CompositionLocalProvider(LocalRippleConfiguration provides QuietRippleConfiguration) {
                            NavigationRailItem(state.topLevel == destination, { vm.navigate(destination) },
                                interactionSource = source,
                                icon = { AppIcon(glyph, Modifier.pressFeedback(source, .9f, .7f)) }, label = { Text(title) },
                                colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val chromePadding = if (standalone) 24.dp else if (expanded) (if (showMini) 104.dp else 32.dp)
                        else if (showMini) 168.dp else 100.dp
                    // Only ever grow the padding while selecting: shrinking it would re-clamp a list scrolled to its end.
                    val bottomPadding = if (selection.active) maxOf(chromePadding, selectionBarHeight + 8.dp +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()) else chromePadding
                    Column(Modifier.widthIn(max = if (expanded) 880.dp else 700.dp).fillMaxSize().align(Alignment.TopCenter)
                        .hazeSource(hazeState).background(MaterialTheme.colorScheme.background)) {
                        CompositionLocalProvider(
                            LocalSongAvailability provides { song -> state.preview || state.online ||
                                song.mid.startsWith("content:") || song.mid.startsWith("file:") || song.mid in state.cachedSongs },
                            LocalTopInset provides topInset,
                            LocalSongSelection provides SongSelectionContext(selection, availableIds)) {
                            Box(Modifier.weight(1f)) {
                                val page: @Composable () -> Unit = { when (state.destination) {
                                    Destination.Home -> HomeScreen(state, profile, vm, bottomPadding, onMore)
                                    Destination.Search -> SearchScreen(state, vm, bottomPadding, onMore)
                                    Destination.Library -> LibraryScreen(state, profile, vm, bottomPadding)
                                    Destination.Settings -> SettingsScreen(state, profile, vm)
                                    Destination.Login -> LoginScreen(state, vm)
                                    Destination.Playlist -> PlaylistScreen(state, vm, bottomPadding, onMore)
                                    Destination.Playlists -> PlaylistCollectionScreen(state, vm, bottomPadding)
                                    Destination.Albums -> AlbumCollectionScreen(state, vm, bottomPadding)
                                    Destination.Purchased -> PurchasedScreen(state, vm, bottomPadding, onMore)
                                    Destination.Local -> LocalScreen(state, vm, bottomPadding, onMore)
                                } }
                                if (state.destination == Destination.Home || state.destination == Destination.Search || state.destination == Destination.Library) {
                                    tabStates.SaveableStateProvider(state.destination.name) { page() }
                                } else page()
                            }
                        }
                    }
                    // Pages scroll under the status bar; the design covers it with glass.
                    if (!selection.active && state.destination != Destination.Login && state.destination != Destination.Playlist) Box(Modifier.fillMaxWidth().height(topInset)
                        .background(MaterialTheme.lilt.glass.copy(alpha = .985f)))
                    val chromeWidth = if (expanded) Modifier.widthIn(max = 760.dp).padding(horizontal = 24.dp) else Modifier
                    // Selection swaps the page header and bottom chrome for its own floating bars; none takes layout space.
                    androidx.compose.animation.AnimatedVisibility(selection.active, Modifier.align(Alignment.TopCenter),
                        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 3 },
                        exit = fadeOut(tween(120)) + slideOutVertically(tween(180)) { -it / 3 }) {
                        SongSelectionHeader(selection, availableIds, state.batchBusy, topInset, hazeState)
                    }
                    androidx.compose.animation.AnimatedVisibility(!selection.active && !standalone, Modifier.align(Alignment.BottomCenter),
                        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 4 },
                        exit = fadeOut(tween(120)) + slideOutVertically(tween(180)) { it / 4 }) {
                        Column(chromeWidth.fillMaxWidth().onSizeChanged { chromeHeight = it.height },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (showMini) MiniPlayer(player, vm.currentSong(), state.preview, hazeState,
                                Modifier.padding(horizontal = 12.dp), { vm.showPlayer(true) }, vm.playback::toggle, vm.playback::next)
                            if (!expanded) BottomNavigation(state.topLevel, hazeState, vm::navigate)
                            else Spacer(Modifier.navigationBarsPadding().height(16.dp))
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(selection.active, Modifier.align(Alignment.BottomCenter),
                        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 },
                        exit = fadeOut(tween(120)) + slideOutVertically(tween(180)) { it / 3 }) {
                        SongSelectionActions(selection, selectableSongs, removableIds, state.batchBusy,
                            onAddTo = { songs ->
                                if (profile == null && !state.preview) vm.navigate(Destination.Login)
                                else menu = SongMenu.AddSelection(songs)
                            }, onDownload = vm::downloadSongs, onRemove = { songs ->
                                if (editablePlaylist != null) removeSelection = editablePlaylist to songs
                                else deleteSelection = songs
                            }, hazeState = hazeState, modifier = chromeWidth.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
                    }
                }
            }
            PlayerOverlay(state, player, vm, playerPosition, onMore)
            // Toasts float just clear of whatever chrome sits at the bottom; over the player, its controls stay free.
            val toastPlacement = when {
                state.playerOpen -> Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp)
                standalone -> Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)
                selection.active -> Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = selectionBarHeight + 12.dp)
                else -> Modifier.align(Alignment.BottomCenter).padding(bottom = with(LocalDensity.current) { chromeHeight.toDp() } + 12.dp)
            }
            SnackbarHost(snackbar, toastPlacement) { LiltToast(it.visuals.message) }
        }
    }
    deleteSelection?.let { songs ->
        LiltDialog("删除 ${songs.size} 首已下载的歌曲？", "删除", onDismiss = { deleteSelection = null },
            onConfirm = { deleteSelection = null; vm.deleteLocalSongs(songs) }, destructive = true, enabled = !state.batchBusy) {
            Text("歌词和封面会一并删除")
        }
    }
    removeSelection?.let { (playlist, songs) ->
        val liked = playlist.dirId == 201
        LiltDialog(if (liked) "取消喜欢 ${songs.size} 首歌曲？" else "从「${playlist.title}」移除 ${songs.size} 首歌曲？",
            if (liked) "取消喜欢" else "移除", onDismiss = { removeSelection = null },
            onConfirm = { removeSelection = null; vm.removeSongsFromPlaylist(songs, playlist) },
            destructive = true, enabled = !state.batchBusy)
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
                    vm.download(song)
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
                onSleep = { menu = SongMenu.Sleep },
                onDelete = if (state.local.any { it.mid == song.mid }) ({ menu = SongMenu.Delete(song) }) else null)
        }
        is SongMenu.Artists -> ArtistPickerSheet(current.song, dark, { menu = null }, { menu = SongMenu.Actions(current.song) }) {
            menu = null; vm.showPlayer(false); vm.openArtist(it)
        }
        is SongMenu.AddTo -> PlaylistPickerSheet(state.library.filter { it.dirId in 1 until 200 }, dark, { menu = null },
            onCreate = { menu = SongMenu.Create(current.song) }) {
            vm.addToPlaylist(current.song, it); menu = null
        }
        is SongMenu.Create -> PlaylistNameDialog("新建歌单", "创建", onDismiss = { menu = null }) {
            vm.createPlaylist(it, current.song); menu = null
        }
        is SongMenu.AddSelection -> PlaylistPickerSheet(state.library.filter { it.dirId in 1 until 200 || it.dirId == 201 },
            dark, { menu = null }, onCreate = { menu = SongMenu.CreateSelection(current.songs) }) {
            vm.addSongsToPlaylist(current.songs, it); menu = null
        }
        is SongMenu.CreateSelection -> PlaylistNameDialog("新建歌单", "创建", onDismiss = { menu = null }) {
            vm.createPlaylistWithSongs(it, current.songs); menu = null
        }
        is SongMenu.Delete -> LiltDialog("删除已下载的「${current.song.title}」？", "删除", onDismiss = { menu = null },
            onConfirm = { menu = null; vm.deleteLocal(current.song) }, destructive = true) {
            Text("歌词和封面会一并删除")
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
private fun BottomNavigation(destination: Destination, hazeState: HazeState, onNavigate: (Destination) -> Unit) {
    val edge = MaterialTheme.lilt.glassEdge
    val glass = MaterialTheme.lilt.glass
    Box(Modifier.fillMaxWidth().testTag("bottom-navigation").hazeEffect(hazeState) {
        blurRadius = 24.dp
        tints = listOf(HazeTint(glass.copy(alpha = .72f)))
    }.drawBehind {
        drawLine(edge, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx())
    }) {
        Row(Modifier.fillMaxWidth().selectableGroup().navigationBarsPadding().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround) {
            Tabs.forEach { (tab, icon, title) ->
                val selected = destination == tab
                val source = remember { MutableInteractionSource() }
                // Keep the indicator's RGB fixed: fading toward transparent black produces a grey intermediate frame.
                val indicatorAlpha by animateFloatAsState(if (selected) 1f else 0f,
                    animationSpec = tween(120), label = "navigationIndicatorAlpha")
                val tint by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(120), label = "navigationTint")
                Column(Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).quietIndication(source)
                    .selectable(selected, interactionSource = source, indication = null, role = Role.Tab, onClick = { onNavigate(tab) })
                    .padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                    Box(Modifier.size(56.dp, 30.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = indicatorAlpha), CircleShape),
                        contentAlignment = Alignment.Center) {
                        AppIcon(icon, tint = tint)
                    }
                    Text(title, fontSize = 12.sp, lineHeight = 16.sp, color = tint, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(player: PlaybackSnapshot, previewSong: Song?, preview: Boolean, hazeState: HazeState, modifier: Modifier,
                       onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val shadow = MaterialTheme.lilt.shadow
    val glass = MaterialTheme.lilt.glass
    Box(modifier.fillMaxWidth().height(60.dp)
        .shadow(18.dp, shape, ambientColor = shadow.copy(alpha = .10f), spotColor = shadow.copy(alpha = .14f))
        .clip(shape).hazeEffect(hazeState) { blurRadius = 24.dp; tints = listOf(HazeTint(glass.copy(alpha = .72f))) }
        .border(1.dp, MaterialTheme.lilt.glassEdge, shape)) {
        Row(Modifier.fillMaxSize().quietClickable(onClick = onOpen).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(player.current?.artwork ?: previewSong?.cover, Modifier.size(44.dp), 12.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(player.current?.title ?: previewSong?.title.orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (player.error != null) "暂时无法播放" else player.current?.artist ?: previewSong?.artist.orEmpty(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (player.isBuffering) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            else TransportButton(if (player.showsPause) Transport.Pause else Transport.Play, if (player.showsPause) "暂停" else "播放",
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
