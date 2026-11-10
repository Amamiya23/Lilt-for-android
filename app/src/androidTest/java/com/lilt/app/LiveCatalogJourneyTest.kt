package com.lilt.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lilt.app.playback.PlaybackConnection
import com.lilt.app.playback.ResolvedTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveCatalogJourneyTest {
    // Opt-in live verification covers QQ's actual URL response through Android's audio loader.
    @Test fun catalogCanStreamAnAuthorizedTrack() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val context = ApplicationProvider.getApplicationContext<LiltApplication>()
        val activity = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        val connection = PlaybackConnection(context)
        try {
            val feed = context.repository.home()
            assertTrue("The public feed must return songs", feed.songs.isNotEmpty())
            val selected = feed.songs.take(8).firstNotNullOfOrNull { song ->
                runCatching { context.repository.songUrl(song) }.getOrNull()?.let { song to it }
            }
            assertNotNull("No authorized song in the sampled feed", selected)
            val (song, url) = requireNotNull(selected)
            val lyrics = context.repository.lyrics(song)
            assertTrue(lyrics.lines.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
            connection.connect()
            connection.setQueue(listOf(ResolvedTrack(song.mid, song.title, song.artist, song.album, song.cover, url)))
            val result = withTimeout(30000) {
                connection.state.first { it.current?.id == song.mid && (it.error != null || it.isPlaying && it.positionMs > 1000) }
            }
            assertNull("The live stream must load on Android: ${result.error}", result.error)
            assertTrue(result.isPlaying)
        } finally {
            connection.remove(0)
            withTimeout(5_000) { connection.state.first { it.tracks.isEmpty() } }
            connection.close()
            activity.close()
        }
    }
}
