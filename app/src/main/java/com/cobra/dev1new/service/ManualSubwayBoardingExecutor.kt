package com.cobra.dev1new.service

import com.cobra.dev1new.domain.JourneyId
import com.cobra.dev1new.domain.LegPhase
import com.cobra.dev1new.domain.RouteCatalog
import com.cobra.dev1new.domain.SubwayTripSelection
import com.cobra.dev1new.domain.TransitionResult
import com.cobra.dev1new.domain.TransportKind
import com.cobra.dev1new.domain.TravelSnapshot

/**
 * The notification-shade equivalent of the app's manual subway boarding.
 * It intentionally selects a complete CSV train first, then delegates the
 * persisted state change to the same repository/Reducer operation as the UI.
 */
internal object ManualSubwayBoardingExecutor {
    sealed interface Result {
        data class Applied(val selectedTrip: SubwayTripSelection) : Result
        data class Rejected(val reason: String) : Result
        data object Ignored : Result
    }

    suspend fun execute(
        snapshot: TravelSnapshot,
        tappedAtEpochMillis: Long,
        selectNearestTrip: suspend (JourneyId, Long) -> SubwayTripSelection,
        applyBoarding: (SubwayTripSelection, Long) -> TransitionResult
    ): Result {
        val progress = snapshot.activeProgress() ?: return Result.Ignored
        val leg = RouteCatalog.journey(progress.journeyId).legs.getOrNull(progress.legIndex)
            ?: return Result.Ignored
        if (progress.phase != LegPhase.PLANNED || leg.kind != TransportKind.SUBWAY) return Result.Ignored

        val selected = selectNearestTrip(progress.journeyId, tappedAtEpochMillis)
        return when (val transition = applyBoarding(selected, tappedAtEpochMillis)) {
            is TransitionResult.Applied -> Result.Applied(selected)
            is TransitionResult.Rejected -> Result.Rejected(transition.reason)
        }
    }
}
