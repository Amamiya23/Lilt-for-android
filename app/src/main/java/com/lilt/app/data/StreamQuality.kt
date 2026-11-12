package com.lilt.app.data

/** Playback must not label a lower quality response with a higher quality cache key. */
internal object StreamQuality {
    fun candidates(quality: String, signedIn: Boolean, strict: Boolean): List<Pair<String, String>> {
        if (strict) return listOf(when (quality) {
            "lossless", "hires" -> "F000" to "flac"
            "hq", "higher", "exhigh" -> "M800" to "mp3"
            else -> "M500" to "mp3"
        })
        // Preserve the existing download behavior, which accepts the best available lower quality.
        return buildList {
            if (signedIn && quality in listOf("lossless", "hires")) add("F000" to "flac")
            if (signedIn && quality in listOf("hq", "higher", "exhigh", "lossless", "hires")) add("M800" to "mp3")
            add("M500" to "mp3")
        }
    }
}
