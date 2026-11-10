package com.lilt.app.playback

import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lilt.app.LiltApplication
import com.lilt.app.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
class LiltPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences by lazy { getSharedPreferences("lilt_settings", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
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
        val resolvingFactory = ResolvingDataSource.Factory(plain) { spec ->
            val uri = spec.uri
            val song = Song(
                mid = uri.lastPathSegment.orEmpty(),
                id = uri.getQueryParameter("id")?.toLongOrNull() ?: 0,
                title = uri.getQueryParameter("title").orEmpty(),
                artist = uri.getQueryParameter("artist").orEmpty(),
                mediaMid = uri.getQueryParameter("mediaMid").orEmpty(),
                albumMid = uri.getQueryParameter("albumMid").orEmpty(),
            )
            // The quality is part of the item's URI, so it is also part of its cache key.
            val quality = uri.getQueryParameter("quality") ?: preferences.getString("quality", "standard") ?: "standard"
            // Media3 calls the resolver on its loader thread, never the main thread.
            val url = runBlocking { app.repository.songUrl(song, quality) }
            spec.withUri(Uri.parse(url))
        }
        // The cache sees the stable lilt:// address; only a cache miss resolves a signed URL.
        val cached = CacheDataSource.Factory().setCache(app.audioCache.cache).setUpstreamDataSourceFactory(resolvingFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(RoutingDataSource.Factory(cached, plain)),
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
        val launchIntent = Intent().setClassName(packageName, "com.lilt.app.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val activity = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(activity)
            .build()
    }

    /**
     * Applies the volume shaping of the settings: loudness normalization from QQ's measured
     * gain, and short fades on play, pause and skipping. Neither ever blocks playback.
     */
    private inner class ShapedPlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {
        private var loudness = 1f
        private var fade = 1f
        private var animator: ValueAnimator? = null
        private var lookup: Job? = null

        private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "normalize") normalize(exo.currentMediaItem)
        }

        init {
            exo.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    normalize(mediaItem)
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && fades() && exo.playWhenReady) ramp(0f, 1f, 420)
                }
            })
            normalize(exo.currentMediaItem)
            preferences.registerOnSharedPreferenceChangeListener(settingsListener)
        }

        override fun play() {
            if (!fades() || exo.isPlaying) { cancelRamp(); fade = 1f; apply(); super.play(); return }
            fade = 0f; apply()
            super.play()
            ramp(0f, 1f, 360)
        }

        override fun pause() {
            if (!fades() || !exo.isPlaying) { cancelRamp(); super.pause(); fade = 1f; apply(); return }
            ramp(fade, 0f, 280) { super.pause(); fade = 1f; apply() }
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

        private fun cancelRamp() { animator?.cancel(); animator = null }
        private fun apply() { exo.volume = loudness * fade }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
