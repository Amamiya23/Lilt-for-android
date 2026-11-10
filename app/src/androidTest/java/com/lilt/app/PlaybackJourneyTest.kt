package com.lilt.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilt.app.playback.PlaybackConnection
import com.lilt.app.playback.ResolvedTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class PlaybackJourneyTest {
    // The service must preserve the current track when reordering or clearing its real Media3 queue.
    @Test fun queueControlsOperateOnTheBackgroundPlayer() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<LiltApplication>()
        val activity = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        val audio = File(context.cacheDir, "playback-test.wav").apply { writeBytes(silence()) }
        val connection = PlaybackConnection(context)
        try {
            connection.connect()
            val tracks = (1..3).map { ResolvedTrack("test-$it", "Test $it", "Local fixture", url = audio.toURI().toString()) }
            connection.setQueue(tracks)
            val started = withTimeout(15000) { connection.state.first {
                it.current?.id == "test-1" && it.isPlaying && it.durationMs > 0
            } }
            assertEquals("test-1", started.current?.id)
            connection.seekTo(5000)
            withTimeout(5000) { connection.state.first { it.positionMs >= 4900 } }
            connection.toggle()
            withTimeout(5000) { connection.state.first { !it.isPlaying } }
            connection.move(2, 1)
            val moved = withTimeout(5000) { connection.state.first { it.tracks.map { t -> t.id } == listOf("test-1", "test-3", "test-2") } }
            assertEquals("test-1", moved.current?.id)
            connection.next()
            withTimeout(5000) { connection.state.first { it.current?.id == "test-3" } }
            connection.clearUpcoming()
            val cleared = withTimeout(5000) { connection.state.first { it.tracks.size == 1 } }
            assertEquals("test-3", cleared.current?.id)
            connection.toggle()
            withTimeout(5000) { connection.state.first { it.isPlaying } }
            activity.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            delay(700)
            assertTrue("Playback must continue after the activity stops", connection.state.value.isPlaying)
            connection.remove(0)
            withTimeout(5000) { connection.state.first { it.tracks.isEmpty() } }
        } finally {
            connection.close()
            activity.close()
            audio.delete()
        }
    }

    private fun silence(): ByteArray {
        val samples = 8000 * 20
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array()
    }
}
