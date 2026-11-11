@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, coil.annotation.ExperimentalCoilApi::class)

package com.lilt.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.imageLoader
import com.lilt.app.*
import com.lilt.app.R
import com.lilt.app.data.*
import com.lilt.app.ui.theme.*

@Composable
fun LibraryScreen(state: AppState, profile: Profile?, vm: LiltViewModel, bottomPadding: Dp) {
    val liked = state.library.firstOrNull { it.dirId == 201 } ?: state.library.firstOrNull { it.title.contains("喜欢") }
    val created = state.library.filter { it.dirId > 0 && it != liked }
    val collected = state.library.filter { it.dirId == 0 && it != liked }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topPadding(4.dp), bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconAction(Glyph.Settings, "设置", onClick = { vm.navigate(Destination.Settings) })
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).quietClickable {
                vm.navigate(if (profile == null) Destination.Login else Destination.Settings)
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(74.dp).softShadow(CircleShape).background(MaterialTheme.colorScheme.surface, CircleShape).padding(3.dp)) {
                    if (profile?.avatar?.isNotEmpty() == true) Artwork(profile.avatar, Modifier.fillMaxSize(), 37.dp)
                    else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center) { AppIcon(Glyph.User, Modifier.size(30.dp), MaterialTheme.colorScheme.primary) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(profile?.name ?: "登录 QQ 音乐", fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (profile == null) Text("让喜欢的音乐，随身同行", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else MembershipLine(state.membership, profile)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LibraryTile("我喜欢", Glyph.Heart, liked?.cover.orEmpty(),
                    if (profile == null) "登录后同步" else "${"%,d".format(liked?.trackCount ?: 0)} 首", Modifier.weight(1f),
                    onPlay = { if (liked != null) vm.playPlaylist(liked) else vm.navigate(Destination.Login) }) {
                    if (profile == null) vm.navigate(Destination.Login)
                    else if (liked != null) vm.openPlaylist(liked)
                    else vm.openSongs("我喜欢", emptyList())
                }
                LibraryTile("最近播放", Glyph.Clock, state.recent.firstOrNull()?.cover.orEmpty(), "${state.recent.size} 首", Modifier.weight(1f),
                    onPlay = { vm.play(state.recent, source = "来自最近播放") }) { vm.openSongs("最近播放", state.recent) }
            }
        }
        item {
            GroupCard {
                SettingRow(Glyph.Download, "本地与下载", onClick = { vm.navigate(Destination.Local) }, iconWrap = true)
                RowDivider()
                SettingRow(Glyph.Purchased, "已购音乐", onClick = vm::openPurchased, iconWrap = true)
                RowDivider()
                SettingRow(Glyph.Disc, "收藏的专辑", onClick = vm::openLikedAlbums, iconWrap = true)
                RowDivider()
                SettingRow(Glyph.Queue, "收藏的歌单", onClick = {
                    if (profile == null) vm.navigate(Destination.Login) else vm.openPlaylists("收藏的歌单", collected)
                }, iconWrap = true)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("我创建的歌单", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (profile != null) IconAction(Glyph.Refresh, "刷新歌单", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconSize = 20.dp, onClick = vm::refreshLibrary)
            }
        }
        if (profile == null) item {
            EmptyState("收藏，都在这里", action = "登录", glyph = Glyph.Heart) { vm.navigate(Destination.Login) }
        } else if (state.libraryLoading && state.library.isEmpty()) item { LoadingState() }
        else if (state.libraryError != null && state.library.isEmpty()) item {
            EmptyState("歌单同步失败", state.libraryError, Glyph.Alert, "重试", onAction = vm::refreshLibrary)
        } else if (created.isEmpty()) item { EmptyState("还没有歌单", "在 QQ 音乐中创建后，可在此同步", Glyph.Queue) }
        else items(created, key = { it.id }) { playlist ->
            PlaylistListRow(playlist.copy(creator = playlist.creator.ifBlank { profile.name })) { vm.openPlaylist(playlist) }
        }
    }
}

/** 绿钻会员 · 2027.03.18 到期, from the account's membership query. */
@Composable
private fun MembershipLine(membership: Membership?, profile: Profile) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val paid = membership?.tier == VipTier.Green || membership?.tier == VipTier.Super
        if (paid) Text(membership!!.tier.label, Modifier.background(Brush.horizontalGradient(listOf(Radix.VipStart, Radix.VipEnd)),
            RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = Color.White)
        Text(when {
            paid && membership!!.expiry.isNotBlank() -> "${membership.expiry.replace('-', '.')} 到期"
            membership?.tier == VipTier.Free -> "${profile.loginLabel} · 免费账号"
            else -> profile.loginLabel.ifBlank { "QQ 音乐账号" }
        }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LibraryTile(title: String, glyph: Glyph, cover: String, subtitle: String,
                        modifier: Modifier, onPlay: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.softShadow(shape).clip(shape).background(MaterialTheme.colorScheme.surface).quietClickable(onClick = onClick)
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Artwork(cover, Modifier.size(56.dp), 14.dp)
            Box(Modifier.size(40.dp).clip(CircleShape).quietClickable(role = Role.Button, onClick = onPlay)
                .semantics { contentDescription = "播放$title" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                    TransportIcon(Transport.Play, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AppIcon(glyph, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val CacheLimits = listOf("512" to "512 MB", "1024" to "1 GB", "2048" to "2 GB", "4096" to "4 GB", "8192" to "8 GB")

private fun bytesLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes shr 20} MB"
    bytes > 0 -> "${(bytes shr 10).coerceAtLeast(1)} KB"
    else -> "0 MB"
}

@Composable
fun SettingsScreen(state: AppState, profile: Profile?, vm: LiltViewModel) {
    var choice by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshCacheUsage() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topPadding(8.dp)).navigationBarsPadding()
        .padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircleAction(Glyph.Back, "返回", MaterialTheme.colorScheme.surface, Modifier.offset(x = (-4).dp), shadow = true, onClick = vm::back)
            Text("设置", style = MaterialTheme.typography.headlineLarge)
        }
        GroupCard(vertical = 0.dp) {
            Row(Modifier.fillMaxWidth().quietClickable { vm.navigate(Destination.Login) }.padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Artwork(profile?.avatar, Modifier.size(48.dp), 24.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(profile?.name ?: "登录 QQ 音乐", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (profile == null) "同步你的喜欢与歌单"
                        else listOf(profile.loginLabel, state.membership?.tier?.label.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppIcon(Glyph.Right, Modifier.size(18.dp), MaterialTheme.lilt.textTertiary)
            }
        }
        SettingsSection("播放") {
            SettingsChoiceRow(Glyph.Headphones, "在线播放音质", QualityOptions, state.quality, vm::setQuality)
            RowDivider()
            SettingsChoiceRow(Glyph.Download, "下载音质", QualityOptions, state.downloadQuality, vm::setDownloadQuality)
            RowDivider()
            SettingsToggleRow(Glyph.Volume, "音量均衡", state.normalize, "按响度统一不同歌曲的音量", vm::setNormalize)
            RowDivider()
            SettingsToggleRow(Glyph.Fade, "淡入淡出", state.fade, "切歌和暂停时平滑过渡", vm::setFade)
        }
        SettingsSection("歌词") {
            SettingsToggleRow(Glyph.Lyrics, "逐字歌词", state.wordLyrics, "有 QQ 逐字歌词时，按字点亮", vm::setWordLyrics)
        }
        SettingsSection("存储与外观") {
            SettingsChoiceRow(Glyph.Storage, "缓存上限", CacheLimits, state.cacheLimitMb.toString()) { vm.setCacheLimit(it.toLong()) }
            RowDivider()
            SettingRow(Glyph.Trash, "清除缓存", bytesLabel(state.cacheUsed), { choice = "clear-cache" })
            RowDivider()
            SettingsChoiceRow(Glyph.Moon, "外观", ThemeMode.entries.map { it.name to it.label }, state.theme.name) {
                vm.setTheme(ThemeMode.valueOf(it))
            }
        }
        SettingsSection("关于") {
            SettingRow(Glyph.Info, "关于 Lilt", onClick = { choice = "about" })
            RowDivider()
            SettingRow(Glyph.External, "开源许可", onClick = { choice = "licenses" })
            RowDivider()
            SettingRow(Glyph.Music, "当前版本", "v${BuildConfig.VERSION_NAME}")
        }
        if (profile != null) {
            val shape = RoundedCornerShape(22.dp)
            Box(Modifier.fillMaxWidth().height(52.dp).softShadow(shape).clip(shape).background(MaterialTheme.colorScheme.surface)
                .quietClickable(role = Role.Button, onClick = vm::logout), contentAlignment = Alignment.Center) {
                Text("退出登录", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    when (choice) {
        "clear-cache" -> AlertDialog(
            onDismissRequest = { choice = null },
            icon = { AppIcon(Glyph.Trash) },
            title = { Text("清除缓存？") },
            text = { Text("清除音频与图片缓存，保留已下载歌曲。") },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            dismissButton = { QuietTextButton({ choice = null }) { Text("取消") } },
            confirmButton = {
                QuietTextButton({
                    choice = null
                    vm.clearCaches { context.imageLoader.memoryCache?.clear(); context.imageLoader.diskCache?.clear() }
                }) { Text("清除") }
            })
        "licenses" -> OpenSourceSheet(onDismiss = { choice = null }, onOpenError = { vm.notify("无法打开链接") })
        "about" -> AlertDialog(onDismissRequest = { choice = null }, title = { Text("Lilt") },
            text = { Text("安静地，听音乐。\n\n基于 Kotlin 与 Jetpack Compose 开发的第三方 QQ 音乐客户端。音乐内容与收听权限由 QQ 音乐提供，版权归各权利人所有。") },
            confirmButton = { QuietTextButton({ choice = null }) { Text("知道了") } })
    }
}

/** Keep the menu anchored to its value, so the row remains visible while choosing. */
@Composable
private fun SettingsChoiceRow(glyph: Glyph, label: String, options: List<Pair<String, String>>,
                              selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val value = options.firstOrNull { it.first == selected }?.second.orEmpty()
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, tween(160), label = "settingsMenuArrow")
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .quietClickable(role = Role.Button, interactionSource = interaction, pressedAlpha = 1f) { expanded = !expanded }
        .semantics { stateDescription = value }
        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(glyph, Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box {
            Row(Modifier.pressFeedback(interaction, 1f, .64f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppIcon(Glyph.Down, Modifier.size(18.dp).graphicsLayer { rotationZ = arrowRotation }, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 216.dp, max = 280.dp), shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.lilt.divider)) {
                options.forEach { (key, text) ->
                    val optionInteraction = remember { MutableInteractionSource() }
                    CompositionLocalProvider(LocalRippleConfiguration provides QuietRippleConfiguration) {
                    DropdownMenuItem(text = { Text(text, fontWeight = if (key == selected) FontWeight.SemiBold else FontWeight.Normal) },
                        onClick = { expanded = false; if (key != selected) onSelect(key) },
                        modifier = Modifier.heightIn(min = 56.dp)
                            .pressFeedback(optionInteraction, 1f, .64f).semantics { this.selected = key == selected },
                        interactionSource = optionInteraction,
                        trailingIcon = {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                if (key == selected) AppIcon(Glyph.Check, Modifier.size(22.dp), MaterialTheme.colorScheme.onSurface)
                            }
                        }, contentPadding = PaddingValues(horizontal = 24.dp))
                    }
                }
            }
        }
    }
}

/** One switch target for the entire row, including the visible thumb. */
@Composable
private fun SettingsToggleRow(glyph: Glyph, label: String, checked: Boolean, subtitle: String,
                              onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).quietIndication(interaction)
        .toggleable(checked, interactionSource = interaction, indication = null,
            role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(glyph, Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        LiltSwitch(checked, null, label, interactionSource = interaction)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.padding(start = 2.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        GroupCard(content = content)
    }
}

@Composable
fun LoginScreen(state: AppState, vm: LiltViewModel) {
    val context = LocalContext.current
    val writeQr: (Uri?) -> Unit = { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream -> stream.write(state.qr?.imageBytes ?: byteArrayOf()) }
        }.onSuccess { vm.notify("二维码已保存") }.onFailure { vm.notify("保存失败，请重试") }
    }
    // QQ serves a PNG and WeChat a JPEG; each is saved unchanged.
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png"), writeQr)
    val saveJpeg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg"), writeQr)
    val wechat = state.provider == "wechat"
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.size(420.dp).offset((-80).dp, (-140).dp).background(Brush.radialGradient(
            listOf(primary.copy(alpha = .18f), Color.Transparent))))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 28.dp).padding(top = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(52.dp).shadow(12.dp, RoundedCornerShape(16.dp), ambientColor = primary.copy(alpha = .25f),
                    spotColor = primary.copy(alpha = .3f)).clip(RoundedCornerShape(16.dp))) {
                    AndroidView(factory = { ImageView(it).apply { setImageResource(R.drawable.ic_lilt_artwork) } },
                        modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(20.dp))
                Text("登录 QQ 音乐账号", fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                Text("同步你喜欢的歌、歌单和会员音质", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().height(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp)).padding(4.dp)) {
                listOf("qq" to "QQ 扫码", "wechat" to "微信扫码").forEach { (provider, label) ->
                    val selected = state.provider == provider
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .then(if (selected) Modifier.shadow(2.dp, RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)) else Modifier)
                        .clip(RoundedCornerShape(18.dp)).quietClickable(role = Role.Tab) { if (!selected) vm.refreshQr(provider) }
                        .semantics { this.selected = selected }, contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val card = RoundedCornerShape(28.dp)
            Column(Modifier.fillMaxWidth().softShadow(card, 14.dp).clip(card).background(MaterialTheme.colorScheme.surface)
                .padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.size(208.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .border(1.dp, MaterialTheme.lilt.divider, RoundedCornerShape(16.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.qrBusy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        state.qr?.imageBytes != null -> {
                            coil.compose.AsyncImage(state.qr.imageBytes, if (wechat) "微信登录二维码" else "QQ 登录二维码",
                                Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                            if (state.qrState == LoginState.EXPIRED) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = .9f))
                                .quietClickable { vm.refreshQr() }, contentAlignment = Alignment.Center) {
                                Text("点按刷新", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Radix.Slate12)
                            } else Box(Modifier.size(32.dp).background(Color.White, RoundedCornerShape(9.dp)).padding(3.dp)
                                .background(primary, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                                AppIcon(Glyph.Music, Modifier.size(16.dp), Color.White)
                            }
                        }
                        else -> AppIcon(Glyph.Qr, Modifier.size(64.dp), Radix.Slate11)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(if (state.qrState == LoginState.EXPIRED) MaterialTheme.colorScheme.error
                        else primary, CircleShape))
                    Text(state.qrMessage, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SoftPill("刷新二维码", Glyph.Refresh, horizontal = 16.dp, vertical = 8.dp, fontSize = 13, enabled = !state.qrBusy) { vm.refreshQr() }
                    if (state.qr?.imageBytes != null) Text("保存", Modifier.clip(CircleShape).quietClickable {
                        if (wechat) saveJpeg.launch("Lilt-微信登录.jpg") else savePng.launch("Lilt-QQ登录.png")
                    }.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LoginStep("1", if (wechat) "打开手机微信，点击右上角「+」→「扫一扫」" else "打开手机 QQ，点击右上角「+」→「扫一扫」")
                LoginStep("2", "在手机上确认登录，本机将自动完成")
            }
            Spacer(Modifier.weight(1f))
            Text("本应用为第三方客户端，与 QQ 音乐官方无关", Modifier.fillMaxWidth(), fontSize = 11.sp,
                color = MaterialTheme.lilt.textTertiary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LoginStep(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
            .border(1.dp, MaterialTheme.lilt.divider, CircleShape), contentAlignment = Alignment.Center) {
            Text(number, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LocalScreen(state: AppState, vm: LiltViewModel, bottomPadding: Dp, onMore: (Song) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), vm::addLocal)
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topPadding(4.dp), bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircleAction(Glyph.Back, "返回", MaterialTheme.colorScheme.surface, shadow = true, onClick = vm::back)
                Text("本地与下载", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconAction(Glyph.Plus, "导入音频", onClick = { picker.launch(arrayOf("audio/*")) })
            }
        }
        item {
            GroupCard {
                SettingRow(Glyph.Plus, "导入本地音频", onClick = { picker.launch(arrayOf("audio/*")) }, iconWrap = true)
                RowDivider()
                SettingRow(Glyph.Download, "查看系统下载", onClick = {
                    runCatching { context.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                        .onFailure { vm.notify("无法打开下载管理器，请使用文件管理器查看 Music/Lilt") }
                }, iconWrap = true)
            }
        }
        if (state.local.isNotEmpty()) {
            item { SectionTitle("本地音乐", "播放全部", Modifier.padding(top = 16.dp)) { vm.play(state.local) } }
            items(state.local, key = { it.mid }) { song -> SongRow(song, { vm.play(state.local, state.local.indexOf(song)) }, { onMore(song) }) }
        } else item { EmptyState("把音乐带在身边", "从设备中选择音频文件", Glyph.Folder, "导入音频") { picker.launch(arrayOf("audio/*")) } }
    }
}

fun qualityLabel(quality: String) = when (quality) { "hq" -> "高品质 HQ"; "lossless" -> "无损 SQ"; else -> "标准" }
