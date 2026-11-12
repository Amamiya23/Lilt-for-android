package com.lilt.app.playback

import com.lilt.app.data.Song

/** The stable playback address and complete catalog identity, never a signed stream URL. */
data class ResolvedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artwork: String = "",
    val url: String,
    val song: Song? = null,
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
    /** SystemClock.elapsedRealtime() when [positionMs] was read, so a view can advance it smoothly. */
    val sampledAtMs: Long = 0,
    val source: String = "",
) {
    val current: ResolvedTrack?
        get() = tracks.getOrNull(currentIndex)

    /** The position now, extrapolated from the last sample while playing. */
    fun positionAt(elapsedRealtimeMs: Long): Long {
        if (!isPlaying || sampledAtMs == 0L) return positionMs
        val position = positionMs + (elapsedRealtimeMs - sampledAtMs).coerceAtLeast(0)
        return if (durationMs > 0) position.coerceAtMost(durationMs) else position
    }

    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}
