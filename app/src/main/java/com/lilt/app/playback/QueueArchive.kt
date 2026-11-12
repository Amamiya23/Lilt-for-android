package com.lilt.app.playback

import com.lilt.app.data.ModelJson
import org.json.JSONArray
import org.json.JSONObject

/** Pure persisted state: playback is deliberately never restored as playing. */
internal data class QueueArchive(
    val tracks: List<ResolvedTrack>,
    val index: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val source: String = "",
) {
    fun normalized(): QueueArchive = copy(
        index = if (tracks.isEmpty()) 0 else index.coerceIn(tracks.indices),
        positionMs = positionMs.coerceAtLeast(0),
        repeatMode = repeatMode.takeIf { it in 0..2 } ?: 0,
    )
}

internal object QueueArchiveJson {
    fun encode(archive: QueueArchive): String {
        val value = archive.normalized()
        return JSONObject().put("version", 1)
            .put("tracks", JSONArray(value.tracks.map { track ->
                require(stableAddress(track.url)) { "Only stable playback addresses may be archived" }
                JSONObject().put("id", track.id).put("title", track.title).put("artist", track.artist)
                    .put("album", track.album).put("artwork", track.artwork).put("address", track.url)
                    .apply { track.song?.let { put("song", ModelJson.encodeSong(it)) } }
            }))
            .put("index", value.index).put("positionMs", value.positionMs)
            .put("shuffle", value.shuffle).put("repeatMode", value.repeatMode).put("source", value.source)
            .toString()
    }

    fun decode(text: String): QueueArchive {
        val json = JSONObject(text)
        require(json.getInt("version") == 1) { "Unsupported queue archive version" }
        val tracks = json.getJSONArray("tracks")
        return QueueArchive((0 until tracks.length()).map { index ->
            val track = tracks.getJSONObject(index)
            val address = track.getString("address")
            require(stableAddress(address)) { "Invalid playback address" }
            ResolvedTrack(track.getString("id"), track.getString("title"), track.getString("artist"),
                track.optString("album"), track.optString("artwork"), address,
                track.optJSONObject("song")?.let(ModelJson::decodeSong))
        }, json.optInt("index"), json.optLong("positionMs"), json.optBoolean("shuffle"),
            json.optInt("repeatMode"), json.optString("source")).normalized()
    }

    private fun stableAddress(address: String): Boolean =
        address.startsWith("lilt://") || address.startsWith("content://") || address.startsWith("file:/")
}
