package com.lilt.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilt.app.data.Song
import com.lilt.app.ui.LocalSongAvailability
import com.lilt.app.ui.SongRow
import com.lilt.app.ui.theme.LiltTheme
import com.lilt.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineSongRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unavailableSongDisablesPlaybackAndKeepsMoreMenuEnabled() {
        val song = Song("row-fixture", 1, "Fixture", "Artist")
        var plays = 0
        var menus = 0
        compose.setContent {
            LiltTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalSongAvailability provides { false }) {
                    SongRow(song, { plays++ }, { menus++ })
                }
            }
        }
        compose.onNodeWithTag("song-row-${song.mid}").assertIsNotEnabled()
        compose.onNodeWithContentDescription("${song.title}的更多操作").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(0, plays)
            assertEquals(1, menus)
        }
    }
}
