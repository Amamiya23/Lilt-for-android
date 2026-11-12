package com.lilt.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Local storage format, independent of QQ's changing response shapes. */
object ModelJson {
    fun encodeSong(song: Song): JSONObject = JSONObject()
        .put("mid", song.mid).put("id", song.id).put("title", song.title).put("artist", song.artist)
        .put("album", song.album).put("cover", song.cover).put("durationMs", song.durationMs)
        .put("mediaMid", song.mediaMid).put("albumMid", song.albumMid)
        .put("artists", JSONArray(song.artists.map(::encodeArtist)))

    fun decodeSong(json: JSONObject): Song = Song(
        json.getString("mid"), json.getLong("id"), json.getString("title"), json.getString("artist"),
        json.optString("album"), json.optString("cover"), json.optLong("durationMs"),
        json.optString("mediaMid"), json.optString("albumMid"),
        json.optJSONArray("artists")?.objects()?.map(::decodeArtist).orEmpty(),
    )

    fun encodeSongs(songs: List<Song>): JSONArray = JSONArray(songs.map(::encodeSong))
    fun decodeSongs(json: JSONArray): List<Song> = json.objects().map(::decodeSong)

    fun encodeArtist(artist: Artist): JSONObject = JSONObject()
        .put("mid", artist.mid).put("id", artist.id).put("name", artist.name).put("cover", artist.cover)
        .put("songCount", artist.songCount).put("albumCount", artist.albumCount)

    fun decodeArtist(json: JSONObject): Artist = Artist(
        json.getString("mid"), json.getLong("id"), json.getString("name"), json.optString("cover"),
        json.optInt("songCount"), json.optInt("albumCount"),
    )

    fun encodePlaylist(playlist: Playlist): JSONObject = JSONObject()
        .put("id", playlist.id).put("title", playlist.title).put("cover", playlist.cover)
        .put("creator", playlist.creator).put("trackCount", playlist.trackCount).put("playCount", playlist.playCount)
        .put("dirId", playlist.dirId).put("albumId", playlist.albumId)
        .put("preview", JSONArray(playlist.preview.map { JSONArray(listOf(it.first, it.second)) }))

    fun decodePlaylist(json: JSONObject): Playlist = Playlist(
        json.getString("id"), json.getString("title"), json.optString("cover"), json.optString("creator"),
        json.optInt("trackCount"), json.optLong("playCount"),
        json.optJSONArray("preview")?.let { array -> (0 until array.length()).map { index ->
            val pair = array.getJSONArray(index)
            pair.getString(0) to pair.getString(1)
        } }.orEmpty(), json.optInt("dirId"), json.optLong("albumId"),
    )

    fun encodePlaylists(playlists: List<Playlist>): JSONArray = JSONArray(playlists.map(::encodePlaylist))
    fun decodePlaylists(json: JSONArray): List<Playlist> = json.objects().map(::decodePlaylist)

    fun encodeDetail(detail: PlaylistDetail): JSONObject = JSONObject()
        .put("playlist", encodePlaylist(detail.playlist)).put("songs", encodeSongs(detail.songs))

    fun decodeDetail(json: JSONObject): PlaylistDetail = PlaylistDetail(
        decodePlaylist(json.getJSONObject("playlist")), decodeSongs(json.getJSONArray("songs")),
    )

    fun encodeHome(home: HomeFeed): JSONObject = JSONObject()
        .put("songs", encodeSongs(home.songs)).put("playlists", encodePlaylists(home.playlists))
        .put("charts", encodePlaylists(home.charts)).put("shelves", JSONArray(home.shelves.map { shelf ->
            JSONObject().put("title", shelf.title).put("songs", encodeSongs(shelf.songs))
        }))

    fun decodeHome(json: JSONObject): HomeFeed = HomeFeed(
        decodeSongs(json.getJSONArray("songs")), decodePlaylists(json.getJSONArray("playlists")),
        decodePlaylists(json.getJSONArray("charts")), json.getJSONArray("shelves").objects().map {
            SongShelf(it.getString("title"), decodeSongs(it.getJSONArray("songs")))
        },
    )

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
}
