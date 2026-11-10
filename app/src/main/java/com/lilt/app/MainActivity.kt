package com.lilt.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilt.app.ui.LiltApp
import com.lilt.app.ui.theme.LiltTheme
import com.lilt.app.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    private val model: LiltViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (BuildConfig.DEBUG && intent.getBooleanExtra("design_preview", false)) {
            model.enablePreview()
            when (intent.getStringExtra("preview_screen")) {
                "player" -> model.showPlayer(true)
                "lyrics" -> { model.showPlayer(true); model.setPlayerPanel(PlayerPanel.Lyrics) }
                "search" -> model.search("周杰伦")
                "library" -> model.navigate(Destination.Library)
                "settings" -> model.navigate(Destination.Settings)
                "playlist" -> model.openPlaylist(model.state.value.home.playlists.first())
            }
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val dark = state.theme == ThemeMode.Dark || state.theme == ThemeMode.System && isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark && !state.playerOpen
                    isAppearanceLightNavigationBars = !dark && !state.playerOpen
                }
            }
            LiltTheme(state.theme) { LiltApp(model) }
        }
    }
}
