package com.lilt.app

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lilt.app.ui.theme.ThemeMode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PurchasedMusicJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[LiltViewModel::class.java]

    @Test fun purchasedTabsOpenAlbumsAndReturnToTheSameCollection() {
        val originalTheme = vm.state.value.theme
        try {
            compose.runOnUiThread {
                vm.enablePreview()
                vm.setTheme(ThemeMode.Light)
                vm.navigate(Destination.Library)
                vm.openPurchased()
            }
            awaitPurchased()
            compose.onNodeWithText("专辑").assertIsSelected().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithText("叶惠美").assertIsDisplayed()
            compose.onNodeWithText("单曲").performClick()
            awaitPurchased()
            compose.onNodeWithText("单曲").assertIsSelected().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithText("播放全部").assertIsDisplayed()
            compose.onNodeWithContentDescription("随机播放").assertIsDisplayed()
            compose.onNodeWithText("专辑").performClick()
            awaitPurchased()
            compose.onNodeWithText("专辑").assertIsSelected()
            compose.onNodeWithText("叶惠美").assertIsDisplayed()
            saveScreenshot("purchased-albums-light.png")
            compose.onNodeWithText("叶惠美").performClick()
            compose.waitUntil(10_000) { !vm.state.value.detailLoading }
            compose.runOnIdle {
                assertEquals(Destination.Playlist, vm.state.value.destination)
                assertEquals(1L, vm.state.value.detail?.playlist?.albumId)
                vm.back()
                assertEquals(Destination.Purchased, vm.state.value.destination)
                assertEquals(PurchasedTab.Albums, vm.state.value.purchasedTab)
            }
            compose.onNodeWithText("专辑").assertIsSelected()
            compose.runOnUiThread { vm.setTheme(ThemeMode.Dark) }
            compose.waitForIdle()
            saveScreenshot("purchased-albums-dark.png")
            compose.onNodeWithText("单曲").performClick()
            compose.onNodeWithText("播放全部").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(8, vm.state.value.purchasedSongs?.size)
                assertNull(vm.state.value.purchasedError)
                vm.back()
                assertEquals(Destination.Library, vm.state.value.destination)
            }
        } finally {
            compose.runOnUiThread { vm.setTheme(originalTheme) }
        }
    }

    /** Read-only service verification; opt in with the existing account on the device. */
    @Test fun signedInOrdersIncludeAlbumsThatOpenTheirTrackList() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("authenticated") == "true")
        assertNotNull("Sign in before running the account journey", vm.profile.value)
        compose.runOnUiThread {
            vm.navigate(Destination.Library)
            vm.openPurchased()
        }
        awaitPurchased()
        assertEquals(PurchasedTab.Albums, vm.state.value.purchasedTab)
        assertNull(vm.state.value.purchasedError)
        assertNotNull(vm.state.value.purchasedAlbums)
        compose.onNodeWithText("单曲").performClick()
        awaitPurchased()
        assertNull(vm.state.value.purchasedError)
        assertNotNull(vm.state.value.purchasedSongs)
        compose.onNodeWithText("专辑").performClick()
        awaitPurchased()
        assertNull(vm.state.value.purchasedError)
        val albums = requireNotNull(vm.state.value.purchasedAlbums)
        assertTrue("Purchased albums must show their titles", albums.all { it.title.isNotBlank() })
        Log.i("LiltPurchasedJourney", "songs=${vm.state.value.purchasedSongs?.size}; albums=${albums.size}")
        albums.firstOrNull()?.let { compose.onNodeWithText(it.title).assertIsDisplayed() }
        saveScreenshot("purchased-albums-account.png")
        albums.firstOrNull()?.let { album ->
            compose.runOnUiThread { vm.openAlbum(album) }
            compose.waitUntil(30_000) { !vm.state.value.detailLoading }
            assertNull(vm.state.value.detailError)
            assertTrue(requireNotNull(vm.state.value.detail).songs.isNotEmpty())
            compose.runOnIdle {
                vm.back()
                assertEquals(Destination.Purchased, vm.state.value.destination)
                assertEquals(albums, vm.state.value.purchasedAlbums)
                assertEquals(PurchasedTab.Albums, vm.state.value.purchasedTab)
            }
        }
    }

    private fun awaitPurchased() {
        compose.waitUntil(30_000) { !vm.state.value.purchasedLoading }
        compose.waitForIdle()
    }

    private fun saveScreenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
