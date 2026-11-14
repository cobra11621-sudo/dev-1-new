package com.cobra.dev1new.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object KtxScheduleResolver {
    private val timePattern = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA)

    /**
     * Imported dev-3 tickets carry their actual travel date. That date is
     * authoritative for KTX tracking and recovery; a blank date preserves the
     * existing nearest-clock behavior for manually entered legacy settings.
     */
    fun departureEpochMillis(schedule: KtxSchedule, referenceEpochMillis: Long): Long? {
        if (schedule.travelDate.isNotBlank()) {
            return resolveOnDate(schedule.travelDate, schedule.departureTime)
        }
        return resolveNearest(schedule.departureTime, referenceEpochMillis)
    }

    fun resolveOnDate(dateText: String, clockText: String): Long? {
        if (!timePattern.matches(clockText)) return null
        val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: return null
        return LocalDateTime.of(date, LocalTime.parse(clockText))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    fun arrivalEpochMillis(schedule: KtxSchedule, departureEpochMillis: Long): Long? {
        if (!timePattern.matches(schedule.arrivalTime)) return null
        val zone = ZoneId.systemDefault()
        val departure = Instant.ofEpochMilli(departureEpochMillis).atZone(zone).toLocalDateTime()
        var arrival = LocalDateTime.of(departure.toLocalDate(), LocalTime.parse(schedule.arrivalTime))
        if (arrival.isBefore(departure)) arrival = arrival.plusDays(1)
        return arrival.atZone(zone).toInstant().toEpochMilli()
    }

    fun resolveNearest(clockText: String, referenceEpochMillis: Long): Long? {
        if (!timePattern.matches(clockText)) return null
        val zone = ZoneId.systemDefault()
        val reference = Instant.ofEpochMilli(referenceEpochMillis).atZone(zone).toLocalDateTime()
        val time = LocalTime.parse(clockText)
        return (-1L..1L)
            .map { LocalDateTime.of(reference.toLocalDate().plusDays(it), time) }
            .minByOrNull { Duration.between(reference, it).abs() }
            ?.atZone(zone)?.toInstant()?.toEpochMilli()
    }

    fun displayClock(epochMillis: Long?): String = epochMillis?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(clockFormat)
    }.orEmpty()

    fun notificationTrainIdentifier(value: String): String {
        val number = Regex("(?<!\\d)(\\d{3,4})(?!\\d)").find(value)?.groupValues?.getOrNull(1)
        return number ?: value.ifBlank { "KTX" }
    }
}
