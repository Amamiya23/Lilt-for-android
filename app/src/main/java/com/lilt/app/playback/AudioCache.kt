package com.lilt.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.ContentMetadata
import com.lilt.app.data.LyricCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.TreeSet

/** Cached online audio. Its size limit is read on every write, so a new limit applies without restarting. */
@UnstableApi
class AudioCache(context: Context) {
    private val preferences = context.getSharedPreferences("lilt_settings", Context.MODE_PRIVATE)
    private val lyrics = LyricCache(context)
    private val complete = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val mutableAvailable = MutableStateFlow<Set<String>>(emptySet())
    val available = mutableAvailable.asStateFlow()
    private val mutableGeneration = MutableStateFlow(0L)
    /** A clear cancels in-flight queue prefetch before it can refill the cleared cache. */
    val generation = mutableGeneration.asStateFlow()
    @Volatile private var ready = false
    val cache: SimpleCache = SimpleCache(File(context.cacheDir, "audio"), DynamicLruEvictor({ limitBytes }) { if (ready) refresh(it) },
        StandaloneDatabaseProvider(context))

    init {
        // The previous URI keys contained display fields. They are intentionally discarded.
        cache.keys.filter { AudioCacheKey.parse(it) == null }.forEach(cache::removeResource)
        ready = true
        refresh()
    }

    val limitBytes: Long get() = preferences.getLong("cache_limit_mb", 2048L) * 1024 * 1024
    val usedBytes: Long get() = cache.cacheSpace

    fun cachedQuality(mid: String, requested: String, online: Boolean): String? =
        selectCachedQuality(requested, complete.value[mid].orEmpty(), online)

    fun clear() {
        mutableGeneration.value += 1
        cache.keys.toList().forEach(cache::removeResource)
        lyrics.clear()
        refresh()
    }

    /** Also run after closing a writer: its content length may change after the final span. */
    fun refresh() = refresh(cache)

    private fun refresh(source: Cache) {
        val result = mutableMapOf<String, MutableSet<String>>()
        source.keys.forEach { key ->
            val (mid, quality) = AudioCacheKey.parse(key) ?: return@forEach
            val length = ContentMetadata.getContentLength(source.getContentMetadata(key))
            if (fullyCached(length, source.getCachedSpans(key).map { it.position to it.length }))
                result.getOrPut(mid) { mutableSetOf() }.add(quality)
        }
        complete.value = result
        mutableAvailable.value = result.keys.toSet()
    }

    /** Evicts least recently used spans beyond a limit that may change while the cache is open. */
    private class DynamicLruEvictor(private val limit: () -> Long, private val changed: (Cache) -> Unit) : CacheEvictor {
        private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan>({ it.lastTouchTimestamp }, { it.key }, { it.position }))
        private var size = 0L
        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() {}
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            if (length != androidx.media3.common.C.LENGTH_UNSET.toLong()) evict(cache, length)
        }
        override fun onSpanAdded(cache: Cache, span: CacheSpan) { spans.add(span); size += span.length; evict(cache, 0); changed(cache) }
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) { if (spans.remove(span)) size -= span.length; changed(cache) }
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            // A touch changes recency only. Coverage and the complete-song set stay the same.
            spans.remove(oldSpan)
            spans.add(newSpan)
            evict(cache, 0)
        }
        private fun evict(cache: Cache, required: Long) {
            while (size + required > limit() && spans.isNotEmpty()) cache.removeSpan(spans.first())
        }
    }
}

/** Select a complete alternative before entering Media3's cache, never in its URL resolver. */
@UnstableApi
class CachedAudioDataSource(
    private val audio: AudioCache,
    private val isOnline: () -> Boolean,
    private val online: DataSource.Factory,
    private val offline: DataSource.Factory,
) : DataSource {
    private var source: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
    override fun open(dataSpec: DataSpec): Long {
        val connected = isOnline()
        val mid = dataSpec.uri.lastPathSegment.orEmpty()
        val requested = dataSpec.uri.getQueryParameter("quality") ?: "standard"
        val quality = audio.cachedQuality(mid, requested, connected)
            ?: throw java.io.IOException("No complete cached audio")
        val actual = (if (connected) online else offline).createDataSource()
        listeners.forEach(actual::addTransferListener)
        source = actual
        return actual.open(dataSpec.buildUpon().setKey(AudioCacheKey.of(mid, quality)).build())
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = requireNotNull(source).read(buffer, offset, length)
    override fun getUri(): Uri? = source?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = source?.responseHeaders.orEmpty()
    override fun close() {
        try { source?.close() } finally { source = null; audio.refresh() }
    }

    class Factory(
        private val audio: AudioCache, private val isOnline: () -> Boolean,
        private val online: DataSource.Factory, private val offline: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource() = CachedAudioDataSource(audio, isOnline, online, offline)
    }
}

/** Online songs (lilt://) go through the cache; local files are read directly. */
@UnstableApi
class RoutingDataSource(private val online: DataSource.Factory, private val local: DataSource.Factory) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
    override fun open(dataSpec: DataSpec): Long {
        val source = (if (dataSpec.uri.scheme == "lilt") online else local).createDataSource()
        listeners.forEach(source::addTransferListener)
        current = source
        return source.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = requireNotNull(current).read(buffer, offset, length)
    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()
    override fun close() { try { current?.close() } finally { current = null } }

    class Factory(private val online: DataSource.Factory, private val local: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(online, local)
    }
}
