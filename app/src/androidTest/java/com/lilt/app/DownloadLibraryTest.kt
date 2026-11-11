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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadLibraryTest {
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
