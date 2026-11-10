@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, coil.annotation.ExperimentalCoilApi::class)

package com.lilt.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.imageLoader
import com.lilt.app.*
import com.lilt.app.data.*
import com.lilt.app.ui.theme.*

@Composable
fun LibraryScreen(state: AppState, profile: Profile?, vm: LiltViewModel, bottomPadding: androidx.compose.ui.unit.Dp) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconAction(Glyph.Qr, "登录账号", onClick = { vm.navigate(Destination.Login) })
                IconAction(Glyph.Settings, "设置", onClick = { vm.navigate(Destination.Settings) })
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                vm.navigate(if (profile == null) Destination.Login else Destination.Settings)
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (profile?.avatar?.isNotEmpty() == true) Artwork(profile.avatar, Modifier.size(68.dp), 34.dp)
                else Box(Modifier.size(68.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) { AppIcon(Glyph.User, Modifier.size(30.dp), MaterialTheme.colorScheme.primary) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(profile?.name ?: "登录 QQ 音乐", style = MaterialTheme.typography.titleLarge)
                    Text(if (profile == null) "让喜欢的音乐，随身同行" else "QQ 音乐账号",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppIcon(Glyph.Right, tint = MaterialTheme.colorScheme.outline)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LibraryTile("我喜欢", Glyph.Heart, state.library.firstOrNull { it.title.contains("喜欢") }?.cover.orEmpty(),
                    if (profile == null) "登录后同步" else "${state.library.firstOrNull { it.title.contains("喜欢") }?.trackCount ?: 0} 首",
                    Modifier.weight(1f)) {
                    val liked = state.library.firstOrNull { it.title.contains("喜欢") }
                    if (profile == null) vm.navigate(Destination.Login)
                    else if (liked != null) vm.openPlaylist(liked)
                    else vm.openSongs("我喜欢", emptyList())
                }
                LibraryTile("最近播放", Glyph.Clock, state.recent.firstOrNull()?.cover.orEmpty(),
                    "${state.recent.size} 首", Modifier.weight(1f)) { vm.openSongs("最近播放", state.recent) }
            }
        }
        item {
            GroupCard {
                SettingRow(Glyph.Folder, "本地与下载", "${state.local.size} 首", { vm.navigate(Destination.Local) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                SettingRow(Glyph.Queue, "我的歌单", if (profile == null) "登录后同步" else "${state.library.size} 个",
                    { if (profile == null) vm.navigate(Destination.Login) else vm.refreshLibrary() })
            }
        }
        item { SectionTitle("我的歌单", if (profile != null) "刷新" else null, vm::refreshLibrary) }
        if (profile == null) item {
            EmptyState("收藏，都在这里", action = "登录", glyph = Glyph.Heart) { vm.navigate(Destination.Login) }
        } else if (state.libraryLoading) item { LoadingState() }
        else if (state.libraryError != null) item {
            EmptyState("歌单同步失败", state.libraryError, Glyph.Alert, "重试", onAction = vm::refreshLibrary)
        } else if (state.library.isEmpty()) item { EmptyState("还没有歌单", "在 QQ 音乐中创建后，可在此同步", Glyph.Queue) }
        else items(state.library, key = { it.id }) { playlist -> PlaylistListRow(playlist) { vm.openPlaylist(playlist) } }
    }
}

@Composable
private fun LibraryTile(title: String, glyph: Glyph, cover: String, subtitle: String,
                        modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick)
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Artwork(cover, Modifier.size(56.dp), 12.dp)
            Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center) { TransportIcon(Transport.Play, Modifier.size(16.dp)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AppIcon(glyph, Modifier.size(16.dp))
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsScreen(state: AppState, profile: Profile?, vm: LiltViewModel) {
    var choice by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var cacheCleared by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)
        .padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column {
            IconAction(Glyph.Back, "返回", onClick = vm::back)
            Spacer(Modifier.height(8.dp)); Text("设置", style = MaterialTheme.typography.headlineLarge)
        }
        GroupCard {
            Row(Modifier.fillMaxWidth().clickable { vm.navigate(Destination.Login) }.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Artwork(profile?.avatar, Modifier.size(48.dp), 24.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(profile?.name ?: "登录 QQ 音乐", style = MaterialTheme.typography.titleSmall)
                    Text(if (profile != null) "QQ 音乐账号" else "同步你的喜欢与歌单",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppIcon(Glyph.Right, tint = MaterialTheme.colorScheme.outline)
            }
        }
        SettingsSection("播放") {
            SettingRow(Glyph.Headphones, "在线播放音质", qualityLabel(state.quality), { choice = "quality" })
            SettingRow(Glyph.Shuffle, "随机播放", trailing = {
                val player by vm.player.collectAsState()
                Switch(player.shuffle, vm.playback::setShuffle)
            })
            SettingRow(Glyph.Repeat, "播放模式", when (vm.player.collectAsState().value.repeatMode) { 1 -> "单曲循环"; 2 -> "列表循环"; else -> "顺序播放" },
                vm.playback::cycleRepeat)
        }
        SettingsSection("歌词") {
            SettingRow(Glyph.Lyrics, "同步歌词", "LRC")
            SettingRow(Glyph.Volume, "锁屏播放控制", "系统媒体面板")
        }
        SettingsSection("存储与外观") {
            SettingRow(Glyph.Moon, "深色模式", state.theme.label, { choice = "theme" })
            SettingRow(Glyph.Trash, "清除图片缓存", if (cacheCleared) "已清除" else null, {
                context.imageLoader.memoryCache?.clear()
                context.imageLoader.diskCache?.clear()
                cacheCleared = true
                vm.notify("图片缓存已清除")
            })
            SettingRow(Glyph.Folder, "本地音乐", "${state.local.size} 首", { vm.navigate(Destination.Local) })
        }
        SettingsSection("关于") {
            SettingRow(Glyph.Music, "当前版本", "v${BuildConfig.VERSION_NAME}")
            SettingRow(Glyph.Info, "关于 Lilt", onClick = { choice = "about" })
            SettingRow(Glyph.External, "Tabler Icons", "MIT", {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tabler/tabler-icons")))
            })
        }
        if (profile != null) TextButton(vm::logout, Modifier.fillMaxWidth().height(52.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("退出登录") }
    }
    if (choice == "quality") ChoiceSheet("在线播放音质", listOf("standard" to "标准", "hq" to "高品质 HQ", "lossless" to "无损 SQ"),
        state.quality, { choice = null }) { vm.setQuality(it); choice = null }
    if (choice == "theme") ChoiceSheet("深色模式", ThemeMode.entries.map { it.name to it.label }, state.theme.name,
        { choice = null }) { vm.setTheme(ThemeMode.valueOf(it)); choice = null }
    if (choice == "about") AlertDialog(onDismissRequest = { choice = null }, title = { Text("Lilt") },
        text = { Text("安静地，听音乐。\n\n基于 Kotlin 与 Jetpack Compose 开发的第三方 QQ 音乐客户端。音乐内容与收听权限由 QQ 音乐提供，版权归各权利人所有。") },
        confirmButton = { TextButton({ choice = null }) { Text("知道了") } })
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.padding(start = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GroupCard(content = content)
    }
}

@Composable
fun ChoiceSheet(title: String, options: List<Pair<String, String>>, selected: String,
                onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    ModalBottomSheet(onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Text(title, Modifier.padding(start = 24.dp, bottom = 16.dp), style = MaterialTheme.typography.titleLarge)
        options.forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(id) }.padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (selected == id) AppIcon(Glyph.Check, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun LoginScreen(state: AppState, vm: LiltViewModel) {
    val context = LocalContext.current
    val saveQr = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream -> stream.write(state.qr?.imageBytes ?: byteArrayOf()) }
        }.onSuccess { vm.notify("二维码已保存") }.onFailure { vm.notify("保存失败，请重试") }
    }
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(
        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background), radius = 800f))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 28.dp)
            .padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { IconAction(Glyph.Close, "关闭登录", onClick = vm::back) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center) { AppIcon(Glyph.Music, Modifier.size(30.dp), MaterialTheme.colorScheme.onPrimary) }
                Spacer(Modifier.height(8.dp))
                Text("登录 QQ 音乐账号", style = MaterialTheme.typography.headlineSmall)
                Text("同步你喜欢的歌、歌单和会员音质", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("qq" to "QQ 扫码", "wechat" to "微信扫码").forEachIndexed { index, (provider, label) ->
                    SegmentedButton(state.provider == provider, { vm.refreshQr(provider) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2), icon = {}) { Text(label) }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.size(208.dp).background(Radix.White, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    when {
                        state.qrBusy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        state.qr?.imageBytes != null -> coil.compose.AsyncImage(state.qr.imageBytes, "QQ 音乐登录二维码",
                            Modifier.fillMaxSize().padding(10.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                        state.qr?.imageUrl?.isNotBlank() == true -> coil.compose.AsyncImage(state.qr.imageUrl, "QQ 音乐登录二维码",
                            Modifier.fillMaxSize().padding(10.dp))
                        else -> AppIcon(Glyph.Qr, Modifier.size(64.dp), Radix.Gray8)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(7.dp).background(if (state.qrState == LoginState.EXPIRED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary, CircleShape))
                    Text(state.qrMessage, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ vm.refreshQr() }, enabled = !state.qrBusy) {
                        AppIcon(Glyph.Refresh, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("刷新二维码")
                    }
                    if (state.qr?.imageBytes != null) TextButton({ saveQr.launch("Lilt-QQ登录.png") }) { Text("保存") }
                }
            }
            if (state.provider == "qq") Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LoginStep("1", "打开手机 QQ，使用「扫一扫」扫描二维码")
                LoginStep("2", "在手机上确认登录，本机将自动完成")
            }
            Spacer(Modifier.height(12.dp))
            Text("本应用为第三方客户端，与 QQ 音乐官方无关", Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LoginStep(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
            Text(number, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LocalScreen(state: AppState, vm: LiltViewModel, bottomPadding: androidx.compose.ui.unit.Dp, onMore: (Song) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), vm::addLocal)
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconAction(Glyph.Back, "返回", onClick = vm::back)
                Text("本地与下载", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconAction(Glyph.Plus, "导入音频", onClick = { picker.launch(arrayOf("audio/*")) })
            }
        }
        item {
            GroupCard {
                SettingRow(Glyph.Plus, "导入本地音频", onClick = { picker.launch(arrayOf("audio/*")) })
                SettingRow(Glyph.Download, "查看系统下载", onClick = {
                    runCatching { context.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                        .onFailure { vm.notify("无法打开下载管理器，请使用文件管理器查看 Music/Lilt") }
                })
            }
        }
        if (state.local.isNotEmpty()) {
            item { SectionTitle("本地音乐", "播放全部") { vm.play(state.local) } }
            items(state.local, key = { it.mid }) { song -> SongRow(song, { vm.play(state.local, state.local.indexOf(song)) }, { onMore(song) }) }
        } else item { EmptyState("把音乐带在身边", "从设备中选择音频文件", Glyph.Folder, "导入音频") { picker.launch(arrayOf("audio/*")) } }
    }
}

fun qualityLabel(quality: String) = when (quality) { "hq" -> "高品质 HQ"; "lossless" -> "无损 SQ"; else -> "标准" }
