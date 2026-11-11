package com.lilt.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lilt.app.data.Song
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchLibraryActionsTest {
    @Test fun batchDeletionKeepsFailuresAndRemovesSuccessfulFilesFromEveryList() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<LiltApplication>()
        val resolver = context.contentResolver
        val settings = context.getSharedPreferences("lilt_settings", Context.MODE_PRIVATE)
        val originalLocal = settings.getString("local", null)
        val originalRecent = settings.getString("recent", null)
        val root = DocumentsContract.buildDocumentUri("com.lilt.app.test.deletion.documents", "root")
        context.startActivity(Intent().setClassName(
            InstrumentationRegistry.getInstrumentation().context.packageName,
            DeletionDocumentGrantActivity::class.java.name,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(10_000) {
            while (context.checkUriPermission(root, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                delay(50)
            }
        }
        val first = DocumentsContract.createDocument(resolver, root, "audio/wav", "batch-first.wav")!!
        var readonly = DocumentsContract.createDocument(resolver, root, "audio/wav", "readonly-batch.wav")!!
        val last = DocumentsContract.createDocument(resolver, root, "audio/wav", "batch-last.wav")!!
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                lateinit var vm: LiltViewModel
                scenario.onActivity { activity ->
                    vm = ViewModelProvider(activity)[LiltViewModel::class.java]
                    vm.addLocal(listOf(first, readonly, last))
                }
                val songs = listOf(first, readonly, last).map { uri ->
                    vm.state.value.local.first { it.mid == uri.toString() }
                }
                scenario.onActivity {
                    vm.openSongs("批量删除测试", songs)
                    vm.play(songs)
                }
                withTimeout(10_000) { vm.player.first { it.tracks.size == 3 && it.current?.id == songs[0].mid } }
                val summary = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(10_000) { vm.messages.first { it.startsWith("已删除 ") } }
                }
                scenario.onActivity {
                    // Duplicate MID must be processed once; an online song is not a local file.
                    vm.deleteLocalSongs(listOf(songs[0], songs[0], songs[1], songs[2], Song("online-mid", 999, "在线歌曲", "歌手")))
                    assertTrue(vm.state.value.batchBusy)
                }
                withTimeout(10_000) { vm.state.first { !it.batchBusy } }
                assertEquals("已删除 2 首，1 首失败（1 首没有删除权限）", summary.await())
                val state = vm.state.value
                assertFalse(state.local.any { it.mid == first.toString() || it.mid == last.toString() })
                assertTrue(state.local.any { it.mid == readonly.toString() })
                assertEquals(listOf(readonly.toString()), state.detail!!.songs.map { it.mid })
                assertEquals(1, state.detail!!.playlist.trackCount)
                assertFalse(state.recent.any { it.mid == first.toString() || it.mid == last.toString() })
                val saved = JSONArray(settings.getString("local", "[]"))
                val savedMids = (0 until saved.length()).map { saved.getJSONObject(it).getString("mid") }
                assertTrue(readonly.toString() in savedMids)
                assertFalse(first.toString() in savedMids || last.toString() in savedMids)
                assertTrue(runCatching { resolver.openInputStream(first)?.close() }.isFailure)
                assertTrue(runCatching { resolver.openInputStream(last)?.close() }.isFailure)
                resolver.openInputStream(readonly)!!.use { assertEquals(82, it.read()) }
                withTimeout(10_000) { vm.player.first { it.tracks.map { track -> track.id } == listOf(readonly.toString()) } }
                scenario.onActivity { vm.playback.remove(0) }
                withTimeout(10_000) { vm.player.first { it.tracks.isEmpty() } }
            }
        } finally {
            runCatching { DocumentsContract.deleteDocument(resolver, first) }
            runCatching { DocumentsContract.deleteDocument(resolver, last) }
            runCatching {
                readonly = DocumentsContract.renameDocument(resolver, readonly, "writable.wav") ?: readonly
                DocumentsContract.deleteDocument(resolver, readonly)
            }
            settings.edit().apply {
                if (originalLocal == null) remove("local") else putString("local", originalLocal)
                if (originalRecent == null) remove("recent") else putString("recent", originalRecent)
            }.apply()
        }
    }
}
