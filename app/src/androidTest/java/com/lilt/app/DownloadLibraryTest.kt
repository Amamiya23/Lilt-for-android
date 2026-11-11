package com.lilt.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilt.app.data.Artist
import com.lilt.app.data.DownloadLibrary
import com.lilt.app.data.Song
import com.lilt.app.data.Lyrics
import com.lilt.app.data.LyricLine
import com.lilt.app.data.LyricWord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadLibraryTest {
    @Suppress("DEPRECATION")
    @Test fun downloadedArtworkAndWordLyricsSurviveRecreationAndDeleteWithAudio() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<LiltApplication>()
        val manager = context.getSystemService(DownloadManager::class.java)
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "offline-resources-test.wav")
        val samples = 8000 * 20
        file.writeBytes(java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array())
        val id = manager.addCompletedDownload("离线资源", "测试歌手", false, "audio/wav",
            file.absolutePath, file.length(), true)
        val artwork = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/h1cAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT)
        val lyrics = Lyrics(listOf(LyricLine(1000, "晴天", "Sunny day", 2400,
            listOf(LyricWord("晴", 1000, 1500), LyricWord("天", 1500, 2400)))))
        val original = Song("online-resources-mid", 123, "离线资源", "测试歌手",
            cover = "https://unreachable.invalid/cover.jpg")
        var savedCover: File? = null
        try {
            DownloadLibrary(context).remember(id, original, lyrics, artwork)
            val recreated = DownloadLibrary(context)
            val local = recreated.completed().first { it.id == id }
            assertTrue(local.cover.startsWith("file:"))
            savedCover = File(Uri.parse(local.cover).path!!)
            assertTrue(savedCover.absolutePath.startsWith(context.filesDir.absolutePath))
            context.contentResolver.openInputStream(Uri.parse(local.cover))!!.use {
                assertArrayEquals(artwork, it.readBytes())
            }
            assertEquals(lyrics, recreated.lyrics(local))
            // A mismatched system ID must not delete some other imported document.
            assertFalse(recreated.delete(local.copy(mid = "content://different/document")))
            assertTrue(file.exists())
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                lateinit var vm: LiltViewModel
                scenario.onActivity { activity ->
                    vm = ViewModelProvider(activity)[LiltViewModel::class.java]
                    vm.play(listOf(local))
                    vm.showPlayer(true)
                }
                withTimeout(10_000) { vm.player.first { it.current?.id == local.mid && it.isPlaying } }
                withTimeout(10_000) { vm.state.first { !it.lyricsLoading && it.lyrics == lyrics } }
                withTimeout(10_000) { vm.state.first { it.local.any { song -> song.mid == local.mid } } }
                scenario.onActivity { vm.deleteLocal(local) }
                withTimeout(10_000) { vm.player.first { it.tracks.isEmpty() } }
                withTimeout(10_000) {
                    vm.state.first { it.local.none { song -> song.mid == local.mid } &&
                        it.lyrics.lines.isEmpty() && !it.lyricsLoading && !it.playerOpen }
                }
            }
            assertFalse(file.exists())
            assertFalse(savedCover.exists())
            assertNull(recreated.lyrics(local))
            assertFalse(context.getSharedPreferences("lilt_downloads", Context.MODE_PRIVATE).contains(id.toString()))
            assertTrue(DownloadLibrary(context).completed().none { it.id == id })
        } finally {
            manager.remove(id)
            context.getSharedPreferences("lilt_downloads", Context.MODE_PRIVATE).edit().remove(id.toString()).apply()
            savedCover?.parentFile?.deleteRecursively()
            file.delete()
        }
    }

    /** Old downloads have only system metadata; new downloads also retain album/artwork across restarts. */
    @Suppress("DEPRECATION")
    @Test fun completedDownloadsRecoverIntoLocalLibraryWithReadableUris() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<LiltApplication>()
        val manager = context.getSystemService(DownloadManager::class.java)
        val files = (1..2).map { File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "download-regression-$it.wav") }
        val ids = mutableListOf<Long>()
        try {
            files.forEachIndexed { index, file ->
                file.writeBytes(byteArrayOf(82, 73, 70, 70))
                // Hidden completed downloads require DOWNLOAD_WITHOUT_NOTIFICATION; use the normal visible path.
                ids += manager.addCompletedDownload("下载测试 $index", "本地测试歌手", false,
                    "audio/wav", file.absolutePath, file.length(), true)
            }
            val original = Song("online-mid", 23, "曲名 2.0", "原歌手", "原专辑", "https://example.com/cover.jpg",
                180_000, albumMid = "album-mid", artists = listOf(Artist("artist-mid", 7, "原歌手")))
            DownloadLibrary(context).remember(ids[1], original)

            // Recreate the data layer: no in-memory download callback or source Song is retained.
            val recovered = DownloadLibrary(context).completed()
            val legacy = recovered.first { it.id == ids[0] }
            val detailed = recovered.first { it.id == ids[1] }
            assertEquals("下载测试 0", legacy.title)
            assertEquals("本地测试歌手", legacy.artist)
            assertEquals(original.title, detailed.title)
            assertEquals(original.album, detailed.album)
            assertEquals(original.cover, detailed.cover)
            assertEquals(original.artists, detailed.artists)
            assertTrue(detailed.mid.startsWith("content:"))
            context.contentResolver.openInputStream(Uri.parse(detailed.mid))!!.use {
                assertArrayEquals(files[1].readBytes(), it.readBytes())
            }

            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                lateinit var vm: LiltViewModel
                scenario.onActivity { activity ->
                    vm = ViewModelProvider(activity)[LiltViewModel::class.java]
                    vm.navigate(Destination.Local)
                }
                val local = withTimeout(10_000) { vm.state.first { state -> state.local.any { it.mid == detailed.mid } } }.local
                assertTrue(local.any { it.mid == legacy.mid })
                assertEquals(original.cover, local.first { it.mid == detailed.mid }.cover)
            }
        } finally {
            if (ids.isNotEmpty()) manager.remove(*ids.toLongArray())
            context.getSharedPreferences("lilt_downloads", Context.MODE_PRIVATE).edit().apply {
                ids.forEach { remove(it.toString()) }
            }.apply()
            files.forEach { it.delete() }
        }
    }
}
