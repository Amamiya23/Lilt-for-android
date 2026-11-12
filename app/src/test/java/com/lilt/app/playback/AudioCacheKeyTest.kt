package com.lilt.app.playback

import org.junit.Assert.*
import org.junit.Test

class AudioCacheKeyTest {
    @Test fun keyIsVersionedAndOnlyContainsSongAndQuality() {
        assertEquals(AudioCacheKey.of("song-1", "hq"), AudioCacheKey.of("song-1", "hq"))
        assertNotEquals(AudioCacheKey.of("song-1", "hq"), AudioCacheKey.of("song-1", "standard"))
        assertNotEquals(AudioCacheKey.of("song-1", "hq"), AudioCacheKey.of("song-2", "hq"))
        assertEquals("歌|曲/one" to "lossless", AudioCacheKey.parse(AudioCacheKey.of("歌|曲/one", "lossless")))
        assertNull(AudioCacheKey.parse("lilt://song/one?title=changed&quality=hq"))
        assertNull(AudioCacheKey.parse("audio-v2|broken"))
    }

    @Test fun offlinePrefersRequestedThenBestCompleteAlternative() {
        assertEquals("hq", selectCachedQuality("hq", setOf("hq", "lossless"), false))
        assertEquals("lossless", selectCachedQuality("hq", setOf("standard", "lossless"), false))
        assertEquals("standard", selectCachedQuality("lossless", setOf("standard"), false))
        assertNull(selectCachedQuality("hq", emptySet(), false))
        assertEquals("hq", selectCachedQuality("hq", setOf("standard"), true))
        assertEquals("hq", selectCachedQuality("hq", emptySet(), true))
    }

    @Test fun onlyKnownLengthWithNoHolesIsComplete() {
        assertFalse(fullyCached(-1, listOf(0L to 100L)))
        assertFalse(fullyCached(0, emptyList()))
        assertFalse(fullyCached(100, listOf(0L to 50L)))
        assertFalse(fullyCached(100, listOf(0L to 40L, 50L to 50L)))
        assertFalse(fullyCached(100, listOf(1L to 100L)))
        assertTrue(fullyCached(100, listOf(50L to 50L, 0L to 50L)))
        assertTrue(fullyCached(100, listOf(0L to 75L, 50L to 50L)))
        // An eviction immediately makes a formerly complete file unavailable.
        assertFalse(fullyCached(100, listOf(50L to 50L)))
    }

    @Test fun skippingVisitsEachOtherItemAtMostOnce() {
        assertNull(nextPlayableIndex(listOf(0, 1, 2), 1) { false })
        assertEquals(2, nextPlayableIndex(listOf(0, 1, 2), 1) { it == 2 })
        assertEquals(0, nextPlayableIndex(listOf(0, 1, 2), 2) { it == 0 })
        assertNull(nextPlayableIndex(listOf(0), 0) { true })
        assertEquals(1, nextPlayableIndex(listOf(2, 0, 1), 0) { it == 1 })
    }
}
