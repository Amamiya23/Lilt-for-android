package com.lilt.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lilt.app.R
import com.lilt.app.data.Song
import com.lilt.app.ui.theme.Radix
import kotlin.math.min

enum class Glyph(@param:DrawableRes val res: Int) {
    Home(R.drawable.ic_home), Search(R.drawable.ic_search), User(R.drawable.ic_user),
    Settings(R.drawable.ic_settings), Back(R.drawable.ic_chevron_left), Right(R.drawable.ic_chevron_right),
    Down(R.drawable.ic_chevron_down), More(R.drawable.ic_dots), Heart(R.drawable.ic_heart),
    Queue(R.drawable.ic_playlist), Lyrics(R.drawable.ic_lyrics), Shuffle(R.drawable.ic_shuffle),
    Repeat(R.drawable.ic_repeat), RepeatOne(R.drawable.ic_repeat_once), Radio(R.drawable.ic_radio),
    Microphone(R.drawable.ic_microphone_2), Disc(R.drawable.ic_disc), Download(R.drawable.ic_download),
    Plus(R.drawable.ic_plus), Close(R.drawable.ic_x), Refresh(R.drawable.ic_refresh),
    Check(R.drawable.ic_check), Clock(R.drawable.ic_clock), Music(R.drawable.ic_music),
    Headphones(R.drawable.ic_headphones), Volume(R.drawable.ic_volume), Moon(R.drawable.ic_moon),
    Trash(R.drawable.ic_trash), Share(R.drawable.ic_share), Folder(R.drawable.ic_folder),
    Qq(R.drawable.ic_brand_qq), Wechat(R.drawable.ic_brand_wechat), Qr(R.drawable.ic_qrcode),
    Sort(R.drawable.ic_arrows_sort), Up(R.drawable.ic_arrow_up), Alert(R.drawable.ic_alert_circle),
    Clover(R.drawable.ic_clover), External(R.drawable.ic_external_link), Info(R.drawable.ic_info_circle),
    Logout(R.drawable.ic_logout), Adjust(R.drawable.ic_adjustments)
}

@Composable
fun AppIcon(glyph: Glyph, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, description: String? = null) {
    Icon(painterResource(glyph.res), description, modifier.size(22.dp), tint)
}

@Composable
fun IconAction(glyph: Glyph, description: String, modifier: Modifier = Modifier,
               tint: Color = LocalContentColor.current, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), enabled = enabled) {
        AppIcon(glyph, tint = if (enabled) tint else tint.copy(alpha = .38f), description = description)
    }
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
    IconButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), enabled = enabled) {
        TransportIcon(kind, Modifier.size(size).semantics { contentDescription = description },
            if (enabled) color else color.copy(alpha = .38f))
    }
}

@Composable
fun SongRow(song: Song, onClick: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier,
            active: Boolean = false, index: Int? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (index != null) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                if (active) AppIcon(Glyph.Volume, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                else Text(index.toString(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(6.dp))
        } else {
            Artwork(song.cover, Modifier.size(48.dp), 10.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(song.title, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconAction(Glyph.More, "${song.title}的更多操作", Modifier.size(48.dp),
            MaterialTheme.colorScheme.onSurfaceVariant, onClick = onMore)
    }
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (action != null) TextButton(onAction, contentPadding = PaddingValues(start = 12.dp)) {
            Text(action, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppIcon(Glyph.Right, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
        if (action != null) FilledTonalButton(onAction) { Text(action) }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = 16.dp, vertical = 4.dp), content = content)
}

@Composable
fun SettingRow(glyph: Glyph, label: String, value: String? = null, onClick: (() -> Unit)? = null,
               trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        AppIcon(glyph, Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing != null) trailing()
        else if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            AppIcon(Glyph.Right, Modifier.size(18.dp), MaterialTheme.colorScheme.outline)
        }
    }
}

fun formatTime(ms: Long): String = "%d:%02d".format((ms.coerceAtLeast(0) / 60000), (ms.coerceAtLeast(0) / 1000) % 60)
fun formatCount(count: Long): String = when {
    count >= 100000000 -> "%.1f 亿".format(count / 100000000.0)
    count >= 10000 -> "%.1f 万".format(count / 10000.0)
    else -> count.toString()
}
