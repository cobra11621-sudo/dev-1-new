package com.cobra.dev1new.domain

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KtxScheduleResolverTest {
    @Test
    fun nearestServiceClockCanResolveAcrossMidnight() {
        val zone = ZoneId.systemDefault()
        val reference = LocalDateTime.of(2026, 10, 4, 23, 50).atZone(zone).toInstant().toEpochMilli()
        val expected = LocalDateTime.of(2026, 10, 5, 0, 10).atZone(zone).toInstant().toEpochMilli()

        assertEquals(expected, KtxScheduleResolver.resolveNearest("00:10", reference))
    }

    @Test
    fun invalidClockIsRejectedAndTrainNumberIsNormalized() {
        assertNull(KtxScheduleResolver.resolveNearest("25:90", 1_800_000_000_000L))
        assertEquals("054", KtxScheduleResolver.notificationTrainIdentifier("KTX-산천 054"))
    }
}
