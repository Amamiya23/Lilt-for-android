package com.lilt.app.playback

import java.net.URLDecoder
import java.net.URLEncoder

/** Versioned identity; display metadata and expiring stream URLs never identify audio. */
internal object AudioCacheKey {
    private const val PREFIX = "audio-v2|"
    fun of(mid: String, quality: String): String = PREFIX + encode(mid) + "|" + encode(quality)
    fun parse(key: String): Pair<String, String>? = runCatching {
        if (!key.startsWith(PREFIX)) return null
        val parts = key.removePrefix(PREFIX).split('|')
        if (parts.size != 2) return null
        decode(parts[0]) to decode(parts[1])
    }.getOrNull()
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun decode(value: String) = URLDecoder.decode(value, Charsets.UTF_8.name())
}

/** Online playback always uses the requested quality; only offline playback may substitute. */
internal fun selectCachedQuality(requested: String, complete: Set<String>, online: Boolean): String? {
    if (online) return requested
    if (requested in complete) return requested
    val order = listOf("hires", "lossless", "exhigh", "higher", "hq", "standard")
    return complete.minWithOrNull(compareBy<String> { order.indexOf(it).takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE }.thenBy { it })
}

/** Unknown lengths, holes and prefixes are deliberately not classified as complete files. */
internal fun fullyCached(length: Long, spans: List<Pair<Long, Long>>): Boolean {
    if (length <= 0) return false
    var end = 0L
    for ((position, size) in spans.sortedBy { it.first }) {
        if (position > end) return false
        end = maxOf(end, position + size)
        if (end >= length) return true
    }
    return false
}

/** Bounded fallback even when repeat-one/all is enabled and every song is unavailable. */
internal fun nextPlayableIndex(order: List<Int>, current: Int, playable: (Int) -> Boolean): Int? =
    order.dropWhile { it != current }.drop(1).plus(order.takeWhile { it != current }).firstOrNull(playable)
