package com.lilt.app.playback

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lilt.app.R
import com.lilt.app.data.QqMusicRepository
import com.lilt.app.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The notification's play-mode and favorite buttons. Android places the first custom
 * action left of the transport controls and the second one right of them.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class NotificationControls(
    private val context: Context,
    private val player: Player,
    private val repository: QqMusicRepository,
    private val scope: CoroutineScope,
    private val songOf: (MediaItem) -> Song?,
) {
    var session: MediaSession? = null
        set(value) { field = value; published = null; publish() }
    private var published: List<Any?>? = null
    private var mid: String? = null
    private var liked = false
    /** The favorite state being written, shown at once rather than after the round trips. */
    private var writing: Pair<String, Boolean>? = null
    private var lookup: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                track()
                publish()
            }
        })
        scope.launch {
            repository.profile.collect { mid = null; track(); publish() }
        }
        scope.launch {
            repository.likeChanges.collect { change ->
                val current = mid ?: return@collect
                if (change.account != repository.accountKey) return@collect
                change.statuses[current]?.let { liked = it; publish() }
            }
        }
    }

    fun buttons(): List<CommandButton> = listOfNotNull(modeButton(), likeButton())

    /** Adds the two commands to every controller; the notification is the one that shows them. */
    fun wrap(callback: MediaLibrarySession.Callback): MediaLibrarySession.Callback =
        object : MediaLibrarySession.Callback by callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val base = callback.onConnect(session, controller)
                if (!base.isAccepted) return base
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(base.availableSessionCommands.buildUpon().add(MODE).add(LIKE).build())
                    .setAvailablePlayerCommands(base.availablePlayerCommands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    MODE.customAction -> cycleMode()
                    LIKE.customAction -> toggleLike()
                    else -> return callback.onCustomCommand(session, controller, customCommand, args)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }

    /** 列表循环, then 随机播放, then 单曲循环, matching the queue's three icons. */
    private fun cycleMode() {
        when {
            player.repeatMode == Player.REPEAT_MODE_ONE -> {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ALL
            }
            player.shuffleModeEnabled -> {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ONE
            }
            else -> player.shuffleModeEnabled = true
        }
    }

    private fun toggleLike() {
        val item = player.currentMediaItem ?: return
        val song = songOf(item) ?: return
        if (writing != null || !canLike(item)) return
        val target = !shownLiked()
        writing = song.mid to target
        publish()
        scope.launch {
            try {
                repository.like(song, target)
                if (mid == song.mid) liked = target
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(context, error.message?.takeIf { it.length < 180 } ?: "连接失败，请检查网络后重试", Toast.LENGTH_SHORT).show()
            } finally {
                writing = null
                publish()
            }
        }
    }

    /** Re-reads the favorite state only when the song or the account changes. */
    private fun track() {
        val item = player.currentMediaItem
        val current = item?.takeIf(::canLike)?.mediaId
        if (current == mid) return
        mid = current
        liked = false
        lookup?.cancel()
        if (current == null) return
        val account = repository.accountKey
        lookup = scope.launch {
            val cached = withContext(Dispatchers.IO) { repository.metadataCache.liked(account)[current] }
            if (mid != current) return@launch
            liked = cached == true
            publish()
            // Offline, this is the cached state; online, it also reaches the app through likeChanges.
            val confirmed = runCatching { repository.isLiked(current) }
                .onFailure { if (it is CancellationException) throw it }.getOrNull() ?: return@launch
            if (mid == current) { liked = confirmed; publish() }
        }
    }

    private fun canLike(item: MediaItem): Boolean =
        repository.profile.value != null && item.localConfiguration?.uri?.scheme == "lilt"

    private fun shownLiked(): Boolean = writing?.takeIf { it.first == mid }?.second ?: liked

    private fun publish() {
        val session = session ?: return
        val key = listOf(player.shuffleModeEnabled, player.repeatMode, mid, shownLiked())
        if (key == published) return
        published = key
        session.setMediaButtonPreferences(buttons())
    }

    private fun modeButton(): CommandButton {
        val (icon, res, name) = when {
            player.repeatMode == Player.REPEAT_MODE_ONE -> Triple(CommandButton.ICON_REPEAT_ONE, R.drawable.ic_repeat_once, "单曲循环")
            player.shuffleModeEnabled -> Triple(CommandButton.ICON_SHUFFLE_ON, R.drawable.ic_shuffle, "随机播放")
            // Like the queue, 顺序播放 shares the repeat icon; the system cannot show the app's off tint.
            player.repeatMode == Player.REPEAT_MODE_ALL -> Triple(CommandButton.ICON_REPEAT_ALL, R.drawable.ic_repeat, "列表循环")
            else -> Triple(CommandButton.ICON_REPEAT_OFF, R.drawable.ic_repeat, "顺序播放")
        }
        return CommandButton.Builder(icon)
            .setCustomIconResId(res)
            .setDisplayName(name)
            .setSessionCommand(MODE)
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
    }

    private fun likeButton(): CommandButton? {
        mid ?: return null
        val liked = shownLiked()
        return CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setCustomIconResId(if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart)
            .setDisplayName(if (liked) "取消喜欢" else "喜欢")
            .setSessionCommand(LIKE)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
    }

    private companion object {
        val MODE = SessionCommand("com.lilt.playback.MODE", Bundle.EMPTY)
        val LIKE = SessionCommand("com.lilt.playback.LIKE", Bundle.EMPTY)
    }
}
