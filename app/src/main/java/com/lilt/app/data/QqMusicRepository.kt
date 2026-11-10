package com.lilt.app.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
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

/** QQ Music's own protocol; all account credentials stay inside this app. */
class QqMusicRepository(context: Context) {
    private val store = MusicSessionStore(context.applicationContext)
    @Volatile private var session: MusicSession? = store.read()
    private val mutableProfile = MutableStateFlow(session?.takeIf { it.valid }?.profile)
    val profile: StateFlow<Profile?> = mutableProfile.asStateFlow()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val authClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val qrLogins = ConcurrentHashMap<String, LoginQr>()
    private val tracks = ConcurrentHashMap<String, Song>()
    private val random = SecureRandom()

    suspend fun refreshProfile() {
        val signed = signedSession()
        val info = rpc("music.UserInfo.userInfoServer", "GetLoginUserInfo", json())
            .optJSONObject("info") ?: throw MusicApiException("QQ 音乐未返回账号资料")
        val updated = signed.copy(
            name = info.text("nick", "nickname").ifBlank { signed.name },
            avatar = info.text("headurl", "logo", "avatarUrl", "avatar").https().ifBlank { signed.avatar },
        )
        if (session != signed) return
        withContext(Dispatchers.IO) { store.write(updated) }
        session = updated
        mutableProfile.value = updated.profile
    }

    suspend fun home(): HomeFeed = coroutineScope {
        val signed = session?.takeIf { it.valid }
        val featured = async {
            optional {
                if (signed == null) featuredPlaylists()
                else optional { personalizedPlaylists(signed) }.getOrNull().orEmpty().ifEmpty { featuredPlaylists() }
            }
        }
        val top = async { optional { charts() } }
        val daily = async {
            if (signed == null) emptyList() else optional { dailySongs() }.getOrDefault(emptyList())
        }
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
        HomeFeed(songs, lists, chartLists)
    }

    suspend fun dailySongs(): List<Song> {
        signedSession()
        val data = rpc("music.srfDissInfo.aiDissInfo", "uniform_get_Dissinfo",
            json("dirid" to 202, "optype" to 3, "tag" to 1, "userinfo" to 0, "pic_dpi" to 300))
        val entries = data.optJSONArray("songlist") ?: throw MusicApiException("QQ 音乐未返回每日推荐")
        return MusicParsers.songs(entries).also(::remember)
    }

    suspend fun search(query: String, type: Int = 0): SearchResult {
        val keyword = query.trim()
        if (keyword.isEmpty()) return SearchResult()
        require(keyword.codePointCount(0, keyword.length) <= 60) { "搜索内容不能超过 60 个字" }
        require(type in listOf(0, 1, 2, 3)) { "不支持的搜索类型" }
        val s = session?.takeIf { it.valid }
        val comm = json("ct" to 11, "cv" to 0, "uin" to (s?.musicId ?: "0"), "format" to "json")
        s?.let { comm.put("qq", it.musicId).put("authst", it.musicKey).put("tmeLoginType", it.loginType) }
        return try {
            val data = rpc(
                "music.search.SearchCgiService", "DoSearchForQQMusicDesktop",
                json("query" to keyword, "search_type" to type, "num_per_page" to 20, "page_num" to 1),
                comm, userAgent = "Mozilla/5.0",
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
        if (id.startsWith("chart:")) {
            val topId = id.removePrefix("chart:").toLongOrNull() ?: throw MusicApiException("榜单标识无效")
            val data = rpc(
                "musicToplist.ToplistInfoServer", "GetDetail",
                json("topid" to topId, "offset" to 0, "num" to 100, "period" to ""),
                json("ct" to 24, "cv" to 0),
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
            if (session?.valid != true) throw MusicApiException("暂时无法读取这张歌单")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (session?.valid != true || (error is MusicApiException && error.serviceCode != null && error.serviceCode != 10)) throw error
        }
        return authenticatedPlaylist(numericId)
    }

    suspend fun songUrl(song: Song, quality: String = "standard"): String {
        val complete = if (song.mediaMid.isBlank()) songDetails(song) else song
        val signed = session?.takeIf { it.valid }
        val qualities = buildList {
            if (signed != null && quality in listOf("lossless", "hires")) add("F000" to "flac")
            if (signed != null && quality in listOf("hq", "higher", "exhigh", "lossless", "hires")) add("M800" to "mp3")
            add("M500" to "mp3")
        }
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

    suspend fun lyrics(song: Song): Lyrics {
        val response = cgi("/lyric/fcgi-bin/fcg_query_lyric_new.fcg",
            mapOf("songmid" to song.mid, "pcachetime" to song.id.toString()))
        fun decode(key: String): String = response.text(key).takeIf { it.isNotBlank() }?.let {
            try { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }
            catch (error: IllegalArgumentException) { throw MusicApiException("歌词数据无法解析", cause = error) }
        }.orEmpty()
        return MusicParsers.lyrics(decode("lyric"), decode("trans"))
    }

    suspend fun userPlaylists(): List<Playlist> {
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
        return lists
    }

    suspend fun like(song: Song, liked: Boolean) {
        signedSession()
        val complete = songDetails(song)
        suspend fun read(): Boolean {
            val data = rpc("music.musicasset.SongFavRead", "IsSongFanByMid", json("v_songMid" to listOf(complete.mid)))
            val value = data.optJSONObject("m_fan")?.opt(complete.mid)
            return value as? Boolean ?: throw MusicApiException("QQ 音乐未返回喜欢状态")
        }
        if (read() == liked) return
        val result = rpc("music.musicasset.PlaylistDetailWrite", if (liked) "AddSonglist" else "DelSonglist",
            json("dirId" to 201, "v_songInfo" to listOf(json("songId" to complete.id, "songType" to 0))))
        if (!result.has("retCode") || result.optInt("retCode", -1) != 0 || result.optJSONObject("result") == null) {
            throw MusicApiException("QQ 音乐未确认喜欢状态的更改")
        }
        if (read() != liked) throw MusicApiException("QQ 音乐未确认喜欢状态，请重试")
    }

    suspend fun isLiked(mid: String): Boolean {
        signedSession()
        val data = rpc("music.musicasset.SongFavRead", "IsSongFanByMid", json("v_songMid" to listOf(mid)))
        return data.optJSONObject("m_fan")?.opt(mid) as? Boolean
            ?: throw MusicApiException("QQ 音乐未返回喜欢状态")
    }

    suspend fun createQr(provider: String): LoginQr {
        if (!provider.equals("qq", true)) {
            return LoginQr(id = "unsupported", provider = provider)
        }
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
        if (!qr.provider.equals("qq", true)) return LoginPoll(LoginState.UNSUPPORTED, "安卓版暂不支持微信登录，请使用 QQ 扫码")
        if (qrLogins[qr.id] == null || System.currentTimeMillis() >= qr.expiresAt) {
            qrLogins.remove(qr.id)
            return LoginPoll(LoginState.EXPIRED, "二维码已失效，点按刷新")
        }
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
            "0" -> {
                val credential = exchangeQq(values[2])
                currentCoroutineContext().ensureActive()
                if (!qrLogins.containsKey(qr.id)) return LoginPoll(LoginState.EXPIRED, "登录已取消")
                withContext(Dispatchers.IO) { store.write(credential) }
                session = credential
                mutableProfile.value = credential.profile
                qrLogins.remove(qr.id)
                LoginPoll(LoginState.SUCCESS, "登录成功", credential.profile)
            }
            else -> throw MusicApiException("QQ 登录服务暂不可用，请刷新重试", values[0].toIntOrNull())
        }
    }

    fun logout() {
        qrLogins.clear()
        session = null
        mutableProfile.value = null
        store.clear()
    }

    fun cancelQr(qr: LoginQr) { qrLogins.remove(qr.id) }

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
        val body = MusicParsers.jsonResponse(response.bytes)
        val req = body.optJSONObject("req") ?: throw MusicApiException("QQ 音乐未返回登录结果")
        checkStatus(body, req)
        val data = req.optJSONObject("data") ?: throw MusicApiException("QQ 音乐未返回登录信息")
        return MusicSession.fromJson(data).also {
            if (!it.valid || it.loginType != 2) throw MusicApiException("QQ 音乐登录凭据无效，请重新扫码")
        }
    }

    private suspend fun featuredPlaylists(): List<Playlist> {
        val data = rpc("music.playlist.PlaylistSquare", "GetRecommendFeed", json("From" to 0, "Size" to 18))
        val entries = data.optJSONArray("List") ?: throw MusicApiException("QQ 音乐未返回推荐歌单")
        return entries.objects().mapNotNull { entry ->
            val basic = entry.optJSONObject("Playlist")?.optJSONObject("basic") ?: return@mapNotNull null
            val id = basic.number("tid").takeIf { it > 0 } ?: return@mapNotNull null
            Playlist(id.toString(), basic.text("title"),
                basic.optJSONObject("cover")?.text("default_url", "big_url", "medium_url", "small_url").orEmpty().https(),
                basic.optJSONObject("creator")?.text("nick").orEmpty(), basic.optInt("song_cnt"), basic.number("play_cnt"))
        }
    }

    private suspend fun personalizedPlaylists(s: MusicSession): List<Playlist> {
        val data = rpc(
            "music.recommend.RecommendFeed", "get_recommend_feed",
            json("direction" to 0, "page" to 1, "s_num" to 0, "v_cache" to emptyList<String>()),
            json("ct" to 20, "cv" to 13020008, "qq" to s.musicId, "authst" to s.musicKey, "tmeLoginType" to s.loginType),
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
        val data = rpc("musicToplist.ToplistInfoServer", "GetAll", json(), json("ct" to 24, "cv" to 0))
        val groups = data.optJSONArray("group") ?: throw MusicApiException("QQ 音乐未返回排行榜")
        return groups.objects().flatMap { it.optJSONArray("toplist").objects() }
            .filter { it.optLong("recType") == 10005L && it.optLong("topId") > 0 }
            .map { Playlist("chart:${it.optLong("topId")}", it.text("title"),
                it.text("headPicUrl", "frontPicUrl", "musichallPicUrl").https(),
                trackCount = it.optInt("totalNum"), playCount = it.optLong("listenNum")) }
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

    private fun signedSession(): MusicSession = session?.takeIf { it.valid }
        ?: throw MusicApiException("请先登录 QQ 音乐")

    private fun remember(songs: List<Song>) { songs.forEach { tracks[it.mid] = it } }

    private suspend fun rpc(
        module: String, method: String, parameters: JSONObject,
        customComm: JSONObject? = null, userAgent: String = USER_AGENT,
    ): JSONObject {
        val s = session?.takeIf { it.valid }
        val comm = customComm ?: if (s == null) json("ct" to 24, "cv" to 0) else json(
            "ct" to 11, "cv" to 14090008, "v" to 14090008, "chid" to "10003505", "tmeAppID" to "qqmusic",
            "qq" to s.musicId, "authst" to s.musicKey, "tmeLoginType" to s.loginType,
        )
        val payload = json("comm" to comm, "req_0" to json("module" to module, "method" to method, "param" to parameters))
        val headers = mutableMapOf("User-Agent" to userAgent, "Referer" to "https://y.qq.com/")
        if (s != null) headers["Cookie"] = s.cookie
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
        session?.takeIf { it.valid }?.let { headers["Cookie"] = it.cookie }
        val reply = send(url("https://c.y.qq.com$path", parameters + mapOf("format" to "json", "outCharset" to "utf-8")), headers = headers)
        val data = MusicParsers.jsonResponse(reply.bytes)
        if (!data.has("code")) throw MusicApiException("QQ 音乐未返回服务状态")
        checkInnerStatus(data)
        return data
    }

    private suspend fun send(
        url: HttpUrl, body: RequestBody? = null, headers: Map<String, String> = emptyMap(), auth: Boolean = false,
    ): HttpReply = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder().url(url).header("User-Agent", if (auth) "Mozilla/5.0" else USER_AGENT)
        headers.forEach { (key, value) -> builder.header(key, value) }
        body?.let(builder::post)
        val call = (if (auth) authClient else client).newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(MusicApiException("网络连接失败，请稍后重试", cause = e))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (response.code !in 200..(if (auth) 399 else 299)) throw MusicApiException("QQ 音乐服务暂不可用（${response.code}）", response.code)
                        val bytes = response.body?.bytes() ?: throw MusicApiException("QQ 音乐未返回内容")
                        if (continuation.isActive) continuation.resume(HttpReply(response.code, response.headers, bytes))
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
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
        private const val USER_AGENT = "QQMusic 14090008(android 10)"
        private const val LOGIN_JUMP = "https://graph.qq.com/oauth2.0/login_jump"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}

internal object MusicParsers {
    fun song(input: JSONObject): Song? {
        val item = input.optJSONObject("musicData") ?: input.optJSONObject("songInfo")
            ?: input.optJSONObject("track_info") ?: input
        val id = item.number("id", "songid", "songId")
        val mid = item.text("mid", "songmid", "songMid")
        if (id <= 0 || mid.isBlank()) return null
        val album = item.optJSONObject("album")
        val albumMid = album?.text("mid", "albummid").orEmpty().ifBlank { item.text("albummid", "album_mid") }
        val singer = item.optJSONArray("singer") ?: item.optJSONArray("singers")
        return Song(
            mid, id, item.text("title", "name", "songname", "songName"),
            singer.objects().map { it.text("name", "singername", "singer_name") }.filter(String::isNotBlank).joinToString(" / ")
                .ifBlank { item.text("singer", "singername") },
            album?.text("name", "title").orEmpty().ifBlank { item.text("albumname", "album_name") },
            cover(albumMid), item.number("interval") * 1000,
            item.optJSONObject("file")?.text("media_mid").orEmpty().ifBlank { item.text("strMediaMid", "media_mid") }, albumMid,
        )
    }

    fun songs(entries: JSONArray?): List<Song> = entries.objects().mapNotNull(::song)

    fun libraryPlaylist(item: JSONObject): Playlist? {
        val id = item.number("tid", "dissid", "id")
        if (id <= 0) return null
        return Playlist(id.toString(), item.text("dirName", "title", "dissname", "name"),
            item.text("bigpicUrl", "picUrl", "picurl", "coverUrl", "logo", "albumPicUrl").https(),
            item.text("nick", "nickname"), item.number("songNum", "songnum", "song_cnt").toInt(),
            item.number("play_cnt", "playCount"))
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
            if (mid.isBlank() || id <= 0) null else Artist(mid, id, it.text("name", "singername", "singerName", "singer_name"), artistCover(mid))
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

    fun lyrics(original: String, translation: String): Lyrics {
        val lines = lrc(original)
        val translated = lrc(translation)
        return Lyrics(lines.map { line ->
            val match = translated.minByOrNull { abs(it.timeMs - line.timeMs) }
            line.copy(translation = match?.takeIf { abs(it.timeMs - line.timeMs) < 1500 }?.text.orEmpty())
        })
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

    fun cover(mid: String): String = if (mid.isBlank()) "" else "https://y.gtimg.cn/music/photo_new/T002R500x500M000$mid.jpg"
    private fun artistCover(mid: String): String = "https://y.gtimg.cn/music/photo_new/T001R500x500M000$mid.jpg"
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
    if (code != 0) throw MusicApiException(if (code in listOf(1000, 104400, 104401)) "登录已过期，请重新登录" else "QQ 音乐暂时无法处理请求（$code）", code)
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
