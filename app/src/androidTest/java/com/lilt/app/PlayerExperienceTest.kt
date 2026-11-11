package com.lilt.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: LiltViewModel
    private lateinit var originalTheme: com.lilt.app.ui.theme.ThemeMode
    private var originalNormalize = false

    @Before fun rememberPreferences() {
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity)[LiltViewModel::class.java]
            originalTheme = vm.state.value.theme
            originalNormalize = vm.state.value.normalize
            vm.setNormalize(false)
        }
    }

    @After fun restorePreferences() {
        compose.runOnUiThread {
            vm.setTheme(originalTheme)
            vm.setNormalize(originalNormalize)
        }
    }

    private fun previewPlayer() {
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity)[LiltViewModel::class.java]
            vm.enablePreview()
            vm.showPlayer(true)
        }
        compose.waitForIdle()
    }

    @Test fun playerFooterKeepsItsTouchTargetsAndDismissingRetainsTheVisiblePanel() {
        previewPlayer()
        compose.onNodeWithContentDescription("同步歌词").assertIsDisplayed()
            .assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp).performClick()
        compose.runOnIdle { assertEquals(PlayerPanel.Lyrics, vm.state.value.playerPanel) }
        compose.onNodeWithContentDescription("播放队列").assertIsDisplayed()
            .assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp).performClick()
        compose.runOnIdle { assertEquals(PlayerPanel.Queue, vm.state.value.playerPanel) }
        compose.onNodeWithContentDescription("收起播放页").performClick()
        compose.runOnIdle {
            assertFalse(vm.state.value.playerOpen)
            assertEquals(PlayerPanel.Queue, vm.state.value.playerPanel)
            vm.showPlayer(true)
            assertEquals(PlayerPanel.Artwork, vm.state.value.playerPanel)
        }
    }

    @Test fun shortHeaderDragReturnsAndLongDragDismisses() {
        previewPlayer()
        compose.onNodeWithContentDescription("收起播放页").performTouchInput {
            swipe(center, center + Offset(0f, 60f), durationMillis = 600)
        }
        compose.runOnIdle { assertTrue(vm.state.value.playerOpen) }
        compose.onNodeWithContentDescription("收起播放页").performTouchInput {
            swipe(center, center + Offset(0f, 800f), durationMillis = 450)
        }
        compose.runOnIdle { assertFalse(vm.state.value.playerOpen) }
    }

    @Test fun settingsUseSingleSwitchTargetsAndAnchoredSelectedOptions() {
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity)[LiltViewModel::class.java]
            vm.enablePreview()
            vm.navigate(Destination.Settings)
        }
        compose.onNodeWithText("音量均衡").performClick()
        compose.runOnIdle { assertTrue(vm.state.value.normalize) }
        compose.onNodeWithText("音量均衡").assertIsOn().performClick()
        compose.onNodeWithText("音量均衡").assertIsOff()
        compose.onNodeWithText("外观").performScrollTo().performClick()
        compose.onNodeWithText("浅色").performClick()
        compose.runOnIdle { assertEquals(com.lilt.app.ui.theme.ThemeMode.Light, vm.state.value.theme) }
        compose.onNodeWithText("外观").performClick()
        compose.onAllNodesWithText("浅色").filter(isSelected()).assertCountEquals(1)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun clearingCacheRequiresConfirmationAndCancelPreservesIt() {
        val app = compose.activity.application as LiltApplication
        val cache = app.audioCache.cache
        val key = "confirmation-${java.util.UUID.randomUUID()}"
        val bytes = ByteArray(2048) { 42 }
        val span = cache.startReadWrite(key, 0, bytes.size.toLong())
        try {
            val file = cache.startFile(key, 0, bytes.size.toLong())
            file.writeBytes(bytes)
            cache.commitFile(file, bytes.size.toLong())
        } finally { cache.releaseHoleSpan(span) }
        try {
            compose.runOnUiThread {
                vm.enablePreview()
                vm.navigate(Destination.Settings)
            }
            compose.onNodeWithText("清除缓存").performScrollTo().performClick()
            compose.onNodeWithText("清除缓存？").assertIsDisplayed()
            assertTrue("Opening the dialog already removed cache", cache.isCached(key, 0, bytes.size.toLong()))
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("清除缓存？").assertDoesNotExist()
            assertTrue("Cancel removed cache", cache.isCached(key, 0, bytes.size.toLong()))
            compose.onNodeWithText("清除缓存").performClick()
            compose.onNodeWithText("清除").performClick()
            compose.waitUntil(10_000) { !cache.isCached(key, 0, bytes.size.toLong()) }
            compose.onNodeWithText("清除缓存？").assertDoesNotExist()
        } finally { cache.removeResource(key) }
    }
}
