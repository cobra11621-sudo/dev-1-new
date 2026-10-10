package com.cobra.dev1new.domain

import java.time.Instant
import java.time.ZoneId

enum class LegPhase { READY, PLANNED, ONBOARD, COMPLETE }

data class SubwayTripSelection(
    val serviceDate: String,
    val trainKey: String,
    val departureEpochMillis: Long,
    val arrivalsEpochMillis: List<Long>,
    val departuresEpochMillis: List<Long>
)

data class KtxSchedule(
    val departureTime: String = "",
    val arrivalTime: String = "",
    val trainIdentifier: String = "",
    val platform: String = "",
    val car: String = "",
    val seat: String = ""
)

data class LegProgress(
    val journeyId: JourneyId,
    val legIndex: Int,
    val phase: LegPhase = LegPhase.READY,
    val stopIndex: Int = 0,
    val plannedAtEpochMillis: Long? = null,
    val boardedAtEpochMillis: Long? = null,
    val journeyStartedAtEpochMillis: Long? = null,
    val selectedSubwayTrip: SubwayTripSelection? = null,
    val arrivalVehicles: List<VehicleArrival> = emptyList(),
    val arrivalFetchedAtEpochMillis: Long? = null,
    val plannedSubwayText: String = "",
    val busTwoStopAlertSent: Boolean = false,
    val busOneStopAlertSent: Boolean = false
)

data class TravelSnapshot(
    /** At most one route owns the active planned/onboard trip at a time. */
    val activeJourneyId: JourneyId? = null,
    val journeys: Map<JourneyId, LegProgress> = emptyMap(),
    val ktxSchedules: Map<JourneyId, KtxSchedule> = emptyMap(),
    val updatedAtEpochMillis: Long = 0L,
    val lastTransition: String = ""
) {
    fun activeProgress(): LegProgress? = activeJourneyId?.let(journeys::get)
}

/** Transient UI feedback for an explicit recovery request; never persisted as journey progress. */
data class RecoveryStatus(
    val journeyId: JourneyId,
    val headline: String,
    val detail: String
)

sealed interface TransitionResult {
    data class Applied(val snapshot: TravelSnapshot) : TransitionResult
    data class Rejected(val snapshot: TravelSnapshot, val reason: String) : TransitionResult
}

/** Pure state transitions; UI, service, and notification code must all call this reducer. */
object JourneyReducer {
    fun startInitialPlan(
        current: TravelSnapshot,
        journeyId: JourneyId,
        nowEpochMillis: Long
    ): TransitionResult {
        val definition = RouteCatalog.journey(journeyId)
        val firstLeg = definition.legs.firstOrNull()
            ?: return TransitionResult.Rejected(current, "경로에 교통수단이 없습니다.")
        if (firstLeg.kind != TransportKind.BUS) {
            return TransitionResult.Rejected(current, "첫 수동 계획은 버스만 시작할 수 있습니다.")
        }
        val reset = current.journeys.toMutableMap()
        current.activeJourneyId?.let { previous ->
            reset[previous] = LegProgress(previous, 0)
        }
        reset[journeyId] = LegProgress(
            journeyId = journeyId,
            legIndex = 0,
            phase = LegPhase.PLANNED,
            plannedAtEpochMillis = nowEpochMillis,
            journeyStartedAtEpochMillis = nowEpochMillis
        )
        return TransitionResult.Applied(
            TravelSnapshot(
                activeJourneyId = journeyId,
                journeys = reset,
                ktxSchedules = current.ktxSchedules,
                updatedAtEpochMillis = nowEpochMillis,
                lastTransition = "manual_initial_plan"
            )
        )
    }

    fun cancelPlan(current: TravelSnapshot, nowEpochMillis: Long): TransitionResult {
        val progress = current.activeProgress()
            ?: return TransitionResult.Rejected(current, "취소할 이동이 없습니다.")
        val updated = current.journeys.toMutableMap()
        // An explicit user cancellation must also clear a wrongly recovered
        // onboard leg. Leaving that state persisted would recreate the live
        // service and its Now Bar card the next time the app opens.
        updated[progress.journeyId] = LegProgress(progress.journeyId, 0)
        return TransitionResult.Applied(
            current.copy(
                activeJourneyId = null,
                journeys = updated,
                updatedAtEpochMillis = nowEpochMillis,
                lastTransition = "journey_cancelled"
            )
        )
    }

    fun autoBoard(current: TravelSnapshot, nowEpochMillis: Long): TransitionResult {
        val progress = current.activeProgress()
            ?: return TransitionResult.Rejected(current, "활성 여정이 없습니다.")
        if (progress.phase != LegPhase.PLANNED) {
            return TransitionResult.Rejected(current, "자동 탑승 대상이 계획 상태가 아닙니다.")
        }
        val leg = RouteCatalog.journey(progress.journeyId).legs[progress.legIndex]
        if (leg.kind == TransportKind.SUBWAY) {
            return TransitionResult.Rejected(current, "지하철 탑승은 사용자 확인이 필요합니다.")
        }
        return setOnboard(current, progress, nowEpochMillis, null, "auto_boarded")
    }

    fun manualBoardSubway(
        current: TravelSnapshot,
        selectedTrip: SubwayTripSelection,
        nowEpochMillis: Long
    ): TransitionResult {
        val progress = current.activeProgress()
            ?: return TransitionResult.Rejected(current, "활성 여정이 없습니다.")
        if (progress.phase != LegPhase.PLANNED) {
            return TransitionResult.Rejected(current, "지하철 탑승예정 상태가 아닙니다.")
        }
        val definition = RouteCatalog.journey(progress.journeyId)
        val leg = definition.legs[progress.legIndex]
        if (leg.kind != TransportKind.SUBWAY || !definition.subwayManualBoarding) {
            return TransitionResult.Rejected(current, "이 구간은 수동 지하철 탑승 대상이 아닙니다.")
        }
        if (selectedTrip.arrivalsEpochMillis.size != leg.stops.size ||
            selectedTrip.departuresEpochMillis.size != leg.stops.size) {
            return TransitionResult.Rejected(current, "선택한 열차의 역별 시간표가 경로와 맞지 않습니다.")
        }
        return setOnboard(current, progress, nowEpochMillis, selectedTrip, "manual_subway_boarded")
    }

    /**
     * A user explicitly requested recovery from a newly received outdoor GPS
     * fix. This is the one canonical state transition for all UI/service
     * surfaces; it never edits an Activity-only copy of the journey.
     */
    fun recoverAt(
        current: TravelSnapshot,
        journeyId: JourneyId,
        legIndex: Int,
        phase: LegPhase,
        stopIndex: Int,
        nowEpochMillis: Long
    ): TransitionResult {
        val route = RouteCatalog.journey(journeyId)
        val leg = route.legs.getOrNull(legIndex)
            ?: return TransitionResult.Rejected(current, "복구할 교통수단을 찾지 못했습니다.")
        if (phase == LegPhase.READY || phase == LegPhase.COMPLETE) {
            return TransitionResult.Rejected(current, "복구 상태가 올바르지 않습니다.")
        }
        val journeys = current.journeys.toMutableMap()
        current.activeJourneyId?.takeIf { it != journeyId }?.let { journeys[it] = LegProgress(it, 0) }
        journeys[journeyId] = LegProgress(
            journeyId = journeyId,
            legIndex = legIndex,
            phase = phase,
            stopIndex = stopIndex.coerceIn(-1, leg.stops.lastIndex.coerceAtLeast(0)),
            plannedAtEpochMillis = nowEpochMillis,
            boardedAtEpochMillis = if (phase == LegPhase.ONBOARD) nowEpochMillis else null,
            journeyStartedAtEpochMillis = nowEpochMillis
        )
        return TransitionResult.Applied(current.copy(
            activeJourneyId = journeyId,
            journeys = journeys,
            updatedAtEpochMillis = nowEpochMillis,
            lastTransition = "gps_position_recovered"
        ))
    }

    private fun setOnboard(
        current: TravelSnapshot,
        progress: LegProgress,
        nowEpochMillis: Long,
        subwayTrip: SubwayTripSelection?,
        event: String
    ): TransitionResult {
        val updated = current.journeys.toMutableMap()
        updated[progress.journeyId] = progress.copy(
            phase = LegPhase.ONBOARD,
            // Bus progress stores the last confirmed-passed stop.  -1 keeps
            // the boarding/origin stop visible until the first 50m.
            stopIndex = if (RouteCatalog.journey(progress.journeyId).legs[progress.legIndex].kind == TransportKind.BUS) -1 else 0,
            boardedAtEpochMillis = nowEpochMillis,
            selectedSubwayTrip = subwayTrip,
            busTwoStopAlertSent = false,
            busOneStopAlertSent = false
        )
        return TransitionResult.Applied(
            current.copy(
                journeys = updated,
                updatedAtEpochMillis = nowEpochMillis,
                lastTransition = event
            )
        )
    }

    fun updateStopIndex(
        current: TravelSnapshot,
        reportedIndex: Int,
        nowEpochMillis: Long
    ): TransitionResult {
        val progress = current.activeProgress()
            ?: return TransitionResult.Rejected(current, "활성 여정이 없습니다.")
        if (progress.phase != LegPhase.ONBOARD) {
            return TransitionResult.Rejected(current, "탑승 중인 구간이 없습니다.")
        }
        val stops = RouteCatalog.allStops(progress.journeyId, progress.legIndex)
        if (stops.isEmpty()) return TransitionResult.Rejected(current, "정류장 경로가 비었습니다.")
        val nextIndex = reportedIndex.coerceIn(progress.stopIndex, stops.lastIndex)
        if (nextIndex == progress.stopIndex) {
            return TransitionResult.Applied(current.copy(updatedAtEpochMillis = nowEpochMillis))
        }
        val updated = current.journeys.toMutableMap()
        updated[progress.journeyId] = progress.copy(stopIndex = nextIndex)
        return TransitionResult.Applied(
            current.copy(
                journeys = updated,
                updatedAtEpochMillis = nowEpochMillis,
                lastTransition = "progress_updated"
            )
        )
    }

    /** Mark one bus pre-arrival threshold atomically so service restarts cannot repeat it. */
    fun markBusPreArrivalAlert(
        current: TravelSnapshot,
        threshold: Int,
        nowEpochMillis: Long
    ): TransitionResult {
        val progress = current.activeProgress()
            ?: return TransitionResult.Rejected(current, "활성 여정이 없습니다.")
        val leg = RouteCatalog.journey(progress.journeyId).legs[progress.legIndex]
        if (progress.phase != LegPhase.ONBOARD || leg.kind != TransportKind.BUS) {
            return TransitionResult.Rejected(current, "버스 탑승 중이 아닙니다.")
        }
        val alreadySent = when (threshold) {
            2 -> progress.busTwoStopAlertSent
            1 -> progress.busOneStopAlertSent
            else -> return TransitionResult.Rejected(current, "지원하지 않는 알림 기준입니다.")
        }
        if (alreadySent) return TransitionResult.Rejected(current, "이미 보낸 하차 전 알림입니다.")
        val updated = current.journeys.toMutableMap()
        updated[progress.journeyId] = when (threshold) {
            2 -> progress.copy(busTwoStopAlertSent = true)
            else -> progress.copy(busOneStopAlertSent = true)
        }
        return TransitionResult.Applied(current.copy(
            journeys = updated,
            updatedAtEpochMillis = nowEpochMillis,
            lastTransition = "bus_${threshold}_stops_alerted"
        ))
    }

    /** Automatic alighting and automatic next-plan creation happen atomically. */
    fun autoAlight(current: TravelSnapshot, nowEpochMillis: Long): TransitionResult {
        val progress = current.activeProgress()
            ?: return TransitionResult.Rejected(current, "활성 여정이 없습니다.")
        if (progress.phase != LegPhase.ONBOARD) {
            return TransitionResult.Rejected(current, "하차할 탑승 구간이 없습니다.")
        }
        return advanceToNextPlan(current, progress, nowEpochMillis, "auto_alighted")
    }

    /**
     * A KTX that never received a usable in-car GPS fix must not block the
     * already scheduled following bus. The service calls this only after the
     * configured KTX arrival time has passed.
     */
    fun advancePlannedKtxAtScheduledArrival(
        current: TravelSnapshot,
        nowEpochMillis: Long
    ): TransitionResult {
        val progress = current.activeProgress()
            ?: return TransitionResult.Rejected(current, "활성 여정이 없습니다.")
        val leg = RouteCatalog.journey(progress.journeyId).legs[progress.legIndex]
        if (progress.phase != LegPhase.PLANNED || leg.kind != TransportKind.KTX) {
            return TransitionResult.Rejected(current, "시간표 전환 대상 KTX 탑승예정이 아닙니다.")
        }
        return advanceToNextPlan(current, progress, nowEpochMillis, "ktx_scheduled_arrival")
    }

    private fun advanceToNextPlan(
        current: TravelSnapshot,
        progress: LegProgress,
        nowEpochMillis: Long,
        eventPrefix: String
    ): TransitionResult {
        val route = RouteCatalog.journey(progress.journeyId)
        val nextIndex = progress.legIndex + 1
        val updated = current.journeys.toMutableMap()
        if (nextIndex >= route.legs.size) {
            updated[progress.journeyId] = progress.copy(
                phase = LegPhase.COMPLETE,
                stopIndex = RouteCatalog.allStops(progress.journeyId, progress.legIndex).lastIndex,
                selectedSubwayTrip = progress.selectedSubwayTrip
            )
            return TransitionResult.Applied(
                current.copy(
                    activeJourneyId = null,
                    journeys = updated,
                    updatedAtEpochMillis = nowEpochMillis,
                    lastTransition = "${eventPrefix}_journey_complete"
                )
            )
        }
        updated[progress.journeyId] = LegProgress(
            journeyId = progress.journeyId,
            legIndex = nextIndex,
            phase = LegPhase.PLANNED,
            plannedAtEpochMillis = nowEpochMillis,
            journeyStartedAtEpochMillis = progress.journeyStartedAtEpochMillis
                ?: progress.plannedAtEpochMillis
                ?: progress.boardedAtEpochMillis
        )
        return TransitionResult.Applied(
            current.copy(
                journeys = updated,
                updatedAtEpochMillis = nowEpochMillis,
                lastTransition = "${eventPrefix}_next_plan_created"
            )
        )
    }

    /** Reset an active commute/return chain when its original service date has passed. */
    fun resetForNewDay(current: TravelSnapshot, nowEpochMillis: Long): TransitionResult {
        val progress = current.activeProgress()
            ?: return TransitionResult.Rejected(current, "활성 여정이 없습니다.")
        if (progress.journeyId != JourneyId.COMMUTE && progress.journeyId != JourneyId.RETURN) {
            return TransitionResult.Rejected(current, "일일 초기화 대상 여정이 아닙니다.")
        }
        val startedAt = progress.journeyStartedAtEpochMillis
            ?: progress.plannedAtEpochMillis
            ?: progress.boardedAtEpochMillis
            ?: return TransitionResult.Rejected(current, "여정 시작일을 확인할 수 없습니다.")
        val zone = ZoneId.systemDefault()
        val startedDate = Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
        if (startedDate == today) return TransitionResult.Rejected(current, "오늘 시작한 여정입니다.")

        val journeys = current.journeys.toMutableMap()
        journeys[progress.journeyId] = LegProgress(progress.journeyId, 0)
        return TransitionResult.Applied(
            current.copy(
                activeJourneyId = null,
                journeys = journeys,
                updatedAtEpochMillis = nowEpochMillis,
                lastTransition = "midnight_reset"
            )
        )
    }
}
