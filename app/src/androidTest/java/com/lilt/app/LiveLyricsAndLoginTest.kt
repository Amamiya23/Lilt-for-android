package com.lilt.app

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lilt.app.data.LoginState
import com.lilt.app.data.QqMusicRepository
import com.lilt.app.data.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live checks. A separate repository is used for login, so the app's saved account is never touched. */
@RunWith(AndroidJUnit4::class)
class LiveLyricsAndLoginTest {
    private val context get() = ApplicationProvider.getApplicationContext<LiltApplication>()

    @Test fun cloudLyricsCarryRealWordTimes() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val lyrics = context.repository.lyrics(Song("0039MnYb0qxYhV", 97773, "晴天", "周杰伦"))
        assertTrue("QQ's cloud lyric must decode as word-timed QRC", lyrics.wordTimed)
        assertTrue(lyrics.lines.all { line -> line.words.zipWithNext().all { (a, b) -> a.startMs <= b.startMs } })
        assertTrue(lyrics.lines.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
        Log.i(TAG, "qrcLines=${lyrics.lines.size}; words=${lyrics.lines.sumOf { it.words.size }}; translated=${lyrics.lines.count { it.translation.isNotEmpty() }}")
    }

    // Covers device registration and QR retrieval; confirming the scan needs a person.
    @Test fun wechatLoginIssuesAScannableCode() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val repository = QqMusicRepository(context)
        val qr = repository.createQr("wechat")
        try {
            val image = requireNotNull(qr.imageBytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
            assertTrue("The WeChat QR must be a real image", bounds.outWidth > 100 && bounds.outHeight > 100)
            val state = repository.pollQr(qr).state
            assertTrue("An unscanned code is waiting: $state", state == LoginState.WAITING)
        } finally { repository.cancelQr(qr) }
        assertEquals(LoginState.EXPIRED, repository.pollQr(qr).state)
    }

    private companion object { const val TAG = "LiltLiveLyrics" }
}
