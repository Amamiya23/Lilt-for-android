package com.lilt.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogParsersTest {
    // Only real calendar dates or positive unix seconds (China time) become an expiry; placeholders stay unknown.
    @Test fun membershipDatesAreStrict() {
        assertEquals("2030-02-03", MusicParsers.membershipDate("2030-02-03"))
        assertEquals("2030-02-03", MusicParsers.membershipDate("2030-02-03 23:59:59"))
        assertEquals("", MusicParsers.membershipDate("2030-02-30"))
        assertEquals("", MusicParsers.membershipDate("0000-00-00"))
        assertEquals("", MusicParsers.membershipDate(0))
        assertEquals("2024-01-01", MusicParsers.membershipDate(1704038400L))
        assertEquals("", MusicParsers.membershipDate(null))
    }

    // A shelf keeps miscellany.songList order and drops IDs that are only other kinds of cards.
    @Test fun shelfSongsFollowTheServiceOrder() {
        val shelf = JSONObject("""{"id":207,"title_template":"「{String}」，这是你的幸运好歌🍀","title_content":"Mark",
            "v_niche":[{"v_card":[{"id":"30","type":200},{"id":"10","type":200},{"id":"99","type":500},
            {"id":"0","type":200,"miscellany":{"songList":"10,99,30,abc,10"}}]}]}""")
        assertEquals(listOf(10L, 30L), MusicParsers.shelfSongIds(shelf))
        assertEquals("「Mark」，这是你的幸运好歌🍀", MusicParsers.shelfTitle(shelf))
        val plain = JSONObject("""{"id":207,"title_content":"为你推荐⁢","v_niche":[{"v_card":[{"id":"5","type":200}]}]}""")
        assertEquals(listOf(5L), MusicParsers.shelfSongIds(plain))
        assertEquals("为你推荐", MusicParsers.shelfTitle(plain))
        assertEquals("为你推荐的歌曲", MusicParsers.shelfTitle(JSONObject("{}")))
    }

    @Test fun likedAlbumsAndOwnedPlaylists() {
        val album = requireNotNull(MusicParsers.libraryAlbum(JSONObject(
            """{"albumid":8220,"albummid":"000MkMni19ClKG","albumname":"叶惠美","singers":[{"name":"周杰伦"}]}""")))
        assertEquals("叶惠美", album.title)
        assertEquals("周杰伦", album.artist)
        assertTrue(album.cover.contains("000MkMni19ClKG"))
        assertNull(MusicParsers.libraryAlbum(JSONObject("""{"albumname":"x"}""")))
        val owned = requireNotNull(MusicParsers.libraryPlaylist(JSONObject("""{"tid":77,"dirId":3,"dirName":"通勤","songNum":4}""")))
        assertEquals(3, owned.dirId)
        val collected = requireNotNull(MusicParsers.libraryPlaylist(JSONObject("""{"tid":78,"dissname":"别人的歌单"}""")))
        assertEquals(0, collected.dirId)
    }

    // Each credited artist keeps the MID its page needs.
    @Test fun songsKeepTheirArtists() {
        val song = requireNotNull(MusicParsers.song(JSONObject(
            """{"id":1,"mid":"M","title":"说好不哭","singer":[{"id":4558,"mid":"A1","name":"周杰伦"},{"id":5,"mid":"A2","name":"阿信"}]}""")))
        assertEquals(listOf("A1", "A2"), song.artists.map { it.mid })
        assertEquals("周杰伦 / 阿信", song.artist)
    }

    @Test fun profileNamesTheLoginMethod() {
        assertEquals("微信登录", MusicSession("1", "k", 1).profile.loginLabel)
        assertEquals("QQ 登录", MusicSession("1", "k", 2).profile.loginLabel)
    }
}
