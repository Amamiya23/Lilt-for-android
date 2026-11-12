@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lilt.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilt.app.*
import com.lilt.app.data.*
import com.lilt.app.ui.theme.Radix
import com.lilt.app.ui.theme.lilt
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(state: AppState, profile: Profile?, vm: LiltViewModel, bottomPadding: Dp, onSongMore: (Song) -> Unit) {
    val player by vm.player.collectAsStateWithLifecycle()
    val home = state.home
    // The first QQ shelf is the lucky one; without an account the day's chart songs stand in.
    val lucky = (home.shelves.firstOrNull { it.title.contains("幸运") } ?: home.shelves.firstOrNull()) ?: home.songs.takeIf { it.isNotEmpty() }?.let {
        SongShelf((profile?.name?.let { "「$it」，" } ?: "") + "这是你的幸运好歌", it)
    }
    PullToRefreshBox(isRefreshing = state.homeLoading && home.playlists.isNotEmpty(), onRefresh = vm::refreshHome) {
        LazyColumn(Modifier.fillMaxSize().testTag("home-feed"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topPadding(8.dp), bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(28.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val greeting = when (LocalTime.now().hour) { in 5..10 -> "早上好"; in 11..13 -> "中午好"; in 14..18 -> "下午好"; else -> "晚上好" }
                        Text(greeting + (profile?.name?.let { "，$it" } ?: ""), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("推荐", style = MaterialTheme.typography.headlineMedium)
                            if (state.preview) Text("设计预览", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Box(Modifier.size(48.dp).clip(CircleShape).quietClickable { vm.navigate(if (profile == null) Destination.Login else Destination.Library) }
                        .semantics { contentDescription = "我的账号" }, contentAlignment = Alignment.Center) {
                        if (profile?.avatar?.isNotBlank() == true) Artwork(profile.avatar, Modifier.size(40.dp), 20.dp)
                        else Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                            AppIcon(Glyph.User, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { DailyCard(home.songs, profile != null || state.preview, onClick = vm::daily) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(Glyph.Radio to "猜你想听", Glyph.Queue to "歌单广场", Glyph.Microphone to "歌手", Glyph.Disc to "新歌")
                        .forEachIndexed { i, (glyph, label) ->
                            val shape = RoundedCornerShape(16.dp)
                            Column(Modifier.weight(1f).softShadow(shape, 4.dp).clip(shape).background(MaterialTheme.colorScheme.surface)
                                .quietClickable {
                                    when (i) {
                                        0 -> lucky?.songs?.let { vm.play(it, shuffle = true, source = "猜你想听"); vm.showPlayer(true) } ?: vm.refreshHome()
                                        1 -> vm.openPlaylists("歌单广场", home.playlists)
                                        2 -> vm.beginSearch(1)
                                        else -> home.charts.firstOrNull { it.title.contains("新歌") }?.let(vm::openPlaylist)
                                            ?: run { vm.notify("新歌榜暂不可用，请稍后重试"); vm.refreshHome() }
                                    }
                                }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AppIcon(glyph, tint = MaterialTheme.colorScheme.primary)
                                Text(label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                }
            }
            if (state.homeLoading && home.playlists.isEmpty()) item { LoadingState() }
            if (state.homeError != null) item {
                EmptyState("暂时无法加载推荐", state.homeError, Glyph.Alert, "重试", onAction = vm::refreshHome)
            }
            lucky?.let { shelf -> item { SongShelfSection(shelf, state, vm, player.current?.id, clover = shelf.title.contains("幸运")) } }
            if (home.playlists.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("为你推荐的歌单", "更多") { vm.openPlaylists("为你推荐的歌单", home.playlists) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(home.playlists, key = { it.id }) { playlist -> PlaylistCard(playlist) { vm.openPlaylist(playlist) } }
                    }
                }
            }
            if (home.charts.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("排行榜", "全部") { vm.openPlaylists("排行榜", home.charts) }
                    GroupCard(radius = 20.dp, vertical = 6.dp) {
                        home.charts.take(3).forEachIndexed { index, chart ->
                            ChartRow(chart, index) { vm.openPlaylist(chart) }
                            if (index < 2) RowDivider()
                        }
                    }
                }
            }
            home.shelves.filter { it !== lucky }.forEach { shelf -> item { SongShelfSection(shelf, state, vm, player.current?.id) } }
            if (!state.homeLoading && state.homeError == null && home.songs.isEmpty() && home.playlists.isEmpty()) {
                item { EmptyState("还没有推荐内容", action = "刷新", onAction = vm::refreshHome) }
            }
        }
    }
}

/** Pages of three songs with a like heart, as the design's lucky and similar-taste shelves. */
@Composable
private fun SongShelfSection(shelf: SongShelf, state: AppState, vm: LiltViewModel, playing: String?, clover: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(shelf.title.removeSuffix("🍀").removeSuffix("💗").trim(), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (clover) { Spacer(Modifier.width(6.dp)); AppIcon(Glyph.Clover, Modifier.size(20.dp), Radix.Clover) }
        }
        BoxWithConstraints {
            val page = (maxWidth - 46.dp).coerceAtMost(340.dp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(shelf.songs.chunked(3)) { group ->
                    Column(Modifier.width(page), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        group.forEach { song ->
                            val liked = song.mid in state.liked
                            SongRow(song, { vm.play(shelf.songs, shelf.songs.indexOf(song), source = shelf.title) }, {},
                                active = playing == song.mid, cover = 52.dp, coverRadius = 10.dp) {
                                IconAction(Glyph.Heart, if (liked) "取消喜欢${song.title}" else "喜欢${song.title}",
                                    tint = if (liked) MaterialTheme.lilt.like else MaterialTheme.lilt.textTertiary, iconSize = 20.dp,
                                    onClick = { vm.toggleLike(song) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartRow(chart: Playlist, index: Int, onClick: () -> Unit) {
    val accent = listOf(Radix.ChartRise, Radix.ChartHot, Radix.ChartNew)[index % 3]
    Row(Modifier.fillMaxWidth().quietClickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Artwork(chart.cover, Modifier.size(72.dp), 12.dp, chart.title)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(chart.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (chart.preview.isEmpty()) Text("${chart.trackCount} 首歌曲", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            chart.preview.take(3).forEachIndexed { rank, (title, artist) ->
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = if (rank == 0) accent else MaterialTheme.colorScheme.onSurfaceVariant)) {
                        append("${rank + 1}  ")
                    }
                    append(listOf(title, artist).filter { it.isNotBlank() }.joinToString(" - "))
                }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DailyCard(songs: List<Song>, signedIn: Boolean, onClick: () -> Unit) {
    val foreground = Color.White
    val shape = RoundedCornerShape(24.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().height(224.dp).shadow(14.dp, shape,
        ambientColor = Radix.DailyShade.copy(alpha = .2f), spotColor = Radix.DailyShade.copy(alpha = .25f))
        .clip(shape).background(Brush.linearGradient(listOf(Radix.DailyStart, Radix.DailyEnd, Radix.DailyShade.copy(alpha = .9f)),
            start = Offset(Float.POSITIVE_INFINITY, 0f), end = Offset(0f, Float.POSITIVE_INFINITY)))
        .quietClickable(onClick = onClick)) {
        val width = maxWidth
        if (songs.firstOrNull()?.cover?.isNotBlank() == true) {
            coil.compose.AsyncImage(songs.first().cover, null, Modifier.fillMaxSize().blur(28.dp).alpha(.28f),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Radix.DailyShade.copy(alpha = .85f), .6f to Color.Transparent)))
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * .84f, size.height * .5f)
            for (i in 0..18) drawCircle(foreground.copy(alpha = (.06f - i * .0025f).coerceAtLeast(.012f)),
                50.dp.toPx() + i * 18.dp.toPx(), center, style = Stroke(.65.dp.toPx()))
        }
        Text(LocalDate.now().dayOfMonth.toString(), Modifier.align(Alignment.Center).offset(x = 14.dp, y = (-15).dp),
            color = foreground.copy(alpha = .06f), fontWeight = FontWeight.ExtraBold, fontSize = 178.sp)
        Column(Modifier.align(Alignment.CenterStart).padding(start = 22.dp).width(width * .48f),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 · EEE", Locale.SIMPLIFIED_CHINESE)),
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .3.sp, color = foreground.copy(alpha = .66f))
            Text("每日推荐", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = foreground)
            Text(if (!signedIn) "登录后开启专属推荐" else if (songs.isEmpty()) "今天，也有好音乐"
                else "${songs.map { it.artist }.distinct().take(2).joinToString("、").take(18)} 等 ${songs.size} 首",
                fontSize = 12.sp, color = foreground.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(9.dp))
            Row(Modifier.height(36.dp).shadow(8.dp, CircleShape, ambientColor = Radix.SlateDark1.copy(alpha = .35f),
                spotColor = Radix.SlateDark1.copy(alpha = .35f)).background(foreground, CircleShape).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TransportIcon(Transport.Play, Modifier.size(14.dp), Radix.Ink)
                Text(if (signedIn) "播放" else "去登录", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Radix.Ink)
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).width(width * .47f).height(160.dp)) {
            songs.getOrNull(2)?.let {
                Artwork(it.cover, Modifier.size(97.dp).align(Alignment.CenterStart).rotate(-10f).alpha(.76f), 10.dp)
            }
            songs.getOrNull(1)?.let {
                Artwork(it.cover, Modifier.size(106.dp).align(Alignment.Center).offset(x = (-16).dp).rotate(-5f).alpha(.88f), 10.dp)
            }
            Box(Modifier.size(104.dp).align(Alignment.CenterEnd)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Radix.SlateDark1)
                    for (i in 0..10) drawCircle(foreground.copy(alpha = .07f), size.minDimension * (.22f + .025f * i), style = Stroke(.6.dp.toPx()))
                }
                if (songs.isNotEmpty()) Artwork(songs.first().cover, Modifier.size(37.dp).align(Alignment.Center), 19.dp)
            }
            if (songs.isNotEmpty()) Artwork(songs.first().cover,
                Modifier.size(112.dp).align(Alignment.Center).offset(x = 4.dp).shadow(16.dp, RoundedCornerShape(12.dp),
                    ambientColor = Radix.SlateDark1.copy(alpha = .5f), spotColor = Radix.SlateDark1.copy(alpha = .5f)), 12.dp)
        }
    }
}

@Composable
private fun BackBar(title: String, vm: LiltViewModel, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircleAction(Glyph.Back, "返回", MaterialTheme.colorScheme.surface, shadow = true, onClick = vm::back)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

@Composable
fun PlaylistCollectionScreen(state: AppState, vm: LiltViewModel, bottomPadding: Dp) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topPadding(4.dp), bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Box(Modifier.padding(start = 0.dp, bottom = 8.dp)) { BackBar(state.collectionTitle, vm) } }
        items(state.collectionPlaylists, key = { it.id }) { playlist -> PlaylistListRow(playlist) { vm.openPlaylist(playlist) } }
        if (state.collectionPlaylists.isEmpty()) item { EmptyState("暂无歌单", action = "返回") { vm.back() } }
    }
}

@Composable
fun AlbumCollectionScreen(state: AppState, vm: LiltViewModel, bottomPadding: Dp) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topPadding(4.dp), bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Box(Modifier.padding(bottom = 8.dp)) { BackBar(state.collectionTitle, vm) } }
        when {
            state.collectionLoading -> item { LoadingState() }
            state.collectionError != null -> item { EmptyState("加载失败", state.collectionError, Glyph.Alert, "重试") { vm.back(); vm.openLikedAlbums() } }
            state.collectionAlbums.isEmpty() -> item { EmptyState("还没有收藏的专辑", "在 QQ 音乐中收藏后，可在此查看", Glyph.Disc) }
            else -> items(state.collectionAlbums, key = { "album-${it.id}" }) { album -> AlbumRow(album) { vm.openAlbum(album) } }
        }
    }
}

@Composable
fun PurchasedScreen(state: AppState, vm: LiltViewModel, bottomPadding: Dp, onMore: (Song) -> Unit) {
    val player by vm.player.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(top = topPadding(4.dp))) {
        Box(Modifier.padding(horizontal = 20.dp)) { BackBar("已购音乐", vm) }
        Row(Modifier.selectableGroup().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PurchasedTab.entries.forEach { tab ->
                val selected = state.purchasedTab == tab
                val source = remember { MutableInteractionSource() }
                Box(Modifier.heightIn(min = 48.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface)
                    .quietIndication(source).pressFeedback(source, pressedScale = 1f)
                    .selectable(selected, interactionSource = source, indication = null, role = Role.Tab,
                        onClick = { vm.selectPurchasedTab(tab) })
                    .padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Text(tab.label, style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        key(state.purchasedTab) {
            LazyColumn(Modifier.weight(1f).testTag("purchased-list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    state.purchasedLoading -> item { LoadingState() }
                    state.purchasedError != null -> item {
                        EmptyState("加载失败", state.purchasedError, Glyph.Alert, "重试", onAction = vm::retryPurchased)
                    }
                    state.purchasedTab == PurchasedTab.Albums -> {
                        val albums = state.purchasedAlbums.orEmpty()
                        if (albums.isEmpty()) item { EmptyState("还没有已购专辑", glyph = Glyph.Disc) }
                        items(albums, key = { "album-${it.id}" }) { album -> AlbumRow(album) { vm.openAlbum(album) } }
                    }
                    else -> {
                        val songs = state.purchasedSongs.orEmpty()
                        if (songs.isEmpty()) item { EmptyState("还没有已购单曲", glyph = Glyph.Music) }
                        else item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${songs.size} 首", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SoftPill("播放全部", Glyph.Music) { vm.play(songs) }
                                IconAction(Glyph.Shuffle, "随机播放", tint = MaterialTheme.colorScheme.primary,
                                    onClick = { vm.play(songs, shuffle = true) })
                            }
                        }
                        itemsIndexed(songs, key = { _, song -> song.mid }) { index, song ->
                            SongRow(song, { vm.play(songs, index) }, { onMore(song) }, active = player.current?.id == song.mid)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(album: Album, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(12.dp)).quietClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(album.cover, Modifier.size(52.dp), 10.dp, album.title)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(album.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (album.artist.isNotBlank()) Text(album.artist, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clip(RoundedCornerShape(16.dp)).quietClickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Artwork(playlist.cover, Modifier.size(132.dp), 16.dp)
            if (playlist.playCount > 0) Row(Modifier.align(Alignment.TopEnd).padding(8.dp)
                .background(Color.Black.copy(alpha = .35f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TransportIcon(Transport.Play, Modifier.size(9.dp), Color.White)
                Text(formatCount(playlist.playCount), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
        }
        Text(playlist.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp))
    }
}

private val SearchTabs = listOf("综合" to 0, "单曲" to 100, "专辑" to 2, "歌手" to 1, "歌单" to 3)

@Composable
fun SearchScreen(state: AppState, vm: LiltViewModel, bottomPadding: Dp, onMore: (Song) -> Unit) {
    val player by vm.player.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize().padding(top = topPadding(8.dp))) {
        val fieldShape = CircleShape
        Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(52.dp).softShadow(fieldShape, 4.dp).clip(fieldShape)
            .background(MaterialTheme.colorScheme.surface).padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Glyph.Search, Modifier.size(22.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (state.query.isEmpty()) Text("搜索歌曲、歌手、专辑", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(state.query, vm::updateQuery, Modifier.fillMaxWidth().semantics { contentDescription = "搜索" },
                    textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface), singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }))
            }
            if (state.query.isNotEmpty()) IconAction(Glyph.Close, "清除搜索", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                iconSize = 20.dp, onClick = { vm.updateQuery("") })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchTabs.forEach { (label, type) ->
                val selected = state.searchType == type
                Box(Modifier.height(33.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface)
                    .quietClickable(role = Role.Tab) { vm.search(type = type) }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                state.query.isBlank() -> {
                    if (state.searchHistory.isNotEmpty()) item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("最近搜索", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                IconAction(Glyph.Trash, "清空搜索记录", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = vm::clearSearchHistory)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.searchHistory.forEach { query ->
                                    Box(Modifier.height(33.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
                                        .quietClickable { vm.search(query) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                        Text(query, fontSize = 13.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    item {
                        Column {
                            SectionTitle("听点什么")
                            listOf("周杰伦", "王菲", "陈奕迅", "纯音乐", "华语经典", "睡前轻音乐").forEachIndexed { index, query ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).quietClickable { vm.search(query) }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("%02d".format(index + 1), Modifier.width(36.dp),
                                        color = if (index < 3) MaterialTheme.colorScheme.primary else MaterialTheme.lilt.textTertiary,
                                        style = MaterialTheme.typography.labelLarge)
                                    Text(query, style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.weight(1f))
                                    AppIcon(Glyph.Search, Modifier.size(18.dp), MaterialTheme.lilt.textTertiary)
                                }
                            }
                        }
                    }
                }
                state.searching -> item { LoadingState() }
                state.searchError != null -> item {
                    EmptyState("搜索未完成", state.searchError, Glyph.Alert, "重试", onAction = { vm.search() })
                }
                state.searched -> {
                    val artists = if (state.searchType == 0) state.search.artists.take(1) else state.search.artists
                    items(artists, key = { "artist-${it.mid}" }) { artist ->
                        Box(Modifier.padding(bottom = 16.dp)) { ArtistCard(artist, { vm.openArtist(artist) }) { vm.playArtist(artist) } }
                    }
                    if (state.search.songs.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("单曲", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                SoftPill("播放全部", Glyph.Music) { vm.play(state.search.songs) }
                            }
                        }
                        items(state.search.songs, key = { it.mid }) { song ->
                            SongRow(song, { focus.clearFocus(); vm.play(state.search.songs, state.search.songs.indexOf(song)) },
                                { onMore(song) }, active = player.current?.id == song.mid,
                                subtitle = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "))
                        }
                    }
                    items(state.search.albums, key = { "album-${it.mid}" }) { album -> AlbumRow(album) { vm.openAlbum(album) } }
                    items(state.search.playlists, key = { it.id }) { playlist -> PlaylistListRow(playlist) { vm.openPlaylist(playlist) } }
                    if (state.search.songs.isEmpty() && state.search.artists.isEmpty() && state.search.albums.isEmpty() && state.search.playlists.isEmpty())
                        item { EmptyState("没有找到相关内容", "换个关键词再试试", Glyph.Search) }
                }
            }
        }
    }
}

@Composable
private fun ArtistCard(artist: Artist, onOpen: () -> Unit, onPlay: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.fillMaxWidth().softShadow(shape, 4.dp).clip(shape).background(MaterialTheme.colorScheme.surface)
        .quietClickable(onClick = onOpen).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Artwork(artist.cover, Modifier.size(72.dp), 36.dp, artist.name)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("歌手", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(artist.name, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val stats = listOfNotNull(artist.songCount.takeIf { it > 0 }?.let { "单曲 $it" }, artist.albumCount.takeIf { it > 0 }?.let { "专辑 $it" })
            if (stats.isNotEmpty()) Text(stats.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).quietClickable(role = Role.Button, onClick = onPlay)
            .semantics { contentDescription = "播放${artist.name}的热门歌曲" }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                TransportIcon(Transport.Play, Modifier.size(22.dp).offset(x = 1.dp), MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
fun PlaylistListRow(playlist: Playlist, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(12.dp)).quietClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(playlist.cover, Modifier.size(52.dp), 12.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(playlist.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf("${playlist.trackCount} 首", playlist.creator).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private enum class PlaylistEdit { Rename, Delete }

/** A self-created playlist's actions, anchored to its button: rename it, or delete it after confirmation. */
@Composable
private fun PlaylistMenu(playlist: Playlist, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<PlaylistEdit?>(null) }
    Box {
        CircleAction(Glyph.More, "歌单操作", MaterialTheme.lilt.chipGlass) { expanded = true }
        DropdownMenu(expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 180.dp), shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.lilt.divider)) {
            listOf(Triple(PlaylistEdit.Rename, Glyph.Edit, "重命名"), Triple(PlaylistEdit.Delete, Glyph.Trash, "删除歌单"))
                .forEach { (target, glyph, label) ->
                    val interaction = remember { MutableInteractionSource() }
                    CompositionLocalProvider(LocalRippleConfiguration provides QuietRippleConfiguration) {
                        DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; edit = target },
                            modifier = Modifier.heightIn(min = 52.dp).pressFeedback(interaction, 1f, .64f),
                            leadingIcon = { AppIcon(glyph, Modifier.size(20.dp)) },
                            interactionSource = interaction, contentPadding = PaddingValues(horizontal = 20.dp))
                    }
                }
        }
    }
    when (edit) {
        PlaylistEdit.Rename -> PlaylistNameDialog("重命名歌单", "保存", playlist.title, onDismiss = { edit = null }) {
            edit = null; onRename(it)
        }
        PlaylistEdit.Delete -> LiltDialog("删除歌单「${playlist.title}」？", "删除", onDismiss = { edit = null },
            onConfirm = { edit = null; onDelete() }, destructive = true)
        null -> Unit
    }
}

@Composable
fun PlaylistScreen(state: AppState, vm: LiltViewModel, bottomPadding: Dp, onMore: (Song) -> Unit) {
    val player by vm.player.collectAsStateWithLifecycle()
    val detail = state.detail ?: return
    val playlist = detail.playlist
    val round = playlist.id.startsWith("artist:")
    // Only playlists this account created can be renamed or deleted; system directories (201 liked) cannot.
    val editable = !state.preview && state.library.any { it.id == playlist.id && it.dirId in 1 until 200 }
    // While selecting, the hero stays in place but its actions rest, so taps go to the selection.
    val selecting = LocalSongSelection.current?.state?.active == true
    val heroAlpha by animateFloatAsState(if (selecting) .38f else 1f, tween(180), label = "playlistHeroActions")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        item {
            Box {
                if (playlist.cover.isNotEmpty()) {
                    coil.compose.AsyncImage(playlist.cover, null, Modifier.fillMaxWidth().height(440.dp).blur(60.dp).alpha(.55f),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    Box(Modifier.fillMaxWidth().height(444.dp).background(Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.background.copy(alpha = .2f), MaterialTheme.colorScheme.background))))
                }
                Column(Modifier.fillMaxWidth().padding(top = topPadding(4.dp)).padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        CircleAction(Glyph.Back, "返回", MaterialTheme.lilt.chipGlass, onClick = vm::back)
                        if (editable) PlaylistMenu(playlist, onRename = { vm.renamePlaylist(playlist, it) },
                            onDelete = { vm.deletePlaylist(playlist) })
                    }
                    val artworkModifier = Modifier.size(188.dp).shadow(18.dp, RoundedCornerShape(if (round) 94.dp else 20.dp),
                        ambientColor = Radix.SlateDark1.copy(alpha = .25f), spotColor = Radix.SlateDark1.copy(alpha = .25f))
                    if (playlist.id == "daily") DailyArtwork(playlist.cover, artworkModifier)
                    else Artwork(playlist.cover, artworkModifier, if (round) 94.dp else 20.dp, playlist.title)
                    Spacer(Modifier.height(20.dp))
                    Text(playlist.title, Modifier.padding(horizontal = 12.dp), fontSize = 20.sp, lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(listOf(playlist.creator, if (round) "" else "${if (detail.songs.isEmpty()) playlist.trackCount else detail.songs.size} 首",
                        if (playlist.playCount > 0) "${formatCount(playlist.playCount)}次播放" else "").filter { it.isNotBlank() }.joinToString(" · "),
                        Modifier.padding(horizontal = 12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).alpha(heroAlpha), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val enabled = detail.songs.isNotEmpty() && !selecting
                        Row(Modifier.weight(1f).height(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = if (detail.songs.isNotEmpty()) 1f else .5f))
                            .quietClickable(enabled, role = Role.Button) { vm.play(detail.songs) },
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            TransportIcon(Transport.Play, Modifier.size(20.dp), MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("播放全部", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
                        }
                        Row(Modifier.weight(1f).height(48.dp).softShadow(CircleShape, 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
                            .quietClickable(enabled, role = Role.Button) { vm.play(detail.songs, shuffle = true) },
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(Glyph.Shuffle, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("随机播放", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
        if (state.detailLoading) item { LoadingState() }
        if (state.detailError != null) item { EmptyState("加载失败", state.detailError, Glyph.Alert, "重试") { vm.back(); vm.openPlaylist(playlist) } }
        itemsIndexed(detail.songs, key = { index, song -> "$index-${song.mid}" }) { index, song ->
            SongRow(song, { vm.play(detail.songs, index) }, { onMore(song) }, Modifier.padding(horizontal = 20.dp),
                active = player.current?.id == song.mid,
                index = if (playlist.id.startsWith("album:")) index + 1 else null,
                height = if (playlist.id.startsWith("album:")) 60.dp else 68.dp)
        }
        if (!state.detailLoading && state.detailError == null && detail.songs.isEmpty()) item { EmptyState("这里还没有歌曲") }
    }
}

@Composable
private fun DailyArtwork(cover: String, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    Box(modifier.clip(RoundedCornerShape(13.dp)).background(Radix.SageDark6)) {
        if (cover.isNotBlank()) {
            val saturation = remember { ColorMatrix().apply { setToSaturation(1.5f) } }
            coil.compose.AsyncImage(cover, null, Modifier.fillMaxSize().blur(35.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop, colorFilter = ColorFilter.colorMatrix(saturation))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0.35f to Color.Transparent, 1f to Radix.Black.copy(alpha = .4f))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 17.dp, bottom = 15.dp)) {
            Text(today.dayOfMonth.toString(), fontSize = 83.sp, lineHeight = 83.sp, letterSpacing = (-3.8).sp,
                fontWeight = FontWeight.Light, color = Radix.White, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
            Text(today.format(DateTimeFormatter.ofPattern("M月 · EEEE", Locale.SIMPLIFIED_CHINESE)),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Radix.White.copy(alpha = .85f))
        }
    }
}
