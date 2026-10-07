package com.cobra.dev1new.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalFormatterTest {
    private fun localEpoch(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, 4, hour, minute)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun clock(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA))

    @Test
    fun tenStopsDoNotIncreaseSixMinuteProviderEta() {
        val fetchedAt = localEpoch(10, 0)
        val result = ArrivalFormatter.formatPlanned(
            listOf(VehicleArrival(remainingSeconds = 6L * 60L, remainingStops = 10)),
            fetchedAt,
            fetchedAt
        )

        assertEquals("${clock(fetchedAt + 6L * 60_000L)}(10개, 6분)", result)
    }

    @Test
    fun every_bus_formatter_keeps_the_clock_stop_wait_contract() {
        val fetchedAt = localEpoch(10, 53)

        assertEquals(
            "11:00(5개, 7분)",
            ArrivalFormatter.formatVehicle(
                VehicleArrival(remainingSeconds = 7L * 60L, remainingStops = 5),
                fetchedAt
            )
        )
    }

    @Test
    fun eachVehicleKeepsItsOwnEtaAndStopCount() {
        val fetchedAt = localEpoch(10, 0)
        val result = ArrivalFormatter.formatPlanned(
            listOf(
                VehicleArrival(remainingSeconds = 0, remainingStops = 14),
                VehicleArrival(remainingSeconds = 18L * 60L, remainingStops = 15)
            ),
            fetchedAt,
            fetchedAt
        )

        assertEquals(
            "${clock(fetchedAt)}(0개, 0분)/${clock(fetchedAt + 18L * 60_000L)}(15개, 18분)",
            result
        )
    }

    @Test
    fun oldResponseAndAlreadyElapsedEstimateAreNotPresentedAsLive() {
        val fetchedAt = localEpoch(10, 0)
        val expired = ArrivalFormatter.formatPlanned(
            listOf(VehicleArrival(remainingSeconds = 30, remainingStops = 1)),
            fetchedAt,
            fetchedAt + 100_000L
        )
        val stale = ArrivalFormatter.formatPlanned(
            listOf(VehicleArrival(remainingSeconds = 5L * 60L, remainingStops = 4)),
            fetchedAt,
            fetchedAt + 121_000L
        )

        assertEquals("도착 예정정보 갱신 대기", expired)
        assertEquals("실시간 도착정보 갱신 지연", stale)
    }
}
