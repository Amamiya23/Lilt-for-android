package com.lilt.app

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lilt.app.data.*
import com.lilt.app.playback.*
import com.lilt.app.ui.theme.ThemeMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

enum class Destination { Home, Search, Library, Settings, Login, Playlist, Playlists, Albums, Purchased, Local }
enum class PlayerPanel { Artwork, Lyrics, Queue }
enum class PurchasedTab(val label: String) { Albums("专辑"), Songs("单曲") }

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
    val membership: Membership? = null,
    val collectionAlbums: List<Album> = emptyList(),
    val collectionLoading: Boolean = false,
    val collectionError: String? = null,
    val purchasedTab: PurchasedTab = PurchasedTab.Albums,
    val purchasedSongs: List<Song>? = null,
    val purchasedAlbums: List<Album>? = null,
    val purchasedLoading: Boolean = false,
    val purchasedError: String? = null,
    /** Where the queue came from, as 来自歌单 · 深夜城市漫游. */
    val queueSource: String = "",
    /** Continue with the personal FM when the queue runs out. */
    val endless: Boolean = false,
    val sleepAt: Long = 0,
    val normalize: Boolean = false,
    val fade: Boolean = false,
    val wordLyrics: Boolean = true,
    val downloadQuality: String = "hq",
    val cacheLimitMb: Long = 2048,
    val cacheUsed: Long = 0,
)

class LiltViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as LiltApplication).repository
    val playback = PlaybackConnection(application)
    val player = playback.state
    val profile = repository.profile
    private val preferences = application.getSharedPreferences("lilt_settings", Context.MODE_PRIVATE)
    private val downloads = DownloadLibrary(application)
    private var downloadedSongs: List<Song> = emptyList()
    private var localRefreshJob: Job? = null
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) refreshLocal()
        }
    }
    private val mutable = MutableStateFlow(AppState(
        theme = ThemeMode.entries.firstOrNull { it.name == preferences.getString("theme", "") } ?: ThemeMode.System,
        quality = preferences.getString("quality", "standard") ?: "standard",
        endless = preferences.getBoolean("endless", false), normalize = preferences.getBoolean("normalize", false),
        fade = preferences.getBoolean("fade", false), wordLyrics = preferences.getBoolean("word_lyrics", true),
        downloadQuality = preferences.getString("download_quality", "hq") ?: "hq",
        cacheLimitMb = preferences.getLong("cache_limit_mb", 2048),
        recent = readSongs("recent"), local = readSongs("local"),
        searchHistory = readStrings("search_history"),
    ))
    val state = mutable.asStateFlow()
    private val songBook = mutableMapOf<String, Song>()
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var purchasedJob: Job? = null
    private var qrJob: Job? = null
    private var lyricJob: Job? = null
    private var radioJob: Job? = null
    private var sleepJob: Job? = null
    private val backStack = mutableListOf<Destination>()
    private val detailStack = mutableListOf<PlaylistDetail>()
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = events.asSharedFlow()

    init {
        songBook.putAll((state.value.local + state.value.recent).associateBy { it.mid })
        ContextCompat.registerReceiver(application, downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        refreshLocal()
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
            profile.collect { user ->
                if (user != null) { refreshLibrary(); refreshHome(); refreshMembership() }
                else mutable.update { it.copy(membership = null) }
            }
        }
        // An endless queue continues with the personal FM two songs before the end.
        viewModelScope.launch {
            player.collect { snapshot ->
                val s = state.value
                if (!s.endless || s.preview || profile.value == null || radioJob?.isActive == true) return@collect
                if (snapshot.tracks.isEmpty() || snapshot.currentIndex < snapshot.tracks.size - 2) return@collect
                radioJob = viewModelScope.launch {
                    try {
                        val queued = snapshot.tracks.map { it.id }.toSet()
                        val fresh = repository.radioTracks().filter { it.mid !in queued }
                        if (fresh.isNotEmpty()) {
                            songBook.putAll(fresh.associateBy { it.mid })
                            playback.append(fresh.map(::resolved))
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                    }
                    delay(5_000)
                }
            }
        }
        viewModelScope.launch {
            repository.sessionExpired.collect { message ->
                mutable.update { it.copy(library = emptyList(), liked = emptySet()) }
                notify(message)
            }
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
        if (state.value.destination == destination) return
        go(destination)
        // Profile changes and the refresh action load the library; switching tabs only reveals the cached page.
        if (destination == Destination.Login) refreshQr()
        if (destination == Destination.Local) refreshLocal()
    }
    private fun go(destination: Destination) {
        val current = state.value.destination
        // Album and artist pages open from a playlist page; each keeps its own way back.
        if (current == Destination.Playlist && destination == Destination.Playlist) {
            state.value.detail?.let { detailStack.add(it) }
            backStack.add(current)
            return
        }
        if (current == destination) return
        if (destination in listOf(Destination.Home, Destination.Search, Destination.Library)) detailStack.clear()
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
            if (state.value.destination == Destination.Playlist && destination == Destination.Playlist) {
                detailJob?.cancel()
                detailStack.removeLastOrNull()?.let { previous ->
                    mutable.update { it.copy(detail = previous, detailLoading = false, detailError = null) }
                }
                return
            }
            mutable.update { it.copy(destination = destination,
                topLevel = if (destination in listOf(Destination.Home, Destination.Search, Destination.Library)) destination else it.topLevel) }
        }
    }
    fun showPlayer(open: Boolean) {
        // Keep the current panel visible throughout dismissal; a fresh opening starts with artwork.
        mutable.update { it.copy(playerOpen = open, playerPanel = if (open) PlayerPanel.Artwork else it.playerPanel) }
    }
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
                val id = playlist.id
                val detail = when {
                    state.value.preview -> PlaylistDetail(playlist, PreviewContent.songs)
                    id.startsWith("album:") -> repository.album(Album(id.removePrefix("album:"), playlist.albumId, playlist.title, playlist.creator, playlist.cover))
                    id.startsWith("artist:") -> repository.artist(Artist(id.removePrefix("artist:"), 0, playlist.title, playlist.cover))
                    else -> repository.playlist(id)
                }
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
                    songBook.putAll(songs.associateBy { it.mid })
                    mutable.update { it.copy(detail = PlaylistDetail(Playlist("local-list", "每日推荐",
                        songs.firstOrNull()?.cover.orEmpty(), trackCount = songs.size), songs), detailLoading = false) }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutable.update { it.copy(detailLoading = false, detailError = readable(error)) }
                }
            }
        }
    }
    fun play(songs: List<Song>, index: Int = 0, shuffle: Boolean = false, source: String = defaultSource()) {
        if (songs.isEmpty()) return
        if (state.value.preview) { notify("设计预览不播放示例音频"); showPlayer(true); return }
        songBook.putAll(songs.associateBy { it.mid })
        mutable.update { it.copy(queueSource = source) }
        playback.setQueue(songs.map(::resolved), index)
        playback.setShuffle(shuffle)
    }
    fun playNext(song: Song) {
        if (state.value.preview) { notify("设计预览不播放示例音频"); return }
        songBook[song.mid] = song
        playback.playNext(listOf(resolved(song)))
        notify("将在下一首播放")
    }
    private fun defaultSource(): String = when (state.value.destination) {
        Destination.Playlist -> state.value.detail?.playlist?.let { p ->
            when {
                p.id.startsWith("album:") -> "来自专辑 · ${p.title}"
                p.id.startsWith("artist:") -> "来自歌手 · ${p.title}"
                p.title == "每日推荐" -> "来自每日推荐"
                else -> "来自歌单 · ${p.title}"
            }
        }.orEmpty()
        Destination.Search -> "来自搜索 · ${state.value.query}"
        Destination.Local -> "来自本地音乐"
        Destination.Purchased -> "来自已购音乐"
        else -> ""
    }
    /** The quality is part of the address, so cached audio of another quality is not reused. */
    private fun resolved(song: Song): ResolvedTrack {
        val uri = if (song.mid.startsWith("content:")) song.mid else Uri.Builder()
            .scheme("lilt").authority("song").appendPath(song.mid)
            .appendQueryParameter("id", song.id.toString())
            .appendQueryParameter("title", song.title)
            .appendQueryParameter("artist", song.artist)
            .appendQueryParameter("mediaMid", song.mediaMid)
            .appendQueryParameter("albumMid", song.albumMid)
            .appendQueryParameter("quality", state.value.quality).build().toString()
        return ResolvedTrack(song.mid, song.title, song.artist, song.album, song.cover, uri)
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
                refreshLibrary()
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
                if (state.value.library.isNotEmpty()) notify("歌单同步失败，请稍后重试")
            }
        }
    }
    fun refreshMembership() {
        if (profile.value == null || state.value.preview) return
        viewModelScope.launch {
            runCatching { repository.membership() }.onSuccess { m -> mutable.update { it.copy(membership = m) } }
        }
    }
    fun openAlbum(album: Album) = openPlaylist(Playlist("album:${album.mid}", album.title, album.cover, album.artist, albumId = album.id))
    fun openArtist(artist: Artist) = openPlaylist(Playlist("artist:${artist.mid}", artist.name, artist.cover))
    fun playPlaylist(playlist: Playlist) {
        if (state.value.preview) { play(PreviewContent.songs); return }
        viewModelScope.launch {
            try {
                val detail = repository.playlist(playlist.id)
                if (detail.songs.isEmpty()) { notify("歌单里还没有歌曲"); return@launch }
                play(detail.songs, source = "来自歌单 · ${playlist.title}")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notify(readable(error))
            }
        }
    }
    fun playArtist(artist: Artist) {
        if (state.value.preview) { play(PreviewContent.songs); return }
        viewModelScope.launch {
            try {
                val detail = repository.artist(artist)
                if (detail.songs.isEmpty()) { notify("暂时没有可播放的歌曲"); return@launch }
                play(detail.songs, source = "来自歌手 · ${detail.playlist.title}")
                showPlayer(true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notify(readable(error))
            }
        }
    }
    fun openLikedAlbums() = openAlbums("收藏的专辑") { repository.likedAlbums() }
    fun openPurchased() {
        if (profile.value == null && !state.value.preview) { navigate(Destination.Login); return }
        go(Destination.Purchased)
        mutable.update { it.copy(purchasedTab = PurchasedTab.Albums, purchasedSongs = null, purchasedAlbums = null) }
        loadPurchased(PurchasedTab.Albums)
    }
    fun selectPurchasedTab(tab: PurchasedTab) {
        if (state.value.purchasedTab == tab) return
        loadPurchased(tab)
    }
    fun retryPurchased() = loadPurchased(state.value.purchasedTab)
    private fun loadPurchased(tab: PurchasedTab) {
        purchasedJob?.cancel()
        val cached = when (tab) {
            PurchasedTab.Songs -> state.value.purchasedSongs != null
            PurchasedTab.Albums -> state.value.purchasedAlbums != null
        }
        mutable.update { it.copy(purchasedTab = tab, purchasedLoading = !cached, purchasedError = null) }
        if (cached) return
        purchasedJob = viewModelScope.launch {
            try {
                when (tab) {
                    PurchasedTab.Songs -> {
                        val songs = if (state.value.preview) PreviewContent.songs else repository.purchasedSongs()
                        songBook.putAll(songs.associateBy { it.mid })
                        mutable.update { it.copy(purchasedSongs = songs, purchasedLoading = false) }
                    }
                    PurchasedTab.Albums -> {
                        val albums = if (state.value.preview) PreviewContent.albums else repository.purchasedAlbums()
                        mutable.update { it.copy(purchasedAlbums = albums, purchasedLoading = false) }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(purchasedLoading = false, purchasedError = readable(error)) }
            }
        }
    }
    private fun openAlbums(title: String, load: suspend () -> List<Album>) {
        if (profile.value == null && !state.value.preview) { navigate(Destination.Login); return }
        go(Destination.Albums)
        mutable.update { it.copy(collectionTitle = title, collectionAlbums = emptyList(), collectionLoading = true, collectionError = null) }
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            try {
                val albums = load()
                mutable.update { it.copy(collectionAlbums = albums, collectionLoading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(collectionLoading = false, collectionError = readable(error)) }
            }
        }
    }
    fun addToPlaylist(song: Song, playlist: Playlist) {
        viewModelScope.launch {
            try {
                repository.addToPlaylist(song, playlist)
                notify("已添加到「${playlist.title}」")
                refreshLibrary()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notify(readable(error))
            }
        }
    }
    /** Creates a playlist, then files [song] into it when the name was asked for from a song's menu. */
    fun createPlaylist(name: String, song: Song? = null) {
        viewModelScope.launch {
            try {
                val playlist = repository.createPlaylist(name)
                if (song != null) {
                    repository.addToPlaylist(song, playlist)
                    notify("已添加到新歌单「${playlist.title}」")
                } else notify("已创建「${playlist.title}」")
                refreshLibrary()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notify(readable(error))
                refreshLibrary()
            }
        }
    }
    fun renamePlaylist(playlist: Playlist, name: String) {
        viewModelScope.launch {
            try {
                repository.renamePlaylist(playlist, name)
                mutable.update { s ->
                    val detail = s.detail?.takeIf { it.playlist.id == playlist.id }
                    s.copy(detail = detail?.copy(playlist = detail.playlist.copy(title = name)) ?: s.detail)
                }
                notify("已重命名为「$name」")
                refreshLibrary()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notify(readable(error))
            }
        }
    }
    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            try {
                repository.deletePlaylist(playlist)
                val s = state.value
                if (s.destination == Destination.Playlist && s.detail?.playlist?.id == playlist.id) back()
                notify("已删除「${playlist.title}」")
                refreshLibrary()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notify(readable(error))
            }
        }
    }
    fun setEndless(enabled: Boolean) {
        if (enabled && profile.value == null && !state.value.preview) { notify("登录后可在播完时继续播放私人电台"); return }
        preferences.edit().putBoolean("endless", enabled).apply()
        mutable.update { it.copy(endless = enabled) }
        notify(if (enabled) "播完后将继续播放私人电台" else "已关闭电台续播")
    }
    /** Pauses after [minutes]; 0 turns the timer off. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) { mutable.update { it.copy(sleepAt = 0) }; notify("已关闭睡眠定时"); return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        mutable.update { it.copy(sleepAt = at) }
        sleepJob = viewModelScope.launch {
            delay(at - System.currentTimeMillis())
            playback.pause()
            mutable.update { it.copy(sleepAt = 0) }
            notify("睡眠定时已暂停播放")
        }
        notify("将在 $minutes 分钟后暂停播放")
    }
    fun setNormalize(enabled: Boolean) {
        preferences.edit().putBoolean("normalize", enabled).apply()
        mutable.update { it.copy(normalize = enabled) }
    }
    fun setFade(enabled: Boolean) {
        preferences.edit().putBoolean("fade", enabled).apply()
        mutable.update { it.copy(fade = enabled) }
    }
    fun setWordLyrics(enabled: Boolean) {
        preferences.edit().putBoolean("word_lyrics", enabled).apply()
        mutable.update { it.copy(wordLyrics = enabled) }
        loadLyrics()
    }
    fun setDownloadQuality(quality: String) {
        preferences.edit().putString("download_quality", quality).apply()
        mutable.update { it.copy(downloadQuality = quality) }
    }
    fun setCacheLimit(megabytes: Long) {
        preferences.edit().putLong("cache_limit_mb", megabytes).apply()
        mutable.update { it.copy(cacheLimitMb = megabytes) }
        refreshCacheUsage()
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun refreshCacheUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            val used = runCatching { getApplication<LiltApplication>().audioCache.usedBytes }.getOrDefault(0)
            mutable.update { it.copy(cacheUsed = used) }
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun clearCaches(clearImages: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { getApplication<LiltApplication>().audioCache.clear() }
            withContext(Dispatchers.Main) { clearImages() }
            val used = runCatching { getApplication<LiltApplication>().audioCache.usedBytes }.getOrDefault(0)
            mutable.update { it.copy(cacheUsed = used) }
            notify("缓存已清除")
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
                val result = (if (state.value.preview) PreviewContent.lyrics else repository.lyrics(song))
                    .let { if (state.value.wordLyrics) it else Lyrics(it.lines.map { line -> line.copy(words = emptyList()) }) }
                mutable.update { it.copy(lyrics = result, lyricsLoading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(lyricsLoading = false, lyricsError = readable(error)) }
            }
        }
    }
    fun refreshQr(provider: String = state.value.provider) {
        cancelQr()
        val app = if (provider == "wechat") "微信" else "QQ"
        mutable.update { it.copy(provider = provider, qr = null, qrBusy = true,
            qrMessage = if (provider == "wechat") "正在准备微信登录" else "正在获取二维码", qrState = LoginState.WAITING) }
        qrJob = viewModelScope.launch {
            try {
                val qr = repository.createQr(provider)
                mutable.update { it.copy(qr = qr, qrBusy = false, qrMessage = "请使用手机${app}扫码") }
                while (isActive) {
                    // WeChat holds its status poll open until something changes; QQ answers at once.
                    delay(if (provider == "wechat") 300 else 1800)
                    val result = repository.pollQr(qr)
                    mutable.update { it.copy(qrState = result.state, qrMessage = result.message.ifBlank { "等待扫码 · 二维码 2 分钟内有效" }) }
                    if (result.state == LoginState.SUCCESS) {
                        viewModelScope.launch { runCatching { repository.refreshProfile() } }
                        go(Destination.Library)
                        notify("登录成功")
                        return@launch
                    }
                    if (result.state == LoginState.EXPIRED) return@launch
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
        // Keep imported documents separate from system downloads so a refresh can reflect removed downloads.
        val imported = (readSongs("local") + songs).distinctBy { it.mid }
        saveSongs("local", imported)
        val local = (downloadedSongs + imported).distinctBy { it.mid }
        songBook.putAll(local.associateBy { it.mid })
        mutable.update { it.copy(local = local) }
    }
    fun refreshLocal() {
        localRefreshJob?.cancel()
        localRefreshJob = viewModelScope.launch {
            val completed = try { withContext(Dispatchers.IO) { downloads.completed() } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                return@launch
            }
            downloadedSongs = completed
            val local = (completed + readSongs("local")).distinctBy { it.mid }
            songBook.putAll(local.associateBy { it.mid })
            mutable.update { it.copy(local = local) }
        }
    }
    fun download(song: Song) {
        if (state.value.preview) { notify("设计预览不下载示例音频"); return }
        viewModelScope.launch {
            try {
                val url = repository.songUrl(song, state.value.downloadQuality)
                val filename = "${song.artist} - ${song.title}".replace(Regex("[\\\\/:*?\"<>|]"), "_") +
                    if (url.substringBefore("?").endsWith(".flac")) ".flac" else ".mp3"
                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle(song.title).setDescription(song.artist)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "Lilt/$filename")
                    .addRequestHeader("Referer", "https://y.qq.com/")
                val id = getApplication<Application>().getSystemService(DownloadManager::class.java).enqueue(request)
                downloads.remember(id, song)
                refreshLocal()
                notify("已开始下载，完成后可在本地与下载中播放")
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
            val artists = obj.optJSONArray("artists")?.let { list ->
                (0 until list.length()).mapNotNull(list::optJSONObject)
                    .map { a -> Artist(a.optString("mid"), a.optLong("id"), a.optString("name"), a.optString("cover")) }
            }.orEmpty()
            Song(obj.getString("mid"), obj.optLong("id"), obj.getString("title"), obj.optString("artist"),
                obj.optString("album"), obj.optString("cover"), obj.optLong("durationMs"), obj.optString("mediaMid"),
                obj.optString("albumMid"), artists)
        }
    }.getOrDefault(emptyList())
    private fun saveSongs(key: String, songs: List<Song>) {
        val array = JSONArray()
        songs.forEach { song -> array.put(JSONObject().put("mid", song.mid).put("id", song.id).put("title", song.title)
            .put("artist", song.artist).put("album", song.album).put("cover", song.cover).put("durationMs", song.durationMs)
            .put("mediaMid", song.mediaMid).put("albumMid", song.albumMid)
            .put("artists", JSONArray(song.artists.map { JSONObject().put("mid", it.mid).put("id", it.id).put("name", it.name).put("cover", it.cover) }))) }
        preferences.edit().putString(key, array.toString()).apply()
    }
    private fun readable(error: Throwable) = error.message?.takeIf { it.length < 180 } ?: "连接失败，请检查网络后重试"
    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(downloadReceiver)
        playback.close()
        super.onCleared()
    }
}

private object PreviewContent {
    private fun cover(index: Int) = "file:///android_asset/design/generated${if (index == 0) "" else "-$index"}.png"
    val albums = listOf(
        Album("preview-album-1", 1, "叶惠美", "周杰伦", cover(4)),
        Album("preview-album-2", 2, "唱游", "王菲", cover(0)),
    )
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
        LyricLine(40000, "还要多久 我才能在你身边", endMs = 66000,
            words = "还要多久 我才能在你身边".mapIndexed { i, c -> LyricWord(c.toString(), 54000L + i * 1000, 55000L + i * 1000) }),
        LyricLine(65000, "等到放晴的那天\n也许我会比较好一点"),
        LyricLine(90000, "从前从前 有个人爱你很久"),
        LyricLine(120000, "但偏偏 风渐渐\n把距离吹得好远"),
        LyricLine(150000, "好不容易 又能再多爱一天"),
    ))
}
