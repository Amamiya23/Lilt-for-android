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
import java.io.File
import java.util.TreeSet

/** Cached online audio. Its size limit is read on every write, so a new limit applies without restarting. */
@UnstableApi
class AudioCache(context: Context) {
    private val preferences = context.getSharedPreferences("lilt_settings", Context.MODE_PRIVATE)
    val cache: SimpleCache = SimpleCache(File(context.cacheDir, "audio"), DynamicLruEvictor { limitBytes },
        StandaloneDatabaseProvider(context))

    val limitBytes: Long get() = preferences.getLong("cache_limit_mb", 2048L) * 1024 * 1024
    val usedBytes: Long get() = cache.cacheSpace

    fun clear() { cache.keys.toList().forEach(cache::removeResource) }

    /** Evicts least recently used spans beyond a limit that may change while the cache is open. */
    private class DynamicLruEvictor(private val limit: () -> Long) : CacheEvictor {
        private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan>({ it.lastTouchTimestamp }, { it.key }, { it.position }))
        private var size = 0L
        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() {}
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            if (length != androidx.media3.common.C.LENGTH_UNSET.toLong()) evict(cache, length)
        }
        override fun onSpanAdded(cache: Cache, span: CacheSpan) { spans.add(span); size += span.length; evict(cache, 0) }
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) { if (spans.remove(span)) size -= span.length }
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan)
        }
        private fun evict(cache: Cache, required: Long) {
            while (size + required > limit() && spans.isNotEmpty()) cache.removeSpan(spans.first())
        }
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
