package com.lilt.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MetadataFreshnessTest {
    private val utc = ZoneId.of("UTC")
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun homeExpiresExactlyOneHourAfterTheSuccessfulWrite() {
        val written = millis("2026-11-12T10:00:00Z")
        assertTrue(MetadataFreshness.homeIsFresh(written, written))
        assertTrue(MetadataFreshness.homeIsFresh(written, written + 3_599_999))
        assertFalse(MetadataFreshness.homeIsFresh(written, written + 3_600_000))
        assertFalse(MetadataFreshness.homeIsFresh(written, written + 3_600_001))
    }

    @Test fun missingInvalidAndFutureSuccessTimesAreNeverFresh() {
        val now = millis("2026-11-12T10:00:00Z")
        for (written in listOf(null, -1L, 0L, now + 1)) {
            assertFalse(MetadataFreshness.homeIsFresh(written, now))
            assertFalse(MetadataFreshness.dailyIsFresh(written, now, utc))
        }
    }

    @Test fun dailyRemainsFreshForTheRestOfItsLocalDate() {
        val written = millis("2026-11-12T00:00:00Z")
        assertTrue(MetadataFreshness.dailyIsFresh(written, written, utc))
        assertTrue(MetadataFreshness.dailyIsFresh(written, millis("2026-11-12T23:59:59.999Z"), utc))
        assertFalse(MetadataFreshness.dailyIsFresh(written, millis("2026-11-13T00:00:00Z"), utc))
    }

    @Test fun dailyChangesAtLocalMidnightRatherThanUtcMidnight() {
        val written = millis("2026-11-12T15:59:59.999Z")
        val now = millis("2026-11-12T16:00:00Z")
        assertFalse(MetadataFreshness.dailyIsFresh(written, now, ZoneId.of("Asia/Shanghai")))
        assertTrue(MetadataFreshness.dailyIsFresh(written, now, utc))
    }

    @Test fun aLongLocalDayDoesNotExpireDailyRecommendationsAfterTwentyFourHours() {
        val zone = ZoneId.of("America/New_York")
        // Daylight saving time ends on November 1, making this local date 25 hours long.
        val written = millis("2026-11-01T04:00:00Z")
        assertTrue(MetadataFreshness.dailyIsFresh(written, millis("2026-11-02T04:59:59.999Z"), zone))
        assertFalse(MetadataFreshness.dailyIsFresh(written, millis("2026-11-02T05:00:00Z"), zone))
    }
}
