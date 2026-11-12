package com.lilt.app.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.Charset
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** [toggled] marks a user's like or unlike, rather than a status read. */
data class LikeChange(val account: String, val statuses: Map<String, Boolean>, val toggled: Boolean)

/** QQ Music's own protocol; all account credentials stay inside this app. */
class QqMusicRepository(context: Context, val network: NetworkMonitor = NetworkMonitor(context)) {
    private val store = MusicSessionStore(context.applicationContext)
    val metadataCache = MetadataCache(java.io.File(context.noBackupFilesDir, "metadata"))
    private val cacheLock = Any()
    private val dailyMutex = Mutex()
    private val savedSession = store.read()
    // Expired credentials are retained while their refresh material can renew them.
    @Volatile private var session: MusicSession? = savedSession?.takeIf { it.valid || it.canRefresh }
    val accountKey: String get() = session?.let { "${it.loginType}:${it.musicId}" } ?: "guest"
    fun cachedHome(): HomeFeed? = metadataCache.home(accountKey)
    fun isHomeFresh(): Boolean = MetadataFreshness.homeIsFresh(metadataCache.homeUpdatedAt(accountKey), System.currentTimeMillis())
    fun isDailyFresh(): Boolean = MetadataFreshness.dailyIsFresh(metadataCache.detailUpdatedAt(accountKey, "daily"), System.currentTimeMillis())
    fun cachedLibrary(): List<Playlist>? = metadataCache.library(accountKey)
    fun cachedDetail(id: String): PlaylistDetail? = metadataCache.detail(accountKey, id)
    fun cachedLiked(): Set<String> = metadataCache.liked(accountKey).filterValues { it }.keys

    init {
        if (savedSession != null && session == null) {
            metadataCache.clear("${savedSession.loginType}:${savedSession.musicId}")
            store.clear()
        }
    }

    /** Logout and cache writes share this lock, so an in-flight refresh cannot recreate a signed-out account's files. */
    private suspend fun cacheFor(account: String, block: MetadataCache.() -> Unit) = withContext(Dispatchers.IO) {
        synchronized(cacheLock) {
            if (account == accountKey) runCatching { metadataCache.block() }
            Unit
        }
    }
    private val mutableProfile = MutableStateFlow(session?.profile)
    val profile: StateFlow<Profile?> = mutableProfile.asStateFlow()
    private val expiredEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Emits when the service refuses the saved login and a new scan is required. */
    val sessionExpired: SharedFlow<String> = expiredEvents.asSharedFlow()
    private val likeEvents = MutableSharedFlow<LikeChange>(extraBufferCapacity = 64)
    /** Every confirmed favorite state, so the app and the playback notification agree. */
    val likeChanges: SharedFlow<LikeChange> = likeEvents.asSharedFlow()
    private val renewal = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val authClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val qrLogins = ConcurrentHashMap<String, LoginQr>()
    private val wechatLogins = ConcurrentHashMap<String, WechatLogin>()
    private val tracks = ConcurrentHashMap<String, Song>()
    private val random = SecureRandom()

    /** GetLoginUserInfo does not return the account ID; the credential's ID is kept. */
    suspend fun refreshProfile() {
        val signed = signedSession()
        val info = try {
            rpc("music.UserInfo.userInfoServer", "GetLoginUserInfo", json())
        } catch (error: MusicApiException) {
            // A refused credential without refresh material cannot recover.
            if (error.loginRejected && !signed.canRefresh) expire(signed)
            throw error
        }.optJSONObject("info") ?: throw MusicApiException("QQ 音乐未返回账号资料")
        val current = session?.takeIf { it.musicId == signed.musicId } ?: return
        val updated = current.copy(
            name = info.text("nick", "nickname").ifBlank { current.name },
            avatar = info.text("headurl", "logo", "avatarUrl", "avatar").https().ifBlank { current.avatar },
        )
        if (updated == current) return
        renewal.withLock {
            if (session != current) return
            withContext(Dispatchers.IO) { store.write(updated) }
            session = updated
            mutableProfile.value = updated.profile
        }
    }

    suspend fun home(): HomeFeed {
        val account = accountKey
        return fetchHome().also { home -> cacheFor(account) { putHome(account, home) } }
    }

    private suspend fun fetchHome(): HomeFeed = coroutineScope {
        val signed = optional { activeSession() }.getOrNull()
        val featured = async {
            optional {
                if (signed == null) featuredPlaylists()
                else optional { personalizedPlaylists() }.getOrNull().orEmpty().ifEmpty { featuredPlaylists() }
            }
        }
        val top = async { optional { charts() } }
        val daily = async {
            if (signed == null) emptyList() else optional { dailySongs() }.getOrDefault(emptyList())
        }
        val shelves = async { if (signed == null) emptyList() else optional { songShelves() }.getOrDefault(emptyList()) }
        val playlists = featured.await()
        val charts = top.await()
        if (playlists.isFailure && charts.isFailure) throw playlists.exceptionOrNull()!!
        val lists = playlists.getOrDefault(emptyList())
        val chartLists = charts.getOrDefault(emptyList())
        val songs = daily.await().ifEmpty {
            optional {
                val first = chartLists.firstOrNull() ?: lists.firstOrNull()
                first?.let { playlist(it.id).songs.take(12) }.orEmpty()
            }.getOrDefault(emptyList())
        }
        HomeFeed(songs, lists, chartLists, shelves.await())
    }

    suspend fun dailySongs(): List<Song> = dailyMutex.withLock {
        val account = accountKey
        if (isDailyFresh()) metadataCache.detail(account, "daily")?.let { return@withLock it.songs.also(::remember) }
        signedSession()
        val data = rpc("music.srfDissInfo.aiDissInfo", "uniform_get_Dissinfo",
            json("dirid" to 202, "optype" to 3, "tag" to 1, "userinfo" to 0, "pic_dpi" to 300))
        val entries = data.optJSONArray("songlist") ?: throw MusicApiException("QQ 音乐未返回每日推荐")
        MusicParsers.songs(entries).also(::remember).also { songs ->
            cacheFor(account) { putDetail(account, "daily", PlaylistDetail(
                Playlist("daily", "每日推荐", songs.firstOrNull()?.cover.orEmpty(), trackCount = songs.size), songs)) }
        }
    }

    suspend fun search(query: String, type: Int = 0): SearchResult {
        val keyword = query.trim()
        if (keyword.isEmpty()) return SearchResult()
        require(keyword.codePointCount(0, keyword.length) <= 60) { "搜索内容不能超过 60 个字" }
        require(type in listOf(0, 1, 2, 3)) { "不支持的搜索类型" }
        return try {
            val data = rpc(
                "music.search.SearchCgiService", "DoSearchForQQMusicDesktop",
                json("query" to keyword, "search_type" to type, "num_per_page" to 20, "page_num" to 1),
                { s ->
                    json("ct" to 11, "cv" to 0, "uin" to (s?.musicId ?: "0"), "format" to "json").apply {
                        s?.let { put("qq", it.musicId).put("authst", it.musicKey).put("tmeLoginType", it.loginType) }
                    }
                }, userAgent = "Mozilla/5.0",
            )
            MusicParsers.search(data, type).also { remember(it.songs) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (type == 3) throw error
            val legacyType = when (type) { 1 -> 9; 2 -> 8; else -> 0 }
            val body = cgi(
                "/soso/fcgi-bin/client_search_cp",
                mapOf("p" to "1", "n" to "20", "w" to keyword, "t" to "$legacyType", "cr" to "1", "new_json" to "1"),
                userAgent = "Mozilla/5.0",
            )
            val key = when (type) { 1 -> "singer"; 2 -> "album"; else -> "song" }
            val section = body.optJSONObject("data")?.optJSONObject(key)
                ?: throw MusicApiException("搜索服务返回的数据不完整")
            val entries = section.optJSONArray("list") ?: throw MusicApiException("搜索服务未返回结果")
            MusicParsers.searchEntries(entries, type, section.optInt("totalnum")).also { remember(it.songs) }
        }
    }

    suspend fun playlist(id: String): PlaylistDetail {
        val account = accountKey
        return fetchPlaylist(id).also { detail -> cacheFor(account) { putDetail(account, id, detail) } }
    }

    private suspend fun fetchPlaylist(id: String): PlaylistDetail {
        if (id.startsWith("chart:")) {
            val topId = id.removePrefix("chart:").toLongOrNull() ?: throw MusicApiException("榜单标识无效")
            val data = rpc(
                "musicToplist.ToplistInfoServer", "GetDetail",
                json("topid" to topId, "offset" to 0, "num" to 100, "period" to ""),
                { json("ct" to 24, "cv" to 0) },
            )
            val info = data.optJSONObject("data") ?: throw MusicApiException("榜单信息不完整")
            val list = MusicParsers.songs(data.optJSONArray("songInfoList"))
            remember(list)
            return PlaylistDetail(
                Playlist(id, info.text("title"), info.text("headPicUrl", "frontPicUrl").https(), trackCount = info.optInt("totalNum")),
                list,
            )
        }
        val numericId = id.toLongOrNull()?.takeIf { it > 0 } ?: throw MusicApiException("歌单标识无效")
        try {
            val data = cgi(
                "/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg",
                mapOf("disstid" to id, "type" to "1", "json" to "1", "utf8" to "1", "onlysong" to "0", "new_format" to "1"),
            )
            val info = data.optJSONArray("cdlist")?.optJSONObject(0)
            if (info != null) {
                val songs = MusicParsers.songs(info.optJSONArray("songlist"))
                val count = info.number("total_song_num", "songnum").toInt()
                if (songs.isNotEmpty() || count == 0) {
                    remember(songs)
                    return PlaylistDetail(
                        Playlist(id, info.text("dissname", "title"), info.text("logo", "picurl").https(),
                            info.text("nickname", "nick"), count.takeIf { it > 0 } ?: songs.size,
                            info.number("visitnum", "listennum")),
                        songs,
                    )
                }
            }
            if (session == null) throw MusicApiException("暂时无法读取这张歌单")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (session == null || (error is MusicApiException && error.serviceCode != null && error.serviceCode != 10)) throw error
        }
        return authenticatedPlaylist(numericId)
    }

    suspend fun songUrl(song: Song, quality: String = "standard", strictQuality: Boolean = false): String {
        val complete = if (song.mediaMid.isBlank()) songDetails(song) else song
        val signed = activeSession()
        val qualities = StreamQuality.candidates(quality, signed != null, strictQuality)
        val guid = buildString { repeat(18) { append(random.nextInt(10)) } }
        for ((prefix, extension) in qualities) {
            val parameters = json(
                "filename" to listOf("$prefix${complete.mid}${complete.mediaMid.ifBlank { complete.mid }}.$extension"),
                "guid" to guid, "songmid" to listOf(complete.mid), "songtype" to listOf(0),
                "uin" to (signed?.musicId ?: "0"),
            )
            val data = if (signed == null) {
                parameters.put("loginflag", 1).put("platform", "20")
                rpc("vkey.GetVkeyServer", "CgiGetVkey", parameters)
            } else {
                parameters.put("ctx", 0)
                rpc("music.vkey.GetVkey", "UrlGetVkey", parameters)
            }
            val entry = data.optJSONArray("midurlinfo").objects().firstOrNull {
                (it.text("songmid").isBlank() || it.text("songmid") == complete.mid) && it.text("purl").isNotBlank()
            } ?: continue
            val purl = entry.text("purl")
            val domains = data.optJSONArray("sip").strings().filter(::validStreamDomain)
            val domain = domains.firstOrNull { !it.startsWith("http://ws") } ?: domains.firstOrNull()
            if (domain != null) return domain.toHttpUrl().resolve(purl)?.toString()
                ?: throw MusicApiException("播放地址无效")
            val dispatch = optional {
                rpc("music.audioCdnDispatch.cdnDispatch", "GetCdnDispatch",
                    json("guid" to guid, "uid" to "0", "use_new_domain" to 1, "use_ipv6" to 1))
                    .optJSONArray("sip").strings().firstOrNull(::validStreamDomain)
            }.getOrNull()
            return (dispatch ?: "http://dl.stream.qqmusic.qq.com/").toHttpUrl().resolve(purl)?.toString()
                ?: throw MusicApiException("播放地址无效")
        }
        throw MusicApiException("QQ 音乐未提供播放链接，可能需要登录或相应会员权限")
    }

    /** Downloads the original image bytes for durable offline artwork, outside Coil's cache. */
    suspend fun coverBytes(song: Song): ByteArray? = song.cover.takeIf(String::isNotBlank)?.let {
        send(it.toHttpUrl(), headers = mapOf("Referer" to "https://y.qq.com/")).bytes
    }

    /** Prefers QQ's real word timings, with the old LRC service as a fallback. */
    suspend fun lyrics(song: Song): Lyrics {
        optional { playLyrics(song.mid) }.getOrNull()?.let { return it }
        val response = cgi("/lyric/fcgi-bin/fcg_query_lyric_new.fcg",
            mapOf("songmid" to song.mid, "pcachetime" to song.id.toString()))
        val original = base64Text(response.text("lyric"))
        val translation = base64Text(response.text("trans"))
            .ifBlank { optional { lyricTranslation(song.mid) }.getOrDefault("") }
        return MusicParsers.lyrics(original, translation)
    }

    private suspend fun playLyrics(mid: String): Lyrics {
        // Signed in, the account identity is used as elsewhere; the endpoint also works anonymously.
        val signed = activeSession() != null
        val data = rpc("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo",
            json("songMID" to mid, "crypt" to 1, "ct" to 24, "cv" to 0, "lver" to 0, "qrc" to 1, "trans" to 1, "roma" to 0),
            if (signed) DEFAULT_COMM else { _ -> json("ct" to 24, "cv" to 0, "uin" to "0", "format" to "json") },
            userAgent = if (signed) USER_AGENT else "Mozilla/5.0", cookie = signed)
        val original = QrcDecoder.decode(data.text("lyric"))
        // Verify the payload itself: version fields alone do not prove word timing.
        val qrc = original.takeIf { MusicParsers.qrc(it).isNotEmpty() }.orEmpty()
        if (qrc.isEmpty() && !LYRIC_TIME.containsMatchIn(original)) throw MusicApiException("QQ 音乐未返回可读歌词")
        // A bad translation must not discard the original's real word timings.
        val translation = optional { QrcDecoder.decode(data.text("trans")) }.getOrNull()
            ?.takeIf { it.isEmpty() || LYRIC_TIME.containsMatchIn(it) }
            ?: optional { lyricTranslation(mid) }.getOrDefault("")
        return MusicParsers.lyrics(if (qrc.isEmpty()) original else "", translation, qrc)
    }

    /** The newer endpoint supplies base64 LRC translations with crypt=0, which the old one often lacks. */
    private suspend fun lyricTranslation(mid: String): String {
        val data = rpc("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo",
            json("songMID" to mid, "songName" to "", "singerName" to "", "albumName" to "", "interval" to 0,
                "crypt" to 0, "ct" to 19, "cv" to 2111, "lrc_t" to 0, "qrc" to 0, "qrc_t" to 0,
                "roma" to 0, "roma_t" to 0, "trans" to 1, "trans_t" to 0, "type" to 0),
            { json("ct" to 11, "cv" to "1003006", "v" to "1003006", "tmeAppID" to "qqmusiclight",
                "nettype" to "NETWORK_WIFI", "udid" to "0", "uid" to "0") },
            userAgent = "okhttp/3.14.9", cookie = false, extraHeaders = mapOf("Cookie" to "tmeLoginType=-1"))
        return base64Text(data.text("trans")).takeIf { LYRIC_TIME.containsMatchIn(it) }.orEmpty()
    }

    private fun base64Text(value: String): String = if (value.isBlank()) "" else try {
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
    } catch (error: IllegalArgumentException) { throw MusicApiException("歌词数据无法解析", cause = error) }

    suspend fun userPlaylists(): List<Playlist> {
        val account = accountKey
        val s = signedSession()
        val data = rpc("music.musicasset.PlaylistBaseRead", "GetPlaylistByUin", json("uin" to s.musicId))
        val made = data.optJSONArray("v_playlist") ?: throw MusicApiException("QQ 音乐未返回歌单列表")
        val lists = made.objects().mapNotNull(MusicParsers::libraryPlaylist).toMutableList()
        if (s.encryptUin.isNotBlank()) {
            var offset = 0
            val seen = lists.map { it.id }.toMutableSet()
            while (offset < 10_000) {
                val page = rpc("music.musicasset.PlaylistFavRead", "CgiGetPlaylistFavInfo",
                    json("uin" to s.encryptUin, "offset" to offset, "size" to 100))
                val raw = page.optJSONArray("v_list") ?: throw MusicApiException("QQ 音乐未返回收藏歌单")
                val items = raw.objects().mapNotNull(MusicParsers::libraryPlaylist)
                val fresh = items.filter { seen.add(it.id) }
                lists.addAll(fresh)
                if (!page.hasMore() || raw.length() == 0 || fresh.isEmpty()) break
                offset += raw.length()
            }
        }
        return lists.also { cacheFor(account) { putLibrary(account, it) } }
    }

    suspend fun like(song: Song, liked: Boolean) {
        val account = accountKey
        signedSession()
        val complete = songDetails(song)
        suspend fun read(): Boolean {
            val data = rpc("music.musicasset.SongFavRead", "IsSongFanByMid", json("v_songMid" to listOf(complete.mid)))
            val value = data.optJSONObject("m_fan")?.opt(complete.mid)
            return value as? Boolean ?: throw MusicApiException("QQ 音乐未返回喜欢状态")
        }
        if (read() == liked) {
            cacheFor(account) { putLiked(account, mapOf(song.mid to liked)) }
            likeEvents.tryEmit(LikeChange(account, mapOf(song.mid to liked), toggled = true))
            return
        }
        val result = rpc("music.musicasset.PlaylistDetailWrite", if (liked) "AddSonglist" else "DelSonglist",
            json("dirId" to 201, "v_songInfo" to listOf(json("songId" to complete.id, "songType" to 0))))
        if (!result.has("retCode") || result.optInt("retCode", -1) != 0 || result.optJSONObject("result") == null) {
            throw MusicApiException("QQ 音乐未确认喜欢状态的更改")
        }
        if (read() != liked) throw MusicApiException("QQ 音乐未确认喜欢状态，请重试")
        cacheFor(account) { putLiked(account, mapOf(song.mid to liked)) }
        likeEvents.tryEmit(LikeChange(account, mapOf(song.mid to liked), toggled = true))
    }

    suspend fun isLiked(mid: String): Boolean {
        if (!network.isOnline) return metadataCache.liked(accountKey)[mid] ?: false
        return likedStatus(listOf(mid)).getValue(mid)
    }

    /** QQ returns an entry for every requested MID; a missing value is not equivalent to false. */
    suspend fun likedStatus(mids: List<String>): Map<String, Boolean> {
        val account = accountKey
        signedSession()
        val selected = mids.distinct()
        if (selected.size !in 1..100 || selected.any { it.isBlank() }) throw MusicApiException("一次请选择 1–100 首歌曲")
        val data = rpc("music.musicasset.SongFavRead", "IsSongFanByMid", json("v_songMid" to selected))
        return PlaylistSongWrites.likedStatus(data, selected).also { statuses ->
            cacheFor(account) { putLiked(account, statuses) }
            likeEvents.tryEmit(LikeChange(account, statuses, toggled = false))
        }
    }

    suspend fun createQr(provider: String): LoginQr {
        if (provider.equals("wechat", true)) return createWechatQr()
        val query = mapOf(
            "appid" to "716027609", "e" to "2", "l" to "M", "s" to "3", "d" to "72",
            "v" to "4", "t" to nonce(8), "daid" to "383", "pt_3rd_aid" to "100497308", "u1" to LOGIN_JUMP,
        )
        val url = url("https://xui.ptlogin2.qq.com/ssl/ptqrshow", query)
        val reply = send(url, auth = true)
        val key = reply.cookie(url, "qrsig", "ptlogin2.qq.com")
        if (key.isBlank() || reply.bytes.size < 8 || !reply.bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
            throw MusicApiException("QQ 未返回有效二维码，请刷新重试")
        }
        val qr = LoginQr(key, "qq", imageBytes = reply.bytes, expiresAt = System.currentTimeMillis() + 120_000)
        qrLogins.entries.removeAll { it.value.expiresAt < System.currentTimeMillis() }
        qrLogins[key] = qr
        return qr
    }

    suspend fun pollQr(qr: LoginQr): LoginPoll {
        if (qrLogins[qr.id] == null || System.currentTimeMillis() >= qr.expiresAt) {
            cancelQr(qr)
            return LoginPoll(LoginState.EXPIRED, "二维码已失效，点按刷新")
        }
        wechatLogins[qr.id]?.let { return pollWechat(qr, it) }
        val url = url("https://xui.ptlogin2.qq.com/ssl/ptqrlogin", mapOf(
            "u1" to LOGIN_JUMP, "from_ui" to "1", "aid" to "716027609", "daid" to "383",
            "pt_3rd_aid" to "100497308", "ptqrtoken" to hash33(qr.id, 0).toString(),
        ))
        val reply = send(url, headers = mapOf("Cookie" to "qrsig=${qr.id}"), auth = true)
        val values = MusicParsers.ptui(String(reply.bytes, Charsets.UTF_8))
        return when (values[0]) {
            "66" -> LoginPoll(LoginState.WAITING)
            "67" -> LoginPoll(LoginState.SCANNED, "请在手机 QQ 上确认登录")
            "65" -> { qrLogins.remove(qr.id); LoginPoll(LoginState.EXPIRED, "二维码已失效，点按刷新") }
            "0" -> finishQr(qr, exchangeQq(values[2]))
            else -> throw MusicApiException("QQ 登录服务暂不可用，请刷新重试", values[0].toIntOrNull())
        }
    }

    /** A closed or replaced QR can no longer sign in, even if its exchange completes later. */
    private suspend fun finishQr(qr: LoginQr, credential: MusicSession): LoginPoll {
        currentCoroutineContext().ensureActive()
        renewal.withLock {
            if (!qrLogins.containsKey(qr.id)) return LoginPoll(LoginState.EXPIRED, "登录已取消")
            withContext(Dispatchers.IO) { store.write(credential) }
            session = credential
            mutableProfile.value = credential.profile
        }
        cancelQr(qr)
        return LoginPoll(LoginState.SUCCESS, "登录成功", credential.profile)
    }

    /** The WeChat web login keeps its cookies private to one QR; the code is exchanged with a registered device. */
    private suspend fun createWechatQr(): LoginQr {
        val device = bootstrapDevice()
        val jar = object : CookieJar {
            private val cookies = mutableListOf<Cookie>()
            @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                this.cookies.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
                this.cookies += cookies
            }
            @Synchronized override fun loadForRequest(url: HttpUrl) = cookies.filter { it.matches(url) }
        }
        // An unchanged QR holds its status poll open for up to about 25 seconds.
        val http = authClient.newBuilder().dispatcher(Dispatcher()).cookieJar(jar).readTimeout(30, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
        val connect = url(WECHAT_CONNECT, mapOf(
            "appid" to WECHAT_APP_ID, "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/",
            "response_type" to "code", "scope" to "snsapi_login", "state" to "STATE",
            "href" to "https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect",
        ))
        val page = send(connect, auth = true, http = http, expect = 200)
        val uuid = MusicParsers.wechatUuid(String(page.bytes, Charsets.UTF_8)) ?: throw MusicApiException("微信未返回二维码，请刷新重试")
        val image = send("https://open.weixin.qq.com/connect/qrcode/$uuid".toHttpUrl(),
            headers = mapOf("Referer" to WECHAT_CONNECT), auth = true, http = http, expect = 200).bytes
        if (!isImage(image)) throw MusicApiException("微信返回的二维码图片无效，请刷新重试")
        val qr = LoginQr(nonce(24), "wechat", imageBytes = image, expiresAt = System.currentTimeMillis() + 120_000)
        pruneQr()
        wechatLogins[qr.id] = WechatLogin(http, uuid, device)
        qrLogins[qr.id] = qr
        return qr
    }

    private suspend fun pollWechat(qr: LoginQr, login: WechatLogin): LoginPoll = login.lock.withLock {
        val waiting = if (login.scanned) LoginPoll(LoginState.SCANNED, "请在微信上确认登录") else LoginPoll(LoginState.WAITING)
        val query = mutableMapOf("uuid" to login.uuid, "_" to System.currentTimeMillis().toString())
        if (login.scanned) query["last"] = "404"
        val reply = try {
            send(url("https://lp.open.weixin.qq.com/connect/l/qrconnect", query),
                headers = mapOf("Referer" to "https://open.weixin.qq.com/"), auth = true, http = login.http, expect = 200)
        } catch (error: MusicApiException) {
            if (error.cause is java.io.InterruptedIOException) return waiting
            throw error
        }
        val (status, code) = MusicParsers.wechatStatus(String(reply.bytes, Charsets.UTF_8))
            ?: throw MusicApiException("微信登录状态无法解析，请刷新重试")
        when (status) {
            "408" -> waiting
            "404" -> { login.scanned = true; LoginPoll(LoginState.SCANNED, "请在微信上确认登录") }
            "402", "403" -> { cancelQr(qr); LoginPoll(LoginState.EXPIRED, "二维码已失效，点按刷新") }
            "405" -> {
                if (code.isEmpty()) throw MusicApiException("微信未返回授权信息，请刷新重试")
                finishQr(qr, exchangeLogin(json("code" to code, "strAppid" to WECHAT_APP_ID), login.device))
            }
            else -> throw MusicApiException("微信登录服务暂不可用，请刷新重试")
        }
    }

    private class WechatLogin(val http: OkHttpClient, val uuid: String, val device: LoginDevice) {
        val lock = Mutex()
        @Volatile var scanned = false
    }

    private fun pruneQr() {
        val now = System.currentTimeMillis()
        qrLogins.values.filter { it.expiresAt < now }.forEach(::cancelQr)
    }

    private fun isImage(bytes: ByteArray) = bytes.size > 8 &&
        (bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) || bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())

    fun logout() = synchronized(cacheLock) {
        qrLogins.keys.toList().forEach { cancelQr(LoginQr(it)) }
        metadataCache.clear(accountKey)
        session = null
        mutableProfile.value = null
        store.clear()
    }

    fun cancelQr(qr: LoginQr) {
        qrLogins.remove(qr.id)
        wechatLogins.remove(qr.id)?.http?.dispatcher?.cancelAll()
    }

    private suspend fun exchangeQq(redirect: String): MusicSession {
        val check = trustedLoginUrl(redirect, "ssl.ptlogin2.graph.qq.com", "/check_sig")
        val checked = send(check, auth = true)
        if (checked.status != 302) throw MusicApiException("QQ 授权未完成，请重新扫码")
        val jump = trustedLoginUrl(checked.headers["Location"].orEmpty(), "graph.qq.com", "/oauth2.0/login_jump")
        val cookieNames = listOf("p_uin", "p_skey", "pt_oauth_token")
        val cookies = cookieNames.associateWith { checked.cookie(check, it, "graph.qq.com") }
        if (cookies.values.any { it.isBlank() }) throw MusicApiException("QQ 授权信息不完整，请重新扫码")
        if (send(jump, auth = true).status != 200) throw MusicApiException("QQ 授权服务暂不可用")
        val state = nonce(16)
        val form = FormBody.Builder()
        mapOf(
            "response_type" to "code", "client_id" to "100497308",
            "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
            "scope" to "get_user_info,get_app_friends", "state" to state, "switch" to "",
            "from_ptlogin" to "1", "src" to "1", "update_auth" to "1", "openapi" to "1010_1030",
            "g_tk" to hash33(cookies.getValue("p_skey"), 5381).toString(),
            "auth_time" to System.currentTimeMillis().toString(), "ui" to java.util.UUID.randomUUID().toString(),
        ).forEach { (key, value) -> form.add(key, value) }
        val authorized = send("https://graph.qq.com/oauth2.0/authorize".toHttpUrl(), form.build(),
            mapOf("Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }), auth = true)
        if (authorized.status != 302) throw MusicApiException("QQ 授权未完成，请重新扫码")
        val back = trustedLoginUrl(authorized.headers["Location"].orEmpty(), "y.qq.com", "/portal/wx_redirect.html")
        val code = back.queryParameter("code").orEmpty()
        if (back.queryParameterValues("state") != listOf(state) || back.queryParameterValues("code").size != 1 ||
            !Regex("^[A-F0-9]{32}$").matches(code)) throw MusicApiException("QQ 授权校验失败，请重新扫码")
        val payload = json(
            "comm" to json("g_tk" to 5381, "platform" to "yqq", "ct" to 24, "cv" to 0),
            "req" to json("module" to "QQConnectLogin.LoginServer", "method" to "QQLogin", "param" to json("code" to code)),
        )
        val response = send(MUSICU.toHttpUrl(), payload.toString().toRequestBody("application/x-www-form-urlencoded".toMediaType()), auth = true)
        return MusicSession.fromLoginResponse(MusicParsers.jsonResponse(response.bytes), 2)
    }

    /** The account's tier (HugeVip / iVipFlag; iSuperVip is also set for green accounts) and its expiry date. */
    suspend fun membership(): Membership {
        val s = signedSession()
        val info = rpc("userInfo.VipQueryServer", "SRFVipQuery_V2", json("uin_list" to listOf(s.musicId)))
            .optJSONObject("infoMap")?.optJSONObject(s.musicId) ?: throw MusicApiException("QQ 音乐未返回会员状态")
        val huge = info.opt("HugeVip") as? Number
        val flag = info.opt("iVipFlag") as? Number
        val tier = when {
            huge == null -> VipTier.Unknown
            huge.toInt() == 1 -> VipTier.Super
            huge.toInt() != 0 || flag == null -> VipTier.Unknown
            flag.toInt() == 0 -> VipTier.Free
            flag.toInt() == 1 -> VipTier.Green
            else -> VipTier.Unknown
        }
        if (tier != VipTier.Green && tier != VipTier.Super) return Membership(tier)
        val identity = optional { rpc("VipLogin.VipLoginInter", "vip_login_base", json()).optJSONObject("identity") }.getOrNull()
        return Membership(tier, MusicParsers.membershipDate(identity?.opt(if (tier == VipTier.Green) "overdate" else "HugeVipEnd")))
    }

    /** QQ's personalized song shelves (id 207), titled by the service, with their songs fetched in one batch. */
    suspend fun songShelves(): List<SongShelf> {
        val s = signedSession()
        val comm: (MusicSession?) -> JSONObject = { c ->
            json("format" to "json", "ct" to 20, "cv" to 2276, "platform" to "wk_v17", "inCharset" to "utf-8",
                "outCharset" to "utf-8", "notice" to 0, "needNewCode" to 1, "uin" to (c ?: s).musicId,
                "qq" to (c ?: s).musicId, "authst" to (c ?: s).musicKey, "tmeLoginType" to (c ?: s).loginType,
                "g_tk" to 0, "g_tk_new_20200303" to 0)
        }
        val shelves = mutableListOf<JSONObject>()
        var page = 1
        while (page <= 4) {
            val data = try {
                rpc("music.recommend.RecommendFeed", "get_recommend_feed", json("direction" to if (page == 1) 0 else 1,
                    "page" to page, "s_num" to shelves.size, "v_cache" to emptyList<String>(), "v_uniq" to emptyList<String>()), comm)
            } catch (error: MusicApiException) { if (page == 1) throw error else break }
            val received = data.optJSONArray("v_shelf").objects()
            shelves += received
            if (received.isEmpty() || data.opt("load_mark")?.toString() != "0" ||
                shelves.count { it.optInt("id") == 207 && MusicParsers.shelfSongIds(it).isNotEmpty() } >= 3) break
            page++
        }
        val groups = shelves.filter { it.optInt("id") == 207 }.map { MusicParsers.shelfTitle(it) to MusicParsers.shelfSongIds(it) }
            .filter { it.second.isNotEmpty() }
        val ids = groups.flatMap { it.second }.distinct()
        if (ids.isEmpty()) return emptyList()
        val tracks = rpc("music.trackInfo.UniformRuleCtrl", "CgiGetTrackInfo",
            json("ids" to ids, "types" to ids.map { 0 }), comm).optJSONArray("tracks")
            ?: throw MusicApiException("QQ 音乐未返回推荐歌曲")
        val byId = MusicParsers.songs(tracks).also(::remember).associateBy { it.id }
        return groups.map { (title, songIds) -> SongShelf(title, songIds.mapNotNull(byId::get)) }.filter { it.songs.isNotEmpty() }
    }

    suspend fun album(album: Album): PlaylistDetail {
        val account = accountKey
        val key = if (album.mid.isNotBlank()) "album:${album.mid}" else "album-id:${album.id}"
        return fetchAlbum(album, key).also { detail -> cacheFor(account) { putDetail(account, key, detail) } }
    }

    private suspend fun fetchAlbum(album: Album, key: String): PlaylistDetail {
        val body = cgi("/v8/fcg-bin/fcg_v8_album_info_cp.fcg",
            if (album.mid.isNotBlank()) mapOf("albummid" to album.mid) else mapOf("albumid" to album.id.toString()))
        val data = body.optJSONObject("data") ?: throw MusicApiException("QQ 音乐未返回专辑信息")
        val songs = MusicParsers.songs(data.optJSONArray("list") ?: data.optJSONArray("songlist")).also(::remember)
        val mid = data.text("mid").ifBlank { album.mid }
        val artist = data.optJSONArray("singer").objects().joinToString(" / ") { it.text("name") }
            .ifBlank { songs.firstOrNull()?.artist.orEmpty() }.ifBlank { album.artist }
        val date = data.text("aDate", "time_public").take(10)
        return PlaylistDetail(Playlist(key, data.text("name").ifBlank { album.title }, MusicParsers.cover(mid),
            listOf(artist, date).filter(String::isNotBlank).joinToString(" · "),
            data.number("total", "songnum").toInt().takeIf { it > 0 } ?: songs.size, albumId = album.id), songs)
    }

    suspend fun artist(artist: Artist): PlaylistDetail {
        val account = accountKey
        return fetchArtist(artist).also { detail -> cacheFor(account) { putDetail(account, "artist:${artist.mid}", detail) } }
    }

    private suspend fun fetchArtist(artist: Artist): PlaylistDetail {
        if (artist.mid.isBlank()) throw MusicApiException("暂时无法打开这位歌手")
        val data = rpc("music.web_singer_info_svr", "get_singer_detail_info",
            json("sort" to 5, "singermid" to artist.mid, "sin" to 0, "num" to 50))
        val info = data.optJSONObject("singer_info")
        val songs = MusicParsers.songs(data.optJSONArray("songlist")).also(::remember)
        val counts = listOf(data.optInt("total_song").takeIf { it > 0 }?.let { "$it 首歌曲" },
            data.optInt("total_album").takeIf { it > 0 }?.let { "$it 张专辑" }).filterNotNull().joinToString(" · ")
        return PlaylistDetail(Playlist("artist:${artist.mid}", info?.text("name").orEmpty().ifBlank { artist.name },
            artist.cover.ifBlank { MusicParsers.artistCover(artist.mid) }, counts, songs.size), songs)
    }

    suspend fun likedAlbums(): List<Album> {
        val s = signedSession()
        val albums = mutableListOf<Album>()
        while (albums.size < 1000) {
            val data = cgi("/fav/fcgi-bin/fcg_get_profile_order_asset.fcg", mapOf("ct" to "20", "cid" to "205360956",
                "userid" to s.musicId, "reqtype" to "2", "sin" to "${albums.size}", "ein" to "${albums.size + 99}"))
                .optJSONObject("data") ?: throw MusicApiException("QQ 音乐未返回收藏的专辑")
            val total = data.optInt("totalalbum", -1).takeIf { it >= 0 } ?: throw MusicApiException("QQ 音乐未返回收藏的专辑")
            val page = data.optJSONArray("albumlist").objects().mapNotNull(MusicParsers::libraryAlbum)
            albums += page
            if (page.isEmpty() || albums.size >= total) break
        }
        return albums
    }

    suspend fun purchasedSongs(): List<Song> =
        purchasedOrders("2", "songlist", MusicParsers::songs).also(::remember)

    suspend fun purchasedAlbums(): List<Album> =
        purchasedOrders("1", "albumlist") { entries -> entries.objects().mapNotNull(MusicParsers::libraryAlbum) }

    private suspend fun <T> purchasedOrders(type: String, list: String, parse: (JSONArray) -> List<T>): List<T> {
        val s = signedSession()
        val tk = hash33(s.musicKey, 5381).toString()
        val orders = mutableListOf<T>()
        var offset = 0
        while (offset < 2000) {
            val data = cgi("/shop/fcgi-bin/fcg_get_order", mapOf("from" to "1", "cmd" to "sales_album", "type" to type,
                "uin" to s.musicId, "start" to "$offset", "num" to "100", "platform" to "yqq", "ct" to "24",
                "cv" to "4747474", "inCharset" to "utf-8", "notice" to "0", "needNewCode" to "1", "g_tk" to tk,
                "g_tk_new_20200303" to tk)).optJSONObject("data") ?: throw MusicApiException("QQ 音乐未返回已购音乐")
            val (page, more) = MusicParsers.purchasedPage(data, list, offset)
            orders += parse(page)
            // Order offsets count every received entry, including records without usable catalog metadata.
            offset += page.length()
            if (!more) break
        }
        return orders
    }

    /** Renaming/deleting a list still excludes system lists; song edits may explicitly opt into 201 liked. */
    private suspend fun ownedDir(playlist: Playlist, refusal: String, allowLiked: Boolean = false): Int {
        val s = signedSession()
        val data = rpc("music.musicasset.PlaylistBaseRead", "GetPlaylistByUin", json("uin" to s.musicId))
        return PlaylistSongWrites.ownedDirectory(data, playlist.id, s.musicId, allowLiked, refusal)
    }

    suspend fun addToPlaylist(song: Song, playlist: Playlist) = addSongsToPlaylist(listOf(song), playlist)

    /** One request accepts at most 100 unique songs; callers can account for each confirmed chunk. */
    suspend fun addSongsToPlaylist(songs: List<Song>, playlist: Playlist) = editPlaylistSongs(songs, playlist, add = true)

    suspend fun removeSongsFromPlaylist(songs: List<Song>, playlist: Playlist) = editPlaylistSongs(songs, playlist, add = false)

    private suspend fun editPlaylistSongs(songs: List<Song>, playlist: Playlist, add: Boolean) {
        val selected = PlaylistSongWrites.songs(songs)
        val dir = ownedDir(playlist, "只能编辑我喜欢或自己创建的歌单", allowLiked = true)
        val likedBefore = if (dir == 201) likedStatus(selected.map { it.mid }) else emptyMap()
        val changes = if (dir == 201) selected.filter { likedBefore.getValue(it.mid) != add } else selected
        if (changes.isEmpty()) return
        val result = rpc("music.musicasset.PlaylistDetailWrite", if (add) "AddSonglist" else "DelSonglist",
            PlaylistSongWrites.parameters(dir, changes))
        PlaylistSongWrites.confirm(result, playlist.id, dir, if (add) "添加" else "移除")
        // Ordinary playlist writes only acknowledge the directory. Liked songs have a separate
        // per-MID read API, so verify their actual state before the UI updates its heart icons.
        if (dir == 201) {
            val after = try { likedStatus(selected.map { it.mid }) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                throw MusicApiException("QQ 音乐未确认喜欢状态的更改，请刷新歌单后查看", cause = error)
            }
            if (after.values.any { it != add }) {
                throw MusicApiException("QQ 音乐未确认喜欢状态的更改，请刷新歌单后查看")
            }
        }
    }

    /** A name already in use is not refused; the service appends a timestamp, so the returned name is the real one. */
    suspend fun createPlaylist(name: String): Playlist {
        signedSession()
        val result = rpc("music.musicasset.PlaylistBaseWrite", "AddPlaylist", json("dirName" to name))
        val created = result.optJSONObject("result")?.takeIf { result.optInt("retCode", -1) == 0 }
        val tid = created?.number("tid") ?: 0
        val dir = created?.optInt("dirId") ?: 0
        if (created == null || tid <= 0 || dir !in 1 until 200) throw MusicApiException("QQ 音乐未确认创建，请稍后在歌单中查看")
        return Playlist(tid.toString(), created.text("dirName").ifBlank { name }, dirId = dir)
    }

    /**
     * The edit writes every field its mask names, so the description, covers, tags and visibility
     * are read back first and sent unchanged alongside the new name.
     */
    suspend fun renamePlaylist(playlist: Playlist, name: String) {
        val dir = ownedDir(playlist, "只能重命名自己创建的歌单")
        val info = rpc("music.srfDissInfo.aiDissInfo", "uniform_get_Dissinfo",
            json("dirid" to dir, "optype" to 3, "tag" to 1, "userinfo" to 0, "pic_dpi" to 300)).optJSONObject("dirinfo")
        if (info == null || !info.has("dir_show")) throw MusicApiException("无法读取歌单信息，请稍后再试")
        val result = rpc("music.musicasset.PlaylistBaseWrite", "EditPlaylist", json(
            "dirId" to dir, "mask" to 95, "dirNewName" to name, "dirNewDesc" to info.text("desc"),
            "dirNewPicUrl" to info.text("picurl"), "dirNewPicUrl2" to info.text("picurl2"),
            "dirNewtaglist" to info.optJSONArray("tag").objects().map { it.number("id") }.filter { it > 0 }.joinToString(","),
            "dirShow" to info.optInt("dir_show"),
        ))
        val confirmed = result.optJSONObject("result")
        if (result.optInt("retCode", -1) != 0 || confirmed == null ||
            confirmed.number("tid").toString() != playlist.id || confirmed.optInt("dirId") != dir) {
            throw MusicApiException("QQ 音乐未确认重命名，请稍后在歌单中查看")
        }
    }

    suspend fun deletePlaylist(playlist: Playlist) {
        val dir = ownedDir(playlist, "只能删除自己创建的歌单")
        val result = rpc("music.musicasset.PlaylistBaseWrite", "DelPlaylist", json("dirId" to dir))
        // A directory that no longer exists is answered with dirId 0 rather than an error.
        if (result.optInt("retCode", -1) != 0 || result.optJSONObject("result")?.optInt("dirId") != dir) {
            throw MusicApiException("QQ 音乐未确认删除，请稍后在歌单中查看")
        }
    }

    /** The personal FM, used to continue an endless queue. */
    suspend fun radioTracks(): List<Song> {
        signedSession()
        val data = rpc("music.radioProxy.MbTrackRadioSvr", "get_radio_track", json("id" to 99, "firstplay" to 1, "num" to 15))
        return MusicParsers.songs(data.optJSONArray("tracks")).also(::remember)
    }

    /** A track's measured loudness (gain dB, linear peak); a track without one plays at unity. */
    suspend fun loudness(mid: String): Pair<Double, Double> {
        val data = rpc("music.pf_song_detail_svr", "get_song_detail_yqq", json("song_mid" to mid),
            { json("ct" to 24, "cv" to 0, "uin" to "0", "format" to "json") }, userAgent = "Mozilla/5.0", cookie = false)
        val track = data.optJSONObject("track_info") ?: throw MusicApiException("QQ 音乐未返回歌曲信息")
        if (track.text("mid") != mid) throw MusicApiException("QQ 音乐返回的歌曲信息不一致")
        val volume = track.optJSONObject("volume") ?: return 0.0 to 0.0
        return volume.optDouble("gain", 0.0) to volume.optDouble("peak", 0.0)
    }

    private suspend fun featuredPlaylists(): List<Playlist> {
        val data = rpc("music.playlist.PlaylistSquare", "GetRecommendFeed", json("From" to 0, "Size" to 18))
        val entries = data.optJSONArray("List") ?: throw MusicApiException("QQ 音乐未返回推荐歌单")
        return entries.objects().mapNotNull { it.optJSONObject("Playlist")?.optJSONObject("basic")?.let(MusicParsers::squarePlaylist) }
    }

    /** 歌单广场's tabs. GetSquareTab omits 推荐 and AI歌单 under ct=20, so it is asked with ct=24. */
    suspend fun squareTabs(): List<SquareTag> {
        val data = rpc(SQUARE, "GetSquareTab", json(), SQUARE_COMM)
        // AI歌单 (TagType 5) lists playlists that no detail API opens.
        return data.optJSONArray("List").objects().filter { it.optInt("TagType") != 5 }
            .mapNotNull { tag ->
                val id = tag.optLong("TagID")
                tag.text("TagName").takeIf { it.isNotBlank() && id != 0L }?.let { SquareTag(id, it) }
            }.distinctBy { it.id }
    }

    suspend fun squareTagGroups(): List<SquareTagGroup> {
        val data = rpc(SQUARE, "GetAllTag", json("qq" to ""), SQUARE_COMM)
        return data.optJSONArray("v_group").objects().mapNotNull { group ->
            val tags = group.optJSONArray("v_item").objects().filter { it.optInt("source") != 1 }
                .mapNotNull { tag -> tag.optLong("id").takeIf { it > 0 }?.let { SquareTag(it, tag.text("name")) } }
                .filter { it.name.isNotBlank() }
            group.text("group_name").takeIf { it.isNotBlank() && tags.isNotEmpty() }?.let { SquareTagGroup(it, tags) }
        }
    }

    /** 推荐 pages by offset up to QQ's FromLimit; a category pages by the last playlist's tid. */
    suspend fun squarePage(tag: SquareTag, cursor: String): SquarePage {
        if (tag.id == SquareTag.RECOMMENDED.id) {
            val from = cursor.toIntOrNull() ?: 0
            val data = rpc(SQUARE, "GetRecommendWhole",
                json("IsReqHead" to false, "IsReqFeed" to true, "FeedReq" to json("From" to from, "Size" to SQUARE_PAGE)), SQUARE_COMM)
            val feed = data.optJSONObject("FeedRsp") ?: throw MusicApiException("QQ 音乐未返回歌单广场")
            val entries = feed.optJSONArray("List").objects()
            val next = from + entries.size
            return SquarePage(
                entries.mapNotNull { it.optJSONObject("Playlist")?.optJSONObject("basic")?.let(MusicParsers::squarePlaylist) },
                next.toString().takeIf { entries.isNotEmpty() && feed.optBoolean("HasMore") && next < feed.optInt("FromLimit", 400) },
            )
        }
        // caller must be a string; the integer 0 is refused with 20011.
        val data = rpc("playlist.PlayListCategoryServer", "get_category_content", json(
            "caller" to (session?.musicId ?: "0"), "category_id" to tag.id, "titleid" to tag.id,
            "size" to SQUARE_PAGE, "last_id" to cursor, "order" to 5, "is_parent" to 0,
        ), SQUARE_COMM)
        val content = data.optJSONObject("content") ?: throw MusicApiException("QQ 音乐未返回分类歌单")
        val basics = content.optJSONArray("v_item").objects().mapNotNull { it.optJSONObject("basic") }
        val last = basics.lastOrNull()?.number("tid")?.takeIf { it > 0 }
        return SquarePage(basics.mapNotNull(MusicParsers::squarePlaylist),
            last?.toString()?.takeIf { basics.size >= SQUARE_PAGE })
    }

    /** QQ's 30 trending searches; they need no login. */
    suspend fun hotSearches(): List<HotSearch> {
        val data = rpc("tencent_musicsoso_hotkey.HotkeyService", "GetHotkeyForQQMusicPC", json(),
            { json("ct" to 24, "cv" to 0, "format" to "json") }, cookie = false)
        return data.optJSONArray("vec_hotkey").objects().mapNotNull { entry ->
            entry.text("query", "title").trim().takeIf { it.isNotEmpty() }?.let { HotSearch(it, entry.text("description") == "新歌") }
        }.distinctBy { it.query }
    }

    private suspend fun personalizedPlaylists(): List<Playlist> {
        val data = rpc(
            "music.recommend.RecommendFeed", "get_recommend_feed",
            json("direction" to 0, "page" to 1, "s_num" to 0, "v_cache" to emptyList<String>()),
            { s -> json("ct" to 20, "cv" to 13020008, "qq" to s?.musicId, "authst" to s?.musicKey, "tmeLoginType" to s?.loginType) },
        )
        return data.optJSONArray("v_shelf").objects()
            .flatMap { it.optJSONArray("v_niche").objects() }
            .flatMap { it.optJSONArray("v_card").objects() }
            .filter { it.optInt("type") == 500 && it.optInt("subtype") == 0 && it.optInt("jumptype") == 10014 }
            .mapNotNull {
                val id = it.text("id").toLongOrNull()?.takeIf { id -> id > 0 } ?: return@mapNotNull null
                Playlist(id.toString(), it.text("title"), it.text("cover").https(), playCount = it.number("cnt"))
            }.distinctBy { it.id }
    }

    private suspend fun charts(): List<Playlist> {
        val data = rpc("musicToplist.ToplistInfoServer", "GetAll", json(), { json("ct" to 24, "cv" to 0) })
        val groups = data.optJSONArray("group") ?: throw MusicApiException("QQ 音乐未返回排行榜")
        return groups.objects().flatMap { it.optJSONArray("toplist").objects() }
            .filter { it.optLong("recType") == 10005L && it.optLong("topId") > 0 }
            .map { Playlist("chart:${it.optLong("topId")}", it.text("title"),
                it.text("headPicUrl", "frontPicUrl", "musichallPicUrl").https(),
                trackCount = it.optInt("totalNum"), playCount = it.optLong("listenNum"),
                preview = it.optJSONArray("song").objects().map { song -> song.text("title") to song.text("singerName") }) }
    }

    private suspend fun authenticatedPlaylist(id: Long): PlaylistDetail {
        val s = signedSession()
        val all = mutableListOf<Song>()
        var first: JSONObject? = null
        var offset = 0
        do {
            val data = rpc("music.srfDissInfo.DissInfo", "CgiGetDiss", json(
                "disstid" to id, "dirid" to 0, "tag" to true, "song_begin" to offset, "song_num" to 100,
                "userinfo" to true, "orderlist" to true, "enc_host_uin" to s.encryptUin,
            ))
            checkInnerStatus(data)
            if (first == null) first = data
            val raw = data.optJSONArray("songlist") ?: throw MusicApiException("QQ 音乐未返回歌单歌曲")
            val page = MusicParsers.songs(raw)
            if (page.isEmpty() || all.firstOrNull()?.mid == page.firstOrNull()?.mid) break
            all.addAll(page)
            offset += raw.length()
            if (!data.hasMore() || offset >= data.optInt("total_song_num", Int.MAX_VALUE)) break
        } while (offset < 10_000)
        val data = first
        val info = data.optJSONObject("dirinfo") ?: throw MusicApiException("歌单信息不完整")
        remember(all)
        return PlaylistDetail(
            Playlist(id.toString(), info.text("title"), info.text("picurl").https(), info.text("host_nick"),
                data.optInt("total_song_num", all.size)), all,
        )
    }

    private suspend fun songDetails(song: Song): Song {
        tracks[song.mid]?.takeIf { it.mediaMid.isNotBlank() }?.let { return it }
        val data = rpc("music.pf_song_detail_svr", "get_song_detail_yqq",
            json("song_type" to 0, "song_mid" to song.mid, "song_id" to song.id.toString()))
        val parsed = data.optJSONObject("track_info")?.let(MusicParsers::song)
            ?: throw MusicApiException("QQ 音乐未返回歌曲信息")
        if (parsed.mid != song.mid || parsed.id != song.id) throw MusicApiException("QQ 音乐返回的歌曲信息不一致")
        remember(listOf(parsed))
        return parsed
    }

    private suspend fun signedSession(): MusicSession = activeSession() ?: throw MusicApiException("请先登录 QQ 音乐")

    /** The usable credential, renewing a locally expired musickey before an authenticated request. */
    private suspend fun activeSession(): MusicSession? {
        val s = session ?: return null
        return when {
            s.valid -> s
            s.canRefresh -> renew(s)
            else -> { expire(s); null }
        }
    }

    /** Shares one renewal among concurrent requests; a renewal never signs back in after logout. */
    private suspend fun renew(prior: MusicSession): MusicSession = renewal.withLock {
        session.let { if (it != prior) return it ?: throw MusicApiException("请先登录 QQ 音乐") }
        val refreshed = try {
            refreshSession(prior)
        } catch (error: MusicApiException) {
            if (!error.loginRejected) throw error
            expire(prior)
            throw MusicApiException("登录已过期，请重新扫码登录", error.serviceCode, error, loginRejected = true)
        }
        if (session != prior) return session ?: throw MusicApiException("请先登录 QQ 音乐")
        withContext(Dispatchers.IO) { store.write(refreshed) }
        session = refreshed
        mutableProfile.value = refreshed.profile
        refreshed
    }

    private fun expire(prior: MusicSession) {
        synchronized(cacheLock) {
            if (session != prior) return
            metadataCache.clear(accountKey)
            session = null
            mutableProfile.value = null
            store.clear()
            expiredEvents.tryEmit("登录已过期，请重新扫码登录")
        }
    }

    private suspend fun refreshSession(s: MusicSession): MusicSession {
        val device = s.device ?: bootstrapDevice()
        val parameters = json(
            "openid" to s.openId, "refresh_token" to s.refreshToken, "str_musicid" to s.musicId, "musickey" to s.musicKey,
            "unionid" to s.unionId, "refresh_key" to s.refreshKey, "loginMode" to 2,
        )
        if (s.loginType == 2) parameters.put("musicid", s.musicId)
        val renewed = exchangeLogin(parameters, device, s)
        return renewed.copy(
            openId = renewed.openId.ifEmpty { s.openId }, refreshToken = renewed.refreshToken.ifEmpty { s.refreshToken },
            unionId = renewed.unionId.ifEmpty { s.unionId }, refreshKey = renewed.refreshKey.ifEmpty { s.refreshKey },
            name = renewed.name.ifEmpty { s.name }, avatar = renewed.avatar.ifEmpty { s.avatar },
            encryptUin = renewed.encryptUin.ifEmpty { s.encryptUin },
        )
    }

    /** music.login's Login, for WeChat codes and credential renewal; the device is kept with the session. */
    private suspend fun exchangeLogin(parameters: JSONObject, device: LoginDevice, prior: MusicSession? = null): MusicSession {
        val loginType = prior?.loginType ?: 1
        val comm = device.comm().put("tmeLoginType", loginType)
        prior?.let { comm.put("qq", it.musicId).put("authst", it.musicKey) }
        val payload = json("comm" to comm, "req" to json("module" to "music.login.LoginServer", "method" to "Login", "param" to parameters))
        val headers = mutableMapOf("User-Agent" to USER_AGENT, "Referer" to "https://y.qq.com/")
        prior?.let { headers["Cookie"] = it.cookie }
        val reply = send(MUSICU.toHttpUrl(), payload.toString().toRequestBody(JSON_MEDIA_TYPE), headers, auth = true)
        return MusicSession.fromLoginResponse(MusicParsers.jsonResponse(reply.bytes), loginType).copy(device = device)
    }

    /** Registers a synthetic client device (QIMEI, then a music session ID), as WeChat login requires. */
    private suspend fun bootstrapDevice(): LoginDevice {
        var device = LoginDevice.create(random)
        var now = System.currentTimeMillis()
        for (attempt in 0..1) {
            val request = QimeiEnvelope.build(device, now, random)
            val reply = try {
                send(QIMEI_URL.toHttpUrl(), request.body.toRequestBody(JSON_MEDIA_TYPE), request.headers, auth = true)
            } catch (error: MusicApiException) {
                throw MusicApiException("登录设备初始化失败，请检查网络后重试", error.serviceCode, error)
            }
            val (q16, q36) = QimeiEnvelope.parse(reply.bytes)
            if (q16.isNotEmpty() && q36.isNotEmpty()) { device = device.copy(qimei = q16, qimei36 = q36); break }
            // The signature is time-bound: retry once on the server's clock.
            val server = reply.headers.getDate("Date")?.time
            if (attempt == 1 || server == null || abs(server - now) <= 60_000) throw MusicApiException("登录设备初始化失败，请稍后重试")
            now = server
        }
        val data = rpc("music.getSession.session", "GetSession", json("uid" to "", "vkey" to 0, "caller" to 0),
            { device.comm() }, cookie = false)
        val info = data.optJSONObject("session")
        fun value(key: String) = info?.opt(key)?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
        val uid = value("uid")
        val sid = value("sid")
        if (uid.isEmpty() || sid.isEmpty()) throw MusicApiException("登录设备初始化失败，请稍后重试")
        return device.copy(uid = uid, sid = sid)
    }

    private fun remember(songs: List<Song>) { songs.forEach { tracks[it.mid] = it } }

    /**
     * [comm] builds the request identity from the credential in use, so a request the service
     * refuses can be retried once with a renewed credential. With [cookie] false no account is sent.
     */
    private suspend fun rpc(
        module: String, method: String, parameters: JSONObject,
        comm: (MusicSession?) -> JSONObject = DEFAULT_COMM, userAgent: String = USER_AGENT,
        cookie: Boolean = true, extraHeaders: Map<String, String> = emptyMap(),
    ): JSONObject {
        val s = if (cookie) activeSession() else null
        return try {
            rpcOnce(module, method, parameters, comm(s), s, userAgent, extraHeaders)
        } catch (error: MusicApiException) {
            if (s == null || !error.loginRejected) throw error
            if (!s.canRefresh) { expire(s); throw error }
            val renewed = renew(s)
            rpcOnce(module, method, parameters, comm(renewed), renewed, userAgent, extraHeaders)
        }
    }

    private suspend fun rpcOnce(
        module: String, method: String, parameters: JSONObject, comm: JSONObject,
        s: MusicSession?, userAgent: String, extraHeaders: Map<String, String>,
    ): JSONObject {
        val payload = json("comm" to comm, "req_0" to json("module" to module, "method" to method, "param" to parameters))
        val headers = mutableMapOf("User-Agent" to userAgent, "Referer" to "https://y.qq.com/")
        if (s != null) headers["Cookie"] = s.cookie
        headers.putAll(extraHeaders)
        val reply = send(MUSICU.toHttpUrl(), payload.toString().toRequestBody(JSON_MEDIA_TYPE), headers)
        val body = MusicParsers.jsonResponse(reply.bytes)
        val req = body.optJSONObject("req_0") ?: throw MusicApiException("QQ 音乐返回的数据不完整")
        checkStatus(body, req)
        val data = req.optJSONObject("data") ?: throw MusicApiException("QQ 音乐未返回数据")
        checkInnerStatus(data)
        return data
    }

    private suspend fun cgi(path: String, parameters: Map<String, String>, userAgent: String = USER_AGENT): JSONObject {
        val headers = mutableMapOf("User-Agent" to userAgent, "Referer" to "https://y.qq.com/")
        activeSession()?.let { headers["Cookie"] = it.cookie }
        val reply = send(url("https://c.y.qq.com$path", parameters + mapOf("format" to "json", "outCharset" to "utf-8")), headers = headers)
        val data = MusicParsers.jsonResponse(reply.bytes)
        if (!data.has("code")) throw MusicApiException("QQ 音乐未返回服务状态")
        checkInnerStatus(data)
        return data
    }

    private suspend fun send(
        url: HttpUrl, body: RequestBody? = null, headers: Map<String, String> = emptyMap(), auth: Boolean = false,
        http: OkHttpClient? = null, expect: Int? = null,
    ): HttpReply = suspendCancellableCoroutine { continuation ->
        if (!network.isOnline) {
            continuation.resumeWithException(MusicApiException("网络连接失败，请稍后重试"))
            return@suspendCancellableCoroutine
        }
        val builder = Request.Builder().url(url).header("User-Agent", if (auth) "Mozilla/5.0" else USER_AGENT)
        headers.forEach { (key, value) -> builder.header(key, value) }
        body?.let(builder::post)
        val call = (http ?: if (auth) authClient else client).newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(MusicApiException("网络连接失败，请稍后重试", cause = e))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (expect != null && response.code != expect || response.code !in 200..(if (auth) 399 else 299)) throw MusicApiException("QQ 音乐服务暂不可用（${response.code}）", response.code)
                        val bytes = response.body?.bytes() ?: throw MusicApiException("QQ 音乐未返回内容")
                        if (continuation.isActive) continuation.resume(HttpReply(response.code, response.headers, bytes))
                    } catch (error: Exception) {
                        val failure = if (error is IOException && error !is MusicApiException) MusicApiException("网络连接失败，请稍后重试", cause = error) else error
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            }
        })
    }

    private fun nonce(size: Int): String = ByteArray(size).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private data class HttpReply(val status: Int, val headers: Headers, val bytes: ByteArray) {
        fun cookie(url: HttpUrl, name: String, domain: String): String =
            Cookie.parseAll(url, headers).lastOrNull { it.name == name && it.domain == domain }?.value.orEmpty()
    }

    companion object {
        private const val MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        private const val QIMEI_URL = "https://api.tencentmusic.com/tme/trpc/proxy"
        private val DEFAULT_COMM: (MusicSession?) -> JSONObject = { it?.comm() ?: json("ct" to 24, "cv" to 0) }
        private const val SQUARE = "music.playlist.PlaylistSquare"
        private const val SQUARE_PAGE = 60
        private val SQUARE_COMM: (MusicSession?) -> JSONObject = { json("ct" to 24, "cv" to 0, "uin" to (it?.musicId ?: "0"), "format" to "json") }
        private val LYRIC_TIME = Regex("\\[\\d{1,3}:\\d{2}")
        private const val USER_AGENT = "QQMusic 14090008(android 10)"
        private const val LOGIN_JUMP = "https://graph.qq.com/oauth2.0/login_jump"
        private const val WECHAT_APP_ID = "wx48db31d50e334801"
        private const val WECHAT_CONNECT = "https://open.weixin.qq.com/connect/qrconnect"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}

/** The account directory and write acknowledgement are distinct from a playlist's public ID. */
internal object PlaylistSongWrites {
    fun songs(songs: List<Song>): List<Song> {
        val selected = songs.distinctBy { it.mid }
        if (selected.size !in 1..100) throw MusicApiException("一次请选择 1–100 首歌曲")
        if (selected.any { it.id <= 0 || it.mid.isBlank() || it.mid.startsWith("content:") || it.mid.startsWith("file:") }) {
            throw MusicApiException("歌曲信息无效，请刷新后重试")
        }
        return selected
    }

    fun ownedDirectory(data: JSONObject, playlistId: String, musicId: String, allowLiked: Boolean, refusal: String): Int {
        val entry = data.optJSONArray("v_playlist").objects().singleOrNull { it.number("tid").toString() == playlistId }
        val dir = entry?.optInt("dirId") ?: 0
        val ordinary = dir in 1 until 200 && entry?.optInt("dirType") == 0
        val owner = entry?.opt("uin")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
        if (entry == null || !(ordinary || allowLiked && dir == 201) ||
            owner.isNotEmpty() && owner != musicId) throw MusicApiException(refusal)
        return dir
    }

    fun parameters(dir: Int, songs: List<Song>): JSONObject =
        json("dirId" to dir, "v_songInfo" to songs.map { json("songId" to it.id, "songType" to 0) })

    fun confirm(data: JSONObject, playlistId: String, dir: Int, action: String) {
        val result = data.optJSONObject("result")
        if (data.optInt("retCode", -1) != 0 || result == null ||
            result.number("tid").toString() != playlistId || result.optInt("dirId") != dir) {
            throw MusicApiException("QQ 音乐未确认$action，请刷新歌单后查看")
        }
    }

    fun likedStatus(data: JSONObject, mids: List<String>): Map<String, Boolean> {
        val statuses = data.optJSONObject("m_fan") ?: throw MusicApiException("QQ 音乐未返回喜欢状态")
        return mids.associateWith { mid ->
            statuses.opt(mid) as? Boolean ?: throw MusicApiException("QQ 音乐未返回完整的喜欢状态，请刷新后查看")
        }
    }
}

internal object MusicParsers {
    /** A playlist's `basic` object, as 歌单广场 returns it. */
    fun squarePlaylist(basic: JSONObject): Playlist? {
        val id = basic.number("tid").takeIf { it > 0 } ?: return null
        return Playlist(id.toString(), basic.text("title"),
            basic.optJSONObject("cover")?.text("default_url", "big_url", "medium_url", "small_url").orEmpty().https(),
            basic.optJSONObject("creator")?.text("nick").orEmpty(), basic.optInt("song_cnt"), basic.number("play_cnt"))
    }

    fun song(input: JSONObject): Song? {
        val item = input.optJSONObject("musicData") ?: input.optJSONObject("songInfo")
            ?: input.optJSONObject("track_info") ?: input
        val id = item.number("id", "songid", "songId")
        val mid = item.text("mid", "songmid", "songMid")
        if (id <= 0 || mid.isBlank()) return null
        val album = item.optJSONObject("album")
        val albumMid = album?.text("mid", "albummid").orEmpty().ifBlank { item.text("albummid", "album_mid") }
        val singer = item.optJSONArray("singer") ?: item.optJSONArray("singers")
        val artists = singer.objects().mapNotNull {
            val artistMid = it.text("mid", "singermid", "singer_mid", "singerMid", "singerMID")
            val artistName = it.text("name", "singername", "singer_name", "singerName")
            if (artistName.isBlank()) null
            else Artist(artistMid, it.number("id", "singerid", "singer_id", "singerID"), artistName, artistMid.takeIf(String::isNotBlank)?.let(::artistCover).orEmpty())
        }
        return Song(
            mid, id, item.text("title", "name", "songname", "songName"),
            singer.objects().map { it.text("name", "singername", "singer_name") }.filter(String::isNotBlank).joinToString(" / ")
                .ifBlank { item.text("singer", "singername") },
            album?.text("name", "title").orEmpty().ifBlank { item.text("albumname", "album_name") },
            cover(albumMid), item.number("interval") * 1000,
            item.optJSONObject("file")?.text("media_mid").orEmpty().ifBlank { item.text("strMediaMid", "media_mid") }, albumMid,
            artists,
        )
    }

    fun songs(entries: JSONArray?): List<Song> = entries.objects().mapNotNull(::song)

    fun libraryPlaylist(item: JSONObject): Playlist? {
        val id = item.number("tid", "dissid", "id")
        if (id <= 0) return null
        return Playlist(id.toString(), item.text("dirName", "title", "dissname", "name"),
            item.text("bigpicUrl", "picUrl", "picurl", "coverUrl", "logo", "albumPicUrl").https(),
            item.text("nick", "nickname"), item.number("songNum", "songnum", "song_cnt").toInt(),
            item.number("play_cnt", "playCount"),
            dirId = item.optInt("dirId").takeIf { item.has("dirName") && it > 0 } ?: 0)
    }

    fun libraryAlbum(item: JSONObject): Album? {
        val id = item.number("albumid", "albumID", "id")
        val mid = item.text("albummid", "albumMID", "albumMid", "album_mid", "mid", "pmid")
        if (id <= 0) return null
        val artist = (item.optJSONArray("singers") ?: item.optJSONArray("singer")).objects()
            .joinToString(" / ") { it.text("name", "singername", "singer_name", "singerName") }
            .ifBlank { item.text("singername", "singerName", "singer_name", "singer") }
        val photoMid = item.text("pmid").ifBlank { item.optJSONObject("photo")?.text("pic_mid").orEmpty() }.ifBlank { mid }
        return Album(mid, id, item.text("albumname", "albumName", "album_name", "name", "title"), artist,
            cover(photoMid).ifBlank { item.text("picurl", "picUrl", "albumPicUrl").https() })
    }

    /** A purchase page advances by raw order count, not the number of usable songs or albums. */
    fun purchasedPage(data: JSONObject, list: String, offset: Int): Pair<JSONArray, Boolean> {
        checkInnerStatus(data)
        val total = data.opt("total")?.toString()?.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw MusicApiException("QQ 音乐未返回有效的已购音乐总数")
        val raw = data.opt(list)
        val entries = when {
            (raw == null || raw == JSONObject.NULL) && total == 0 -> JSONArray()
            raw is JSONArray -> raw
            else -> throw MusicApiException("QQ 音乐未返回有效的已购音乐列表")
        }
        if (entries.length() == 0 && offset < total) throw MusicApiException("QQ 音乐返回的已购音乐列表不完整")
        return entries to (offset.toLong() + entries.length() < total)
    }

    /** A membership date: yyyy-MM-dd[ HH:mm:ss] or unix seconds in China time; anything else is unknown. */
    fun membershipDate(value: Any?): String {
        val zone = java.util.TimeZone.getTimeZone("GMT+8")
        return when (value) {
            is Number -> value.toLong().takeIf { it > 0 }?.let {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = zone }.format(java.util.Date(it * 1000))
            }?.takeIf { it.take(4).toInt() in 1971..9999 }.orEmpty()
            is String -> {
                val date = value.trim().take(10)
                val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { isLenient = false; timeZone = zone }
                runCatching { format.parse(date) }.getOrNull()?.let { format.format(it) }
                    ?.takeIf { it == date && date.take(4).toInt() > 1970 }.orEmpty()
            }
            else -> ""
        }
    }

    /** A shelf's songs: miscellany.songList order, without IDs that are only other kinds of cards. */
    fun shelfSongIds(shelf: JSONObject): List<Long> {
        val cards = shelf.optJSONArray("v_niche").objects().flatMap { it.optJSONArray("v_card").objects() }
        val songCards = cards.filter { it.optInt("type") == 200 }.mapNotNull { it.text("id").toLongOrNull()?.takeIf { id -> id > 0 } }
        val others = cards.filter { it.optInt("type") != 200 }.mapNotNull { it.text("id").toLongOrNull() }.toSet() - songCards.toSet()
        val listed = cards.mapNotNull { it.optJSONObject("miscellany")?.text("songList") }.flatMap { it.split(',') }
            .mapNotNull { it.trim().toLongOrNull()?.takeIf { id -> id > 0 } }.filter { it !in others }.distinct()
        return listed.ifEmpty { songCards.distinct() }
    }

    fun shelfTitle(shelf: JSONObject): String {
        val template = shelf.text("title_template")
        val content = shelf.text("title_content")
        return (if (template.isNotEmpty()) template.replace("{String}", content) else content)
            .replace("⁢", "").trim().ifEmpty { "为你推荐的歌曲" }
    }

    fun search(data: JSONObject, type: Int): SearchResult {
        val body = data.optJSONObject("body") ?: throw MusicApiException("搜索服务返回的数据不完整")
        val meta = data.optJSONObject("meta") ?: throw MusicApiException("搜索服务未返回分页信息")
        val key = when (type) { 1 -> "singer"; 2 -> "album"; 3 -> "songlist"; else -> "song" }
        val raw = body.optJSONObject(key)?.optJSONArray("list") ?: throw MusicApiException("搜索服务未返回结果")
        if (raw.length() == 0 && meta.optInt("is_filter") < 0) throw MusicApiException("搜索暂时不可用，请稍后重试")
        return searchEntries(raw, type, meta.optInt("sum"))
    }

    fun searchEntries(raw: JSONArray, type: Int, total: Int): SearchResult = when (type) {
        1 -> SearchResult(artists = raw.objects().mapNotNull {
            val mid = it.text("mid", "singermid", "singerMID", "singer_mid", "singerMid")
            val id = it.number("id", "singerid", "singerID", "singer_id", "singerId")
            if (mid.isBlank() || id <= 0) null else Artist(mid, id, it.text("name", "singername", "singerName", "singer_name"),
                it.text("singerPic").https().ifBlank { artistCover(mid) }, it.optInt("songNum"), it.optInt("albumNum"))
        }, total = total)
        2 -> SearchResult(albums = raw.objects().mapNotNull {
            val mid = it.text("mid", "albummid", "albumMID", "album_mid", "albumMid")
            val id = it.number("id", "albumid", "albumID", "album_id", "albumId")
            if (mid.isBlank() || id <= 0) null else Album(mid, id, it.text("name", "title", "albumname", "albumName"),
                (it.optJSONArray("singer") ?: it.optJSONArray("singer_list")).objects().joinToString(" / ") { a -> a.text("name") },
                it.text("albumPic").ifBlank { cover(mid) }.https())
        }, total = total)
        3 -> SearchResult(playlists = raw.objects().mapNotNull(::libraryPlaylist), total = total)
        else -> SearchResult(songs = songs(raw), total = total)
    }

    /**
     * Prefers real QRC word times, falling back to LRC line times, and gives each
     * translation to the line nearest its time: word-timed lines start a little
     * off the LRC times their translations follow.
     */
    fun lyrics(original: String, translation: String, qrc: String = ""): Lyrics {
        val lines = qrc(qrc).ifEmpty {
            val timed = lrc(original)
            timed.mapIndexed { i, line ->
                val next = timed.getOrNull(i + 1)?.timeMs
                line.copy(endMs = minOf(line.timeMs + 6000, next ?: Long.MAX_VALUE))
            }
        }.toMutableList()
        for (t in lrc(translation)) {
            val best = lines.indices.minByOrNull { abs(lines[it].timeMs - t.timeMs) } ?: continue
            if (abs(lines[best].timeMs - t.timeMs) < 1500 && lines[best].translation.isEmpty()) {
                lines[best] = lines[best].copy(translation = t.text)
            }
        }
        return Lyrics(lines)
    }

    /**
     * QQ's word-timed lyrics, either their XML wrapper or its LyricContent. Each text
     * fragment precedes its (absolute start, duration) in milliseconds; literal
     * parentheses and timed spaces remain part of the text.
     */
    fun qrc(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val content = Regex("\\bLyricContent=\"([^\"]*)\"").find(raw)?.groupValues?.get(1)?.let(::unescapeXml) ?: raw
        val header = Regex("^\\[(\\d+),(\\d+)]")
        val unit = Regex("\\((\\d+),(\\d+)\\)")
        return content.lineSequence().mapNotNull { source ->
            val line = source.trim()
            val head = header.find(line) ?: return@mapNotNull null
            val start = head.groupValues[1].toLong()
            var end = start + head.groupValues[2].toLong()
            val body = line.substring(head.range.last + 1)
            val words = mutableListOf<LyricWord>()
            var at = 0
            for (match in unit.findAll(body)) {
                val text = body.substring(at, match.range.first)
                at = match.range.last + 1
                if (text.isEmpty()) continue // An empty timed unit carries no text to highlight.
                val wordStart = match.groupValues[1].toLong()
                val word = LyricWord(text, wordStart, wordStart + match.groupValues[2].toLong())
                words += word
                end = maxOf(end, word.endMs)
            }
            val text = words.joinToString("") { it.text }.trim()
            if (text.isEmpty() || body.substring(at).isNotBlank() || text.contains("此歌曲为没有填词的纯音乐")) null
            else LyricLine(start, text, endMs = end, words = words)
        }.sortedBy { it.timeMs }.toList()
    }

    private fun unescapeXml(text: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos);").replace(text) {
        when (val entity = it.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
            else -> {
                val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                code?.takeIf(Character::isValidCodePoint)?.let { c -> String(Character.toChars(c)) } ?: it.value
            }
        }
    }

    private fun lrc(raw: String): List<LyricLine> {
        val tag = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        return raw.lineSequence().flatMap { line ->
            val matches = tag.findAll(line).toList()
            val text = matches.lastOrNull()?.let { line.substring(it.range.last + 1).trim() }.orEmpty()
            if (text.isEmpty() || text.contains("此歌曲为没有填词的纯音乐")) emptySequence()
            else matches.asSequence().map { match ->
                val parts = match.groupValues
                val fraction = parts[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
                LyricLine((parts[1].toLong() * 60_000 + parts[2].toLong() * 1000 + fraction + offset).coerceAtLeast(0), text)
            }
        }.sortedBy { it.timeMs }.toList()
    }

    fun jsonResponse(bytes: ByteArray): JSONObject {
        val utf = String(bytes, Charsets.UTF_8)
        val text = if ('\uFFFD' in utf) String(bytes, Charset.forName("GB18030")) else utf
        val trimmed = text.trim().removePrefix("\uFEFF")
        val json = Regex("^\\w+\\((.*)\\);?\\s*$", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed
        return try { JSONObject(json) }
        catch (error: Exception) { throw MusicApiException("QQ 音乐返回的数据无法解析", cause = error) }
    }

    /** Parse callback string literals as data. Never evaluate the login response. */
    fun ptui(response: String): List<String> {
        val body = response.trim().removeSuffix(";")
        if (!body.startsWith("ptuiCB(") || !body.endsWith(")")) throw MusicApiException("QQ 登录状态无法解析")
        var rest = body.substring(7, body.length - 1).trim()
        val literal = Regex("^'((?:[^'\\\\\\r\\n]|\\\\(?:['\\\\nrt]|x[0-9a-fA-F]{2}|u[0-9a-fA-F]{4}))*)'\\s*")
        val values = mutableListOf<String>()
        while (rest.isNotEmpty()) {
            val match = literal.find(rest) ?: throw MusicApiException("QQ 登录状态无法解析")
            val escaped = match.groupValues[1]
            val decoded = buildString {
                var i = 0
                while (i < escaped.length) {
                    val char = escaped[i++]
                    if (char != '\\') { append(char); continue }
                    when (val next = escaped[i++]) {
                        'n' -> append('\n'); 'r' -> append('\r'); 't' -> append('\t')
                        'x', 'u' -> {
                            val n = if (next == 'x') 2 else 4
                            append(escaped.substring(i, i + n).toInt(16).toChar()); i += n
                        }
                        else -> append(next)
                    }
                }
            }
            values.add(decoded)
            rest = rest.substring(match.value.length).trim()
            if (rest.isEmpty()) break
            if (!rest.startsWith(",") || values.size >= 7) throw MusicApiException("QQ 登录状态无法解析")
            rest = rest.substring(1).trim()
            if (rest.isEmpty()) throw MusicApiException("QQ 登录状态无法解析")
        }
        if (values.size !in 6..7) throw MusicApiException("QQ 登录状态无法解析")
        return values
    }

    fun wechatUuid(page: String): String? =
        Regex("(?:uuid=|/connect/qrcode/)([A-Za-z0-9_-]+)").find(page)?.groupValues?.get(1)

    /** The long-poll reply is a script; it is matched as data, never evaluated. Returns (errcode, code). */
    fun wechatStatus(reply: String): Pair<String, String>? =
        Regex("""window\.wx_errcode\s*=\s*(\d+)\s*;\s*window\.wx_code\s*=\s*'([^'\r\n]*)'""")
            .find(reply)?.groupValues?.let { it[1] to it[2] }

    fun cover(mid: String): String = if (mid.isBlank()) "" else "https://y.gtimg.cn/music/photo_new/T002R500x500M000$mid.jpg"
    fun artistCover(mid: String): String = "https://y.gtimg.cn/music/photo_new/T001R500x500M000$mid.jpg"
}

internal fun JSONObject.text(vararg keys: String): String = keys.firstNotNullOfOrNull {
    (opt(it) as? String)?.takeIf(String::isNotBlank)
}.orEmpty()
internal fun JSONObject.number(vararg keys: String): Long = keys.firstNotNullOfOrNull { key ->
    when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
} ?: 0
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }
private fun String.https(): String = if (startsWith("http://")) "https://${removePrefix("http://")}" else this
private fun JSONObject.hasMore(): Boolean = opt("hasmore") == true || opt("hasmore")?.toString() == "1"
private fun json(vararg entries: Pair<String, Any?>): JSONObject = JSONObject().apply {
    entries.forEach { (key, value) -> put(key, if (value is Collection<*>) JSONArray(value) else value ?: JSONObject.NULL) }
}
private fun url(raw: String, values: Map<String, String>): HttpUrl = raw.toHttpUrl().newBuilder().apply {
    values.forEach { (key, value) -> addQueryParameter(key, value) }
}.build()
private fun trustedLoginUrl(raw: String, host: String, path: String): HttpUrl {
    val parsed = try { raw.toHttpUrl() } catch (_: IllegalArgumentException) { throw MusicApiException("QQ 授权地址无效") }
    if (parsed.scheme != "https" || parsed.host != host || parsed.encodedPath != path ||
        parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null) {
        throw MusicApiException("QQ 授权地址无效")
    }
    return parsed
}
private fun validStreamDomain(raw: String): Boolean {
    val parsed = try { raw.toHttpUrl() } catch (_: IllegalArgumentException) { return false }
    return parsed.host == "qqmusic.qq.com" || parsed.host.endsWith(".qqmusic.qq.com") || parsed.host.endsWith(".qq.com")
}
private fun hash33(text: String, initial: Long): Long = text.fold(initial) { value, char -> (value * 33 + char.code) and 0x7fffffff }
private fun checkStatus(body: JSONObject, item: JSONObject) {
    if (!body.has("code") || !item.has("code")) throw MusicApiException("QQ 音乐未返回服务状态")
    val code = body.optInt("code", -1).takeIf { it != 0 } ?: item.optInt("code", -1).takeIf { it != 0 } ?: item.optInt("subcode")
    val rejected = body.optInt("code", -1) == 0 && code in listOf(1000, 104400, 104401)
    if (code != 0) throw MusicApiException(if (rejected) "登录已过期，请重新登录" else "QQ 音乐暂时无法处理请求（$code）", code, loginRejected = rejected)
}
private fun checkInnerStatus(data: JSONObject) {
    for (key in listOf("code", "subcode", "retcode")) {
        if (data.has(key) && data.optInt(key, -1) != 0) {
            val code = data.optInt(key, -1)
            throw MusicApiException("QQ 音乐暂时无法处理请求（$code）", code)
        }
    }
}
private suspend fun <T> optional(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Exception) {
    if (error is CancellationException) throw error
    Result.failure(error)
}
