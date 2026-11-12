package com.lilt.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilt.app.AppState
import com.lilt.app.Destination
import com.lilt.app.PurchasedTab
import com.lilt.app.data.Song
import com.lilt.app.ui.theme.lilt
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

@Stable
internal class SongSelectionState {
    var active by mutableStateOf(false)
        private set
    var selectedIds by mutableStateOf(emptySet<String>())
        private set

    fun start(mid: String) {
        active = true
        selectedIds = setOf(mid)
    }

    fun toggle(mid: String) {
        selectedIds = if (mid in selectedIds) selectedIds - mid else selectedIds + mid
    }

    fun selectAll(ids: Set<String>) {
        selectedIds = if (selectedIds.containsAll(ids)) emptySet() else ids
    }

    fun clear() {
        active = false
        selectedIds = emptySet()
    }

    /** Keep failed deletions selected, and leave selection mode when all selected files disappeared. */
    fun reconcile(ids: Set<String>) {
        val remaining = selectedIds.intersect(ids)
        if (selectedIds.isNotEmpty() && remaining.isEmpty()) clear()
        else selectedIds = remaining
    }
}

internal data class SongSelectionContext(val state: SongSelectionState, val availableIds: Set<String>)
internal val LocalSongSelection = staticCompositionLocalOf<SongSelectionContext?> { null }

internal fun songsForSelection(state: AppState): List<Song> = when (state.destination) {
    Destination.Home -> state.home.shelves.takeIf { it.isNotEmpty() }?.flatMap { it.songs } ?: state.home.songs
    Destination.Search -> if (state.searched && state.query.isNotBlank() && !state.searching && state.searchError == null)
        state.search.songs else emptyList()
    Destination.Playlist -> state.detail?.songs.orEmpty()
    Destination.Purchased -> if (state.purchasedTab == PurchasedTab.Songs) state.purchasedSongs.orEmpty() else emptyList()
    Destination.Local -> state.local
    else -> emptyList()
}.distinctBy { it.mid }

internal fun selectionScope(state: AppState): String = when (state.destination) {
    Destination.Playlist -> "playlist:${state.detail?.playlist?.id}:${state.detail?.playlist?.title}"
    Destination.Search -> "search:${state.searchType}:${state.query}"
    Destination.Purchased -> "purchased:${state.purchasedTab}"
    else -> state.destination.name
}

/** Stops touches on the floating selection chrome from reaching the list beneath it. */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

/**
 * Overlays the top of the page in place of its own header, so entering selection never moves the list.
 * [topInset] is the status bar, which the header's glass also covers.
 */
@Composable
internal fun SongSelectionHeader(selection: SongSelectionState, availableIds: Set<String>, busy: Boolean,
                                 topInset: Dp, hazeState: HazeState, modifier: Modifier = Modifier) {
    val edge = MaterialTheme.lilt.glassEdge
    val glass = MaterialTheme.lilt.glass
    val allSelected = availableIds.isNotEmpty() && selection.selectedIds.containsAll(availableIds)
    val enabled = !busy && availableIds.isNotEmpty()
    Row(modifier.fillMaxWidth().blockTouches().hazeEffect(hazeState) {
        blurRadius = 24.dp
        tints = listOf(HazeTint(glass.copy(alpha = .72f)))
    }
        .drawBehind { drawLine(edge, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
        .padding(top = topInset).height(56.dp).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconAction(Glyph.Close, "退出多选", onClick = selection::clear)
        Text("已选 ${selection.selectedIds.size} 首", Modifier.weight(1f).padding(start = 4.dp), fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (busy) CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(18.dp), strokeWidth = 2.dp)
        Box(Modifier.heightIn(min = 48.dp).quietClickable(enabled, role = Role.Button) { selection.selectAll(availableIds) },
            contentAlignment = Alignment.Center) {
            Text(if (allSelected) "取消全选" else "全选",
                Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .38f),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Floats where the mini player and navigation were, without taking layout space from the page. */
@Composable
internal fun SongSelectionActions(selection: SongSelectionState, songs: List<Song>, removableIds: Set<String>,
                                  busy: Boolean, onAddTo: (List<Song>) -> Unit, onDownload: (List<Song>) -> Unit,
                                  onRemove: (List<Song>) -> Unit, hazeState: HazeState, modifier: Modifier = Modifier) {
    val selected = songs.filter { it.mid in selection.selectedIds }
    val online = selected.filterNot { it.mid.startsWith("content:") || it.mid.startsWith("file:") }
    val removable = selected.filter { it.mid in removableIds }
    val shape = RoundedCornerShape(24.dp)
    val shadow = MaterialTheme.lilt.shadow
    val glass = MaterialTheme.lilt.glass
    Row(modifier.fillMaxWidth().blockTouches()
        .shadow(18.dp, shape, ambientColor = shadow.copy(alpha = .10f), spotColor = shadow.copy(alpha = .14f))
        .clip(shape).hazeEffect(hazeState) {
            blurRadius = 24.dp
            tints = listOf(HazeTint(glass.copy(alpha = .72f)))
        }.border(1.dp, MaterialTheme.lilt.glassEdge, shape)
        .padding(6.dp)) {
        SelectionAction("添加到", Glyph.PlaylistAdd, !busy && online.isNotEmpty(), Modifier.weight(1f)) { onAddTo(online) }
        SelectionAction("下载", Glyph.Download, !busy && online.isNotEmpty(), Modifier.weight(1f)) {
            onDownload(online)
        }
        SelectionAction("移除", Glyph.Trash, !busy && removable.isNotEmpty(), Modifier.weight(1f), destructive = true) {
            onRemove(removable)
        }
    }
}

@Composable
private fun SelectionAction(label: String, glyph: Glyph, enabled: Boolean, modifier: Modifier,
                            destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val tint = color.copy(alpha = if (enabled) 1f else .38f)
    Column(modifier.height(56.dp).clip(RoundedCornerShape(18.dp)).quietClickable(enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        AppIcon(glyph, Modifier.size(22.dp), tint)
        Text(label, color = tint, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** One selection target per row; its check is visual, so TalkBack does not announce a second checkbox. */
@Composable
internal fun SongSelectionCheck(checked: Boolean) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).background(
            if (checked) MaterialTheme.lilt.accent else MaterialTheme.colorScheme.background, CircleShape)
            .border(if (checked) 0.dp else 1.5.dp,
                if (checked) MaterialTheme.lilt.accent else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center) {
            if (checked) AppIcon(Glyph.Check, Modifier.size(16.dp), MaterialTheme.lilt.onAccent)
        }
    }
}
