package com.lilt.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.AtomicFile
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilt.app.data.Artist
import com.lilt.app.data.LyricLine
import com.lilt.app.data.LyricWord
import com.lilt.app.data.LyricCache
import com.lilt.app.data.Lyrics
import com.lilt.app.data.Song
import com.lilt.app.playback.AudioCache
import com.lilt.app.playback.AudioCacheKey
import com.lilt.app.playback.PlaybackConnection
import com.lilt.app.playback.QueueArchiveJson
import com.lilt.app.playback.ResolvedTrack
import com.lilt.app.playback.ServicePlaybackArchive
import com.lilt.app.playback.TrackMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Deterministic offline fixtures, not an authenticated QQ Music or real-device acceptance test. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class OfflinePlaybackJourneyTest {
    @Test fun realServiceUsesCompleteAlternateQualitySkipsPartialAndStopsUnavailableQueue() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<LiltApplication>()
        assumeFalse("Run this journey with the device offline", app.network.isOnline)
        val prefix = "offline-fixture-${UUID.randomUUID()}"
        val full = fixture("$prefix-full")
        val partial = fixture("$prefix-partial")
        val missing = fixture("$prefix-missing")
        val bytes = silence()
        seed(app.audioCache, full.id, bytes, bytes.size.toLong())
        seed(app.audioCache, partial.id, bytes.copyOf(bytes.size / 2), bytes.size.toLong())
        assertTrue(full.id in app.audioCache.available.value)
        assertFalse(partial.id in app.audioCache.available.value)
        assertEquals("standard", app.audioCache.cachedQuality(full.id, "hq", false))
        val activity = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        val connection = PlaybackConnection(app)
        connection.connect()
        val original = connection.state.value
        try {
            // All items request hq; only the last item has complete standard audio.
            connection.setQueue(listOf(missing, partial, full), source = "Fixture queue")
            val playing = withTimeout(15_000) { connection.state.first {
                it.current?.id == full.id && it.isPlaying && it.durationMs >= 19_000
            } }
            assertEquals(full.song, playing.current?.song)
            assertEquals("Fixture queue", playing.source)
            assertNull(playing.error)

            connection.setQueue(listOf(missing, partial), source = "Unavailable fixture queue")
            withTimeout(10_000) { connection.state.first {
                it.tracks.map { track -> track.id } == listOf(missing.id, partial.id) && !it.isPlaying && !it.isBuffering
            } }
            delay(750)
            assertFalse("An entirely unavailable queue must stay stopped", connection.state.value.isPlaying)
            assertFalse(connection.state.value.isBuffering)
        } finally {
            if (original.tracks.isEmpty()) {
                connection.state.value.tracks.indices.reversed().forEach(connection::remove)
                withTimeout(5_000) { connection.state.first { it.tracks.isEmpty() } }
            } else {
                connection.setQueue(original.tracks, original.currentIndex, original.source)
                connection.pause()
                connection.seekTo(original.positionMs)
                withTimeout(5_000) { connection.state.first {
                    it.current?.id == original.current?.id && !it.isPlaying
                } }
            }
            connection.close()
            activity.close()
            listOf(full.id, partial.id).forEach { app.audioCache.cache.removeResource(AudioCacheKey.of(it, "standard")) }
            app.audioCache.refresh()
        }
    }

    @Test fun independentPlayerArchiveRestoresFullSongPositionSourceAndModesPaused() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<LiltApplication>()
        assumeFalse("Run this journey with the device offline", app.network.isOnline)
        val root = File(app.cacheDir, "offline-archive-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = FixtureContext(app, root)
        val audio = File(root, "song.wav").apply { writeBytes(silence()) }
        val song = fixture("archive-fixture").song!!
        val track = ResolvedTrack(song.mid, song.title, song.artist, song.album, song.cover, audio.toURI().toString(), song)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var first: ExoPlayer? = null
        var second: ExoPlayer? = null
        var firstArchive: ServicePlaybackArchive? = null
        var secondArchive: ServicePlaybackArchive? = null
        try {
            withContext(Dispatchers.Main) {
                first = ExoPlayer.Builder(app).build()
                firstArchive = ServicePlaybackArchive(isolated, first!!, scope).also { it.restoreIfEmpty() }
                first!!.setMediaItems(listOf(TrackMediaItem.toMediaItem(track, "来自歌单 · Fixture")), 0, 7_000)
                first!!.shuffleModeEnabled = true
                first!!.repeatMode = Player.REPEAT_MODE_ALL
                first!!.prepare()
            }
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { first!!.playbackState == Player.STATE_READY }) delay(50)
            }
            withContext(Dispatchers.Main) { firstArchive!!.close(); first!!.release(); first = null }
            val saved = File(isolated.filesDir, "playback-queue.json")
            withTimeout(5_000) {
                while (runCatching { QueueArchiveJson.decode(String(AtomicFile(saved).readFully(), Charsets.UTF_8)).positionMs }.getOrNull() != 7_000L) delay(50)
            }
            withContext(Dispatchers.Main) {
                second = ExoPlayer.Builder(app).build()
                secondArchive = ServicePlaybackArchive(isolated, second!!, scope).also { it.restoreIfEmpty() }
                assertEquals(0, second!!.currentMediaItemIndex)
                assertEquals(7_000L, second!!.currentPosition)
                assertFalse(second!!.playWhenReady)
                assertFalse(second!!.isPlaying)
                assertEquals(Player.STATE_IDLE, second!!.playbackState)
                assertTrue(second!!.shuffleModeEnabled)
                assertEquals(Player.REPEAT_MODE_ALL, second!!.repeatMode)
                assertEquals(song, TrackMediaItem.song(second!!.currentMediaItem!!))
                assertEquals("来自歌单 · Fixture", TrackMediaItem.source(second!!.currentMediaItem!!))
            }
        } finally {
            withContext(Dispatchers.Main) {
                if (first != null) { firstArchive?.close(); first?.release() }
                secondArchive?.close(); second?.release()
            }
            scope.cancel()
            // The archive's final IO write drains after close; leave its private directory for OS cache cleanup.
        }
    }

    @Test fun isolatedClearRemovesCompleteAvailabilityAndWordLyricsTogether() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<LiltApplication>()
        val root = File(app.cacheDir, "offline-clear-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = FixtureContext(app, root)
        val audio = AudioCache(isolated)
        val lyrics = LyricCache(isolated)
        val mid = "clear-fixture"
        try {
            val bytes = silence()
            seed(audio, mid, bytes, bytes.size.toLong())
            val expected = Lyrics(listOf(LyricLine(1_000, "晴天", "Sunny", 2_000,
                listOf(LyricWord("晴", 1_000, 1_500), LyricWord("天", 1_500, 2_000)))))
            lyrics.put(mid, expected)
            assertEquals(expected, lyrics.get(mid))
            assertTrue(mid in audio.available.value)
            audio.clear()
            assertTrue(audio.available.value.isEmpty())
            assertEquals(0L, audio.usedBytes)
            assertNull(lyrics.get(mid))
        } finally { audio.cache.release() }
    }

    private fun fixture(mid: String): ResolvedTrack {
        val song = Song(mid, 123, "Offline fixture", "Fixture artist", "Fixture album", durationMs = 20_000,
            mediaMid = "media-$mid", albumMid = "album-fixture", artists = listOf(Artist("artist-fixture", 77, "Fixture artist")))
        return ResolvedTrack(song.mid, song.title, song.artist, song.album, song.cover, "lilt://song/$mid?quality=hq", song)
    }

    private fun seed(audio: AudioCache, mid: String, bytes: ByteArray, contentLength: Long) {
        val cache = audio.cache
        val key = AudioCacheKey.of(mid, "standard")
        val span = cache.startReadWrite(key, 0, bytes.size.toLong())
        try {
            val file = cache.startFile(key, 0, bytes.size.toLong())
            file.writeBytes(bytes)
            cache.commitFile(file, bytes.size.toLong())
            val mutations = ContentMetadataMutations()
            ContentMetadataMutations.setContentLength(mutations, contentLength)
            cache.applyContentMetadataMutations(key, mutations)
        } finally { cache.releaseHoleSpan(span) }
        audio.refresh()
    }

    private fun silence(): ByteArray {
        val samples = 8_000 * 20
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array()
    }

    private class FixtureContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    }
}
