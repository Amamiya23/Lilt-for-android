package com.lilt.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MetadataCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun detail(id: String) = PlaylistDetail(Playlist(id, "歌单 $id"), listOf(Song("song-$id", 1, "歌曲", "歌手")))

    @Test fun accountSnapshotsRemainIsolatedAcrossReopenAndLogout() {
        val root = temporary.newFolder()
        val first = MetadataCache(root)
        val home = HomeFeed(songs = detail("a").songs)
        first.putHome("qq:1", home)
        first.putLibrary("qq:1", listOf(Playlist("a", "A")))
        first.putDetail("qq:1", "a", detail("a"))
        first.putLiked("qq:1", mapOf("a" to true, "b" to false))
        first.putHome("wechat:1", HomeFeed())
        val reopened = MetadataCache(root)
        assertEquals(home, reopened.home("qq:1"))
        assertEquals(listOf(Playlist("a", "A")), reopened.library("qq:1"))
        assertEquals(detail("a"), reopened.detail("qq:1", "a"))
        assertEquals(mapOf("a" to true, "b" to false), reopened.liked("qq:1"))
        assertEquals(HomeFeed(), reopened.home("wechat:1"))
        assertNull(reopened.home("guest"))
        reopened.clear("qq:1")
        assertNull(reopened.home("qq:1"))
        assertNull(reopened.detail("qq:1", "a"))
        assertEquals(emptyMap<String, Boolean>(), reopened.liked("qq:1"))
        assertEquals(HomeFeed(), reopened.home("wechat:1"))
    }

    @Test fun emptySuccessfulDataIsDifferentFromCacheMiss() {
        val cache = MetadataCache(temporary.newFolder())
        assertNull(cache.library("user"))
        cache.putLibrary("user", emptyList())
        assertEquals(emptyList<Playlist>(), cache.library("user"))
    }

    @Test fun detailLimitEvictsLeastRecentlyReadWithoutDiscardingHome() {
        val cache = MetadataCache(temporary.newFolder(), detailLimit = 2)
        cache.putHome("user", HomeFeed())
        cache.putDetail("user", "a", detail("a"))
        cache.putDetail("user", "b", detail("b"))
        assertEquals(detail("a"), cache.detail("user", "a"))
        cache.putDetail("user", "c", detail("c"))
        assertNull(cache.detail("user", "b"))
        assertEquals(detail("a"), cache.detail("user", "a"))
        assertEquals(detail("c"), cache.detail("user", "c"))
        assertEquals(HomeFeed(), cache.home("user"))
    }

    @Test fun corruptSnapshotsAreCacheMissesAndCanBeReplaced() {
        val root = temporary.newFolder()
        val cache = MetadataCache(root)
        cache.putHome("user", HomeFeed())
        root.walkTopDown().first { it.name == "home.json" }.writeText("{broken")
        assertNull(cache.home("user"))
        cache.putHome("user", HomeFeed())
        assertEquals(HomeFeed(), cache.home("user"))
    }

    @Test fun laterFavoriteStatesReplaceEarlierStates() {
        val cache = MetadataCache(temporary.newFolder())
        cache.putLiked("user", mapOf("a" to true, "b" to true))
        cache.putLiked("user", mapOf("a" to false))
        assertEquals(mapOf("a" to false, "b" to true), cache.liked("user"))
    }

    @Test fun readingAndDetailLruTouchesDoNotExtendTheSuccessfulWriteTime() {
        val root = temporary.newFolder()
        var now = 1_000L
        val cache = MetadataCache(root, now = { now })
        val home = HomeFeed(songs = detail("a").songs)
        cache.putHome("user", home)
        now = 2_000L
        cache.putDetail("user", "a", detail("a"))
        val detailFile = root.walkTopDown().first { it.name.startsWith("detail-") && it.extension == "json" }
        assertTrue(detailFile.setLastModified(1L))

        now = 3_000L
        repeat(2) {
            assertEquals(home, cache.home("user"))
            assertEquals(detail("a"), cache.detail("user", "a"))
            assertEquals(1_000L, cache.homeUpdatedAt("user"))
            assertEquals(2_000L, cache.detailUpdatedAt("user", "a"))
        }
        assertTrue("Detail reads should still update LRU recency", detailFile.lastModified() > 1L)

        val reopened = MetadataCache(root, now = { 9_000L })
        assertEquals(1_000L, reopened.homeUpdatedAt("user"))
        assertEquals(2_000L, reopened.detailUpdatedAt("user", "a"))
    }

    @Test fun laterSuccessfulWritesReplaceOnlyTheMatchingAccountAndResourceTimestamp() {
        var now = 1_000L
        val cache = MetadataCache(temporary.newFolder(), now = { now })
        assertNull(cache.homeUpdatedAt("qq:1"))
        assertNull(cache.detailUpdatedAt("qq:1", "daily"))
        cache.putHome("qq:1", HomeFeed())
        cache.putDetail("qq:1", "daily", detail("daily"))
        now = 2_000L
        cache.putHome("wechat:1", HomeFeed())
        cache.putDetail("wechat:1", "daily", detail("daily"))
        now = 3_000L
        cache.putHome("qq:1", HomeFeed(songs = detail("updated").songs))
        assertEquals(3_000L, cache.homeUpdatedAt("qq:1"))
        assertEquals(1_000L, cache.detailUpdatedAt("qq:1", "daily"))
        assertEquals(2_000L, cache.homeUpdatedAt("wechat:1"))
        assertEquals(2_000L, cache.detailUpdatedAt("wechat:1", "daily"))

        now = 4_000L
        cache.putDetail("qq:1", "daily", detail("updated"))
        assertEquals(4_000L, cache.detailUpdatedAt("qq:1", "daily"))
        assertEquals(3_000L, cache.homeUpdatedAt("qq:1"))
        cache.clear("qq:1")
        assertNull(cache.homeUpdatedAt("qq:1"))
        assertNull(cache.detailUpdatedAt("qq:1", "daily"))
        assertEquals(2_000L, cache.homeUpdatedAt("wechat:1"))
    }

    @Test fun oldSnapshotsWithoutSuccessTimesRemainReadableUntilReplaced() {
        val root = temporary.newFolder()
        var now = 1_000L
        val cache = MetadataCache(root, now = { now })
        val home = HomeFeed(songs = detail("a").songs)
        cache.putHome("user", home)
        cache.putDetail("user", "a", detail("a"))
        root.walkTopDown().filter { it.extension == "json" }.forEach { file ->
            val envelope = JSONObject(file.readText())
            envelope.remove("updatedAt")
            file.writeText(envelope.toString())
        }

        val reopened = MetadataCache(root, now = { now })
        assertEquals(home, reopened.home("user"))
        assertEquals(detail("a"), reopened.detail("user", "a"))
        assertNull(reopened.homeUpdatedAt("user"))
        assertNull(reopened.detailUpdatedAt("user", "a"))
        now = 5_000L
        reopened.putHome("user", home)
        assertEquals(5_000L, reopened.homeUpdatedAt("user"))
        assertNull(reopened.detailUpdatedAt("user", "a"))
        reopened.putDetail("user", "a", detail("a"))
        assertEquals(5_000L, reopened.detailUpdatedAt("user", "a"))
    }
}
