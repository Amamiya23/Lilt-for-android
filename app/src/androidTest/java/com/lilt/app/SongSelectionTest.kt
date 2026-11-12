package com.lilt.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lilt.app.data.Playlist
import com.lilt.app.data.Song
import com.lilt.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SongSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val songs = listOf(
        Song("selection-one", 901, "多选测试一", "测试歌手"),
        Song("selection-two", 902, "多选测试二", "测试歌手"),
        Song("selection-three", 903, "多选测试三", "测试歌手"),
    )

    private fun openFixture(): LiltViewModel {
        lateinit var vm: LiltViewModel
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity)[LiltViewModel::class.java]
            vm.enablePreview()
            vm.showPlayer(false)
            vm.openSongs("多选测试列表", songs)
        }
        compose.waitForIdle()
        return vm
    }

    private fun row(index: Int) = compose.onNodeWithTag("song-row-${songs[index].mid}")
    private fun visibleRow(index: Int): SemanticsNodeInteraction {
        // The playlist hero is item 0. Bring the song to the top, then clear of the floating selection header.
        scrollClearOfChrome(index + 1)
        return row(index).assertIsDisplayed()
    }

    /** Puts [item] in the middle band of the screen, between the floating top and bottom chrome. */
    private fun scrollClearOfChrome(item: Int) {
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(item)
        val offset = with(compose.density) { 200.dp.toPx() }
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -offset) }
    }
    private fun longPress(index: Int) {
        visibleRow(index).performTouchInput { longClick() }
    }

    private fun saveScreenshot(name: String) {
        val vm = ViewModelProvider(compose.activity)[LiltViewModel::class.java]
        val originalTheme = vm.state.value.theme
        try {
            for (theme in listOf(ThemeMode.Light, ThemeMode.Dark)) {
                compose.runOnUiThread { vm.setTheme(theme) }
                compose.waitForIdle()
                val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                try {
                    val filename = if (theme == ThemeMode.Light) name else name.removeSuffix(".png") + "-dark.png"
                    val file = File(requireNotNull(compose.activity.getExternalFilesDir(null)), filename)
                    file.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    screenshot.recycle()
                }
            }
        } finally {
            compose.runOnUiThread { vm.setTheme(originalTheme) }
        }
    }

    @Test fun longPressTogglesSelectionAndSelectAllWithoutPlaying() {
        val vm = openFixture()
        longPress(0)
        compose.onNodeWithText("已选 1 首").assertIsDisplayed()
        row(0).assertIsSelected()
        compose.runOnIdle { assertFalse(vm.state.value.playerOpen) }

        visibleRow(1).performClick()
        compose.onNodeWithText("已选 2 首").assertIsDisplayed()
        row(1).assertIsSelected()
        visibleRow(0).performClick()
        compose.onNodeWithText("已选 1 首").assertIsDisplayed()
        row(0).assertIsNotSelected()

        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("已选 3 首").assertIsDisplayed()
        songs.indices.forEach { visibleRow(it).assertIsSelected() }
        visibleRow(0)
        saveScreenshot("selection-online.png")
        compose.onNodeWithText("取消全选").performClick()
        compose.onNodeWithText("已选 0 首").assertIsDisplayed()
        compose.onNodeWithText("添加到", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("下载", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("移除", substring = false).assertIsNotEnabled()

        // Empty selection stays in selection mode; another tap selects instead of playing.
        visibleRow(2).performClick()
        compose.onNodeWithText("已选 1 首").assertIsDisplayed()
        row(2).assertIsSelected()
        compose.onNodeWithText("添加到", substring = false).assertIsEnabled()
        compose.onNodeWithText("下载", substring = false).assertIsEnabled()
        compose.onNodeWithText("移除", substring = false).assertIsNotEnabled()
        compose.runOnIdle { assertFalse(vm.state.value.playerOpen) }
    }

    @Test fun backExitsSelectionBeforeNavigationAndRestoresNormalSongTap() {
        val vm = openFixture()
        longPress(0)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("退出多选").assertDoesNotExist()
        compose.runOnIdle { assertEquals(Destination.Playlist, vm.state.value.destination) }

        // Preview playback opens the player without requesting an online audio URL.
        visibleRow(1).performClick()
        compose.waitUntil(5_000) { vm.state.value.playerOpen }
    }

    @Test fun leavingAListClearsSelectionAndExplicitExitKeepsTheListOpen() {
        val vm = openFixture()
        longPress(0)
        compose.onNodeWithContentDescription("退出多选").performClick()
        compose.onNodeWithContentDescription("退出多选").assertDoesNotExist()
        compose.runOnIdle { assertEquals(Destination.Playlist, vm.state.value.destination) }

        longPress(1)
        compose.runOnUiThread { vm.navigate(Destination.Library) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("退出多选").assertDoesNotExist()
        compose.runOnUiThread { vm.openSongs("再次打开列表", songs) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("退出多选").assertDoesNotExist()
        longPress(2)
        compose.onNodeWithText("已选 1 首").assertIsDisplayed()
        visibleRow(2).assertIsSelected()
        visibleRow(0).assertIsNotSelected()
        visibleRow(1).assertIsNotSelected()
    }

    @Test fun localBatchDeleteRequiresConfirmationAndExitsSelectionAfterSuccess() {
        val context = compose.activity.application
        val resolver = context.contentResolver
        val settings = context.getSharedPreferences("lilt_settings", Context.MODE_PRIVATE)
        val originalLocal = settings.getString("local", null)
        val uris = (1..2).map { index ->
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "selection-delete-$index-${UUID.randomUUID()}.wav")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/LiltTests")
            }
            requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)).also { uri ->
                resolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(82, 73, 70, 70)) }
            }
        }
        fun exists(index: Int): Boolean = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID), "${MediaStore.Audio.Media._ID} = ?",
            arrayOf(ContentUris.parseId(uris[index]).toString()), null)!!.use { it.count > 0 }
        lateinit var vm: LiltViewModel
        fun localRow(index: Int): SemanticsNodeInteraction {
            val songIndex = vm.state.value.local.indexOfFirst { it.mid == uris[index].toString() }
            check(songIndex >= 0)
            // The local page has its title, import card and section heading before the songs.
            scrollClearOfChrome(songIndex + 3)
            return compose.onNodeWithTag("song-row-${uris[index]}").assertIsDisplayed()
        }
        try {
            compose.runOnUiThread {
                vm = ViewModelProvider(compose.activity)[LiltViewModel::class.java]
                vm.showPlayer(false)
                vm.addLocal(uris)
                vm.navigate(Destination.Local)
            }
            compose.waitUntil(10_000) { uris.all { uri -> vm.state.value.local.any { it.mid == uri.toString() } } }
            localRow(0).performTouchInput { longClick() }
            localRow(1).performClick()
            compose.onNodeWithText("已选 2 首").assertIsDisplayed()
            compose.onNodeWithText("添加到", substring = false).assertIsNotEnabled()
            compose.onNodeWithText("下载", substring = false).assertIsNotEnabled()
            compose.onNodeWithText("移除", substring = false).assertIsEnabled()
            localRow(0)
            saveScreenshot("selection-local.png")
            compose.onNodeWithText("移除", substring = false).performClick()
            compose.onNodeWithText("删除 2 首已下载的歌曲？").assertIsDisplayed()
            compose.onNodeWithText("取消", substring = false).performClick()
            compose.onNodeWithText("已选 2 首").assertIsDisplayed()
            assertTrue(exists(0))
            assertTrue(exists(1))
            localRow(0).assertIsSelected()
            localRow(1).assertIsSelected()

            compose.onNodeWithText("移除", substring = false).performClick()
            compose.onNode(hasText("删除", substring = false) and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(10_000) {
                uris.none { uri -> vm.state.value.local.any { it.mid == uri.toString() } } && !vm.state.value.batchBusy
            }
            assertFalse(exists(0))
            assertFalse(exists(1))
            compose.onNodeWithContentDescription("退出多选").assertDoesNotExist()
        } finally {
            uris.forEach { runCatching { resolver.delete(it, null, null) } }
            settings.edit().apply {
                if (originalLocal == null) remove("local") else putString("local", originalLocal)
            }.apply()
        }
    }

    @Test fun onlineSelectionCanOpenThePlaylistPicker() {
        openFixture()
        longPress(0)
        compose.onNodeWithText("添加到", substring = false).performClick()
        compose.onNodeWithText("添加到歌单", substring = false).assertIsDisplayed()
        compose.onNodeWithText("新建歌单", substring = false).assertIsDisplayed()
    }

    private fun openPreviewSongMenu(playlist: Playlist): Pair<LiltViewModel, Song> {
        lateinit var vm: LiltViewModel
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity)[LiltViewModel::class.java]
            vm.enablePreview()
            vm.showPlayer(false)
            vm.openPlaylist(playlist)
        }
        compose.waitUntil(10_000) { !vm.state.value.detailLoading && vm.state.value.detail?.songs.orEmpty().size >= 2 }
        // Use the second track so opening its menu does not depend on the preview's current song.
        val song = vm.state.value.detail!!.songs[1]
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithContentDescription("${song.title}的更多操作").performClick()
        return vm to song
    }

    @Test fun likedPlaylistShowsAlreadyLikedForAnUnplayedSong() {
        val (vm, song) = openPreviewSongMenu(Playlist("preview-liked", "我喜欢", dirId = 201))
        compose.onNodeWithText("已喜欢", substring = false).assertIsDisplayed()
        compose.onNodeWithText("喜欢", substring = false).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(song.mid in vm.state.value.liked)
            assertFalse(vm.state.value.playerOpen)
        }
    }

    @Test fun aPublicPlaylistNamedLikeFavoritesDoesNotMarkItsSongsAsLiked() {
        val (vm, song) = openPreviewSongMenu(Playlist("preview-public", "我喜欢", dirId = 0))
        compose.onNodeWithText("喜欢", substring = false).assertIsDisplayed()
        compose.onNodeWithText("已喜欢", substring = false).assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(song.mid in vm.state.value.liked)
            assertFalse(vm.state.value.playerOpen)
        }
    }

    @Test fun removingFromFavoritesConfirmsUnlikeAndCancelPreservesSelection() {
        lateinit var vm: LiltViewModel
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity)[LiltViewModel::class.java]
            vm.enablePreview()
            vm.showPlayer(false)
            vm.openPlaylist(Playlist("preview-liked", "我喜欢", dirId = 201))
        }
        compose.waitUntil(10_000) { !vm.state.value.detailLoading && vm.state.value.detail?.songs.orEmpty().size >= 2 }
        val song = vm.state.value.detail!!.songs[1]
        val likedBefore = vm.state.value.liked.toSet()
        scrollClearOfChrome(2)
        compose.onNodeWithTag("song-row-${song.mid}").performTouchInput { longClick() }
        compose.onNodeWithText("已选 1 首").assertIsDisplayed()
        compose.onNodeWithText("添加到", substring = false).assertIsEnabled()
        compose.onNodeWithText("下载", substring = false).assertIsEnabled()
        compose.onNodeWithText("移除", substring = false).assertIsEnabled().performClick()
        compose.onNodeWithText("取消喜欢 1 首歌曲？").assertIsDisplayed()
        compose.onNode(hasText("取消喜欢", substring = false) and hasAnyAncestor(isDialog())).assertIsEnabled()
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.onNodeWithText("已选 1 首").assertIsDisplayed()
        compose.onNodeWithTag("song-row-${song.mid}").assertIsSelected()
        compose.runOnIdle {
            assertEquals(likedBefore, vm.state.value.liked)
            assertTrue(vm.state.value.detail!!.songs.any { it.mid == song.mid })
            assertFalse(vm.state.value.batchBusy)
        }
    }
}
