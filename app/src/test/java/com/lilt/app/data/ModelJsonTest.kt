package com.lilt.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelJsonTest {
    private val song = Song("mid", 922337203685477000L, "歌名 \"夜\"", "歌手 / Guest", "专辑", "file:///cover",
        123456, "media", "album", listOf(Artist("artist", 42, "歌手", "cover", 7, 3)))
    private val playlist = Playlist("123", "歌单", "cover", "作者", 1, 999999,
        listOf("歌曲" to "歌手"), 201, 98)

    @Test fun songRoundTripRetainsNavigationAndProviderIdentity() {
        assertEquals(song, ModelJson.decodeSong(JSONObject(ModelJson.encodeSong(song).toString())))
    }

    @Test fun detailRoundTripRetainsDirectoryAndAllSongs() {
        val detail = PlaylistDetail(playlist, listOf(song, song.copy(mid = "content://music/1", id = 0)))
        assertEquals(detail, ModelJson.decodeDetail(JSONObject(ModelJson.encodeDetail(detail).toString())))
    }

    @Test fun homeRoundTripRetainsShelvesChartsAndEmptyLists() {
        val home = HomeFeed(listOf(song), listOf(playlist), listOf(playlist.copy(id = "chart:1")),
            listOf(SongShelf("推荐", listOf(song)), SongShelf("空", emptyList())))
        assertEquals(home, ModelJson.decodeHome(JSONObject(ModelJson.encodeHome(home).toString())))
        assertEquals(HomeFeed(), ModelJson.decodeHome(ModelJson.encodeHome(HomeFeed())))
    }
}
