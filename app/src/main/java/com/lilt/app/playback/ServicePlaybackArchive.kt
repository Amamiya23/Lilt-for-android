package com.lilt.app.playback

import android.content.Context
import android.util.AtomicFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Service-owned persistence continues when the Activity and its controller are gone. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ServicePlaybackArchive(context: Context, private val player: Player, serviceScope: CoroutineScope) {
    private val store = ArchiveFile(File(context.filesDir, "playback-queue.json"))
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<QueueArchive>(Channel.CONFLATED)
    private var last: QueueArchive? = null
    private var started = false
    private val writer = writerScope.launch {
        for (archive in pending) runCatching { store.write(archive) }
    }.also { it.invokeOnCompletion { writerScope.cancel() } }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { save() }
    }
    private val ticker: Job = serviceScope.launch {
        while (isActive) {
            delay(1_000)
            if (started && player.isPlaying) save()
        }
    }

    /** Called before publishing the session; a live queue always takes precedence. */
    fun restoreIfEmpty() {
        if (player.mediaItemCount == 0) {
            val archive = store.read()
            if (archive != null && archive.tracks.isNotEmpty()) {
                player.setMediaItems(archive.tracks.map { TrackMediaItem.toMediaItem(it, archive.source) }, archive.index, archive.positionMs)
                player.shuffleModeEnabled = archive.shuffle
                player.repeatMode = archive.repeatMode
                player.playWhenReady = false
                // Do not prepare: reopening the app must neither play nor start an offline load.
            }
        }
        started = true
        player.addListener(listener)
    }

    private fun capture(): QueueArchive = QueueArchive(
        (0 until player.mediaItemCount).map { TrackMediaItem.fromMediaItem(player.getMediaItemAt(it)) },
        player.currentMediaItemIndex, player.currentPosition, player.shuffleModeEnabled, player.repeatMode,
        player.currentMediaItem?.let(TrackMediaItem::source).orEmpty(),
    ).normalized()

    private fun save() {
        if (!started) return
        val archive = capture()
        if (archive == last) return
        last = archive
        pending.trySend(archive)
    }

    fun close() {
        save()
        player.removeListener(listener)
        ticker.cancel()
        // Drain the final snapshot on IO even when the service's coroutine scope is cancelled.
        pending.close()
    }

    private fun currentArchive(): QueueArchive? =
        if (player.mediaItemCount > 0) capture() else store.read()

    private fun recentItem(): MediaItem? = currentArchive()?.let { archive ->
        archive.tracks.getOrNull(archive.index)?.let { TrackMediaItem.toMediaItem(it, archive.source) }
    }

    private fun rootItem(): MediaItem = MediaItem.Builder().setMediaId(ROOT)
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Lilt").setIsBrowsable(true).setIsPlayable(false).build())
        .build()

    /** Exposes only the last played item for Android's resumption card, without network browsing. */
    val callback = object : MediaLibrarySession.Callback {
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
            val archive = currentArchive()
            val byId = archive?.tracks?.associateBy { it.id }.orEmpty()
            return Futures.immediateFuture(mediaItems.map { item ->
                if (item.localConfiguration != null) item
                else byId[item.mediaId]?.let { TrackMediaItem.toMediaItem(it, archive?.source.orEmpty()) } ?: item
            })
        }

        override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val archive = currentArchive()
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(
                archive?.tracks?.map { TrackMediaItem.toMediaItem(it, archive.source) }.orEmpty(),
                archive?.index ?: 0, archive?.positionMs ?: 0,
            ))
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = if (mediaId == ROOT) rootItem() else recentItem()?.takeIf { it.mediaId == mediaId }
            return Futures.immediateFuture(if (item == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                else LibraryResult.ofItem(item, null))
        }

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT || page < 0 || pageSize <= 0)
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            val items = if (page == 0) listOfNotNull(recentItem()) else emptyList()
            return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
        }
    }

    private class ArchiveFile(file: File) {
        private val atomic = AtomicFile(file)
        @Synchronized fun read(): QueueArchive? = runCatching {
            QueueArchiveJson.decode(String(atomic.readFully(), Charsets.UTF_8))
        }.getOrNull()

        @Synchronized fun write(archive: QueueArchive) {
            val bytes = QueueArchiveJson.encode(archive).toByteArray(Charsets.UTF_8)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        }
    }

    private companion object { const val ROOT = "lilt-recent" }
}
