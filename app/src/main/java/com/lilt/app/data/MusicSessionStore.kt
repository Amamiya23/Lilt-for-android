package com.lilt.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class MusicSession(
    val musicId: String,
    val musicKey: String,
    val loginType: Int,
    val createdAt: Long = 0,
    val expiresIn: Long = 0,
    val name: String = "",
    val avatar: String = "",
    val encryptUin: String = "",
    val openId: String = "",
    val refreshToken: String = "",
    val unionId: String = "",
    val refreshKey: String = "",
) {
    val valid: Boolean get() = musicId.toLongOrNull()?.let { it > 0 } == true && musicKey.isNotBlank() &&
        loginType in 1..2 && (createdAt <= 0 || expiresIn <= 0 || System.currentTimeMillis() / 1000 < createdAt + expiresIn)
    val profile: Profile get() = Profile(name.ifBlank { musicId }, avatar, musicId)
    val cookie: String get() = "uin=$musicId; qqmusic_uin=$musicId; qm_keyst=$musicKey; qqmusic_key=$musicKey"

    fun toJson(): JSONObject = JSONObject().apply {
        put("musicid", musicId); put("musickey", musicKey); put("loginType", loginType)
        put("musickeyCreateTime", createdAt); put("keyExpiresIn", expiresIn)
        put("nick", name); put("logo", avatar); put("encryptUin", encryptUin)
        put("openid", openId); put("refresh_token", refreshToken); put("unionid", unionId); put("refresh_key", refreshKey)
    }

    companion object {
        fun fromJson(data: JSONObject) = MusicSession(
            data.opt("musicid")?.toString().orEmpty(), data.text("musickey"), data.optInt("loginType"),
            data.number("musickeyCreateTime"), data.number("keyExpiresIn"), data.text("nick"), data.text("logo"),
            data.text("encryptUin"), data.text("openid"), data.text("refresh_token"), data.text("unionid"), data.text("refresh_key"),
        )
    }
}

/** Only the service credential is persisted, encrypted using an app-scoped Keystore key. */
internal class MusicSessionStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "music-session.enc"))
    private val keyAlias = "${context.packageName}.music-session"

    fun read(): MusicSession? = runCatching {
        if (!file.baseFile.exists()) return null
        val buffer = ByteBuffer.wrap(file.readFully())
        val ivLength = buffer.get().toInt()
        require(ivLength == 12)
        val iv = ByteArray(ivLength).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        MusicSession.fromJson(JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8)))
    }.getOrNull()

    fun write(session: MusicSession) {
        require(session.valid)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(session.toJson().toString().toByteArray(Charsets.UTF_8))
        val bytes = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte()).put(cipher.iv).put(encrypted).array()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw MusicApiException("无法安全保存登录状态，请重试", cause = error)
        }
    }

    fun clear() { file.delete() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }
}
