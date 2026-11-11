package com.lilt.app

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilt.app.ui.theme.ThemeMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BottomNavigationExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: LiltViewModel
    private lateinit var originalTheme: ThemeMode

    @Before fun setUp() {
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity)[LiltViewModel::class.java]
            originalTheme = vm.state.value.theme
            vm.enablePreview()
            vm.setTheme(ThemeMode.Light)
            vm.navigate(Destination.Home)
        }
        compose.waitForIdle()
    }

    @After fun restoreTheme() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { vm.setTheme(originalTheme) }
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and isSelectable())

    /** A pale green selection must not turn into a dark grey pill during a tab change. */
    @Test fun selectionDoesNotFlashGreyDuringTheTransition() {
        compose.mainClock.autoAdvance = false
        tab("搜索").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(60)
        for (label in listOf("首页", "搜索")) {
            val pixels = tab(label).captureToImage().toPixelMap()
            // Beside the glyph, inside the selection pill, away from its rounded ends.
            val x = pixels.width / 2 - with(compose.density) { 20.dp.roundToPx() }
            val y = with(compose.density) { 18.dp.roundToPx() }
            val color = pixels[x, y]
            assertTrue("$label selection flashed dark: $color", minOf(color.red, color.green, color.blue) > .9f)
        }
        tab("搜索").assertIsSelected()
    }

    @Test fun switchingTabsPreservesTheHomeScrollPosition() {
        val feed = compose.onNodeWithTag("home-feed")
        feed.performTouchInput { swipeUp() }
        compose.waitForIdle()
        val before = feed.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("The feed did not scroll", before > 0f)
        tab("搜索").performClick()
        tab("首页").performClick()
        compose.waitForIdle()
        val after = compose.onNodeWithTag("home-feed").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals("Returning home reset the feed to the top", before, after, .001f)
    }
}
