package com.lilt.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamQualityTest {
    @Test fun strictPlaybackNeverFallsBackEvenWithoutAnAccount() {
        for (signedIn in listOf(true, false)) {
            assertEquals(listOf("F000" to "flac"), StreamQuality.candidates("lossless", signedIn, true))
            assertEquals(listOf("F000" to "flac"), StreamQuality.candidates("hires", signedIn, true))
            assertEquals(listOf("M800" to "mp3"), StreamQuality.candidates("hq", signedIn, true))
            assertEquals(listOf("M500" to "mp3"), StreamQuality.candidates("standard", signedIn, true))
        }
    }

    @Test fun downloadsRetainTheirPreviousFallbackOrder() {
        assertEquals(listOf("F000" to "flac", "M800" to "mp3", "M500" to "mp3"),
            StreamQuality.candidates("lossless", true, false))
        assertEquals(listOf("M500" to "mp3"), StreamQuality.candidates("lossless", false, false))
        assertEquals(listOf("M800" to "mp3", "M500" to "mp3"), StreamQuality.candidates("hq", true, false))
    }
}
