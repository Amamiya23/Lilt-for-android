package com.lilt.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicParsersTest {
    // A search entry must retain the separate media MID required by QQ's playback filename.
    @Test fun songRetainsPlaybackIdentityAndConvertsSeconds() {
        val raw = JSONObject(
            """{"musicData":{"songid":"123","songmid":"SONGMID","songname":"夜に駆ける",
              "interval":246,"singer":[{"id":4,"name":"YOASOBI"},{"id":5,"name":"Guest"}],
              "album":{"mid":"ALBUMMID","name":"THE BOOK"},"file":{"media_mid":"MEDIAMID"}}}""",
        )
        val song = requireNotNull(MusicParsers.song(raw))
        assertEquals(123L, song.id)
        assertEquals("SONGMID", song.mid)
        assertEquals("MEDIAMID", song.mediaMid)
        assertEquals(246_000L, song.durationMs)
        assertEquals("YOASOBI / Guest", song.artist)
        assertTrue(song.cover.contains("T002R500x500M000ALBUMMID.jpg"))
    }

    // Sparse suggestions must not become a playable full song without a valid numeric ID.
    @Test fun rejectsSongsWithoutACompletePublicIdentity() {
        assertNull(MusicParsers.song(JSONObject("""{"mid":"MID","title":"Song"}""")))
        assertNull(MusicParsers.song(JSONObject("""{"id":1,"title":"Song"}""")))
        val sparse = MusicParsers.song(JSONObject("""{"id":1,"mid":"MID","title":"Song"}"""))
        assertEquals("", requireNotNull(sparse).mediaMid)
    }

    // Repeated time tags, fractional seconds and the service's offset must survive lyric parsing.
    @Test fun parsesLrcTimesAndMatchesTranslationByTime() {
        val lyrics = MusicParsers.lyrics(
            "[offset:100]\n[00:01.25][00:03.500]同じ歌詞\n[00:05]最後",
            "[00:01.30]同一行\n[00:03.45]再次出现\n[00:09]不应匹配",
        )
        assertEquals(listOf(1350L, 3600L, 5100L), lyrics.lines.map { it.timeMs })
        assertEquals(listOf("同じ歌詞", "同じ歌詞", "最後"), lyrics.lines.map { it.text })
        assertEquals(listOf("同一行", "再次出现", ""), lyrics.lines.map { it.translation })
    }

    // QQ's instrumental placeholder is distinct from song lyrics.
    @Test fun instrumentalAndMetadataDoNotBecomeSungLines() {
        val lyrics = MusicParsers.lyrics("[ti:Title]\n[ar:Artist]\n[00:00.00]此歌曲为没有填词的纯音乐，请您欣赏", "")
        assertTrue(lyrics.lines.isEmpty())
    }

    // Login JSONP is parsed as quoted data, including escapes, without running JavaScript.
    @Test fun parsesQuotedLoginCallback() {
        val values = MusicParsers.ptui(
            """ptuiCB('67','0','','0','请在手机\x20QQ 确认','小\u660E');""",
        )
        assertEquals("67", values[0])
        assertEquals("请在手机 QQ 确认", values[4])
        assertEquals("小明", values[5])
    }

    // Extra JavaScript after a callback must fail rather than becoming a successful login.
    @Test fun refusesExecutableOrMalformedLoginResponses() {
        listOf(
            "alert('0')",
            "ptuiCB('0','0','','0','ok','name');alert('x');",
            "ptuiCB('0','0','','0','ok','name',)",
            "ptuiCB('0','0','','0','ok')",
        ).forEach { response ->
            val result = runCatching { MusicParsers.ptui(response) }
            assertTrue(response, result.exceptionOrNull() is MusicApiException)
        }
    }

    // A filtered empty search is a service failure, not a genuine zero-result query.
    @Test fun filteredEmptySearchRaisesRetryableUiFailure() {
        val data = JSONObject("""{"body":{"song":{"list":[]}},"meta":{"sum":0,"is_filter":-9}}""")
        assertTrue(runCatching { MusicParsers.search(data, 0) }.exceptionOrNull() is MusicApiException)
        data.getJSONObject("meta").put("is_filter", 0)
        assertEquals(0, MusicParsers.search(data, 0).total)
    }

    // Search decoding reads nested desktop lists while keeping album and singer identities.
    @Test fun desktopSearchDecodesItsActualListShape() {
        val data = JSONObject(
            """{"body":{"song":{"list":[{"id":123,"mid":"MID","title":"Song","interval":1,
                "album":{"mid":"ALBUM","name":"Album"},"singer":[{"name":"Artist"}]}]}},
                "meta":{"sum":20,"is_filter":0}}""",
        )
        val result = MusicParsers.search(data, 0)
        assertEquals(20, result.total)
        assertEquals("MID", result.songs.single().mid)
        assertEquals("Artist", result.songs.single().artist)
        assertFalse(result.songs.single().cover.isEmpty())
    }

    // The CGI still wraps some valid responses in JSONP and uses GB18030 for Chinese text.
    @Test fun cgiJsonpAndLegacyEncodingAreReadable() {
        val gb = """callback({"code":0,"title":"中文"});""".toByteArray(charset("GB18030"))
        assertEquals("中文", MusicParsers.jsonResponse(gb).getString("title"))
        assertEquals(0, MusicParsers.jsonResponse("""{"code":0}""".toByteArray()).getInt("code"))
    }

    // A library list may represent its identifiers and counts as numeric strings.
    @Test fun libraryPlaylistAcceptsNumericStrings() {
        val item = MusicParsers.libraryPlaylist(
            JSONObject("""{"tid":"123","dirName":"收藏","songNum":"14","play_cnt":"120","picUrl":"http://example.test/art.jpg"}"""),
        )
        assertEquals("123", requireNotNull(item).id)
        assertEquals(14, item.trackCount)
        assertEquals(120L, item.playCount)
        assertEquals("https://example.test/art.jpg", item.cover)
        assertEquals(0, MusicParsers.searchEntries(JSONArray(), 2, 0).albums.size)
    }
}
