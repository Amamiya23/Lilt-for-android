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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lilt.app.ui.LiltApp
import com.lilt.app.ui.theme.LiltTheme
import com.lilt.app.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    private val model: LiltViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LiltViewModel(application, BuildConfig.DEBUG && intent.getBooleanExtra("design_preview", false)) as T
        }
    }
    override fun onStart() {
        super.onStart()
        model.onAppForegrounded()
    }

    override fun onStop() {
        model.onAppBackgrounded()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (BuildConfig.DEBUG && intent.getBooleanExtra("design_preview", false)) {
            model.enablePreview()
            when (intent.getStringExtra("preview_screen")) {
                "player" -> { model.showPlayer(true); model.setPlayerPanel(PlayerPanel.Artwork) }
                "lyrics" -> { model.showPlayer(true); model.setPlayerPanel(PlayerPanel.Lyrics) }
                "queue" -> { model.showPlayer(true); model.setPlayerPanel(PlayerPanel.Queue) }
                "login" -> model.navigate(Destination.Login)
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
