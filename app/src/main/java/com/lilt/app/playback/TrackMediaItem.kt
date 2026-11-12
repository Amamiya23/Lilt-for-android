package com.lilt.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.lilt.app.data.ModelJson
import com.lilt.app.data.Song
import org.json.JSONObject

/** Catalog identity travels through the session, including after an Activity reconnects. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object TrackMediaItem {
    private const val SONG = "com.lilt.song"
    private const val SOURCE = "com.lilt.queueSource"
    private const val ADDRESS = "com.lilt.playbackAddress"

    fun toMediaItem(track: ResolvedTrack, source: String = ""): MediaItem = MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(track.url)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .apply { if (track.artwork.isNotBlank()) setArtworkUri(Uri.parse(track.artwork)) }
            .setExtras(Bundle().apply {
                track.song?.let { putString(SONG, ModelJson.encodeSong(it).toString()) }
                putString(SOURCE, source)
                // MediaController may omit localConfiguration. This is a stable lilt/local URI.
                putString(ADDRESS, track.url)
            })
            .build())
        .build()

    fun song(item: MediaItem): Song? = item.mediaMetadata.extras?.getString(SONG)?.let {
        runCatching { ModelJson.decodeSong(JSONObject(it)) }.getOrNull()
    }

    fun source(item: MediaItem): String = item.mediaMetadata.extras?.getString(SOURCE).orEmpty()

    fun fromMediaItem(item: MediaItem): ResolvedTrack {
        val metadata = item.mediaMetadata
        return ResolvedTrack(
            item.mediaId, metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(),
            metadata.albumTitle?.toString().orEmpty(), metadata.artworkUri?.toString().orEmpty(),
            item.localConfiguration?.uri?.toString()
                ?: metadata.extras?.getString(ADDRESS).orEmpty(), song(item),
        )
    }
}
