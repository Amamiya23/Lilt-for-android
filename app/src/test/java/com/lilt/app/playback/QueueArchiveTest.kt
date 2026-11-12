package com.lilt.app.playback

import com.lilt.app.data.Artist
import com.lilt.app.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueArchiveTest {
    private val song = Song("mid-1", 123, "晴天 \"live\"", "周杰伦 / 合唱者", "叶惠美", "https://cover", 269000,
        "media-mid", "album-mid", listOf(Artist("artist-mid", 77, "周杰伦", "https://artist", 100, 12)))
    private val track = ResolvedTrack(song.mid, song.title, song.artist, song.album, song.cover,
        "lilt://song/mid-1?quality=hq", song)

    @Test fun roundTripRetainsQueueCatalogIdentityAndPlaybackSettings() {
        val archive = QueueArchive(listOf(track, track.copy(id = "content://music/9", url = "content://music/9")),
            1, 34567, shuffle = true, repeatMode = 2, source = "来自歌单 · 夜晚\n☀️")
        assertEquals(archive, QueueArchiveJson.decode(QueueArchiveJson.encode(archive)))
        assertEquals(song.artists, QueueArchiveJson.decode(QueueArchiveJson.encode(archive)).tracks[0].song?.artists)
    }

    @Test fun emptyQueueRoundTripsAndNeverStoresPlayingState() {
        val text = QueueArchiveJson.encode(QueueArchive(emptyList()))
        assertEquals(QueueArchive(emptyList()), QueueArchiveJson.decode(text))
        assertFalse(text.contains("isPlaying"))
        assertFalse(text.contains("playWhenReady"))
    }

    @Test fun stalePositionIndexAndRepeatModeAreNormalized() {
        val decoded = QueueArchiveJson.decode(QueueArchiveJson.encode(QueueArchive(listOf(track), 99, -1, repeatMode = 99)))
        assertEquals(0, decoded.index)
        assertEquals(0L, decoded.positionMs)
        assertEquals(0, decoded.repeatMode)
    }

    @Test(expected = IllegalArgumentException::class) fun signedNetworkAddressesAreNeverArchived() {
        QueueArchiveJson.encode(QueueArchive(listOf(track.copy(url = "https://stream/song?vkey=secret"))))
    }

    @Test(expected = IllegalArgumentException::class) fun unknownVersionsAreRejected() {
        QueueArchiveJson.decode("{\"version\":2,\"tracks\":[]}")
    }

    @Test fun archiveDoesNotLoseDuplicateQueueEntries() {
        val archive = QueueArchive(listOf(track, track, track), index = 2, positionMs = 50)
        assertEquals(archive, QueueArchiveJson.decode(QueueArchiveJson.encode(archive)))
        assertTrue(QueueArchiveJson.encode(archive).contains("album-mid"))
    }

    @Test fun acceptsJavaFileUriWithSingleSlash() {
        val archive = QueueArchive(listOf(track.copy(url = "file:/data/user/0/com.lilt.app/cache/song.wav")))
        assertEquals(archive, QueueArchiveJson.decode(QueueArchiveJson.encode(archive)))
    }
}
