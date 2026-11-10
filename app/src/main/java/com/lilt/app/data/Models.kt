package com.lilt.app.data

data class Song(
    val mid: String,
    val id: Long,
    val title: String,
    val artist: String,
    val album: String = "",
    val cover: String = "",
    val durationMs: Long = 0,
    val mediaMid: String = "",
    val albumMid: String = "",
)

data class Playlist(
    val id: String,
    val title: String,
    val cover: String = "",
    val creator: String = "",
    val trackCount: Int = 0,
    val playCount: Long = 0,
)

data class HomeFeed(
    val songs: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val charts: List<Playlist> = emptyList(),
)

data class PlaylistDetail(val playlist: Playlist, val songs: List<Song>)
data class Lyrics(val lines: List<LyricLine> = emptyList())
data class LyricLine(val timeMs: Long, val text: String, val translation: String = "")
data class Profile(val name: String, val avatar: String, val userId: String)
data class Artist(val mid: String, val id: Long, val name: String, val cover: String = "")
data class Album(
    val mid: String,
    val id: Long,
    val title: String,
    val artist: String = "",
    val cover: String = "",
)

data class SearchResult(
    val songs: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val total: Int = 0,
)

data class LoginQr(
    val id: String,
    val provider: String = "qq",
    val imageBytes: ByteArray? = null,
    val imageUrl: String = "",
    val expiresAt: Long = 0,
)

enum class LoginState { WAITING, SCANNED, EXPIRED, SUCCESS, UNSUPPORTED }

data class LoginPoll(
    val state: LoginState,
    val message: String = "",
    val profile: Profile? = null,
)

class MusicApiException(
    message: String,
    val serviceCode: Int? = null,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)
