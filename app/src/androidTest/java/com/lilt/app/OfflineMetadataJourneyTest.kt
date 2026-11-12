package com.lilt.app

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilt.app.data.HomeFeed
import com.lilt.app.data.Playlist
import com.lilt.app.data.PlaylistDetail
import com.lilt.app.data.Song
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class OfflineMetadataJourneyTest {
    @Test fun offlineActivityImmediatelyShowsPersistedHomeAndDetailWithoutLoadingOrErrors() {
        val app = ApplicationProvider.getApplicationContext<LiltApplication>()
        assumeFalse("Run this journey with the device offline", app.network.isOnline)
        val repository = app.repository
        val account = repository.accountKey
        val directory = File(app.noBackupFilesDir, "metadata/${digest(account)}")
        val originals = directory.listFiles().orEmpty().associate { it.name to it.readBytes() }
        val song = Song("offline-page-fixture", 42, "Cached song", "Cached artist", albumMid = "cached-album")
        val playlist = Playlist("98765432101234", "Cached playlist", trackCount = 1)
        val home = HomeFeed(listOf(song), listOf(playlist))
        val detail = PlaylistDetail(playlist, listOf(song))
        try {
            repository.metadataCache.putHome(account, home)
            repository.metadataCache.putDetail(account, playlist.id, detail)
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { activity ->
                activity.onActivity {
                    val vm = ViewModelProvider(it)[LiltViewModel::class.java]
                    assertFalse(vm.state.value.online)
                    assertEquals(home, vm.state.value.home)
                    assertFalse(vm.state.value.homeLoading)
                    assertNull(vm.state.value.homeError)
                    vm.openPlaylist(playlist)
                    assertEquals(detail, vm.state.value.detail)
                    assertFalse(vm.state.value.detailLoading)
                    assertNull(vm.state.value.detailError)
                    assertFalse(vm.canPlay(song))
                    assertTrue(vm.canPlay(song.copy(mid = "content://fixture/song")))
                    assertTrue(vm.canPlay(song.copy(mid = "file:///fixture/song.wav")))
                }
            }
            // A second Activity and ViewModel must read the persisted snapshots again.
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { activity ->
                activity.onActivity {
                    val vm = ViewModelProvider(it)[LiltViewModel::class.java]
                    assertEquals(home, vm.state.value.home)
                    assertFalse(vm.state.value.homeLoading)
                    assertNull(vm.state.value.homeError)
                }
            }
        } finally {
            // Restore only this account's snapshots, including anything displaced by the bounded LRU.
            directory.listFiles().orEmpty().filter { it.name !in originals }.forEach(File::delete)
            originals.forEach { (name, bytes) -> File(directory, name).writeBytes(bytes) }
        }
    }

    @Test fun continuousBrowsingKeepsTheVisibleHomeUntilAnExplicitRefresh() {
        val app = ApplicationProvider.getApplicationContext<LiltApplication>()
        assumeFalse("Run this journey with the device offline", app.network.isOnline)
        val repository = app.repository
        val account = repository.accountKey
        val directory = File(app.noBackupFilesDir, "metadata/${digest(account)}")
        val originals = directory.listFiles().orEmpty().associate { it.name to it.readBytes() }
        val song = Song("stable-home-fixture", 43, "Original recommendation", "Cached artist")
        val playlist = Playlist("98765432101235", "Stable home playlist", trackCount = 1)
        val original = HomeFeed(listOf(song), listOf(playlist))
        val replacement = original.copy(songs = listOf(song.copy(mid = "replacement-home-fixture", title = "Replacement recommendation")))
        val detail = PlaylistDetail(playlist, listOf(song))
        try {
            repository.metadataCache.putHome(account, original)
            repository.metadataCache.putDetail(account, playlist.id, detail)
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { activity ->
                activity.onActivity {
                    val vm = ViewModelProvider(it)[LiltViewModel::class.java]
                    vm.showPlayer(false)
                    assertFalse(vm.state.value.online)
                    assertEquals(original, vm.state.value.home)

                    // A different disk snapshot makes any unintended navigation refresh observable.
                    repository.metadataCache.putHome(account, replacement)
                    vm.navigate(Destination.Search)
                    assertEquals(original, vm.state.value.home)
                    vm.navigate(Destination.Home)
                    assertEquals(original, vm.state.value.home)

                    vm.openPlaylist(playlist)
                    assertEquals(detail, vm.state.value.detail)
                    assertEquals(original, vm.state.value.home)
                    vm.back()
                    assertEquals(Destination.Home, vm.state.value.destination)
                    assertEquals(original, vm.state.value.home)

                    vm.onAppBackgrounded()
                    vm.onAppForegrounded()
                    assertEquals(original, vm.state.value.home)

                    vm.refreshHome()
                    assertEquals(replacement, vm.state.value.home)
                    assertFalse(vm.state.value.homeLoading)
                    assertFalse(vm.state.value.homeRefreshing)
                    assertNull(vm.state.value.homeError)
                }
            }
        } finally {
            directory.listFiles().orEmpty().filter { it.name !in originals }.forEach(File::delete)
            originals.forEach { (name, bytes) -> File(directory, name).writeBytes(bytes) }
        }
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
