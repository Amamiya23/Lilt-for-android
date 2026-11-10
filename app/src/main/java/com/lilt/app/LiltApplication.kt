package com.lilt.app

import android.app.Application
import com.lilt.app.data.QqMusicRepository
import com.lilt.app.playback.AudioCache

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiltApplication : Application() {
    val repository by lazy { QqMusicRepository(this) }
    /** One instance per process: SimpleCache locks its directory. */
    val audioCache by lazy { AudioCache(this) }
}
