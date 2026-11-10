package com.lilt.app

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationJourneyTest {
    // Back must pop nested account destinations instead of getting trapped on Settings.
    @Test fun nestedAccountScreensReturnToTheirParents() {
        val context = ApplicationProvider.getApplicationContext<LiltApplication>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[LiltViewModel::class.java]
                vm.navigate(Destination.Library)
                vm.openSongs("最近播放", emptyList())
                assertEquals(Destination.Library, vm.state.value.topLevel)
                vm.back()
                vm.navigate(Destination.Settings)
                vm.navigate(Destination.Login)
                vm.back()
                assertEquals(Destination.Settings, vm.state.value.destination)
                vm.back()
                assertEquals(Destination.Library, vm.state.value.destination)
                vm.navigate(Destination.Settings)
                vm.navigate(Destination.Local)
                vm.back()
                assertEquals(Destination.Settings, vm.state.value.destination)
                vm.back()
                assertEquals(Destination.Library, vm.state.value.destination)
            }
        }
    }

    // A search query is retained across tabs while the full-screen player consumes Back first.
    @Test fun playerBackKeepsTheUnderlyingSearch() {
        val context = ApplicationProvider.getApplicationContext<LiltApplication>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[LiltViewModel::class.java]
                vm.navigate(Destination.Search)
                vm.openSongs("搜索结果", emptyList())
                assertEquals(Destination.Search, vm.state.value.topLevel)
                vm.back()
                vm.updateQuery("陈奕迅")
                vm.showPlayer(true)
                vm.setPlayerPanel(PlayerPanel.Lyrics)
                vm.back()
                assertEquals(PlayerPanel.Artwork, vm.state.value.playerPanel)
                assertEquals(true, vm.state.value.playerOpen)
                vm.back()
                assertEquals(false, vm.state.value.playerOpen)
                assertEquals(Destination.Search, vm.state.value.destination)
                assertEquals("陈奕迅", vm.state.value.query)
            }
        }
    }
}
