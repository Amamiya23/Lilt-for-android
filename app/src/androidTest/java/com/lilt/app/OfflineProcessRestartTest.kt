package com.lilt.app

import android.content.Intent
import android.os.Process
import android.util.AtomicFile
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lilt.app.data.*
import com.lilt.app.playback.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/** Invoke each method separately, force-stopping the target app between prepare and verify. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class OfflineProcessRestartTest {
    @Test fun prepareFixture() = runBlocking<Unit> {
        assumeStage("prepare")
        val app = app()
        assumeFalse("Requires real offline connectivity state", app.network.isOnline)
        val backup = backup(app)
        check(!File(backup, "manifest.json").exists()) { "Run cleanupFixture before preparing another fixture" }
        backup.mkdirs()
        val account = app.repository.accountKey
        val metadata = accountDirectory(app, account)
        if (metadata.exists()) metadata.copyRecursively(File(backup, "metadata"), overwrite = true)
        val queue = File(app.filesDir, "playback-queue.json")
        if (queue.exists()) File(backup, "playback-queue.json").writeBytes(AtomicFile(queue).readFully())
        val id = "restart-fixture-${UUID.randomUUID()}"
        val preferences = app.getSharedPreferences("lilt_settings", 0)
        val manifest = JSONObject().put("id", id).put("account", account)
            .put("metadataExisted", metadata.exists()).put("queueExisted", queue.exists())
            .put("pid", Process.myPid()).put("recentExisted", preferences.contains("recent"))
            .put("recent", preferences.getString("recent", "[]"))
            .put("fadeExisted", preferences.contains("fade")).put("fade", preferences.getBoolean("fade", false))
        File(backup, "manifest.json").writeText(manifest.toString())
        // ShapedPlayer deliberately finishes its pause after a fade; this fixture tests exact seek persistence.
        preferences.edit().putBoolean("fade", false).commit()

        val songs = songs(id)
        val playlist = playlist(id)
        val bytes = silence()
        seed(app, songs[0].mid, bytes, bytes.size.toLong())
        seed(app, songs[1].mid, bytes.copyOf(bytes.size / 2), bytes.size.toLong())
        app.lyricCache.put(songs[0].mid, lyrics())
        app.repository.metadataCache.putHome(account, HomeFeed(songs, listOf(playlist)))
        app.repository.metadataCache.putDetail(account, playlist.id, PlaylistDetail(playlist, songs))
        app.repository.metadataCache.putLiked(account, mapOf(songs[0].mid to true))
        assertTrue(songs[0].mid in app.audioCache.available.value)
        assertFalse(songs[1].mid in app.audioCache.available.value)

        val activity = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        val connection = PlaybackConnection(app)
        try {
            connection.connect()
            connection.setQueue(songs.map(::track), 0, SOURCE)
            withTimeout(15_000) { connection.state.first { it.current?.id == songs[0].mid && it.isPlaying } }
            connection.pause()
            withTimeout(5_000) { connection.state.first { !it.isPlaying } }
            connection.seekTo(7_000)
            connection.setShuffle(true)
            repeat(3) {
                val mode = connection.state.value.repeatMode
                if (mode != Player.REPEAT_MODE_ALL) {
                    connection.cycleRepeat()
                    withTimeout(5_000) { connection.state.first { it.repeatMode != mode } }
                }
            }
            withTimeout(5_000) { connection.state.first { !it.isPlaying && it.positionMs == 7_000L && it.shuffle && it.repeatMode == Player.REPEAT_MODE_ALL } }
            withTimeout(5_000) {
                while (!runCatching {
                    val archive = QueueArchiveJson.decode(String(AtomicFile(queue).readFully(), Charsets.UTF_8))
                    archive.positionMs == 7_000L && archive.shuffle && archive.repeatMode == Player.REPEAT_MODE_ALL && archive.source == SOURCE && archive.tracks.first().song == songs[0]
                }.getOrDefault(false)) delay(50)
            }
        } finally { connection.close(); activity.close() }
    }

    @Test fun verifyRestored() = runBlocking<Unit> {
        assumeStage("verify")
        val app = app()
        assumeFalse("Requires real offline connectivity state", app.network.isOnline)
        val manifest = manifest(app)
        assertNotEquals("prepare and verify must run in different target processes", manifest.getInt("pid"), Process.myPid())
        assertEquals(manifest.getString("account"), app.repository.accountKey)
        val id = manifest.getString("id")
        val songs = songs(id)
        val playlist = playlist(id)
        assertTrue(songs[0].mid in app.audioCache.available.value)
        assertFalse(songs[1].mid in app.audioCache.available.value)
        assertEquals("standard", app.audioCache.cachedQuality(songs[0].mid, "hq", false))
        assertEquals(lyrics(), app.lyricCache.get(songs[0].mid))
        val activity = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        lateinit var vm: LiltViewModel
        try {
            activity.onActivity {
                vm = ViewModelProvider(it)[LiltViewModel::class.java]
                // These assertions happen in the same main-thread call, without waiting for any request.
                assertEquals(songs, vm.state.value.home.songs)
                assertFalse(vm.state.value.homeLoading)
                assertNull(vm.state.value.homeError)
                vm.openPlaylist(playlist)
                assertEquals(songs, vm.state.value.detail?.songs)
                assertFalse(vm.state.value.detailLoading)
                assertNull(vm.state.value.detailError)
            }
            val restored = withTimeout(10_000) { vm.player.first { it.current?.id == songs[0].mid } }
            assertFalse(restored.isPlaying)
            assertFalse(restored.isBuffering)
            assertEquals(7_000L, restored.positionMs)
            assertTrue(restored.shuffle)
            assertEquals(Player.REPEAT_MODE_ALL, restored.repeatMode)
            assertEquals(SOURCE, restored.source)
            assertEquals(songs, restored.tracks.map { it.song })
            val state = withTimeout(5_000) { vm.state.first { !it.lyricsLoading && it.lyrics.lines.isNotEmpty() } }
            assertNull(state.lyricsError)
            assertEquals("Sunny day", state.lyrics.lines.single().translation)
            if (state.wordLyrics) assertEquals(lyrics(), state.lyrics)
            assertTrue(songs[0].mid in state.liked)
            assertTrue(vm.canPlay(songs[0]))
            assertFalse(vm.canPlay(songs[1]))
        } finally { activity.close() }
    }

    /** Force-stop before invoking cleanup, so no live service can write its fixture queue back. */
    @Test fun cleanupFixture() {
        assumeStage("cleanup")
        val app = app()
        val backup = backup(app)
        val manifest = manifest(app)
        val metadata = accountDirectory(app, manifest.getString("account"))
        metadata.deleteRecursively()
        if (manifest.getBoolean("metadataExisted")) File(backup, "metadata").copyRecursively(metadata, overwrite = true)
        val queue = File(app.filesDir, "playback-queue.json")
        AtomicFile(queue).delete()
        if (manifest.getBoolean("queueExisted")) queue.writeBytes(File(backup, "playback-queue.json").readBytes())
        val preferences = app.getSharedPreferences("lilt_settings", 0)
        preferences.edit().apply {
            if (manifest.getBoolean("recentExisted")) putString("recent", manifest.getString("recent")) else remove("recent")
            // Backups from the original fixture version did not change or record this setting.
            if (manifest.has("fadeExisted")) {
                if (manifest.getBoolean("fadeExisted")) putBoolean("fade", manifest.getBoolean("fade")) else remove("fade")
            }
        }.commit()
        songs(manifest.getString("id")).forEach { song ->
            app.audioCache.cache.removeResource(AudioCacheKey.of(song.mid, "standard"))
            AtomicFile(File(app.cacheDir, "audio-lyrics/${digest(song.mid)}.json")).delete()
        }
        app.audioCache.refresh()
        backup.deleteRecursively()
    }

    private fun app() = ApplicationProvider.getApplicationContext<LiltApplication>()
    private fun assumeStage(stage: String) = assumeTrue("Invoke with -e offline_restart_stage $stage",
        InstrumentationRegistry.getArguments().getString("offline_restart_stage") == stage)
    private fun backup(app: LiltApplication) = File(app.cacheDir, "offline-process-backup")
    private fun manifest(app: LiltApplication) = JSONObject(File(backup(app), "manifest.json").readText())
    private fun accountDirectory(app: LiltApplication, account: String) = File(app.noBackupFilesDir, "metadata/${digest(account)}")
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun playlist(id: String) = Playlist("$id-playlist", "Offline process fixture", trackCount = 2)
    private fun songs(id: String) = listOf("$id-full", "$id-partial").map { mid ->
        Song(mid, 123, "Offline process fixture", "Fixture artist", "Fixture album", durationMs = 20_000,
            mediaMid = "media-$mid", albumMid = "album-fixture", artists = listOf(Artist("artist-fixture", 77, "Fixture artist")))
    }
    private fun track(song: Song) = ResolvedTrack(song.mid, song.title, song.artist, song.album, song.cover, "lilt://song/${song.mid}?quality=hq", song)
    private fun lyrics() = Lyrics(listOf(LyricLine(1_000, "晴天", "Sunny day", 2_000,
        listOf(LyricWord("晴", 1_000, 1_500), LyricWord("天", 1_500, 2_000)))))
    private fun seed(app: LiltApplication, mid: String, bytes: ByteArray, fullLength: Long) {
        val cache = app.audioCache.cache
        val key = AudioCacheKey.of(mid, "standard")
        val span = cache.startReadWrite(key, 0, bytes.size.toLong())
        try {
            val file = cache.startFile(key, 0, bytes.size.toLong())
            file.writeBytes(bytes)
            cache.commitFile(file, bytes.size.toLong())
            val mutations = ContentMetadataMutations()
            ContentMetadataMutations.setContentLength(mutations, fullLength)
            cache.applyContentMetadataMutations(key, mutations)
        } finally { cache.releaseHoleSpan(span) }
        app.audioCache.refresh()
    }
    private fun silence(): ByteArray {
        val samples = 8_000 * 20
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array()
    }
    private companion object { const val SOURCE = "来自歌单 · Offline process fixture" }
}
