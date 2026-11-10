package com.lilt.app.playback

/** A playable URL resolved by the music provider. URLs are never persisted here. */
data class ResolvedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artwork: String = "",
    val url: String,
)

data class PlaybackSnapshot(
    val tracks: List<ResolvedTrack> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val error: String? = null,
) {
    val current: ResolvedTrack?
        get() = tracks.getOrNull(currentIndex)

    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}
