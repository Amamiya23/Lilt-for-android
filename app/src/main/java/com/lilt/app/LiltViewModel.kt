package com.lilt.app

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lilt.app.data.*
import com.lilt.app.playback.*
import com.lilt.app.ui.theme.ThemeMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

enum class Destination { Home, Search, Library, Settings, Login, Playlist, Playlists, Local }
enum class PlayerPanel { Artwork, Lyrics, Queue }

data class AppState(
    val destination: Destination = Destination.Home,
    val topLevel: Destination = Destination.Home,
    val home: HomeFeed = HomeFeed(),
    val homeLoading: Boolean = true,
    val homeError: String? = null,
    val query: String = "",
    val searchType: Int = 0,
    val search: SearchResult = SearchResult(),
    val searching: Boolean = false,
    val searched: Boolean = false,
    val searchError: String? = null,
    val searchHistory: List<String> = emptyList(),
    val detail: PlaylistDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val collectionTitle: String = "",
    val collectionPlaylists: List<Playlist> = emptyList(),
    val library: List<Playlist> = emptyList(),
    val libraryLoading: Boolean = false,
    val libraryError: String? = null,
    val playerOpen: Boolean = false,
    val playerPanel: PlayerPanel = PlayerPanel.Artwork,
    val lyrics: Lyrics = Lyrics(),
    val lyricsLoading: Boolean = false,
    val lyricsError: String? = null,
    val liked: Set<String> = emptySet(),
    val likeBusy: Boolean = false,
    val qr: LoginQr? = null,
    val qrBusy: Boolean = false,
    val qrMessage: String = "",
    val qrState: LoginState = LoginState.WAITING,
    val provider: String = "qq",
    val theme: ThemeMode = ThemeMode.System,
    val quality: String = "standard",
    val recent: List<Song> = emptyList(),
    val local: List<Song> = emptyList(),
    val preview: Boolean = false,
)

class LiltViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as LiltApplication).repository
    val playback = PlaybackConnection(application)
    val player = playback.state
    val profile = repository.profile
    private val preferences = application.getSharedPreferences("lilt_settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AppState(
        theme = ThemeMode.entries.firstOrNull { it.name == preferences.getString("theme", "") } ?: ThemeMode.System,
        quality = preferences.getString("quality", "standard") ?: "standard",
        recent = readSongs("recent"), local = readSongs("local"),
        searchHistory = readStrings("search_history"),
    ))
    val state = mutable.asStateFlow()
    private val songBook = mutableMapOf<String, Song>()
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var qrJob: Job? = null
    private var lyricJob: Job? = null
    private val backStack = mutableListOf<Destination>()
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = events.asSharedFlow()

    init {
        songBook.putAll((state.value.local + state.value.recent).associateBy { it.mid })
        refreshHome()
        viewModelScope.launch { if (profile.value != null) runCatching { repository.refreshProfile() } }
        viewModelScope.launch { runCatching { playback.connect() }.onFailure { notify("播放器连接失败，请重试") } }
        viewModelScope.launch {
            player.map { it.current?.id }.distinctUntilChanged().collect { mid ->
                if (mid != null) {
                    songBook[mid]?.let { song ->
                        val recent = (listOf(song) + state.value.recent).distinctBy { it.mid }.take(100)
                        mutable.update { it.copy(recent = recent) }
                        saveSongs("recent", recent)
                        loadLyrics(song)
                    }
                }
            }
        }
        viewModelScope.launch {
            profile.collect { user -> if (user != null) { refreshLibrary(); refreshHome() } }
        }
        viewModelScope.launch {
            combine(player.map { it.current?.id }.distinctUntilChanged(), profile) { mid, user -> mid to user }
                .collectLatest { (mid, user) ->
                    if (mid != null && user != null && !mid.startsWith("content:")) {
                        mutable.update { it.copy(likeBusy = true) }
                        try {
                            val liked = repository.isLiked(mid)
                            mutable.update { it.copy(liked = if (liked) it.liked + mid else it.liked - mid) }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                        } finally { mutable.update { it.copy(likeBusy = false) } }
                    }
                }
        }
    }

    fun notify(message: String) { events.tryEmit(message) }
    fun navigate(destination: Destination) {
        go(destination)
        if (destination == Destination.Library) refreshLibrary()
        if (destination == Destination.Login) refreshQr()
    }
    private fun go(destination: Destination) {
        val current = state.value.destination
        if (current == destination) return
        if (destination in listOf(Destination.Home, Destination.Search, Destination.Library)) {
            backStack.clear()
            if (destination != Destination.Home) backStack.add(Destination.Home)
        } else backStack.add(current)
        if (current == Destination.Login) cancelQr()
        mutable.update { it.copy(destination = destination,
            topLevel = if (destination in listOf(Destination.Home, Destination.Search, Destination.Library)) destination else it.topLevel) }
    }
    fun back() {
        if (state.value.playerOpen) {
            if (state.value.playerPanel != PlayerPanel.Artwork) setPlayerPanel(PlayerPanel.Artwork)
            else showPlayer(false)
        } else {
            if (state.value.destination == Destination.Login) cancelQr()
            val destination = backStack.removeLastOrNull() ?: Destination.Home
            mutable.update { it.copy(destination = destination,
                topLevel = if (destination in listOf(Destination.Home, Destination.Search, Destination.Library)) destination else it.topLevel) }
        }
    }
    fun showPlayer(open: Boolean) { mutable.update { it.copy(playerOpen = open, playerPanel = PlayerPanel.Artwork) } }
    fun setPlayerPanel(panel: PlayerPanel) { mutable.update { it.copy(playerPanel = panel) } }
    fun setTheme(mode: ThemeMode) {
        preferences.edit().putString("theme", mode.name).apply()
        mutable.update { it.copy(theme = mode) }
    }
    fun setQuality(quality: String) {
        preferences.edit().putString("quality", quality).apply()
        mutable.update { it.copy(quality = quality) }
    }
    fun refreshHome() {
        if (state.value.preview) return
        viewModelScope.launch {
            mutable.update { it.copy(homeLoading = true, homeError = null) }
            try {
                val home = repository.home()
                songBook.putAll(home.songs.associateBy { it.mid })
                mutable.update { if (it.preview) it else it.copy(home = home, homeLoading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { if (it.preview) it else it.copy(homeLoading = false, homeError = readable(error)) }
            }
        }
    }
    fun updateQuery(query: String) {
        mutable.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            mutable.update { it.copy(search = SearchResult(), searching = false, searched = false, searchError = null) }
        } else searchJob = viewModelScope.launch { delay(400); performSearch() }
    }
    fun search(query: String = state.value.query, type: Int = state.value.searchType) {
        go(Destination.Search)
        mutable.update { it.copy(query = query, searchType = type, destination = Destination.Search) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch { performSearch() }
    }
    fun beginSearch(type: Int) {
        searchJob?.cancel()
        go(Destination.Search)
        mutable.update { it.copy(query = "", searchType = type, search = SearchResult(),
            searching = false, searched = false, searchError = null) }
    }
    private suspend fun performSearch() {
        val query = state.value.query.trim()
        val type = state.value.searchType
        if (query.isEmpty()) return
        mutable.update { it.copy(searching = true, searchError = null) }
        try {
            val result = if (state.value.preview) SearchResult(songs = PreviewContent.songs, total = 8)
                else repository.search(query, if (type == 100) 0 else type)
            songBook.putAll(result.songs.associateBy { it.mid })
            val history = (listOf(query) + state.value.searchHistory).distinct().take(12)
            preferences.edit().putString("search_history", JSONArray(history).toString()).apply()
            mutable.update { it.copy(search = result, searching = false, searched = true, searchHistory = history) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.update { it.copy(searching = false, searched = true, searchError = readable(error)) }
        }
    }
    fun clearSearchHistory() {
        preferences.edit().remove("search_history").apply()
        mutable.update { it.copy(searchHistory = emptyList()) }
    }
    fun openPlaylist(playlist: Playlist) {
        if (playlist.id == "daily") { daily(); return }
        detailJob?.cancel()
        go(Destination.Playlist)
        mutable.update { it.copy(destination = Destination.Playlist, detail = PlaylistDetail(playlist, emptyList()), detailLoading = true, detailError = null) }
        detailJob = viewModelScope.launch {
            try {
                val detail = if (state.value.preview) PlaylistDetail(playlist, PreviewContent.songs)
                    else repository.playlist(playlist.id)
                songBook.putAll(detail.songs.associateBy { it.mid })
                mutable.update { it.copy(detail = detail, detailLoading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(detailLoading = false, detailError = readable(error)) }
            }
        }
    }
    fun openSongs(title: String, songs: List<Song>) {
        go(Destination.Playlist)
        songBook.putAll(songs.associateBy { it.mid })
        mutable.update { it.copy(destination = Destination.Playlist,
            detail = PlaylistDetail(Playlist("local-list", title, songs.firstOrNull()?.cover.orEmpty(), trackCount = songs.size), songs),
            detailLoading = false, detailError = null) }
    }
    fun openPlaylists(title: String, playlists: List<Playlist>) {
        go(Destination.Playlists)
        mutable.update { it.copy(collectionTitle = title, collectionPlaylists = playlists) }
    }
    fun daily() {
        if (profile.value == null && !state.value.preview) navigate(Destination.Login)
        else if (state.value.preview) openSongs("每日推荐", state.value.home.songs)
        else {
            go(Destination.Playlist)
            mutable.update { it.copy(destination = Destination.Playlist,
                detail = PlaylistDetail(Playlist("daily", "每日推荐"), emptyList()), detailLoading = true, detailError = null) }
            detailJob?.cancel()
            detailJob = viewModelScope.launch {
                try {
                    val songs = repository.dailySongs()
                    openSongs("每日推荐", songs)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutable.update { it.copy(detailLoading = false, detailError = readable(error)) }
                }
            }
        }
    }
    fun play(songs: List<Song>, index: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        if (state.value.preview) { notify("设计预览不播放示例音频"); showPlayer(true); return }
        songBook.putAll(songs.associateBy { it.mid })
        playback.setQueue(songs.map { song ->
            val uri = if (song.mid.startsWith("content:")) song.mid else Uri.Builder()
                .scheme("lilt").authority("song").appendPath(song.mid)
                .appendQueryParameter("id", song.id.toString())
                .appendQueryParameter("title", song.title)
                .appendQueryParameter("artist", song.artist)
                .appendQueryParameter("mediaMid", song.mediaMid)
                .appendQueryParameter("albumMid", song.albumMid).build().toString()
            ResolvedTrack(song.mid, song.title, song.artist, song.album, song.cover, uri)
        }, index)
        playback.setShuffle(shuffle)
    }
    fun currentSong(): Song? = songBook[player.value.current?.id] ?: if (state.value.preview) PreviewContent.songs.first() else null
    fun toggleLike(song: Song? = currentSong()) {
        song ?: return
        if (profile.value == null) { navigate(Destination.Login); showPlayer(false); return }
        if (state.value.likeBusy) return
        viewModelScope.launch {
            mutable.update { it.copy(likeBusy = true) }
            try {
                val liked = !repository.isLiked(song.mid)
                repository.like(song, liked)
                mutable.update { it.copy(liked = if (liked) it.liked + song.mid else it.liked - song.mid) }
                notify(if (liked) "已添加到我喜欢" else "已取消喜欢")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notify(readable(error))
            } finally { mutable.update { it.copy(likeBusy = false) } }
        }
    }
    fun refreshLibrary() {
        if (profile.value == null || state.value.preview) return
        viewModelScope.launch {
            mutable.update { it.copy(libraryLoading = true, libraryError = null) }
            try {
                val library = repository.userPlaylists()
                mutable.update { it.copy(library = library, libraryLoading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(libraryLoading = false, libraryError = readable(error)) }
            }
        }
    }
    fun loadLyrics(song: Song? = currentSong()) {
        song ?: return
        lyricJob?.cancel()
        if (song.mid.startsWith("content:")) {
            mutable.update { it.copy(lyrics = Lyrics(), lyricsLoading = false, lyricsError = null) }
            return
        }
        lyricJob = viewModelScope.launch {
            mutable.update { it.copy(lyrics = Lyrics(), lyricsLoading = true, lyricsError = null) }
            try {
                val result = if (state.value.preview) PreviewContent.lyrics else repository.lyrics(song)
                mutable.update { it.copy(lyrics = result, lyricsLoading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(lyricsLoading = false, lyricsError = readable(error)) }
            }
        }
    }
    fun refreshQr(provider: String = state.value.provider) {
        cancelQr()
        mutable.update { it.copy(provider = provider, qr = null, qrBusy = true, qrMessage = "正在获取二维码", qrState = LoginState.WAITING) }
        qrJob = viewModelScope.launch {
            try {
                val qr = repository.createQr(provider)
                mutable.update { it.copy(qr = qr, qrBusy = false, qrMessage = "请使用手机${if (provider == "qq") "QQ" else "微信"}扫码") }
                while (isActive) {
                    delay(1800)
                    val result = repository.pollQr(qr)
                    mutable.update { it.copy(qrState = result.state, qrMessage = result.message.ifBlank { "等待扫码 · 二维码 2 分钟内有效" }) }
                    if (result.state == LoginState.SUCCESS) {
                        viewModelScope.launch { runCatching { repository.refreshProfile() } }
                        go(Destination.Library)
                        notify("登录成功")
                        return@launch
                    }
                    if (result.state == LoginState.EXPIRED || result.state == LoginState.UNSUPPORTED) return@launch
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(qrBusy = false, qrMessage = readable(error), qrState = LoginState.EXPIRED) }
            }
        }
    }
    private fun cancelQr() {
        qrJob?.cancel()
        state.value.qr?.let(repository::cancelQr)
        qrJob = null
    }
    fun logout() {
        repository.logout()
        mutable.update { it.copy(library = emptyList(), liked = emptySet()) }
        notify("已退出登录")
        refreshHome()
    }
    fun addLocal(uris: List<Uri>) {
        val context = getApplication<Application>()
        val songs = uris.map { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            var title = uri.lastPathSegment.orEmpty()
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) title = it.getString(0)
            }
            Song(uri.toString(), uri.hashCode().toLong(), title.substringBeforeLast("."), "本地音乐")
        }
        val local = (state.value.local + songs).distinctBy { it.mid }
        songBook.putAll(local.associateBy { it.mid })
        saveSongs("local", local)
        mutable.update { it.copy(local = local) }
    }
    fun download(song: Song) {
        if (state.value.preview) { notify("设计预览不下载示例音频"); return }
        viewModelScope.launch {
            try {
                val url = repository.songUrl(song, state.value.quality)
                val filename = "${song.artist} - ${song.title}".replace(Regex("[\\\\/:*?\"<>|]"), "_") +
                    if (url.substringBefore("?").endsWith(".flac")) ".flac" else ".mp3"
                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle(song.title).setDescription(song.artist)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "Lilt/$filename")
                    .addRequestHeader("Referer", "https://y.qq.com/")
                getApplication<Application>().getSystemService(DownloadManager::class.java).enqueue(request)
                notify("已开始下载，可在系统下载中查看")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notify(readable(error))
            }
        }
    }
    fun enablePreview() {
        if (!BuildConfig.DEBUG) return
        mutable.update { it.copy(preview = true, home = PreviewContent.home, homeLoading = false, homeError = null,
            lyrics = PreviewContent.lyrics, recent = PreviewContent.songs.take(3)) }
        songBook.putAll(PreviewContent.songs.associateBy { it.mid })
    }
    private fun readStrings(key: String): List<String> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())
    private fun readSongs(key: String): List<Song> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]"))
        (0 until array.length()).map {
            val obj = array.getJSONObject(it)
            Song(obj.getString("mid"), obj.optLong("id"), obj.getString("title"), obj.optString("artist"),
                obj.optString("album"), obj.optString("cover"), obj.optLong("durationMs"), obj.optString("mediaMid"), obj.optString("albumMid"))
        }
    }.getOrDefault(emptyList())
    private fun saveSongs(key: String, songs: List<Song>) {
        val array = JSONArray()
        songs.forEach { song -> array.put(JSONObject().put("mid", song.mid).put("id", song.id).put("title", song.title)
            .put("artist", song.artist).put("album", song.album).put("cover", song.cover).put("durationMs", song.durationMs)
            .put("mediaMid", song.mediaMid).put("albumMid", song.albumMid)) }
        preferences.edit().putString(key, array.toString()).apply()
    }
    private fun readable(error: Throwable) = error.message?.takeIf { it.length < 180 } ?: "连接失败，请检查网络后重试"
    override fun onCleared() { playback.close(); super.onCleared() }
}

private object PreviewContent {
    private fun cover(index: Int) = "file:///android_asset/design/generated${if (index == 0) "" else "-$index"}.png"
    val songs = listOf(
        Song("preview-1", 1, "晴天", "周杰伦", "叶惠美", cover(4), 269000),
        Song("preview-2", 2, "夜曲", "周杰伦", "十一月的萧邦", cover(3), 226000),
        Song("preview-3", 3, "光年之外", "G.E.M.邓紫棋", "光年之外", cover(6), 235000),
        Song("preview-4", 4, "红豆", "王菲", "唱游", cover(0), 255000),
        Song("preview-5", 5, "起风了", "买辣椒也用券", "起风了", cover(1), 325000),
        Song("preview-6", 6, "稻香", "周杰伦", "魔杰座", cover(7), 223000),
        Song("preview-7", 7, "若月亮没来", "王宇宙Leto", "", cover(5), 230000),
        Song("preview-8", 8, "孤勇者", "陈奕迅", "", cover(2), 256000),
    )
    val home = HomeFeed(songs, listOf(
        Playlist("preview-list-1", "深夜城市漫游：雨后霓虹与慢摇", cover(3), "QQ音乐官方", 48, 120000000),
        Playlist("preview-list-2", "冬日安静时光", cover(7), "音乐生活", 31, 820000),
        Playlist("preview-list-3", "华语经典 · 慢慢听", cover(0), "QQ音乐官方", 60, 12300000),
    ), listOf(
        Playlist("preview-chart-1", "飙升榜", cover(6), trackCount = 100),
        Playlist("preview-chart-2", "热歌榜", cover(5), trackCount = 100),
        Playlist("preview-chart-3", "新歌榜", cover(4), trackCount = 100),
    ))
    val lyrics = Lyrics(listOf(
        LyricLine(0, "刮风这天 我试过握着你手"),
        LyricLine(20000, "但偏偏 雨渐渐\n大到我看你不见"),
        LyricLine(40000, "还要多久 我才能在你身边"),
        LyricLine(65000, "等到放晴的那天\n也许我会比较好一点"),
        LyricLine(90000, "从前从前 有个人爱你很久"),
        LyricLine(120000, "但偏偏 风渐渐\n把距离吹得好远"),
        LyricLine(150000, "好不容易 又能再多爱一天"),
    ))
}
