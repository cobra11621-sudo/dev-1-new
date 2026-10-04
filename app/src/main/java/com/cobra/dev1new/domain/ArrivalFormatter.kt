package com.cobra.dev1new.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

data class VehicleArrival(
    /** Provider-reported seconds, when available. */
    val remainingSeconds: Long? = null,
    /** Provider-reported whole minutes fallback. */
    val remainingMinutes: Int? = null,
    /** Stop count belonging to this same vehicle record. */
    val remainingStops: Int? = null,
    val stateLabel: String? = null
) {
    fun displayMinutes(): Int? = when {
        remainingSeconds != null -> ceil(remainingSeconds.coerceAtLeast(0L) / 60.0).toInt()
        remainingMinutes != null -> remainingMinutes.coerceAtLeast(0)
        else -> null
    }
}

object ArrivalFormatter {
    /** Minutes and stops are paired from one vehicle record; stop count never changes the ETA. */
    fun formatVehicle(item: VehicleArrival): String {
        val minutes = item.displayMinutes()
        if (minutes == null) return item.stateLabel.orEmpty()
        val stops = if (minutes == 0) 0 else item.remainingStops?.coerceAtLeast(0)
        return buildString {
            append(minutes).append("분")
            if (stops != null) append(", ").append(stops).append("개")
        }
    }

    fun formatVehicles(items: List<VehicleArrival>, limit: Int = 2): String {
        val rendered = items.take(limit).map(::formatVehicle).filter(String::isNotBlank)
        return rendered.joinToString("/").ifBlank { "현재 도착 예정 버스가 없습니다." }
    }

    /** Approved planned-bus surface: expected clock time, stops, and provider ETA, without stop-based correction. */
    fun formatPlanned(
        items: List<VehicleArrival>,
        fetchedAtEpochMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
        staleAfterMillis: Long = 120_000L,
        limit: Int = 2
    ): String {
        if (items.isEmpty()) return "현재 도착 예정 버스가 없습니다."
        val ageMillis = max(0L, nowEpochMillis - fetchedAtEpochMillis)
        if (ageMillis > staleAfterMillis) return "실시간 도착정보 갱신 지연"
        val rendered = items.take(limit).map { item ->
            val rawSeconds = item.remainingSeconds?.coerceAtLeast(0L)
                ?: item.remainingMinutes?.coerceAtLeast(0)?.toLong()?.times(60L)
                ?: return@map item.stateLabel.orEmpty()
            if (rawSeconds * 1_000L + 30_000L < ageMillis) {
                return@map "도착 예정정보 갱신 대기"
            }
            val secondsRemaining = (rawSeconds - ageMillis / 1_000L).coerceAtLeast(0L)
            val minutes = ceil(secondsRemaining / 60.0).toInt()
            val stops = if (minutes == 0) 0 else item.remainingStops?.coerceAtLeast(0)
            val clock = Instant.ofEpochMilli(fetchedAtEpochMillis + rawSeconds * 1_000L)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA))
            buildString {
                append(clock).append('(')
                if (stops != null) append(stops).append("개, ")
                append(minutes).append("분)")
            }
        }.filter(String::isNotBlank)
        return rendered.joinToString("/").ifBlank { "현재 도착 예정 버스가 없습니다." }
    }
}
