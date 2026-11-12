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
    /** Each credited artist with the MID their page needs; [artist] is the joined display text. */
    val artists: List<Artist> = emptyList(),
)

data class Playlist(
    val id: String,
    val title: String,
    val cover: String = "",
    val creator: String = "",
    val trackCount: Int = 0,
    val playCount: Long = 0,
    /** A chart's top songs as (title, artist), shown on the home card. */
    val preview: List<Pair<String, String>> = emptyList(),
    /** The account-local directory of a playlist this account created (201 is 我喜欢); 0 for collected lists. */
    val dirId: Int = 0,
    /** Numeric identity retained when an album order has no MID. */
    val albumId: Long = 0,
)

/** A 歌单广场 category, as QQ numbers them; [RECOMMENDED] is the square's own feed. */
data class SquareTag(val id: Long, val name: String) {
    companion object { val RECOMMENDED = SquareTag(-1, "推荐") }
}
data class SquareTagGroup(val name: String, val tags: List<SquareTag>)
/** [next] continues the list; null at its end. */
data class SquarePage(val playlists: List<Playlist>, val next: String?)
/** A trending search; [fresh] marks QQ's 新歌. */
data class HotSearch(val query: String, val fresh: Boolean)

data class HomeFeed(
    val songs: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val charts: List<Playlist> = emptyList(),
    /** Personalized song shelves titled by QQ, as 「Mark」，这是你的幸运好歌. */
    val shelves: List<SongShelf> = emptyList(),
)

data class SongShelf(val title: String, val songs: List<Song>)

enum class VipTier(val label: String) { Super("超级会员"), Green("绿钻会员"), Free("免费账号"), Unknown("") }
data class Membership(val tier: VipTier, val expiry: String = "")

data class PlaylistDetail(val playlist: Playlist, val songs: List<Song>)
data class Lyrics(val lines: List<LyricLine> = emptyList()) {
    /** True when QQ supplied real word times (QRC), not just line times. */
    val wordTimed: Boolean get() = lines.isNotEmpty() && lines.all { it.words.isNotEmpty() }
}

/** [endMs] is when the line stops being sung; [words] are only present for QRC. */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String = "",
    val endMs: Long = timeMs,
    val words: List<LyricWord> = emptyList(),
)

/** A QRC unit with absolute start and end times in milliseconds. */
data class LyricWord(val text: String, val startMs: Long, val endMs: Long) {
    /** How much of the word has been sung at [positionMs], following seeks and pauses directly. */
    fun progress(positionMs: Long): Float = when {
        positionMs < startMs -> 0f
        positionMs >= endMs -> 1f
        else -> (positionMs - startMs).toFloat() / (endMs - startMs)
    }
}
data class Profile(val name: String, val avatar: String, val userId: String, val loginLabel: String = "")
data class Artist(val mid: String, val id: Long, val name: String, val cover: String = "",
                  val songCount: Int = 0, val albumCount: Int = 0)
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

enum class LoginState { WAITING, SCANNED, EXPIRED, SUCCESS }

data class LoginPoll(
    val state: LoginState,
    val message: String = "",
    val profile: Profile? = null,
)

class MusicApiException(
    message: String,
    val serviceCode: Int? = null,
    cause: Throwable? = null,
    /** The service refused the account credential itself, as opposed to a network or data failure. */
    val loginRejected: Boolean = false,
) : java.io.IOException(message, cause)
