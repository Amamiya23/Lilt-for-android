package com.lilt.app.update

import android.content.Context
import android.os.Build
import com.lilt.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val packageName: String,
    val minSdk: Int,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val notes: String,
)

/** The release API locates assets; only the signed manifest is trusted for their contents. */
class UpdateRepository(context: Context, private val client: OkHttpClient = OkHttpClient()) {
    private val app = context.applicationContext

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val key = try {
            app.assets.open("update_public_key.pem").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw IllegalStateException("更新验证公钥未配置", e)
        }
        val release = JSONObject(fetch(LATEST_RELEASE, 1024 * 1024).toString(Charsets.UTF_8))
        val assets = release.getJSONArray("assets")
        fun assetUrl(name: String): String {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name") == name) return asset.getString("browser_download_url")
            }
            throw IllegalStateException("发布版本缺少 $name")
        }
        val manifest = fetch(assetUrl("update.json"), 256 * 1024)
        val signature = fetch(assetUrl("update.sig"), 8192)
        val info = verifyUpdateManifest(manifest, signature, key)
        require(info.packageName == app.packageName) { "更新包名不匹配" }
        if (info.versionCode <= BuildConfig.VERSION_CODE || info.minSdk > Build.VERSION.SDK_INT) null else info
    }

    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        requireHttps(info.apkUrl)
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val partial = File(dir, "update.partial")
        val result = File(dir, "update.apk")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "下载失败 (${response.code})" }
                val body = response.body ?: error("下载内容为空")
                var count = 0L
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            count += n
                            check(count <= info.size) { "更新文件大小不匹配" }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            onProgress((count * 100 / info.size).toInt())
                        }
                    }
                }
                check(count == info.size) { "更新文件大小不匹配" }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actual.equals(info.sha256, ignoreCase = true)) { "更新文件校验失败" }
            check(partial.renameTo(result) || run { partial.copyTo(result, overwrite = true); partial.delete(); true })
            result
        } finally {
            partial.delete()
        }
    }

    private fun fetch(url: String, limit: Int): ByteArray {
        requireHttps(url)
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "检查更新失败 (${response.code})" }
            val body = response.body ?: error("更新信息为空")
            return body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= limit) {
                    val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
                val data = output.toByteArray()
                check(data.size <= limit) { "更新信息过大" }
                data
            }
        }
    }

    companion object {
        private const val LATEST_RELEASE = "https://api.github.com/repos/Amamiya23/Lilt-for-android/releases/latest"
    }
}

internal fun verifyUpdateManifest(bytes: ByteArray, signature: ByteArray, pem: String): UpdateInfo {
    val encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "").replace(Regex("\\s"), "")
    val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
    val verifier = Signature.getInstance("SHA256withRSA")
    verifier.initVerify(key)
    verifier.update(bytes)
    check(verifier.verify(signature)) { "更新信息签名验证失败" }
    val json = JSONObject(bytes.toString(Charsets.UTF_8))
    return UpdateInfo(
        versionName = json.getString("versionName").also { require(it.isNotBlank()) },
        versionCode = json.getInt("versionCode").also { require(it > 0) },
        packageName = json.getString("packageName"),
        minSdk = json.getInt("minSdk").also { require(it > 0) },
        apkUrl = json.getString("apkUrl").also { requireHttps(it) },
        sha256 = json.getString("sha256").also { require(it.matches(Regex("[0-9a-fA-F]{64}"))) },
        size = json.getLong("size").also { require(it > 0) },
        notes = json.getString("notes"),
    )
}

private fun requireHttps(url: String) {
    require(url.startsWith("https://")) { "更新地址无效" }
}
