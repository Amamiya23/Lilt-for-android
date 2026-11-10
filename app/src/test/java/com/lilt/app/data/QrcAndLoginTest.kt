package com.lilt.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

class QrcAndLoginTest {
    private fun fixture(name: String) = requireNotNull(javaClass.classLoader).getResource("qrc/$name")!!.readText().trim()

    // QQ cloud lyrics decode only with the altered S-boxes, word byte order and PC-2 offset.
    @Test fun decodesCloudQrcWithAbsoluteWordTimes() {
        val text = QrcDecoder.decode(fixture("qrc_qingtian.hex"))
        assertTrue(text.contains("[29264,3446]故(29264,390)事(29654,392)"))
        assertTrue(text.contains("LyricContent="))
        val lines = MusicParsers.qrc(text)
        val first = lines.first { it.timeMs == 29264L }
        assertEquals("故", first.words[0].text)
        assertEquals(29654L, first.words[1].startMs)
        assertEquals(29654L + 392, first.words[1].endMs)
    }

    @Test fun decodesJapaneseQrcAndRejectsDamage() {
        val hex = fixture("qrc_kimi.hex")
        val text = QrcDecoder.decode(hex)
        assertTrue(text.contains("[ti:君をのせて]"))
        assertTrue(MusicParsers.qrc(text).sumOf { it.words.size } >= 10)
        // A changed block must fail the compressed stream's checksum.
        val chars = hex.toCharArray()
        chars[chars.size / 2] = if (chars[chars.size / 2] == '0') '1' else '0'
        assertThrows(MusicApiException::class.java) { QrcDecoder.decode(String(chars)) }
    }

    // The same envelope carries LRC fallbacks and translations without word timing.
    @Test fun decodesLrcOnlyAndTranslationPayloads() {
        for (name in listOf("qrc_lrc_only.hex", "qrc_kimi_trans.hex")) {
            val text = QrcDecoder.decode(fixture(name))
            assertTrue(Regex("\\[\\d{1,3}:\\d{2}").containsMatchIn(text))
            assertFalse(text.contains("LyricContent="))
            assertTrue(MusicParsers.qrc(text).isEmpty())
        }
    }

    @Test fun rejectsCorruptCiphertext() {
        assertEquals("", QrcDecoder.decode(""))
        for (input in listOf("not-hex!", "00", "0000000000000000")) {
            assertThrows(MusicApiException::class.java) { QrcDecoder.decode(input) }
        }
    }

    // Word times are absolute; translations go to the nearest line even though word-timed lines start off the LRC times.
    @Test fun buildsWordTimedLinesWithTranslations() {
        val qrc = """<?xml version="1.0" encoding="utf-8"?>
<QrcInfos><LyricInfo><Lyric_1 LyricType="1" LyricContent="[ti:测试]
[offset:0]
[29264,3446]甲(29264,390)乙(29654,392)丙(30046,448)丁(30494,922)戊(31416,374)己(31790,504)
[32710,3537]下一句(32710,3537)
"/></LyricInfo></QrcInfos>"""
        val lyrics = MusicParsers.lyrics("[00:30.00]旧逐行时间", "[00:29.26]第一句译文\n[00:32.71]第二句译文", qrc)
        assertTrue(lyrics.wordTimed)
        assertEquals(2, lyrics.lines.size)
        val line = lyrics.lines[0]
        assertEquals("甲乙丙丁戊己", line.text)
        assertEquals(32710L, line.endMs)
        assertEquals(30494L to 31416L, line.words[3].startMs to line.words[3].endMs)
        assertEquals(listOf("第一句译文", "第二句译文"), lyrics.lines.map { it.translation })
    }

    // Timed spaces, literal parentheses and escaped XML text survive; silent units and broken tails do not become lines.
    @Test fun keepsQrcTextFaithfully() {
        val content = "[5000,200]末(5000,200)\n[0,1000](0,1000)\n" +
            "[1000,3500]I(1000,200) (1200,100)&amp;(1300,300) (1600,100)((1700,100)you(1800,1200))(3000,100)(3200,100)！(3300,200)"
        val lines = MusicParsers.qrc("<Lyric_1 LyricType=\"1\" LyricContent=\"$content\"/>")
        assertEquals(2, lines.size)
        assertEquals("I & (you)！", lines[0].text)
        assertEquals(" ", lines[0].words[1].text)
        assertEquals(4500L, lines[0].endMs)
        assertEquals(1, MusicParsers.qrc("[1000,400]原始(1000,400)").size)
        assertTrue(MusicParsers.qrc("[00:01.00]只有逐行歌词").isEmpty())
        assertTrue(MusicParsers.qrc("[1000,500]甲(1000,500)残缺尾部").isEmpty())
    }

    @Test fun fallsBackToLineTimes() {
        val lyrics = MusicParsers.lyrics("[00:01.00]备用歌词\n[00:08.00]下一句", "", "not QRC")
        assertFalse(lyrics.wordTimed)
        assertEquals(listOf(1000L, 8000L), lyrics.lines.map { it.timeMs })
        assertEquals(7000L, lyrics.lines[0].endMs)
        assertEquals(14_000L, lyrics.lines[1].endMs)
    }

    @Test fun wordProgressFollowsPosition() {
        val word = LyricWord("甲", 1000, 3000)
        assertEquals(0f, word.progress(500))
        assertEquals(0f, word.progress(1000))
        assertEquals(.25f, word.progress(1500))
        assertEquals(1f, word.progress(4000))
        assertEquals(1f, LyricWord("", 1000, 1000).progress(1000))
    }

    @Test fun parsesWechatPageAndStatus() {
        assertEquals("071IViZb1G8p1w3i", MusicParsers.wechatUuid("""<img src="/connect/qrcode/071IViZb1G8p1w3i" />"""))
        assertNull(MusicParsers.wechatUuid("<html></html>"))
        assertEquals("408" to "", MusicParsers.wechatStatus("window.wx_errcode=408;window.wx_code='';"))
        assertEquals("405" to "CODE_1", MusicParsers.wechatStatus("window.wx_errcode=405;window.wx_code='CODE_1';"))
        assertNull(MusicParsers.wechatStatus("alert(1)"))
    }

    // WeChat logins identify the account by str_musicid; numeric-string expiry fields are accepted.
    @Test fun decodesWechatLoginResponse() {
        val now = System.currentTimeMillis() / 1000
        val body = JSONObject("""{"code":0,"req":{"code":0,"data":{"musicid":0,"str_musicid":"7001","musickey":"W_X_key",
            "loginType":"1","musickeyCreateTime":"$now","keyExpiresIn":259200,"refresh_key":"rk","openid":"o","unionid":"u"}}}""")
        val session = MusicSession.fromLoginResponse(body, 1)
        assertEquals("7001", session.musicId)
        assertEquals(1, session.loginType)
        assertTrue(session.valid && session.canRefresh)
        val rejected = assertThrows(MusicApiException::class.java) {
            MusicSession.fromLoginResponse(JSONObject("""{"code":0,"req":{"code":1000}}"""), 1)
        }
        assertTrue(rejected.loginRejected)
        assertThrows(MusicApiException::class.java) {
            MusicSession.fromLoginResponse(JSONObject("""{"code":0,"req":{"code":0,"data":{"musicid":"5","musickey":"k","loginType":2}}}"""), 1)
        }
    }

    // An expired musickey is kept while its refresh material and device survive a save.
    @Test fun expiredSessionRemainsRefreshableWithItsDevice() {
        val device = LoginDevice("a", "u", "1", "q16", "q36", "123", "sid")
        val expired = MusicSession("10001", "old", 2, createdAt = 1, expiresIn = 1, refreshKey = "rk", device = device)
        assertFalse(expired.valid)
        assertTrue(expired.canRefresh)
        val restored = MusicSession.fromJson(expired.toJson())
        assertEquals(expired, restored)
        assertEquals("q16", restored.comm().getString("QIMEI"))
        assertEquals("old", restored.comm().getString("authst"))
        assertFalse(MusicSession("10001", "old", 2, createdAt = 1, expiresIn = 1).canRefresh)
    }

    @Test fun syntheticDeviceHasValidLuhnImei() {
        val device = LoginDevice.create(SecureRandom())
        assertEquals(15, device.imei.length)
        val sum = device.imei.reversed().mapIndexed { i, c ->
            var n = c - '0'
            if (i % 2 == 1) { n *= 2; if (n > 9) n -= 9 }
            n
        }.sum()
        assertEquals(0, sum % 10)
        assertEquals(16, device.androidId.length)
    }

    // The payload is AES-CBC with the RSA-wrapped key as both key and IV, and the signature binds the time.
    @Test fun qimeiEnvelopeIsSignedAndDecryptable() {
        val device = LoginDevice("0123456789abcdef", "u".repeat(32), "490154203237518")
        val now = 1_794_000_000_123L
        val request = QimeiEnvelope.build(device, now, SecureRandom())
        val params = JSONObject(request.body).getJSONObject("qimeiParams")
        assertEquals("1794000000", params.getString("time"))
        assertEquals(md5("qimei_qq_androidpzAuCmaFAaFaHrdakPjLIEqKrGnSOOvH", "1794000000"), request.headers["Sign"])
        assertEquals(md5(params.getString("key"), params.getString("params"), "1794000000000", params.getString("nonce"),
            "ZdJqM15EeO2zWc08", params.getString("extra")), params.getString("sign"))
        assertEquals(128, java.util.Base64.getDecoder().decode(params.getString("key")).size)
        assertNotNull(BigInteger(1, java.util.Base64.getDecoder().decode(params.getString("key"))))
        val encrypted = java.util.Base64.getDecoder().decode(params.getString("params"))
        assertEquals(0, encrypted.size % 16)
        assertTrue(request.body.contains("\"app\":0") && request.body.contains("\"os\":1"))
        assertEquals("q", QimeiEnvelope.parse("""{"data":"{\"data\":{\"q16\":\"q\",\"q36\":\"r\"}}"}""".toByteArray()).first)
        assertEquals("r", QimeiEnvelope.parse("""{"data":{"data":"{\"q16\":\"q\",\"q36\":\"r\"}"}}""".toByteArray()).second)
        assertEquals("", QimeiEnvelope.parse("oops".toByteArray()).first)
    }
}
