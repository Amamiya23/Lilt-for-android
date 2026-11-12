package com.lilt.app.data

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Small account-scoped JSON snapshots. Details use a bounded least-recently-used file set. */
class MetadataCache(private val root: File, private val detailLimit: Int = 40,
    private val now: () -> Long = System::currentTimeMillis) {
    init { require(detailLimit > 0) }

    @Synchronized fun home(account: String): HomeFeed? = read(account, "home")?.let { json ->
        runCatching { ModelJson.decodeHome(json) }.getOrNull()
    }

    @Synchronized fun putHome(account: String, home: HomeFeed) = write(account, "home", ModelJson.encodeHome(home))

    @Synchronized fun homeUpdatedAt(account: String): Long? = updatedAt(account, "home")

    @Synchronized fun detailUpdatedAt(account: String, id: String): Long? = updatedAt(account, "detail-${digest(id)}")

    @Synchronized fun library(account: String): List<Playlist>? = read(account, "library")?.let { json ->
        runCatching { ModelJson.decodePlaylists(json.getJSONArray("playlists")) }.getOrNull()
    }

    @Synchronized fun putLibrary(account: String, playlists: List<Playlist>) =
        write(account, "library", JSONObject().put("playlists", ModelJson.encodePlaylists(playlists)))

    @Synchronized fun detail(account: String, id: String): PlaylistDetail? {
        val key = "detail-${digest(id)}"
        return read(account, key)?.let { json ->
            runCatching { ModelJson.decodeDetail(json) }.getOrNull()?.also { touch(account, key) }
        }
    }

    @Synchronized fun putDetail(account: String, id: String, detail: PlaylistDetail) {
        val key = "detail-${digest(id)}"
        write(account, key, ModelJson.encodeDetail(detail))
        touch(account, key)
        directory(account).listFiles().orEmpty().filter { it.name.startsWith("detail-") && it.extension == "json" }
            .sortedByDescending(File::lastModified).drop(detailLimit).forEach(File::delete)
    }

    @Synchronized fun liked(account: String): Map<String, Boolean> = read(account, "liked")?.let { json ->
        runCatching { json.keys().asSequence().associateWith { json.getBoolean(it) } }.getOrNull()
    }.orEmpty()

    @Synchronized fun putLiked(account: String, statuses: Map<String, Boolean>) {
        val combined = liked(account) + statuses
        write(account, "liked", JSONObject(combined))
    }

    @Synchronized fun clear(account: String) { directory(account).deleteRecursively() }

    private fun read(account: String, key: String): JSONObject? = runCatching {
        val json = JSONObject(file(account, key).readText())
        if (json.getInt("version") != VERSION) null else json.getJSONObject("data")
    }.getOrNull()

    private fun updatedAt(account: String, key: String): Long? = runCatching {
        val json = JSONObject(file(account, key).readText())
        if (json.getInt("version") != VERSION) null else json.optLong("updatedAt").takeIf { it > 0 }
    }.getOrNull()

    private fun write(account: String, key: String, value: JSONObject) {
        val destination = file(account, key)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(JSONObject().put("version", VERSION).put("updatedAt", now()).put("data", value).toString())
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun directory(account: String) = File(root, digest(account))
    private fun file(account: String, key: String) = File(directory(account), "$key.json")

    private fun touch(account: String, key: String) {
        val latest = directory(account).listFiles().orEmpty().maxOfOrNull(File::lastModified) ?: 0L
        file(account, key).setLastModified(maxOf(System.currentTimeMillis(), latest + 1))
    }

    companion object {
        private const val VERSION = 1
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
