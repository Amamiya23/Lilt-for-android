package com.lilt.app.playback

import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.DefaultMediaNotificationProvider
import com.lilt.app.LiltApplication
import com.lilt.app.R
import com.lilt.app.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Owns the player independently from the Activity. MediaSessionService supplies the
 * foreground notification and routes lock-screen / headset controls to this player.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LiltPlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences by lazy { getSharedPreferences("lilt_settings", Context.MODE_PRIVATE) }
    private var archive: ServicePlaybackArchive? = null
    private var prefetchJob: Job? = null
    @Volatile private var prefetchWriter: CacheWriter? = null
    private var prefetchIdentity: String? = null
    private var lyricJob: Job? = null
    private var lyricMid: String? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.mipmap.ic_lilt)
            },
        )
        val app = application as LiltApplication
        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val httpFactory = OkHttpDataSource.Factory(http)
            .setUserAgent("Lilt-Android/0.1")
            .setDefaultRequestProperties(
                mapOf("Referer" to "https://y.qq.com/", "Origin" to "https://y.qq.com"),
            )
        val plain = DefaultDataSource.Factory(this, httpFactory)
        val resolvingFactory = DataSource.Factory {
            // Resolver state belongs to a single loader; playback and prefetch can run together.
            ResolvingDataSource(plain.createDataSource(), object : ResolvingDataSource.Resolver {
                private var sourceUri: Uri? = null
                override fun resolveDataSpec(spec: DataSpec): DataSpec {
                    val uri = spec.uri
                    sourceUri = uri.takeIf { it.scheme == "lilt" }
                    if (sourceUri == null) return spec
                    val quality = uri.getQueryParameter("quality") ?: preferences.getString("quality", "standard") ?: "standard"
                    // Media3 calls the resolver on its loader thread, never the main thread.
                    val url = runBlocking { app.repository.songUrl(songFromUri(uri), quality, strictQuality = true) }
                    return spec.withUri(Uri.parse(url))
                }

                // CacheDataSource persists getUri() as redirect metadata. Signed URLs must stay transient.
                override fun resolveReportedUri(uri: Uri): Uri = sourceUri ?: uri
            })
        }
        // CachedAudioDataSource supplies a stable song/quality key before signed URL resolution.
        val cached = CacheDataSource.Factory().setCache(app.audioCache.cache).setUpstreamDataSourceFactory(resolvingFactory)
        val cacheOnly = CacheDataSource.Factory().setCache(app.audioCache.cache)
            .setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)
        val onlineAudio = CachedAudioDataSource.Factory(app.audioCache, { app.network.isOnline }, cached, cacheOnly)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(RoutingDataSource.Factory(onlineAudio, plain)),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val player = ShapedPlayer(exo)
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                skipUnavailable(exo)
                fetchCurrentLyrics(exo)
                prefetchNext(exo, cached)
            }
        })
        scope.launch {
            app.network.online.collectLatest { online ->
                if (!online) {
                    cancelPrefetch()
                    reopenOffline(exo)
                }
                skipUnavailable(exo)
                fetchCurrentLyrics(exo)
                prefetchNext(exo, cached)
            }
        }
        scope.launch {
            app.audioCache.available.collectLatest { skipUnavailable(exo) }
        }
        scope.launch {
            app.audioCache.generation.collectLatest { cancelPrefetch() }
        }
        archive = ServicePlaybackArchive(this, player, scope).also { it.restoreIfEmpty() }
        val launchIntent = Intent().setClassName(packageName, "com.lilt.app.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val activity = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val controls = NotificationControls(this, player, app.repository, scope) { item ->
            TrackMediaItem.song(item) ?: item.localConfiguration?.uri?.let(::songFromUri)
        }
        session = MediaLibrarySession.Builder(this, player, controls.wrap(requireNotNull(archive).callback))
            .setSessionActivity(activity)
            .setMediaButtonPreferences(controls.buttons())
            .build()
            .also { controls.session = it }
    }

    private fun canPlay(item: MediaItem): Boolean {
        val app = application as LiltApplication
        return item.localConfiguration?.uri?.scheme != "lilt" || item.mediaId in app.audioCache.available.value
    }

    /** Drop an open network loader so a complete alternate quality can be selected immediately. */
    private fun reopenOffline(exo: ExoPlayer) {
        val app = application as LiltApplication
        val item = exo.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        if (uri.scheme != "lilt") return
        val requested = uri.getQueryParameter("quality") ?: "standard"
        if (app.audioCache.cachedQuality(item.mediaId, requested, false) == requested) return
        val index = exo.currentMediaItemIndex
        val position = exo.currentPosition
        exo.stop()
        exo.seekTo(index, position)
        if (exo.playWhenReady && canPlay(item)) exo.prepare()
    }

    private fun skipUnavailable(exo: ExoPlayer) {
        val app = application as LiltApplication
        if (app.network.isOnline || !exo.playWhenReady || exo.mediaItemCount == 0) return
        val current = exo.currentMediaItem ?: return
        if (canPlay(current)) return
        // Traverse the actual shuffle order without repeat, then wrap once. Repeat-one cannot loop.
        val timeline = exo.currentTimeline
        val order = buildList {
            var index = timeline.getFirstWindowIndex(exo.shuffleModeEnabled)
            while (index != C.INDEX_UNSET && size < exo.mediaItemCount) {
                add(index)
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, exo.shuffleModeEnabled)
            }
        }.ifEmpty { (0 until exo.mediaItemCount).toList() }
        val next = nextPlayableIndex(order, exo.currentMediaItemIndex) { canPlay(exo.getMediaItemAt(it)) }
        if (next == null) { exo.pause(); exo.stop() }
        else { exo.seekToDefaultPosition(next); exo.prepare() }
    }

    private fun fetchCurrentLyrics(exo: ExoPlayer) {
        val app = application as LiltApplication
        val item = exo.currentMediaItem ?: return
        if (!app.network.isOnline || item.localConfiguration?.uri?.scheme != "lilt") return
        val song = TrackMediaItem.song(item) ?: songFromUri(item.localConfiguration!!.uri)
        if (lyricMid == song.mid) return
        lyricJob?.cancel()
        lyricMid = song.mid
        lyricJob = scope.launch(Dispatchers.IO) {
            try {
                if (app.lyricCache.get(song.mid) == null) app.lyricCache.put(song.mid, app.repository.lyrics(song))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                lyricMid = null
            }
        }
    }

    private fun prefetchNext(exo: ExoPlayer, cached: CacheDataSource.Factory) {
        val app = application as LiltApplication
        if (!app.network.isOnline || !exo.playWhenReady) { cancelPrefetch(); return }
        val nextIndex = exo.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex == exo.currentMediaItemIndex) { cancelPrefetch(); return }
        val item = exo.getMediaItemAt(nextIndex)
        val uri = item.localConfiguration?.uri
        if (uri?.scheme != "lilt") { cancelPrefetch(); return }
        val quality = uri.getQueryParameter("quality") ?: "standard"
        val key = AudioCacheKey.of(item.mediaId, quality)
        if (prefetchIdentity == key) return
        cancelPrefetch()
        prefetchIdentity = key
        val song = TrackMediaItem.song(item) ?: songFromUri(uri)
        prefetchJob = scope.launch(Dispatchers.IO) {
            var writer: CacheWriter? = null
            try {
                try {
                    if (app.lyricCache.get(song.mid) == null) app.lyricCache.put(song.mid, app.repository.lyrics(song))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                }
                ensureActive()
                writer = CacheWriter(cached.createDataSource(), DataSpec.Builder().setUri(uri).setKey(key).build(), null, null)
                prefetchWriter = writer
                ensureActive()
                writer.cache()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Prefetch never turns an otherwise playable queue into an error state.
            } finally {
                if (prefetchWriter === writer) prefetchWriter = null
                app.audioCache.refresh()
            }
        }
    }

    private fun cancelPrefetch() {
        prefetchWriter?.cancel()
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchIdentity = null
    }

    private fun songFromUri(uri: Uri) = Song(uri.lastPathSegment.orEmpty(),
        uri.getQueryParameter("id")?.toLongOrNull() ?: 0, uri.getQueryParameter("title").orEmpty(),
        uri.getQueryParameter("artist").orEmpty(), mediaMid = uri.getQueryParameter("mediaMid").orEmpty(),
        albumMid = uri.getQueryParameter("albumMid").orEmpty())

    /**
     * Applies the volume shaping of the settings: loudness normalization from QQ's measured
     * gain, and short fades on play, pause and skipping. Neither ever blocks playback. A fading
     * pause is reported at once, so controls follow the tap rather than the end of the fade.
     */
    private inner class ShapedPlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {
        private var loudness = 1f
        private var fade = 1f
        private var animator: ValueAnimator? = null
        private var lookup: Job? = null
        private var fadingOut = false
        private val listeners = mutableListOf<Player.Listener>()

        private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "normalize") normalize(exo.currentMediaItem)
        }

        init {
            exo.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    normalize(mediaItem)
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && fades() && playWhenReady) ramp(0f, 1f, 420)
                }
            })
            normalize(exo.currentMediaItem)
            preferences.registerOnSharedPreferenceChangeListener(settingsListener)
        }

        override fun addListener(listener: Player.Listener) { super.addListener(listener); listeners += listener }
        override fun removeListener(listener: Player.Listener) { super.removeListener(listener); listeners -= listener }
        override fun getPlayWhenReady() = !fadingOut && super.getPlayWhenReady()
        override fun isPlaying() = !fadingOut && super.isPlaying()

        override fun play() {
            if (!fades() || exo.isPlaying) { cancelRamp(); fade = 1f; apply(); super.play(); return }
            fade = 0f; apply()
            super.play()
            ramp(0f, 1f, 360)
        }

        override fun pause() {
            if (fadingOut) return
            if (!fades() || !exo.isPlaying) { cancelRamp(); super.pause(); fade = 1f; apply(); return }
            ramp(fade, 0f, 280) { super.pause(); fade = 1f; apply(); setFadingOut(false) }
            setFadingOut(true)
        }

        override fun release() {
            cancelRamp(); lookup?.cancel()
            preferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
            super.release()
        }

        private fun fades() = preferences.getBoolean("fade", false)

        /** gain = 10^((dB + 4)/20), capped by the peak; Android can attenuate but not amplify. */
        private fun normalize(item: MediaItem?) {
            lookup?.cancel()
            loudness = 1f; apply()
            val mid = item?.mediaId?.takeUnless { it.startsWith("content:") || it.contains(':') } ?: return
            if (!preferences.getBoolean("normalize", false)) return
            lookup = scope.launch {
                val (gain, peak) = runCatching { (application as LiltApplication).repository.loudness(mid) }.getOrNull() ?: return@launch
                if (exo.currentMediaItem?.mediaId != mid || gain == 0.0 && peak == 0.0) return@launch
                var value = 10.0.pow((gain + 4) / 20)
                if (peak > 0) value = min(value, 1 / peak)
                loudness = value.coerceIn(0.05, 1.0).toFloat()
                apply()
            }
        }

        private fun ramp(from: Float, to: Float, duration: Long, then: (() -> Unit)? = null) {
            cancelRamp()
            animator = ValueAnimator.ofFloat(from, to).apply {
                this.duration = duration
                addUpdateListener { fade = it.animatedValue as Float; apply() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) then?.invoke() }
                })
                start()
            }
        }

        private fun cancelRamp() { animator?.cancel(); animator = null; setFadingOut(false) }

        /** The wrapped player only learns of the pause when the fade ends, so tell the listeners here. */
        private fun setFadingOut(value: Boolean) {
            val wasReady = playWhenReady
            val wasPlaying = isPlaying
            fadingOut = value
            val ready = playWhenReady
            val playing = isPlaying
            if (ready == wasReady && playing == wasPlaying) return
            val events = FlagSet.Builder().add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                .addIf(Player.EVENT_IS_PLAYING_CHANGED, playing != wasPlaying).build()
            listeners.toList().forEach {
                if (ready != wasReady) it.onPlayWhenReadyChanged(ready, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                if (playing != wasPlaying) it.onIsPlayingChanged(playing)
                it.onEvents(this, Player.Events(events))
            }
        }
        private fun apply() { exo.volume = loudness * fade }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (
            player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        cancelPrefetch()
        archive?.close()
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
