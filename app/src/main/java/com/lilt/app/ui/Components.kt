@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lilt.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lilt.app.R
import com.lilt.app.data.Song
import com.lilt.app.ui.theme.lilt
import kotlinx.coroutines.flow.filterNot

/** Share the source with the clickable so visual feedback follows press, release and cancellation. */
@Composable
fun Modifier.pressFeedback(source: MutableInteractionSource, pressedScale: Float = .94f, pressedAlpha: Float = 1f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f,
        animationSpec = tween(if (pressed) 90 else 180), label = "pressFeedback")
    val opacity by animateFloatAsState(if (pressed) pressedAlpha else 1f,
        animationSpec = tween(if (pressed) 90 else 180), label = "pressOpacity")
    return graphicsLayer { scaleX = scale; scaleY = scale; alpha = opacity }
}

/** Keep keyboard focus and mouse hover visible, without a touch ripple covering the hit area. */
@Composable
fun Modifier.quietIndication(source: InteractionSource): Modifier {
    val focusAndHover = remember(source) {
        object : InteractionSource {
            override val interactions = source.interactions.filterNot { it is PressInteraction }
        }
    }
    return indication(focusAndHover, LocalIndication.current)
}

@Composable
fun Modifier.quietClickable(enabled: Boolean = true, role: Role? = null, onClickLabel: String? = null,
                            interactionSource: MutableInteractionSource? = null, pressedAlpha: Float = .76f,
                            onClick: () -> Unit): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return quietIndication(source).pressFeedback(source, pressedScale = 1f, pressedAlpha = pressedAlpha)
        .clickable(interactionSource = source, indication = null, enabled = enabled,
            role = role, onClickLabel = onClickLabel, onClick = onClick)
}

val QuietRippleConfiguration = RippleConfiguration(rippleAlpha = RippleAlpha(
    draggedAlpha = .16f, focusedAlpha = .12f, hoveredAlpha = .08f, pressedAlpha = 0f))

@Composable
fun QuietTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                    colors: ButtonColors = ButtonDefaults.textButtonColors(), content: @Composable RowScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    CompositionLocalProvider(LocalRippleConfiguration provides QuietRippleConfiguration) {
        TextButton(onClick, modifier.pressFeedback(source, 1f, .64f),
            enabled = enabled, colors = colors, interactionSource = source, content = content)
    }
}

enum class Glyph(@param:DrawableRes val res: Int) {
    Home(R.drawable.ic_home), Search(R.drawable.ic_search), User(R.drawable.ic_user),
    Settings(R.drawable.ic_settings), Back(R.drawable.ic_chevron_left), Right(R.drawable.ic_chevron_right),
    Down(R.drawable.ic_chevron_down), More(R.drawable.ic_dots), MoreVertical(R.drawable.ic_dots_vertical),
    Heart(R.drawable.ic_heart), Queue(R.drawable.ic_playlist), Lyrics(R.drawable.ic_lyrics), Shuffle(R.drawable.ic_shuffle),
    Repeat(R.drawable.ic_repeat), RepeatOne(R.drawable.ic_repeat_once), Radio(R.drawable.ic_radio),
    Microphone(R.drawable.ic_microphone_2), Disc(R.drawable.ic_disc), Download(R.drawable.ic_download),
    Plus(R.drawable.ic_plus), Close(R.drawable.ic_x), Refresh(R.drawable.ic_refresh),
    Check(R.drawable.ic_check), Clock(R.drawable.ic_clock), Music(R.drawable.ic_music),
    Headphones(R.drawable.ic_headphones), Volume(R.drawable.ic_volume), Moon(R.drawable.ic_moon),
    Trash(R.drawable.ic_trash), Edit(R.drawable.ic_pencil), Folder(R.drawable.ic_folder),
    Qr(R.drawable.ic_qrcode), Sort(R.drawable.ic_arrows_sort), Up(R.drawable.ic_arrow_up), Alert(R.drawable.ic_alert_circle),
    Clover(R.drawable.ic_clover), External(R.drawable.ic_external_link), Info(R.drawable.ic_info_circle),
    Logout(R.drawable.ic_logout), Adjust(R.drawable.ic_adjustments), Endless(R.drawable.ic_infinity),
    Drag(R.drawable.ic_grip_horizontal), PlayNext(R.drawable.ic_player_track_next), PlaylistAdd(R.drawable.ic_playlist_add),
    Sleep(R.drawable.ic_zzz), Purchased(R.drawable.ic_shopping_bag), Fade(R.drawable.ic_wave_sine),
    Storage(R.drawable.ic_database), Lock(R.drawable.ic_lock),
}

@Composable
fun AppIcon(glyph: Glyph, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, description: String? = null) {
    Icon(painterResource(glyph.res), description, modifier.size(22.dp), tint)
}

@Composable
fun IconAction(glyph: Glyph, description: String, modifier: Modifier = Modifier,
               tint: Color = LocalContentColor.current, enabled: Boolean = true, iconSize: Dp = 22.dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).pressFeedback(source)
        .quietClickable(enabled = enabled, role = Role.Button, interactionSource = source, onClick = onClick), contentAlignment = Alignment.Center) {
        AppIcon(glyph, Modifier.size(iconSize), if (enabled) tint else tint.copy(alpha = .38f), description)
    }
}

/** A round button on a filled disc, as the design's back, like and more buttons; the touch target stays 48. */
@Composable
fun CircleAction(glyph: Glyph, description: String, fill: Color, modifier: Modifier = Modifier, size: Dp = 40.dp,
                 iconSize: Dp = 20.dp, tint: Color = LocalContentColor.current, shadow: Boolean = false,
                 enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(modifier.size(maxOf(size, 48.dp)).pressFeedback(source).clip(CircleShape)
        .quietClickable(interactionSource = source, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).then(if (shadow) Modifier.softShadow(CircleShape) else Modifier).background(fill, CircleShape),
            contentAlignment = Alignment.Center) {
            AppIcon(glyph, Modifier.size(iconSize), if (enabled) tint else tint.copy(alpha = .38f))
        }
    }
}

/** The design's card shadow: #16181C at 4%, y 4, blur 20. */
@Composable
fun Modifier.softShadow(shape: Shape, elevation: Dp = 10.dp): Modifier {
    val color = MaterialTheme.lilt.shadow
    return shadow(elevation, shape, clip = false, ambientColor = color.copy(alpha = .05f), spotColor = color.copy(alpha = .10f))
}

@Composable
fun Artwork(model: Any?, modifier: Modifier = Modifier, radius: Dp = 12.dp, description: String? = null) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        AppIcon(Glyph.Disc, Modifier.fillMaxSize(.36f), MaterialTheme.colorScheme.outline)
        AsyncImage(model, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

enum class Transport { Play, Pause, Next, Previous }

@Composable
fun TransportIcon(kind: Transport, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun triangle(left: Float, right: Float) {
            val p = Path().apply {
                moveTo(w * left, h * .18f)
                quadraticTo(w * left, h * .11f, w * (left + .055f), h * .15f)
                lineTo(w * right, h * .455f)
                quadraticTo(w * (right + .06f), h * .5f, w * right, h * .545f)
                lineTo(w * (left + .055f), h * .85f)
                quadraticTo(w * left, h * .89f, w * left, h * .82f)
                close()
            }
            drawPath(p, color, style = Fill)
        }
        when (kind) {
            Transport.Play -> triangle(.27f, .79f)
            Transport.Pause -> {
                val radius = androidx.compose.ui.geometry.CornerRadius(w * .045f)
                drawRoundRect(color, androidx.compose.ui.geometry.Offset(w * .24f, h * .15f),
                    androidx.compose.ui.geometry.Size(w * .19f, h * .70f), radius)
                drawRoundRect(color, androidx.compose.ui.geometry.Offset(w * .57f, h * .15f),
                    androidx.compose.ui.geometry.Size(w * .19f, h * .70f), radius)
            }
            Transport.Next -> { triangle(.08f, .48f); triangle(.49f, .89f) }
            Transport.Previous -> {
                with(drawContext.canvas) {
                    save()
                    translate(w, 0f)
                    scale(-1f, 1f)
                    triangle(.08f, .48f); triangle(.49f, .89f)
                    restore()
                }
            }
        }
    }
}

@Composable
fun TransportButton(kind: Transport, description: String, size: Dp = 28.dp,
                    color: Color = LocalContentColor.current, modifier: Modifier = Modifier,
                    enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).pressFeedback(source, .88f)
        .quietClickable(enabled = enabled, role = Role.Button, interactionSource = source, onClick = onClick),
        contentAlignment = Alignment.Center) {
        val iconModifier = Modifier.size(size).semantics { contentDescription = description }
        val tint = if (enabled) color else color.copy(alpha = .38f)
        if (kind == Transport.Play || kind == Transport.Pause) PlayPauseIcon(kind == Transport.Pause, iconModifier, tint)
        else TransportIcon(kind, iconModifier, tint)
    }
}

/** Two adjoining halves of the play triangle become the pause bars without changing the hit target. */
@Composable
private fun PlayPauseIcon(playing: Boolean, modifier: Modifier, color: Color) {
    val progress by animateFloatAsState(if (playing) 1f else 0f, tween(200), label = "playPause")
    Canvas(modifier) {
        fun half(play: List<Pair<Float, Float>>, pause: List<Pair<Float, Float>>) {
            val points = play.zip(pause) { a, b ->
                androidx.compose.ui.geometry.Offset(
                    (a.first + (b.first - a.first) * progress) * size.width,
                    (a.second + (b.second - a.second) * progress) * size.height)
            }
            val path = Path()
            points.forEachIndexed { index, point ->
                val previous = points[(index + points.size - 1) % points.size]
                val next = points[(index + 1) % points.size]
                val before = androidx.compose.ui.geometry.lerp(point, previous, .10f)
                val after = androidx.compose.ui.geometry.lerp(point, next, .10f)
                if (index == 0) path.moveTo(before.x, before.y) else path.lineTo(before.x, before.y)
                path.quadraticTo(point.x, point.y, after.x, after.y)
            }
            path.close()
            drawPath(path, color)
        }
        half(listOf(.27f to .15f, .54f to .315f, .54f to .685f, .27f to .85f),
            listOf(.24f to .15f, .43f to .15f, .43f to .85f, .24f to .85f))
        half(listOf(.53f to .309f, .81f to .48f, .81f to .52f, .53f to .691f),
            listOf(.57f to .15f, .76f to .15f, .76f to .85f, .57f to .85f))
    }
}

/** A song row. [subtitle] defaults to the artist; [trailing] replaces the more button, as the like heart on home shelves. */
@Composable
fun SongRow(song: Song, onClick: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier,
            active: Boolean = false, index: Int? = null, height: Dp = 64.dp, cover: Dp = 48.dp, coverRadius: Dp = 8.dp,
            subtitle: String = song.artist, trailing: (@Composable () -> Unit)? = null) {
    val tertiary = MaterialTheme.lilt.textTertiary
    val selection = LocalSongSelection.current?.takeIf { song.mid in it.availableIds }?.state
    val selecting = selection?.active == true
    val checked = selecting && song.mid in selection.selectedIds
    val source = remember { MutableInteractionSource() }
    val interaction = if (selection == null) Modifier.quietClickable(onClick = onClick)
        else Modifier.quietIndication(source).pressFeedback(source, 1f, .76f).combinedClickable(
            interactionSource = source, indication = null,
            role = if (selecting) Role.Checkbox else Role.Button,
            onLongClickLabel = "多选歌曲",
            onLongClick = {
                if (selection.active) selection.toggle(song.mid) else selection.start(song.mid)
            },
            onClick = { if (selection.active) selection.toggle(song.mid) else onClick() })
    Row(modifier.fillMaxWidth().heightIn(min = height).clip(RoundedCornerShape(12.dp))
        .background(if (checked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .testTag("song-row-${song.mid}").semantics {
            if (selecting) {
                selected = checked
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            }
        }
        .then(interaction), verticalAlignment = Alignment.CenterVertically) {
        if (index != null) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                if (active) AppIcon(Glyph.Volume, Modifier.size(18.dp), MaterialTheme.colorScheme.primary, "正在播放")
                else Text(index.toString(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = tertiary)
            }
            Spacer(Modifier.width(14.dp))
        } else {
            Artwork(song.cover, Modifier.size(cover), coverRadius)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(song.title, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selecting) SongSelectionCheck(checked)
        else if (trailing != null) trailing()
        else IconAction(Glyph.More, "${song.title}的更多操作", tint = tertiary, iconSize = 20.dp, onClick = onMore)
    }
}

@Composable
fun SectionTitle(title: String, action: String? = null, modifier: Modifier = Modifier, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (action != null) Row(Modifier.clip(RoundedCornerShape(10.dp)).quietClickable(onClick = onAction)
            .heightIn(min = 40.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(action, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppIcon(Glyph.Right, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The design's soft green pill, as 播放全部 in search and 刷新二维码 on login. */
@Composable
fun SoftPill(text: String, glyph: Glyph?, modifier: Modifier = Modifier, iconSize: Dp = 16.dp,
             horizontal: Dp = 12.dp, vertical: Dp = 6.dp, fontSize: Int = 12, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(modifier.heightIn(min = 48.dp).pressFeedback(source).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
        .quietClickable(interactionSource = source, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = horizontal, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (glyph != null) AppIcon(glyph, Modifier.size(iconSize), MaterialTheme.colorScheme.primary)
        Text(text, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun EmptyState(title: String, message: String? = null, glyph: Glyph = Glyph.Music,
               action: String? = null, modifier: Modifier = Modifier, onAction: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppIcon(glyph, Modifier.size(32.dp), MaterialTheme.colorScheme.outline)
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (!message.isNullOrBlank()) Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) SoftPill(action, null, horizontal = 16.dp, vertical = 8.dp, fontSize = 13, onClick = onAction)
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

/** A white card with the design's soft shadow; rows inside draw their own dividers. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, radius: Dp = 22.dp, horizontal: Dp = 16.dp, vertical: Dp = 2.dp,
              content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Column(modifier.fillMaxWidth().softShadow(shape).clip(shape).background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = horizontal, vertical = vertical), content = content)
}

@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, 1.dp, MaterialTheme.lilt.divider)
}

/** A settings row: 21 icon, 15 label with an optional 12 subtitle, a 14 value and an 18 chevron. */
@Composable
fun SettingRow(glyph: Glyph, label: String, value: String? = null, onClick: (() -> Unit)? = null,
               subtitle: String? = null, iconWrap: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val source = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = if (iconWrap) 58.dp else 48.dp)
        .then(if (onClick != null) Modifier.quietClickable(interactionSource = source, pressedAlpha = 1f, onClick = onClick) else Modifier)
        .padding(vertical = if (iconWrap) 12.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (iconWrap) Box(Modifier.size(34.dp).pressFeedback(source, .94f, .7f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center) { AppIcon(glyph, Modifier.size(19.dp)) }
        else AppIcon(glyph, Modifier.size(21.dp).pressFeedback(source, .94f, .7f))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null) Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp))
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
        else if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            AppIcon(Glyph.Right, Modifier.size(18.dp), MaterialTheme.lilt.textTertiary)
        }
    }
}

/** The design's 46×28 switch with a 22 thumb. */
@Composable
fun LiltSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, description: String, enabled: Boolean = true,
               interactionSource: MutableInteractionSource? = null) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val track by androidx.compose.animation.animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, label = "switchTrack")
    val offset by androidx.compose.animation.core.animateDpAsState(if (checked) 18.dp else 0.dp, label = "switchThumb")
    Box(Modifier.size(48.dp, 48.dp).then(if (onChange != null) Modifier.clip(CircleShape).quietIndication(source)
        .toggleable(checked, interactionSource = source, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = description } else Modifier), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.size(46.dp, 28.dp).background(track.copy(alpha = if (enabled) 1f else .5f), RoundedCornerShape(14.dp)).padding(3.dp)) {
            Box(Modifier.offset(x = offset).size(22.dp).graphicsLayer { scaleX = if (pressed) .9f else 1f; scaleY = if (pressed) .9f else 1f }
                .shadow(1.5.dp, CircleShape).background(Color.White, CircleShape))
        }
    }
}

fun formatTime(ms: Long): String = "%d:%02d".format((ms.coerceAtLeast(0) / 60000), (ms.coerceAtLeast(0) / 1000) % 60)
fun formatCount(count: Long): String = when {
    count >= 100000000 -> "%.1f亿".format(count / 100000000.0).replace(".0亿", "亿")
    count >= 10000 -> "%.1f万".format(count / 10000.0).replace(".0万", "万")
    else -> count.toString()
}
