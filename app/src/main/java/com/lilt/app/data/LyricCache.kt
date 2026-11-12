package com.lilt.app.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Companion to transient audio, including original word timings and translated lines. */
class LyricCache(context: Context) {
    private val directory = File(context.cacheDir, "audio-lyrics")

    fun get(mid: String): Lyrics? = synchronized(lock) {
        runCatching { OfflineLyrics.decode(AtomicFile(file(mid)).readFully().toString(Charsets.UTF_8)) }.getOrNull()
    }

    fun put(mid: String, lyrics: Lyrics) = synchronized(lock) {
        directory.mkdirs()
        val target = AtomicFile(file(mid))
        val output = target.startWrite()
        try {
            output.write(OfflineLyrics.encode(lyrics).toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (error: Exception) {
            target.failWrite(output)
            throw error
        }
    }

    fun clear() = synchronized(lock) { directory.listFiles()?.forEach { it.delete() }; Unit }

    private fun file(mid: String) = File(directory,
        MessageDigest.getInstance("SHA-256").digest(mid.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".json")

    private companion object { val lock = Any() }
}
