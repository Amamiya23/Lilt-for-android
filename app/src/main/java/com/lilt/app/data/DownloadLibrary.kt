package com.lilt.app.data

import android.app.DownloadManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bridges app-owned system downloads into the local library, including downloads from older versions. */
internal class DownloadLibrary(context: Context) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val metadata = context.getSharedPreferences("lilt_downloads", Context.MODE_PRIVATE)

    fun remember(downloadId: Long, song: Song) {
        val json = JSONObject().put("title", song.title).put("artist", song.artist)
            .put("album", song.album).put("cover", song.cover).put("durationMs", song.durationMs)
            .put("albumMid", song.albumMid).put("artists", JSONArray(song.artists.map {
                JSONObject().put("mid", it.mid).put("id", it.id).put("name", it.name).put("cover", it.cover)
            }))
        metadata.edit().putString(downloadId.toString(), json.toString()).apply()
    }

    /** Querying DownloadManager recovers completion even if the app was closed during the download. */
    fun completed(): List<Song> = buildList {
        manager.query(DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL))?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val titleColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = manager.getUriForDownloadedFile(id) ?: continue
                val saved = runCatching { JSONObject(metadata.getString(id.toString(), "{}").orEmpty()) }
                    .getOrElse { JSONObject() }
                val artists = saved.optJSONArray("artists")?.let { list ->
                    (0 until list.length()).mapNotNull(list::optJSONObject).map {
                        Artist(it.optString("mid"), it.optLong("id"), it.optString("name"), it.optString("cover"))
                    }
                }.orEmpty()
                add(Song(
                    mid = uri.toString(), id = id,
                    title = saved.optString("title").ifBlank { cursor.getString(titleColumn).orEmpty() },
                    artist = saved.optString("artist").ifBlank { cursor.getString(artistColumn).orEmpty().ifBlank { "本地音乐" } },
                    album = saved.optString("album"), cover = saved.optString("cover"),
                    durationMs = saved.optLong("durationMs"), albumMid = saved.optString("albumMid"), artists = artists,
                ))
            }
        }
    }
}
