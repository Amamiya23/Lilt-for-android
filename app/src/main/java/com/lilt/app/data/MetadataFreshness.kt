package com.lilt.app.data

import java.time.Instant
import java.time.ZoneId

/** Freshness is based on successful writes, never on navigation or cache reads. */
object MetadataFreshness {
    const val HOME_VALIDITY_MS = 60 * 60 * 1_000L

    fun homeIsFresh(updatedAt: Long?, now: Long): Boolean =
        updatedAt != null && updatedAt > 0 && now >= updatedAt && now - updatedAt < HOME_VALIDITY_MS

    fun dailyIsFresh(updatedAt: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        updatedAt != null && updatedAt > 0 && now >= updatedAt &&
            Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
}
