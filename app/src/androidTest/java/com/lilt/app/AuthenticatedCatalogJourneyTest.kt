package com.lilt.app

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lilt.app.data.QqMusicRepository
import com.lilt.app.playback.PlaybackConnection
import com.lilt.app.playback.ResolvedTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly opt in after scanning the app's QR. Never changes the user's cloud library. */
@RunWith(AndroidJUnit4::class)
class AuthenticatedCatalogJourneyTest {
    @Test fun signedInLibraryDailyLyricsAndStreaming() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("authenticated") == "true")
        val context = ApplicationProvider.getApplicationContext<LiltApplication>()
        val repository = context.repository
        assertNotNull("Sign in through the app before running this journey", repository.profile.value)
        repository.refreshProfile()
        assertTrue("The account profile must include a display name", repository.profile.value?.name?.isNotBlank() == true)
        // A new repository must recover the same account from the encrypted session store.
        assertEquals(repository.profile.value, QqMusicRepository(context).profile.value)
        val playlists = repository.userPlaylists()
        assertTrue("Account playlists must be available", playlists.isNotEmpty())
        val favorites = playlists.firstOrNull { it.title.contains("喜欢") } ?: playlists.first()
        val detail = repository.playlist(favorites.id)
        assertTrue("The selected account playlist must contain songs", detail.songs.isNotEmpty())
        val liked = repository.isLiked(detail.songs.first().mid)
        if (favorites.title.contains("喜欢")) assertTrue("Favorite state must agree with the library", liked)
        val daily = repository.dailySongs()
        assertTrue("The personalized daily mix must return songs", daily.isNotEmpty())
        val selected = (daily.take(5) + detail.songs.take(5)).firstNotNullOfOrNull { song ->
            runCatching { repository.songUrl(song) }.getOrNull()?.let { song to it }
        }
        assertNotNull("An account-authorized track must be playable", selected)
        val (song, standardUrl) = requireNotNull(selected)
        val lyrics = repository.lyrics(song)
        assertTrue(lyrics.lines.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
        Log.i(TAG, "library=${playlists.size}; selectedPlaylistSongs=${detail.songs.size}; daily=${daily.size}; lyricLines=${lyrics.lines.size}; favoriteRead=$liked")
        val activity = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        val connection = PlaybackConnection(context)
        try {
            connection.connect()
            for (quality in listOf("standard", "hq", "lossless")) {
                val url = if (quality == "standard") standardUrl else repository.songUrl(song, quality)
                // Only log the format prefix, never a signed URL or account credential.
                val format = Uri.parse(url).lastPathSegment.orEmpty().take(4)
                val playbackId = "${song.mid}:$quality"
                connection.setQueue(listOf(ResolvedTrack(playbackId, song.title, song.artist, song.album, song.cover, url)))
                val result = withTimeout(30_000) {
                    connection.state.first {
                        it.current?.id == playbackId && (it.error != null || it.isPlaying && it.positionMs > 1_500)
                    }
                }
                assertNull("Account stream $quality must load on Android: ${result.error}", result.error)
                assertTrue(result.isPlaying)
                Log.i(TAG, "requested=$quality; resolved=$format; streamed=true")
            }
        } finally {
            connection.remove(0)
            withTimeout(5_000) { connection.state.first { it.tracks.isEmpty() } }
            connection.close()
            activity.close()
        }
    }

    private companion object { const val TAG = "LiltAccountJourney" }
}
