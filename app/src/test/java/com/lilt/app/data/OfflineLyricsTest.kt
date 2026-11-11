package com.lilt.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineLyricsTest {
    @Test fun preservesWordTimesTranslationsAndUnicode() {
        val lyrics = Lyrics(listOf(
            LyricLine(1200, "晴天 ☀️\n\"hello\"", "A sunny day", 2400,
                listOf(LyricWord("晴", 1200, 1600), LyricWord("天 ☀️\n\"hello\"", 1600, 2400))),
            LyricLine(4000, "尾声", endMs = 7000),
        ))
        assertEquals(lyrics, OfflineLyrics.decode(OfflineLyrics.encode(lyrics)))
    }

    @Test fun preservesEmptyInstrumentalLyrics() {
        assertEquals(Lyrics(), OfflineLyrics.decode(OfflineLyrics.encode(Lyrics())))
    }
}
