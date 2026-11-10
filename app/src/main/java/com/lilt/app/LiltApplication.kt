package com.lilt.app

import android.app.Application
import com.lilt.app.data.QqMusicRepository

class LiltApplication : Application() {
    val repository by lazy { QqMusicRepository(this) }
}
