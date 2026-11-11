package com.lilt.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistSongWritesTest {
    private val song = Song("song-mid", 42, "歌曲", "歌手")
    private val library = JSONObject("""{"v_playlist":[
        {"tid":900,"dirId":12,"dirType":0,"uin":"10001"},
        {"tid":901,"dirId":201,"uin":"10001"},
        {"tid":902,"dirId":202,"uin":"10001"},
        {"tid":903,"dirId":13,"dirType":0,"uin":20002},
        {"tid":904,"dirId":14,"dirType":1,"uin":"10001"}
    ]}""")

    @Test fun resolvesAccountDirectoriesAndOnlyAllowsLikedForSongEdits() {
        assertEquals(12, PlaylistSongWrites.ownedDirectory(library, "900", "10001", false, "refused"))
        assertEquals(201, PlaylistSongWrites.ownedDirectory(library, "901", "10001", true, "refused"))
        rejects { PlaylistSongWrites.ownedDirectory(library, "901", "10001", false, "refused") }
        // System daily playlists, a different owner, collected lists and unknown public IDs cannot be edited.
        listOf("902", "903", "904", "999").forEach { id ->
            rejects { PlaylistSongWrites.ownedDirectory(library, id, "10001", true, "refused") }
        }
    }

    @Test fun writesNumericSongIdsToTheAccountDirectory() {
        val songs = PlaylistSongWrites.songs(listOf(song, song.copy(title = "重复歌曲")))
        assertEquals(1, songs.size)
        val params = PlaylistSongWrites.parameters(12, songs)
        assertEquals(12, params.getInt("dirId"))
        val entry = params.getJSONArray("v_songInfo").getJSONObject(0)
        assertEquals(42L, entry.getLong("songId"))
        assertEquals(0, entry.getInt("songType"))
    }

    @Test fun rejectsInvalidSongsAndRequestsLargerThanOneHundred() {
        rejects { PlaylistSongWrites.songs(emptyList()) }
        rejects { PlaylistSongWrites.songs(listOf(song.copy(id = 0))) }
        rejects { PlaylistSongWrites.songs(listOf(song.copy(mid = "content://media/42"))) }
        rejects { PlaylistSongWrites.songs(listOf(song.copy(mid = "file:///music/song.mp3"))) }
        val hundred = (1..100).map { song.copy(mid = "song-$it", id = it.toLong()) }
        assertEquals(100, PlaylistSongWrites.songs(hundred).size)
        rejects { PlaylistSongWrites.songs(hundred + song) }
    }

    @Test fun requiresAnAcknowledgementForTheSamePublicPlaylistAndAccountDirectory() {
        PlaylistSongWrites.confirm(JSONObject("""{"retCode":0,"result":{"tid":900,"dirId":12}}"""), "900", 12, "添加")
        PlaylistSongWrites.confirm(JSONObject("""{"retCode":0,"result":{"tid":901,"dirId":201}}"""), "901", 201, "移除")
        listOf(
            """{"result":{"tid":900,"dirId":12}}""",
            """{"retCode":1,"result":{"tid":900,"dirId":12}}""",
            """{"retCode":0}""",
            """{"retCode":0,"result":{"tid":901,"dirId":12}}""",
            """{"retCode":0,"result":{"tid":900,"dirId":201}}""",
            """{"retCode":0,"result":{}}""",
        ).forEach { response ->
            val error = rejects { PlaylistSongWrites.confirm(JSONObject(response), "900", 12, "添加") }
            assertTrue(error.message.orEmpty().contains("刷新歌单"))
        }
    }

    @Test fun likedReadsRequireEveryRequestedMidAndPreserveFalse() {
        assertEquals(mapOf("a" to true, "b" to false),
            PlaylistSongWrites.likedStatus(JSONObject("""{"m_fan":{"a":true,"b":false}}"""), listOf("a", "b")))
        rejects { PlaylistSongWrites.likedStatus(JSONObject("""{"m_fan":{"a":true}}"""), listOf("a", "b")) }
        rejects { PlaylistSongWrites.likedStatus(JSONObject("""{"m_fan":{"a":1}}"""), listOf("a")) }
        rejects { PlaylistSongWrites.likedStatus(JSONObject("{}"), listOf("a")) }
    }

    private fun rejects(block: () -> Any?): MusicApiException {
        try {
            block()
            fail("Expected MusicApiException")
        } catch (error: MusicApiException) {
            return error
        }
        throw AssertionError("unreachable")
    }
}
