package com.lilt.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in account verification; reads favorite state without changing the user's cloud library. */
@RunWith(AndroidJUnit4::class)
class LikedMenuJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun anUnplayedFavoriteShowsAlreadyLikedInItsMenu() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("authenticated") == "true")
        val context = compose.activity.application as LiltApplication
        assumeTrue("Sign in through the app before this read-only journey", context.repository.profile.value != null)
        lateinit var vm: LiltViewModel
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity)[LiltViewModel::class.java]
            vm.showPlayer(false)
            vm.refreshLibrary()
        }
        compose.waitUntil(30_000) { !vm.state.value.libraryLoading && vm.state.value.library.any { it.dirId == 201 } }
        val liked = vm.state.value.library.first { it.dirId == 201 }
        compose.runOnUiThread { vm.openPlaylist(liked) }
        compose.waitUntil(30_000) {
            !vm.state.value.detailLoading && vm.state.value.detail?.playlist?.id == liked.id
        }
        assertNull("The account's favorite playlist must load", vm.state.value.detailError)
        val songs = vm.state.value.detail!!.songs
        val song = songs.firstOrNull { it.mid != vm.player.value.current?.id }
        assumeTrue("The favorites list needs an unplayed track for this check", song != null)
        val selected = requireNotNull(song)
        assertTrue("The service must confirm the track is liked", runBlocking {
            withTimeout(30_000) { context.repository.isLiked(selected.mid) }
        })

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(songs.indexOf(selected) + 1)
        compose.onNodeWithContentDescription("${selected.title}的更多操作").performClick()
        compose.onNodeWithText("已喜欢", substring = false).assertIsDisplayed()
        compose.onNodeWithText("喜欢", substring = false).assertDoesNotExist()
    }
}
