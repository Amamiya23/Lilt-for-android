package com.lilt.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lilt.app.AppState
import com.lilt.app.Destination
import com.lilt.app.PurchasedTab
import com.lilt.app.data.Song
import com.lilt.app.ui.theme.lilt

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

@Composable
internal fun SongSelectionHeader(selection: SongSelectionState, availableIds: Set<String>, busy: Boolean,
                                 modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).background(MaterialTheme.colorScheme.background)
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(Glyph.Close, "退出多选", onClick = selection::clear)
        Text("已选 ${selection.selectedIds.size} 首", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (busy) CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(18.dp), strokeWidth = 2.dp)
        QuietTextButton({ selection.selectAll(availableIds) }, enabled = !busy && availableIds.isNotEmpty()) {
            Text(if (selection.selectedIds.containsAll(availableIds)) "取消全选" else "全选")
        }
    }
}

/** One selection target per row; its check is visual, so TalkBack does not announce a second checkbox. */
@Composable
internal fun SongSelectionCheck(checked: Boolean) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).background(
            if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background, CircleShape)
            .border(if (checked) 0.dp else 1.5.dp,
                if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center) {
            if (checked) AppIcon(Glyph.Check, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
internal fun SongSelectionActions(selection: SongSelectionState, songs: List<Song>, removableIds: Set<String>,
                                  busy: Boolean, onAddTo: (List<Song>) -> Unit, onDownload: (List<Song>) -> Unit,
                                  onRemove: (List<Song>) -> Unit, modifier: Modifier = Modifier) {
    val selected = songs.filter { it.mid in selection.selectedIds }
    val online = selected.filterNot { it.mid.startsWith("content:") || it.mid.startsWith("file:") }
    val removable = selected.filter { it.mid in removableIds }
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.lilt.divider)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionAction("添加到", Glyph.PlaylistAdd, !busy && online.isNotEmpty(), Modifier.weight(1f)) { onAddTo(online) }
            SelectionAction("下载", Glyph.Download, !busy && online.isNotEmpty(), Modifier.weight(1f)) {
                onDownload(online)
            }
            SelectionAction("移除", Glyph.Trash, !busy && removable.isNotEmpty(), Modifier.weight(1f), destructive = true) {
                onRemove(removable)
            }
        }
    }
}

@Composable
private fun SelectionAction(label: String, glyph: Glyph, enabled: Boolean, modifier: Modifier,
                            destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(modifier.heightIn(min = 56.dp).quietClickable(enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val tint = color.copy(alpha = if (enabled) 1f else .38f)
        AppIcon(glyph, Modifier.size(21.dp), tint)
        Spacer(Modifier.width(8.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelLarge)
    }
}
