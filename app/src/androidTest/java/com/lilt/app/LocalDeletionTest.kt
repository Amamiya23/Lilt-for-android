package com.lilt.app

import android.app.DownloadManager
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lilt.app.data.DownloadLibrary
import com.lilt.app.data.Song
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import org.json.JSONArray

@RunWith(AndroidJUnit4::class)
class LocalDeletionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Suppress("DEPRECATION")
    @Test fun downloadDeletionRequiresConfirmationAndDoesNotReturnAfterRefresh() {
        val context = compose.activity.application
        val manager = context.getSystemService(DownloadManager::class.java)
        val title = "删除测试-${UUID.randomUUID()}"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "$title.wav")
        file.writeBytes(byteArrayOf(82, 73, 70, 70))
        val id = manager.addCompletedDownload(title, "本地测试", false, "audio/wav",
            file.absolutePath, file.length(), true)
        try {
            DownloadLibrary(context).remember(id, Song("test-online", 1, title, "本地测试"))
            lateinit var vm: LiltViewModel
            compose.runOnUiThread {
                vm = ViewModelProvider(compose.activity)[LiltViewModel::class.java]
                vm.navigate(Destination.Local)
            }
            compose.waitUntil(10_000) { vm.state.value.local.any { it.id == id } }
            val local = vm.state.value.local.first { it.id == id }
            compose.onNodeWithContentDescription("${title}的更多操作").performScrollTo().performClick()
            compose.onNodeWithText("删除").performClick()
            compose.onNodeWithText("删除歌曲？").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            assertTrue(file.exists())
            assertTrue(vm.state.value.local.any { it.id == id })

            compose.onNodeWithContentDescription("${title}的更多操作").performClick()
            compose.onNodeWithText("删除").performClick()
            compose.onNodeWithText("删除").performClick()
            compose.waitUntil(10_000) { vm.state.value.local.none { it.mid == local.mid } }
            assertFalse(file.exists())
            assertTrue(DownloadLibrary(context).completed().none { it.id == id })
            compose.runOnUiThread { vm.refreshLocal() }
            compose.waitForIdle()
            assertTrue(vm.state.value.local.none { it.id == id })
        } finally {
            manager.remove(id)
            context.getSharedPreferences("lilt_downloads", Context.MODE_PRIVATE).edit().remove(id.toString()).apply()
            file.delete()
        }
    }

    @Test fun importedAudioDeletionRemovesTheFileAndPersistentLibraryEntry() {
        val context = compose.activity.application
        val resolver = context.contentResolver
        val title = "import-delete-${UUID.randomUUID()}.wav"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, title)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/LiltTests")
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            resolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(82, 73, 70, 70)) }
            lateinit var vm: LiltViewModel
            compose.runOnUiThread {
                vm = ViewModelProvider(compose.activity)[LiltViewModel::class.java]
                vm.addLocal(listOf(uri))
            }
            compose.waitUntil(10_000) { vm.state.value.local.any { it.mid == uri.toString() } }
            val song = vm.state.value.local.first { it.mid == uri.toString() }
            compose.runOnUiThread { vm.deleteLocal(song) }
            compose.waitUntil(10_000) { vm.state.value.local.none { it.mid == uri.toString() } }
            resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media._ID} = ?", arrayOf(ContentUris.parseId(uri).toString()), null)!!.use {
                assertEquals(0, it.count)
            }
            assertFalse(savedLocalMids(context).contains(uri.toString()))
        } finally {
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    @Test fun safDocumentDeletionUsesTheProviderAndPermissionFailurePreservesTheSong() = runBlocking<Unit> {
        val context = compose.activity.application
        val resolver = context.contentResolver
        val root = DocumentsContract.buildDocumentUri("com.lilt.app.test.deletion.documents", "root")
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        context.startActivity(Intent().setClassName(testPackage, DeletionDocumentGrantActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(10_000) {
            context.checkUriPermission(root, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        }
        val writable = DocumentsContract.createDocument(resolver, root, "audio/wav", "writable.wav")!!
        var readonly = DocumentsContract.createDocument(resolver, root, "audio/wav", "readonly.wav")!!
        val settings = context.getSharedPreferences("lilt_settings", Context.MODE_PRIVATE)
        val originalLocal = settings.getString("local", null)
        try {
            lateinit var vm: LiltViewModel
            compose.runOnUiThread {
                vm = ViewModelProvider(compose.activity)[LiltViewModel::class.java]
                vm.addLocal(listOf(writable, readonly))
            }
            assertTrue(DocumentsContract.isDocumentUri(context, writable))
            val writableSong = vm.state.value.local.first { it.mid == writable.toString() }
            val readonlySong = vm.state.value.local.first { it.mid == readonly.toString() }
            compose.runOnUiThread { vm.deleteLocal(writableSong) }
            compose.waitUntil(10_000) { vm.state.value.local.none { it.mid == writable.toString() } }
            assertTrue(runCatching { resolver.openInputStream(writable)?.close() }.isFailure)

            // Register before issuing the command so the shared flow event cannot be missed.
            val failure = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                withTimeout(10_000) { vm.messages.first { it.contains("删除权限") } }
            }
            compose.runOnUiThread { vm.deleteLocal(readonlySong) }
            failure.await()
            assertTrue(vm.state.value.local.any { it.mid == readonly.toString() })
            assertTrue(savedLocalMids(context).contains(readonly.toString()))
            resolver.openInputStream(readonly)!!.use { assertEquals(82, it.read()) }
        } finally {
            runCatching { DocumentsContract.deleteDocument(resolver, writable) }
            readonly = DocumentsContract.renameDocument(resolver, readonly, "writable.wav") ?: readonly
            runCatching { DocumentsContract.deleteDocument(resolver, readonly) }
            settings.edit().apply {
                if (originalLocal == null) remove("local") else putString("local", originalLocal)
            }.apply()
        }
    }

    private fun savedLocalMids(context: Context): List<String> {
        val songs = JSONArray(context.getSharedPreferences("lilt_settings", Context.MODE_PRIVATE).getString("local", "[]"))
        return (0 until songs.length()).map { songs.getJSONObject(it).getString("mid") }
    }
}
