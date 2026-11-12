package com.lilt.app

import android.app.Application
import com.lilt.app.data.QqMusicRepository
import com.lilt.app.data.NetworkMonitor
import com.lilt.app.data.LyricCache
import com.lilt.app.playback.AudioCache

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiltApplication : Application() {
    val network by lazy { NetworkMonitor(this) }
    val repository by lazy { QqMusicRepository(this, network) }
    val lyricCache by lazy { LyricCache(this) }
    /** One instance per process: SimpleCache locks its directory. */
    val audioCache by lazy { AudioCache(this) }
}
