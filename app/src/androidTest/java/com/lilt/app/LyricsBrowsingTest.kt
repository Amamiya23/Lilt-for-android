package com.lilt.app

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilt.app.data.LyricLine
import com.lilt.app.ui.LyricsList
import com.lilt.app.ui.theme.LiltTheme
import com.lilt.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsBrowsingTest {
    @get:Rule val compose = createComposeRule()

    private val lines = List(40) { index ->
        LyricLine(timeMs = index * 5_000L, text = "歌词第 ${index.toString().padStart(2, '0')} 句")
    }
    private val activeIndex = mutableIntStateOf(3)
    private val position = mutableStateOf(lines[3].timeMs)
    private val songKey = mutableStateOf("lyrics-browsing-fixture")
    private val seeks = mutableListOf<Long>()
    private var acknowledgeSeekImmediately = true

    private fun showLyrics(lyrics: List<LyricLine> = lines) {
        compose.setContent {
            LiltTheme(ThemeMode.Dark) {
                LyricsList(
                    lines = lyrics,
                    activeIndex = activeIndex.intValue,
                    position = position,
                    onSeek = { time ->
                        seeks += time
                        if (acknowledgeSeekImmediately) {
                            position.value = time
                            activeIndex.intValue = lyrics.indexOfFirst { it.timeMs == time }
                        }
                    },
                    modifier = Modifier.width(320.dp).height(420.dp),
                    songKey = songKey.value,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun browseForward() {
        compose.onNodeWithTag("lyrics-list").performTouchInput {
            swipeUp(durationMillis = 1_000)
        }
        compose.waitForIdle()
        compose.onNodeWithText("回到当前").assertIsDisplayed()
    }

    /** A real visible reading target, independent of the list's internal scroll state. */
    private fun visibleReadingLine(): String {
        val viewport = compose.onNodeWithTag("lyrics-list").fetchSemanticsNode().boundsInRoot
        return compose.onAllNodesWithText("歌词第", substring = true).fetchSemanticsNodes()
            .filter { node ->
                val bounds = node.boundsInRoot
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom && bounds.height > 0f
            }
            .minBy { kotlin.math.abs(it.boundsInRoot.center.y - viewport.center.y) }
            .config[SemanticsProperties.Text].joinToString("") { it.text }
    }

    private fun advanceTo(index: Int) {
        compose.runOnIdle {
            position.value = lines[index].timeMs
            activeIndex.intValue = index
        }
        compose.waitForIdle()
    }

    private fun assertFirstLineStartsAtTop() {
        val viewport = compose.onNodeWithTag("lyrics-list").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText(lines.first().text).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val gap = first.top - viewport.top
        assertTrue(
            "The first lyric has empty space above it: $gap px",
            gap >= -1f && gap <= with(compose.density) { 8.dp.toPx() },
        )
    }

    @Test fun openingAtFirstLineAndFollowingSecondLineNeverAddsTopSpace() {
        activeIndex.intValue = 0
        position.value = lines.first().timeMs
        showLyrics()
        assertFirstLineStartsAtTop()
        val initial = compose.onNodeWithText(lines.first().text).fetchSemanticsNode().boundsInRoot

        advanceTo(1)
        assertFirstLineStartsAtTop()
        val secondLinePlaying = compose.onNodeWithText(lines.first().text).fetchSemanticsNode().boundsInRoot
        assertEquals("Following the second line moved the list away from its start", initial.top, secondLinePlaying.top, 1f)

        browseForward()
        compose.onNodeWithText("回到当前").performClick()
        compose.waitForIdle()
        assertFirstLineStartsAtTop()
        compose.onNodeWithText(lines[1].text).assertIsDisplayed()
    }

    @Test fun seekingFirstLineFromLaterPlaybackReturnsToTheNaturalListStart() {
        activeIndex.intValue = 20
        position.value = lines[20].timeMs
        showLyrics()
        // Browse back from a later verse with real drags, without changing playback.
        repeat(6) {
            compose.onNodeWithTag("lyrics-list").performTouchInput { swipeDown(durationMillis = 1_000) }
            compose.waitForIdle()
        }
        compose.onNodeWithText("回到当前").assertIsDisplayed()
        compose.onNodeWithText(lines.first().text).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(lines.first().timeMs), seeks) }
        compose.onNodeWithText("回到当前").assertDoesNotExist()
        assertFirstLineStartsAtTop()
        advanceTo(1)
        assertFirstLineStartsAtTop()
    }

    @Test fun browsingKeepsReadingPositionAcrossLineChangesUntilExplicitReturn() {
        showLyrics()
        browseForward()
        val readingLine = visibleReadingLine()
        val before = compose.onNodeWithText(readingLine).fetchSemanticsNode().boundsInRoot

        advanceTo(4)
        // Remaining still to read must not silently re-enable following after a timeout.
        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()
        val after = compose.onNodeWithText(readingLine).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Playback moved the user's reading position", before.top, after.top, 1f)
        assertTrue("Scrolling changed playback position", seeks.isEmpty())
        compose.onNodeWithText("回到当前").assertIsDisplayed().performClick()

        compose.onNodeWithText("回到当前").assertDoesNotExist()
        compose.onNodeWithText(lines[4].text).assertIsDisplayed()
        advanceTo(20)
        compose.onNodeWithText(lines[20].text).assertIsDisplayed()
    }

    @Test fun tappingBrowsedLineSeeksToItsTimestampAndRestoresFollowing() {
        showLyrics()
        browseForward()
        val readingLine = visibleReadingLine()
        val target = lines.single { it.text == readingLine }
        compose.onNodeWithText(readingLine).performClick()

        compose.runOnIdle { assertEquals(listOf(target.timeMs), seeks) }
        compose.onNodeWithText("回到当前").assertDoesNotExist()
        compose.onNodeWithText(target.text).assertIsDisplayed()
        advanceTo(25)
        compose.onNodeWithText(lines[25].text).assertIsDisplayed()
    }

    @Test fun pendingSeekKeepsDestinationWhileOldPlaybackAdvancesBeforeAcknowledgement() {
        showLyrics()
        browseForward()
        val readingLine = visibleReadingLine()
        val targetIndex = lines.indexOfFirst { it.text == readingLine }
        compose.runOnIdle { acknowledgeSeekImmediately = false }
        compose.onNodeWithText(readingLine).performClick()
        compose.waitForIdle()
        val destination = compose.onNodeWithText(readingLine).fetchSemanticsNode().boundsInRoot

        // A normal position update can arrive from the old playback location before seek completes.
        advanceTo(4)
        val awaitingAck = compose.onNodeWithText(readingLine).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("An old playback update pulled the pending seek backwards", destination.top, awaitingAck.top, 1f)
        compose.runOnIdle { assertEquals(listOf(lines[targetIndex].timeMs), seeks) }

        advanceTo(targetIndex)
        // Replaying the already-active line needs no index change to acknowledge the destination.
        compose.onNodeWithText(readingLine).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(lines[targetIndex].timeMs, lines[targetIndex].timeMs), seeks) }
        advanceTo(25)
        compose.onNodeWithText(lines[25].text).assertIsDisplayed()
    }

    @Test fun changingSongLeavesBrowsingAndStartsFollowingItsCurrentLine() {
        showLyrics()
        browseForward()
        compose.runOnIdle {
            songKey.value = "another-song"
            activeIndex.intValue = 20
            position.value = lines[20].timeMs
        }
        compose.waitForIdle()
        compose.onNodeWithText("回到当前").assertDoesNotExist()
        compose.onNodeWithText(lines[20].text).assertIsDisplayed()
        advanceTo(25)
        compose.onNodeWithText(lines[25].text).assertIsDisplayed()
    }

    @Test fun wrappedLineTranslationRemainsReachableAndSeeksToTheSameLine() {
        val translation = "这是一句换行歌词的译文"
        val wrapped = lines.mapIndexed { index, line ->
            if (index == 3) line.copy(text = "这一句很长的歌词需要自然换行".repeat(6), translation = translation)
            else line
        }
        showLyrics(wrapped)
        compose.onNodeWithText(translation, useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed().performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf(lines[3].timeMs), seeks) }
        compose.onNodeWithText("回到当前").assertDoesNotExist()
    }
}
