@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lilt.app.ui

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilt.app.*
import com.lilt.app.data.*
import com.lilt.app.ui.theme.Radix
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

@Composable
fun HomeScreen(state: AppState, profile: Profile?, vm: LiltViewModel, bottomPadding: androidx.compose.ui.unit.Dp,
               onSongMore: (Song) -> Unit) {
    val player by vm.player.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = state.homeLoading && state.home.playlists.isNotEmpty(), onRefresh = vm::refreshHome) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
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
                IconButton({ vm.navigate(if (profile == null) Destination.Login else Destination.Library) },
                    Modifier.semantics { contentDescription = "我的账号" }) {
                    if (profile?.avatar?.isNotBlank() == true) Artwork(profile.avatar, Modifier.size(36.dp), 18.dp)
                    else Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                        AppIcon(Glyph.User, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { DailyCard(state.home.songs, profile != null || state.preview, onClick = vm::daily) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Triple(Glyph.Radio, "猜你想听", "随心听"), Triple(Glyph.Queue, "歌单广场", "歌单"),
                    Triple(Glyph.Microphone, "歌手", "歌手"), Triple(Glyph.Disc, "新歌", "新歌")).forEachIndexed { i, (glyph, label, _) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            when (i) {
                                0 -> if (state.home.songs.isNotEmpty()) { vm.play(state.home.songs, shuffle = true); vm.showPlayer(true) } else vm.refreshHome()
                                1 -> vm.openPlaylists("歌单广场", state.home.playlists)
                                2 -> vm.beginSearch(1)
                                else -> state.home.charts.firstOrNull { it.title.contains("新歌") }?.let(vm::openPlaylist)
                                    ?: run { vm.notify("新歌榜暂不可用，请稍后重试"); vm.refreshHome() }
                            }
                        }.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        AppIcon(glyph, tint = MaterialTheme.colorScheme.primary)
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (state.homeLoading) item { LoadingState() }
        if (state.homeError != null) item {
            EmptyState("暂时无法加载推荐", state.homeError, Glyph.Alert, "重试", onAction = vm::refreshHome)
        }
        if (state.home.songs.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("这是你的幸运好歌", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(6.dp))
                        AppIcon(Glyph.Clover, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(state.home.songs.chunked(3)) { group ->
                            Column(Modifier.width(310.dp)) {
                                group.forEach { song -> SongRow(song, { vm.play(state.home.songs, state.home.songs.indexOf(song)) },
                                    { onSongMore(song) }, active = player.current?.id == song.mid) }
                            }
                        }
                    }
                }
            }
        }
        if (state.home.playlists.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("为你推荐的歌单", "更多") { vm.openPlaylists("为你推荐的歌单", state.home.playlists) }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.home.playlists, key = { it.id }) { playlist ->
                        PlaylistCard(playlist) { vm.openPlaylist(playlist) }
                    }
                }
            }
        }
        if (state.home.charts.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionTitle("排行榜", "更多") { vm.openPlaylists("排行榜", state.home.charts) }
                GroupCard {
                    state.home.charts.take(3).forEachIndexed { index, chart ->
                        Row(Modifier.fillMaxWidth().clickable { vm.openPlaylist(chart) }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Artwork(chart.cover, Modifier.size(60.dp), 12.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(chart.title, style = MaterialTheme.typography.titleSmall)
                                Text(if (chart.playCount > 0) "${formatCount(chart.playCount)}次播放" else "${chart.trackCount} 首歌曲", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconAction(Glyph.Right, "打开${chart.title}", onClick = { vm.openPlaylist(chart) })
                        }
                        if (index < 2) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    }
                }
            }
        }
        if (!state.homeLoading && state.homeError == null && state.home.songs.isEmpty() && state.home.playlists.isEmpty()) {
            item { EmptyState("还没有推荐内容", action = "刷新", onAction = vm::refreshHome) }
        }
    }
    }
}

@Composable
private fun DailyCard(songs: List<Song>, signedIn: Boolean, onClick: () -> Unit) {
    val foreground = Radix.White
    BoxWithConstraints(Modifier.fillMaxWidth().height(224.dp).shadow(12.dp, RoundedCornerShape(24.dp),
        ambientColor = Radix.Ruby12.copy(alpha = .15f), spotColor = Radix.Ruby12.copy(alpha = .2f))
        .clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Radix.Ruby12, Radix.Plum12, Radix.Sand11)))
        .clickable(onClick = onClick)) {
        val width = maxWidth
        if (songs.firstOrNull()?.cover?.isNotBlank() == true) {
            coil.compose.AsyncImage(songs.first().cover, null, Modifier.fillMaxSize().blur(38.dp).alpha(.22f),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * .84f, size.height * .5f)
            for (i in 0..18) drawCircle(foreground.copy(alpha = (.075f - i * .003f).coerceAtLeast(.015f)),
                50.dp.toPx() + i * 18.dp.toPx(), center, style = Stroke(.65.dp.toPx()))
        }
        Text(LocalDate.now().dayOfMonth.toString(), Modifier.align(Alignment.Center).offset(x = 14.dp, y = (-15).dp),
            color = foreground.copy(alpha = .065f), fontWeight = FontWeight.ExtraBold, fontSize = 178.sp)
        Column(Modifier.align(Alignment.CenterStart).padding(start = 22.dp).width(width * .48f),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.SIMPLIFIED_CHINESE)),
                style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .8f))
            Text("每日推荐", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = foreground)
            Text(if (!signedIn) "登录后开启专属推荐" else if (songs.isEmpty()) "今天，也有好音乐" else "${songs.map { it.artist }.distinct().take(2).joinToString("、").take(18)} 等 ${songs.size} 首",
                style = MaterialTheme.typography.bodySmall, color = foreground.copy(alpha = .85f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(9.dp))
            Surface(color = foreground, shape = CircleShape) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TransportIcon(Transport.Play, Modifier.size(16.dp), Radix.Ruby12)
                    Text(if (signedIn) "听一听" else "去登录", style = MaterialTheme.typography.labelMedium, color = Radix.Ruby12)
                }
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).width(width * .47f).height(160.dp)) {
            songs.getOrNull(2)?.let {
                Artwork(it.cover, Modifier.size(84.dp).align(Alignment.CenterStart).rotate(-12f).alpha(.75f), 10.dp)
            }
            songs.getOrNull(1)?.let {
                Artwork(it.cover, Modifier.size(98.dp).align(Alignment.Center).offset(x = (-16).dp).rotate(-6f), 10.dp)
            }
            Canvas(Modifier.size(108.dp).align(Alignment.CenterEnd)) {
                drawCircle(Radix.Sand12)
                for (i in 0..10) drawCircle(foreground.copy(alpha = .07f), size.minDimension * (.22f + .025f * i), style = Stroke(.6.dp.toPx()))
                drawCircle(Radix.Ruby12, size.minDimension * .16f)
                drawCircle(Radix.Sand12, 2.dp.toPx())
            }
            if (songs.isNotEmpty()) Artwork(songs.first().cover,
                Modifier.size(112.dp).align(Alignment.Center).offset(x = 4.dp).shadow(16.dp, RoundedCornerShape(12.dp)), 12.dp)
        }
    }
}

@Composable
fun PlaylistCollectionScreen(state: AppState, vm: LiltViewModel, bottomPadding: androidx.compose.ui.unit.Dp) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconAction(Glyph.Back, "返回", onClick = vm::back)
                Text(state.collectionTitle, style = MaterialTheme.typography.titleLarge)
            }
        }
        items(state.collectionPlaylists, key = { it.id }) { playlist ->
            PlaylistListRow(playlist) { vm.openPlaylist(playlist) }
        }
        if (state.collectionPlaylists.isEmpty()) item { EmptyState("暂无歌单", action = "返回") { vm.back() } }
    }
}

@Composable
fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Artwork(playlist.cover, Modifier.size(132.dp), 14.dp)
            if (playlist.playCount > 0) Surface(Modifier.align(Alignment.BottomEnd).padding(6.dp),
                color = Radix.Black.copy(alpha = .55f), shape = CircleShape) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    TransportIcon(Transport.Play, Modifier.size(10.dp), Radix.White)
                    Text(formatCount(playlist.playCount), color = Radix.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(playlist.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
fun SearchScreen(state: AppState, vm: LiltViewModel, bottomPadding: androidx.compose.ui.unit.Dp, onMore: (Song) -> Unit) {
    val player by vm.player.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(state.query, vm::updateQuery,
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp).heightIn(min = 52.dp),
            placeholder = { Text("搜索歌曲、歌手、专辑", style = MaterialTheme.typography.bodyLarge) },
            leadingIcon = { AppIcon(Glyph.Search, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = { if (state.query.isNotEmpty()) IconAction(Glyph.Close, "清除搜索", onClick = { vm.updateQuery("") }) },
            shape = RoundedCornerShape(20.dp), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedBorderColor = Color.Transparent, focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = .45f)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("综合" to 0, "单曲" to 100, "专辑" to 2, "歌手" to 1, "歌单" to 3).forEach { (label, type) ->
                FilterChip(state.searchType == type, { vm.search(type = type) }, { Text(label) },
                    shape = CircleShape, border = null, colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                        selectedLabelColor = MaterialTheme.colorScheme.surface))
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.query.isBlank() -> {
                    if (state.searchHistory.isNotEmpty()) item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("最近搜索", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                IconAction(Glyph.Trash, "清空搜索记录", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    onClick = vm::clearSearchHistory)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.searchHistory.forEach { query ->
                                    SuggestionChip({ vm.search(query) }, { Text(query) }, shape = CircleShape)
                                }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle("听点什么")
                            listOf("周杰伦", "王菲", "陈奕迅", "纯音乐", "华语经典", "睡前轻音乐").forEachIndexed { index, query ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.search(query) }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("%02d".format(index + 1), Modifier.width(36.dp),
                                        color = if (index < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        style = MaterialTheme.typography.labelLarge)
                                    Text(query, style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.weight(1f))
                                    AppIcon(Glyph.Search, Modifier.size(18.dp), MaterialTheme.colorScheme.outline)
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
                    if (state.search.artists.isNotEmpty()) items(state.search.artists, key = { "artist-${it.mid}" }) { artist ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface)
                            .clickable { vm.search(artist.name, 100) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Artwork(artist.cover, Modifier.size(72.dp), 36.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(artist.name, style = MaterialTheme.typography.titleLarge)
                                Text("歌手", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            AppIcon(Glyph.Right, tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                    if (state.search.songs.isNotEmpty()) {
                        item { SectionTitle("单曲", "播放全部") { vm.play(state.search.songs) } }
                        items(state.search.songs, key = { it.mid }) { song ->
                            SongRow(song, { vm.play(state.search.songs, state.search.songs.indexOf(song)) },
                                { onMore(song) }, active = player.current?.id == song.mid)
                        }
                    }
                    items(state.search.albums, key = { "album-${it.mid}" }) { album ->
                        Row(Modifier.fillMaxWidth().clickable { vm.search("${album.artist} ${album.title}", 100) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Artwork(album.cover, Modifier.size(68.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(album.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            AppIcon(Glyph.Right, tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                    items(state.search.playlists, key = { it.id }) { playlist ->
                        PlaylistListRow(playlist) { vm.openPlaylist(playlist) }
                    }
                    if (state.search.songs.isEmpty() && state.search.artists.isEmpty() && state.search.albums.isEmpty() && state.search.playlists.isEmpty())
                        item { EmptyState("没有找到相关内容", "换个关键词再试试", Glyph.Search) }
                }
            }
        }
    }
}

@Composable
fun PlaylistListRow(playlist: Playlist, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(playlist.cover, Modifier.size(56.dp), 12.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(playlist.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf("${playlist.trackCount} 首", playlist.creator).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppIcon(Glyph.Right, Modifier.size(18.dp), MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun PlaylistScreen(state: AppState, vm: LiltViewModel, bottomPadding: androidx.compose.ui.unit.Dp, onMore: (Song) -> Unit) {
    val context = LocalContext.current
    val player by vm.player.collectAsStateWithLifecycle()
    val detail = state.detail ?: return
    val playlist = detail.playlist
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        item {
            Box {
                if (playlist.cover.isNotEmpty()) {
                    coil.compose.AsyncImage(playlist.cover, null, Modifier.fillMaxWidth().height(410.dp).blur(60.dp).alpha(.25f),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    Box(Modifier.fillMaxWidth().height(420.dp).background(Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconAction(Glyph.Back, "返回", onClick = vm::back)
                        if (playlist.id.toLongOrNull() != null || playlist.id.startsWith("chart:")) {
                            IconAction(Glyph.Share, "分享歌单", onClick = {
                                val url = if (playlist.id.startsWith("chart:"))
                                    "https://y.qq.com/n/ryqq/toplist/${playlist.id.removePrefix("chart:")}"
                                else "https://y.qq.com/n/ryqq/playlist/${playlist.id}"
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "${playlist.title}\n$url")
                                }, "分享歌单"))
                            })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Artwork(playlist.cover, Modifier.size(188.dp).shadow(14.dp, RoundedCornerShape(18.dp)), 18.dp)
                    Spacer(Modifier.height(20.dp))
                    Text(playlist.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(listOf(playlist.creator, "${if (detail.songs.isEmpty()) playlist.trackCount else detail.songs.size} 首",
                        if (playlist.playCount > 0) "${formatCount(playlist.playCount)}次播放" else "").filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button({ vm.play(detail.songs) }, Modifier.weight(1f).heightIn(min = 48.dp),
                            enabled = detail.songs.isNotEmpty(), contentPadding = PaddingValues(horizontal = 10.dp)) {
                            TransportIcon(Transport.Play, Modifier.size(19.dp), MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(6.dp)); Text("播放全部")
                        }
                        FilledTonalButton({ vm.play(detail.songs, shuffle = true) }, Modifier.weight(1f).heightIn(min = 48.dp),
                            enabled = detail.songs.isNotEmpty(), contentPadding = PaddingValues(horizontal = 10.dp)) {
                            AppIcon(Glyph.Shuffle, Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("随机播放")
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
        if (state.detailLoading) item { LoadingState() }
        if (state.detailError != null) item { EmptyState("歌单加载失败", state.detailError, Glyph.Alert, "重试") { vm.openPlaylist(playlist) } }
        itemsIndexed(detail.songs, key = { _, song -> song.mid }) { index, song ->
            SongRow(song, { vm.play(detail.songs, index) }, { onMore(song) }, Modifier.padding(horizontal = 20.dp),
                active = player.current?.id == song.mid, index = index + 1)
        }
        if (!state.detailLoading && state.detailError == null && detail.songs.isEmpty()) item { EmptyState("歌单里还没有歌曲") }
    }
}
