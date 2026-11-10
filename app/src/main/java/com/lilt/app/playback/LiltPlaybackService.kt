package com.lilt.app.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.lilt.app.LiltApplication
import com.lilt.app.data.Song
import kotlinx.coroutines.runBlocking
import android.net.Uri

/**
 * Owns the player independently from the Activity. MediaSessionService supplies the
 * foreground notification and routes lock-screen / headset controls to this player.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LiltPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val httpFactory = OkHttpDataSource.Factory(http)
            .setUserAgent("Lilt-Android/0.1")
            .setDefaultRequestProperties(
                mapOf("Referer" to "https://y.qq.com/", "Origin" to "https://y.qq.com"),
            )
        val resolvingFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, httpFactory),
        ) { spec ->
            if (spec.uri.scheme != "lilt") spec else {
                val uri = spec.uri
                val song = Song(
                    mid = uri.lastPathSegment.orEmpty(),
                    id = uri.getQueryParameter("id")?.toLongOrNull() ?: 0,
                    title = uri.getQueryParameter("title").orEmpty(),
                    artist = uri.getQueryParameter("artist").orEmpty(),
                    mediaMid = uri.getQueryParameter("mediaMid").orEmpty(),
                    albumMid = uri.getQueryParameter("albumMid").orEmpty(),
                )
                // Media3 calls the resolver on its loader thread, never the main thread.
                val quality = getSharedPreferences("lilt_settings", MODE_PRIVATE).getString("quality", "standard") ?: "standard"
                val url = runBlocking { (application as LiltApplication).repository.songUrl(song, quality) }
                spec.withUri(Uri.parse(url))
            }
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    resolvingFactory,
                ),
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
        super.onDestroy()
    }
}
