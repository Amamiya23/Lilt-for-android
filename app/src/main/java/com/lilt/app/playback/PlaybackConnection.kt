package com.lilt.app.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * An Activity-independent connection to the playback service. UI commands are
 * marshalled onto Media3's application looper; closing the connection does not
 * stop background playback.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackConnection(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectMutex = Mutex()
    private val mainExecutor = Executor { Handler(Looper.getMainLooper()).post(it) }
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var closed = false
    private val mutableState = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = mutableState.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
            updateTicker()
        }

        override fun onPlayerError(error: PlaybackException) {
            mutableState.value = mutableState.value.copy(error = playbackErrorMessage(error))
        }
    }

    suspend fun connect() = withContext(Dispatchers.Main.immediate) {
        connectMutex.withLock {
            check(!closed) { "PlaybackConnection is closed" }
            if (controller != null) return@withLock
            val token = SessionToken(
                appContext,
                ComponentName(appContext, LiltPlaybackService::class.java),
            )
            val future = MediaController.Builder(appContext, token).buildAsync()
            val connected = suspendCancellableCoroutine<MediaController> { continuation ->
                future.addListener(
                    {
                        if (continuation.isActive) {
                            try {
                                continuation.resume(future.get())
                            } catch (error: Exception) {
                                continuation.resumeWithException(error.cause ?: error)
                            }
                        }
                    },
                    mainExecutor,
                )
                continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
            }
            if (closed) {
                connected.release()
                return@withLock
            }
            controller = connected
            connected.addListener(listener)
            publish()
            updateTicker()
        }
    }

    fun setQueue(tracks: List<ResolvedTrack>, index: Int = 0, source: String = "") = command {
        if (tracks.isEmpty()) return@command
        require(tracks.all { it.url.isNotBlank() }) { "A resolved playback URL is required" }
        mutableState.value = mutableState.value.copy(error = null)
        setMediaItems(tracks.map { TrackMediaItem.toMediaItem(it, source) }, index.coerceIn(tracks.indices), 0L)
        prepare()
        play()
    }

    /** Inserts after the current song, or starts playing when the queue is empty. */
    fun playNext(tracks: List<ResolvedTrack>) = command {
        if (tracks.isEmpty()) return@command
        val source = currentMediaItem?.let(TrackMediaItem::source).orEmpty()
        if (mediaItemCount == 0) { setMediaItems(tracks.map { TrackMediaItem.toMediaItem(it, source) }); prepare(); play(); return@command }
        addMediaItems(currentMediaItemIndex + 1, tracks.map { TrackMediaItem.toMediaItem(it, source) })
    }

    fun append(tracks: List<ResolvedTrack>) = command {
        if (tracks.isEmpty()) return@command
        val source = currentMediaItem?.let(TrackMediaItem::source).orEmpty()
        addMediaItems(tracks.map { TrackMediaItem.toMediaItem(it, source) })
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) { prepare(); play() }
    }

    fun pause() = command { pause() }

    fun toggle() = command {
        when {
            playbackState == Player.STATE_IDLE && mediaItemCount > 0 -> {
                mutableState.value = mutableState.value.copy(error = null)
                prepare()
                play()
            }
            playbackState == Player.STATE_ENDED -> {
                seekToDefaultPosition()
                play()
            }
            playWhenReady -> pause()
            mediaItemCount > 0 -> play()
        }
    }

    fun next() = command {
        if (hasNextMediaItem()) seekToNextMediaItem()
    }

    fun previous() = command {
        if (currentPosition > 3_000 || !hasPreviousMediaItem()) {
            seekTo(0)
        } else {
            seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) = command {
        val upper = duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        seekTo(positionMs.coerceIn(0, upper))
    }

    fun playAt(index: Int) = command {
        if (index !in 0 until mediaItemCount) return@command
        mutableState.value = mutableState.value.copy(error = null)
        seekToDefaultPosition(index)
        if (playbackState == Player.STATE_IDLE) prepare()
        play()
    }

    fun remove(index: Int) = command {
        if (index !in 0 until mediaItemCount) return@command
        removeMediaItem(index)
    }

    fun move(from: Int, to: Int) = command {
        if (from !in 0 until mediaItemCount || to !in 0 until mediaItemCount) return@command
        moveMediaItem(from, to)
    }

    /** Keep the current track and its playback position, removing everything else. */
    fun clearUpcoming() = command {
        val current = currentMediaItemIndex
        if (current !in 0 until mediaItemCount) return@command
        if (current + 1 < mediaItemCount) removeMediaItems(current + 1, mediaItemCount)
        if (current > 0) removeMediaItems(0, current)
    }

    fun setShuffle(enabled: Boolean) = command {
        shuffleModeEnabled = enabled
    }

    fun cycleRepeat() = command {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun close() {
        if (closed) return
        closed = true
        scope.launch {
            ticker?.cancel()
            controller?.removeListener(listener)
            controller?.release()
            controller = null
            scope.cancel()
        }
    }

    private fun command(action: MediaController.() -> Unit) {
        if (closed) return
        scope.launch {
            try {
                connect()
                controller?.action()
                publish()
                updateTicker()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    error = error.message?.takeIf { it.isNotBlank() } ?: "无法连接播放器，请重试",
                )
            }
        }
    }

    private fun publish() {
        val player = controller ?: return
        val tracks = (0 until player.mediaItemCount).map { index ->
            TrackMediaItem.fromMediaItem(player.getMediaItemAt(index))
        }
        val failure = player.playerError?.let(::playbackErrorMessage)
        mutableState.value = PlaybackSnapshot(
            tracks = tracks,
            currentIndex = player.currentMediaItemIndex.takeIf { tracks.isNotEmpty() } ?: -1,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                ?: tracks.getOrNull(player.currentMediaItemIndex)?.song?.durationMs ?: 0,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            error = failure ?: mutableState.value.error,
            sampledAtMs = android.os.SystemClock.elapsedRealtime(),
            source = player.currentMediaItem?.let(TrackMediaItem::source).orEmpty(),
        )
    }

    private fun updateTicker() {
        val needsTicks = controller?.let { it.isPlaying || it.playbackState == Player.STATE_BUFFERING } == true
        if (!needsTicks) {
            ticker?.cancel()
            ticker = null
            return
        }
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                publish()
                delay(400)
            }
        }
    }

}

internal fun playbackErrorMessage(error: PlaybackException): String {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is com.lilt.app.data.MusicApiException) return cause.message ?: "这首歌暂时无法播放"
        cause = cause.cause
    }
    return when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接中断，点按播放重试"
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "播放地址已失效，请重新选择这首歌"
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "暂不支持此音频格式，请更换音质后重试"
    else -> "播放失败，请重新选择歌曲或更换音质"
    }
}
