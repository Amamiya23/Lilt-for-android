package com.lilt.app.data

import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * QQ Music's WeChat login and credential renewal require a registered client device.
 * The identifiers are synthetic and random; no hardware identifier of this phone is read.
 */
internal data class LoginDevice(
    val androidId: String,
    val openUdid: String,
    val imei: String,
    val qimei: String = "",
    val qimei36: String = "",
    val uid: String = "",
    val sid: String = "",
) {
    fun comm(): JSONObject = JSONObject().apply {
        put("ct", 11); put("cv", 14090008); put("v", 14090008); put("chid", "10003505"); put("tmeAppID", "qqmusic")
        put("QIMEI", qimei); put("QIMEI36", qimei36); put("OpenUDID", openUdid); put("udid", openUdid); put("OpenUDID2", openUdid)
        put("aid", androidId); put("os_ver", "10"); put("phonetype", "MI 6"); put("devicelevel", "29"); put("newdevicelevel", "29")
        put("rom", "xiaomi/iarim/sagit:10/eomam.200122.001/2001221:user/release-keys")
        if (uid.isNotEmpty()) { put("uid", uid); put("sid", sid) }
    }

    fun toJson(): JSONObject = JSONObject().put("androidId", androidId).put("openUdid", openUdid).put("imei", imei)
        .put("qimei", qimei).put("qimei36", qimei36).put("uid", uid).put("sid", sid)

    companion object {
        fun fromJson(data: JSONObject?): LoginDevice? = data?.let {
            LoginDevice(it.text("androidId"), it.text("openUdid"), it.text("imei"), it.text("qimei"),
                it.text("qimei36"), it.text("uid"), it.text("sid"))
        }?.takeIf { it.qimei.isNotEmpty() && it.uid.isNotEmpty() }

        fun create(random: SecureRandom): LoginDevice {
            val imei = digits(random, 14)
            // Luhn check digit, so the synthetic IMEI is well-formed.
            val sum = imei.mapIndexed { i, c ->
                var n = c - '0'
                if (i % 2 == 1) { n *= 2; if (n > 9) n -= 9 }
                n
            }.sum()
            return LoginDevice(hex(random, 8), hex(random, 16), imei + (10 - sum % 10) % 10)
        }
    }
}

/** The encrypted GetQimei request the official Android client sends to register a device. */
internal object QimeiEnvelope {
    private const val APP_KEY = "0AND0HD6FE4HY80F"
    private const val MODULUS = "c4231830a2eb5fc2827170641e79d80fec51bda9a22e4b4ab37d1f205a4ae44d928cda25879f66a3429051663312a127faf8a246bdaaf63918417e90d7c95b5908aa6a2d0f852e4a6770294a548ac1c2fe8f1f252fb826f4ac86ab9a00e7ce47d002a56e7c4b51eb889acc60ca6adbc9f72e81f4d31b1dd7464805264530ab1d"
    private val DATED = setOf(1, 2, 13, 14, 17, 18, 21, 22, 25, 26, 29, 30, 33, 34, 37, 38)

    class Request(val body: String, val headers: Map<String, String>)

    fun build(device: LoginDevice, nowMs: Long, random: SecureRandom): Request {
        val cryptKey = hex(random, 8)
        val nonce = hex(random, 8)
        val first = digits(random, 6)
        val second = digits(random, 9)
        val month = utc("yyyy-MM", nowMs)
        val beacon = buildString {
            for (i in 1..40) {
                val value = when {
                    i == 3 -> "0000000000000000"
                    i == 4 -> cryptKey.replace('0', '1')
                    i in DATED -> "$month-01$first.$second"
                    else -> digits(random, 4)
                }
                append("k$i:$value;")
            }
        }
        val reserved = JSONObject().put("harmony", "0").put("clone", "0").put("containe", "")
            .put("oz", "UhYmelwouA+V2nPWbOvLTgN2/m8jwGB+yUB5v9tysQg=").put("oo", "Xecjt+9S1+f8Pz2VLSxgpw==")
            .put("kelong", "0").put("uptimes", utc("yyyy-MM-dd HH:mm:ss", nowMs - 3_600_000)).put("multiUser", "0")
            .put("bod", "Xiaomi").put("dv", "sagit").put("firstLevel", "").put("manufact", "Xiaomi").put("name", "MI 6")
            .put("host", "se.infra").put("kernel", "Linux 5.4.0-54-generic (android-build@google.com)")
        val payload = JSONObject().put("androidId", device.androidId).put("platformId", 1).put("appKey", APP_KEY)
            .put("appVersion", "14.9.0.8").put("beaconIdSrc", beacon).put("brand", "Xiaomi").put("channelId", "10003505")
            .put("cid", "").put("imei", device.imei).put("imsi", "").put("mac", "").put("model", "MI 6")
            .put("networkType", "unknown").put("oaid", "").put("osVersion", "Android 10,level 29").put("qimei", "")
            .put("qimei36", "").put("sdkVersion", "1.2.13.6").put("targetSdkVersion", "33").put("audit", "")
            .put("userId", "{}").put("packageId", "com.tencent.qqmusic").put("deviceType", "Phone").put("sdkName", "")
            .put("reserved", reserved.toString())
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(MODULUS, 16), BigInteger.valueOf(65537)))
        val wrapped = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, publicKey) }
            .doFinal(cryptKey.toByteArray(Charsets.US_ASCII))
        val aesKey = cryptKey.toByteArray(Charsets.US_ASCII)
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesKey)) }
            .doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        val key = java.util.Base64.getEncoder().encodeToString(wrapped)
        val params = java.util.Base64.getEncoder().encodeToString(encrypted)
        val seconds = (nowMs / 1000).toString()
        val extra = "{\"appKey\":\"$APP_KEY\"}"
        val body = JSONObject().put("app", 0).put("os", 1).put("qimeiParams", JSONObject()
            .put("key", key).put("params", params).put("time", seconds).put("nonce", nonce)
            .put("sign", md5(key, params, (nowMs / 1000 * 1000).toString(), nonce, "ZdJqM15EeO2zWc08", extra))
            .put("extra", extra))
        return Request(body.toString(), mapOf(
            "Method" to "GetQimei", "Service" to "trpc.tme_datasvr.qimeiproxy.QimeiProxy", "Appid" to "qimei_qq_android",
            "Sign" to md5("qimei_qq_androidpzAuCmaFAaFaHrdakPjLIEqKrGnSOOvH", seconds), "Timestamp" to seconds,
            "Content-Type" to "application/json", "User-Agent" to "QQMusic",
        ))
    }

    /** The reply nests its data as JSON objects or as strings holding JSON. Returns (q16, q36). */
    fun parse(bytes: ByteArray): Pair<String, String> {
        fun embedded(value: Any?): JSONObject? = when (value) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        }
        val outer = embedded(String(bytes, Charsets.UTF_8))
        val data = embedded(embedded(outer?.opt("data"))?.opt("data"))
        return data?.text("q16").orEmpty() to data?.text("q36").orEmpty()
    }

    private fun utc(pattern: String, ms: Long) = SimpleDateFormat(pattern, Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}

internal fun md5(vararg parts: String): String = MessageDigest.getInstance("MD5")
    .digest(parts.joinToString("").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun hex(random: SecureRandom, bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
internal fun digits(random: SecureRandom, count: Int): String = buildString { repeat(count) { append(random.nextInt(10)) } }
