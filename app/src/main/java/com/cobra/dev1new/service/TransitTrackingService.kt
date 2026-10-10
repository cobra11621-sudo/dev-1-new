package com.cobra.dev1new.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import com.cobra.dev1new.CommuteApplication
import com.cobra.dev1new.data.ApiKeyVault
import com.cobra.dev1new.data.BusApiClient
import com.cobra.dev1new.data.SubwayScheduleClient
import com.cobra.dev1new.domain.BusBoardingProbe
import com.cobra.dev1new.domain.GeoFix
import com.cobra.dev1new.domain.JourneyId
import com.cobra.dev1new.domain.KtxScheduleResolver
import com.cobra.dev1new.domain.LegPhase
import com.cobra.dev1new.domain.RouteCatalog
import com.cobra.dev1new.domain.RouteGeometry
import com.cobra.dev1new.domain.RecoveryStatus
import com.cobra.dev1new.domain.SubwayTripSelection
import com.cobra.dev1new.domain.TransitGpsRules
import com.cobra.dev1new.domain.TransportKind
import com.cobra.dev1new.domain.TravelSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class TransitTrackingService : Service() {
    private lateinit var repository: com.cobra.dev1new.data.TravelRepository
    private lateinit var keyVault: ApiKeyVault
    private lateinit var busApi: BusApiClient
    private lateinit var subwayApi: SubwayScheduleClient
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stateObserver: Job? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var locationSubscriptionKey = ""
    private var trackingWakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var busProbe: BusBoardingProbe? = null
    private var busDestinationFixCount = 0
    private var ktxDestinationFixCount = 0
    private var lastBusFetchAt = 0L
    private var busFetchInFlight = false
    private var lastSubwayFetchAt = 0L
    private var subwayFetchInFlight = false
    private var lastUsableKtxFixAt = 0L
    private var ktxGpsRecoveryAnnouncementPending = false
    private var recoveryJourneyId: JourneyId? = null
    private var recoveryRequestedElapsedRealtime = 0L
    private var recoveryProgressStep = 0
    private var recoveryLastFailureReason = ""
    private val eventGenerations = mutableMapOf<Int, Long>()
    @Volatile private var lastBusApiError = ""

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!foregroundStarted) return
            val reset = repository.resetForNewDay(System.currentTimeMillis())
            if (reset is com.cobra.dev1new.domain.TransitionResult.Applied) {
                postEvent("새 날짜 이동 초기화", "이전 출퇴근 여정을 종료했습니다")
                stopForegroundAndSelf()
                return
            }
            val snapshot = repository.snapshot()
            val progress = snapshot.activeProgress()
            if (recoveryJourneyId != null &&
                SystemClock.elapsedRealtime() - recoveryRequestedElapsedRealtime >= RECOVERY_TIMEOUT_MS
            ) {
                val journeyId = recoveryJourneyId!!
                recoveryJourneyId = null
                recoveryRequestedElapsedRealtime = 0L
                clearRecoveryStatus()
                val reason = recoveryLastFailureReason.ifBlank { "90초 안에 새 GPS 위치를 받지 못했습니다." }
                repository.setRecoveryStatus(RecoveryStatus(journeyId, "현재 위치 복구 실패", reason))
                postEvent("현재 위치로 경로 복구 실패", "$reason 실외에서 다시 눌러 주세요")
                reconcileLocationSubscription(snapshot)
            }
            if (progress == null && recoveryJourneyId == null) {
                stopForegroundAndSelf()
                return
            }
            if (progress == null) {
                postCurrentNotification()
                mainHandler.postDelayed(this, nextTickDelayMillis())
                return
            }
            when (progress.phase) {
                LegPhase.PLANNED -> when (RouteCatalog.journey(progress.journeyId).legs[progress.legIndex].kind) {
                    TransportKind.BUS -> refreshBusArrivalIfDue(progress)
                    TransportKind.SUBWAY -> refreshPlannedSubwayIfDue(progress)
                    TransportKind.KTX -> advancePlannedKtxAtScheduledArrivalIfDue(snapshot, progress)
                }
                LegPhase.ONBOARD -> {
                    if (RouteCatalog.journey(progress.journeyId).legs[progress.legIndex].kind == TransportKind.SUBWAY) {
                        evaluateSubwayTimetable(snapshot)
                    }
                }
                LegPhase.READY, LegPhase.COMPLETE -> Unit
            }
            postCurrentNotification()
            mainHandler.postDelayed(this, nextTickDelayMillis())
        }
    }

    /** Keep normal refreshes economical, but finish a requested recovery at its stated deadline. */
    private fun nextTickDelayMillis(): Long {
        val requestedAt = recoveryRequestedElapsedRealtime
        if (recoveryJourneyId == null || requestedAt == 0L) return TICK_INTERVAL_MS
        val remaining = RECOVERY_TIMEOUT_MS - (SystemClock.elapsedRealtime() - requestedAt)
        return remaining.coerceIn(1L, TICK_INTERVAL_MS)
    }

    private val recoveryProgressRunnable = object : Runnable {
        override fun run() {
            if (recoveryJourneyId == null) return
            recoveryProgressStep += 1
            val step = recoveryProgressStep.coerceAtMost(3)
            val message = if (step < 3) "새 GPS 위치를 확인하고 있습니다." else "새 GPS 위치를 기다리는 중입니다."
            repository.setRecoveryStatus(RecoveryStatus(recoveryJourneyId!!, "GPS 확인 중 ($step/3)", message))
            TransitNotificationFactory.postRecoveryStatus(this@TransitTrackingService, "GPS 확인 중 ($step/3)", message)
            if (recoveryProgressStep < 3) mainHandler.postDelayed(this, RECOVERY_PROGRESS_INTERVAL_MS)
        }
    }

    private fun clearRecoveryStatus() {
        mainHandler.removeCallbacks(recoveryProgressRunnable)
        getSystemService(android.app.NotificationManager::class.java)
            .cancel(TransitNotificationFactory.RECOVERY_STATUS_NOTIFICATION_ID)
    }

    override fun onCreate() {
        super.onCreate()
        repository = (application as CommuteApplication).travelRepository
        keyVault = ApiKeyVault(this)
        busApi = BusApiClient(keyVault)
        subwayApi = SubwayScheduleClient(this)
        TransitNotificationFactory.createChannel(this)
        stateObserver = serviceScope.launch(Dispatchers.Main.immediate) {
            repository.state.collect { snapshot ->
                if (foregroundStarted) {
                    reconcileLocationSubscription(snapshot)
                    postCurrentNotification()
                    if (snapshot.activeProgress() == null && recoveryJourneyId == null) stopForegroundAndSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val snapshot = repository.snapshot()
        val requestedRecovery = intent?.getStringExtra(EXTRA_RECOVERY_JOURNEY_ID)
            ?.let { runCatching { JourneyId.valueOf(it) }.getOrNull() }
        if (requestedRecovery != null) {
            recoveryJourneyId = requestedRecovery
            recoveryRequestedElapsedRealtime = SystemClock.elapsedRealtime()
            recoveryProgressStep = 0
            recoveryLastFailureReason = ""
            mainHandler.removeCallbacks(recoveryProgressRunnable)
            mainHandler.post(recoveryProgressRunnable)
        }
        if (snapshot.activeProgress() == null && recoveryJourneyId == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val initialSubwayText = snapshot.activeProgress()?.plannedSubwayText
            ?.takeIf(String::isNotBlank) ?: "지하철 시간표 계산 중"
        val initial = TransitNotificationFactory.build(
            this, snapshot, keyVault.isConfigured(), initialSubwayText
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                TransitNotificationFactory.NOTIFICATION_ID,
                initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(TransitNotificationFactory.NOTIFICATION_ID, initial)
        }
        foregroundStarted = true
        acquireTrackingWakeLock()
        reconcileLocationSubscription(snapshot)
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.post(tickRunnable)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        foregroundStarted = false
        mainHandler.removeCallbacks(tickRunnable)
        clearRecoveryStatus()
        removeLocationUpdates()
        releaseTrackingWakeLock()
        stateObserver?.cancel()
        serviceScope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun reconcileLocationSubscription(snapshot: TravelSnapshot) {
        val progress = snapshot.activeProgress()
        val leg = progress?.let {
            RouteCatalog.journey(it.journeyId).legs.getOrNull(it.legIndex)
        }
        val shouldTrack = recoveryJourneyId != null || (progress != null && leg != null &&
            progress.phase in setOf(LegPhase.PLANNED, LegPhase.ONBOARD) &&
            leg.kind != TransportKind.SUBWAY)
        val nextKey = if (shouldTrack) {
            recoveryJourneyId?.let { "recovery:$it:$recoveryRequestedElapsedRealtime" }
                ?: "${progress!!.journeyId}:${leg!!.id}:${progress.phase}"
        } else ""
        if (nextKey == locationSubscriptionKey) return
        locationSubscriptionKey = nextKey
        lastBusFetchAt = 0L
        busProbe = null
        busDestinationFixCount = 0
        ktxDestinationFixCount = 0
        lastUsableKtxFixAt = 0L
        if (!shouldTrack) {
            removeLocationUpdates()
            return
        }
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            removeLocationUpdates()
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = manager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = handleLocation(location)
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated by Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        locationListener = listener
        var requested = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, LOCATION_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
                    requested = true
                }
            } catch (_: SecurityException) {
                // The user can revoke location while a trip is active; keep the notification but stop GPS reads.
            } catch (_: IllegalArgumentException) {
                // A provider may not exist on the device.
            }
        }
        if (!requested) {
            locationListener = null
            locationSubscriptionKey = ""
        }
    }

    private fun removeLocationUpdates() {
        val manager = locationManager
        val listener = locationListener
        if (manager != null && listener != null) runCatching { manager.removeUpdates(listener) }
        locationListener = null
        locationManager = null
    }

    private fun handleLocation(location: Location) {
        val pendingRecovery = recoveryJourneyId
        if (pendingRecovery != null) {
            if (location.elapsedRealtimeNanos <= 0L ||
                location.elapsedRealtimeNanos / 1_000_000L < recoveryRequestedElapsedRealtime
            ) return
            handleRecoveryFix(pendingRecovery, location)
            return
        }
        val snapshot = repository.snapshot()
        val progress = snapshot.activeProgress() ?: return
        val leg = RouteCatalog.journey(progress.journeyId).legs.getOrNull(progress.legIndex) ?: return
        val now = System.currentTimeMillis()
        val fix = GeoFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0.0f,
            elapsedRealtimeMillis = if (location.elapsedRealtimeNanos > 0L)
                location.elapsedRealtimeNanos / 1_000_000L else SystemClock.elapsedRealtime()
        )
        val projection = RouteGeometry.project(leg.routePoints, fix.latitude, fix.longitude) ?: return
        if (ktxGpsRecoveryAnnouncementPending && fix.accuracyMeters != null && fix.accuracyMeters <= KTX_MAX_ACCURACY_METERS) {
            ktxGpsRecoveryAnnouncementPending = false
            postEvent("GPS 위치 수신 정상화", "KTX 이후 위치를 다시 확인했습니다")
        }
        when (leg.kind) {
            TransportKind.BUS -> handleBusFix(snapshot, progress, leg, fix, projection, now)
            TransportKind.KTX -> handleKtxFix(snapshot, progress, leg, fix, projection, now)
            TransportKind.SUBWAY -> Unit
        }
    }

    /** Use only the first fresh location received after the explicit button tap. */
    private fun handleRecoveryFix(journeyId: JourneyId, location: Location) {
        val now = System.currentTimeMillis()
        val fix = GeoFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0.0f,
            elapsedRealtimeMillis = location.elapsedRealtimeNanos / 1_000_000L
        )
        val route = RouteCatalog.journey(journeyId)
        val accuracy = fix.accuracyMeters
        if (accuracy == null) {
            recoveryLastFailureReason = "GPS 정확도를 확인하지 못했습니다."
            repository.setRecoveryStatus(RecoveryStatus(journeyId, "GPS 확인 중", recoveryLastFailureReason))
            return
        }
        if (accuracy > RECOVERY_MAX_ACCURACY_METERS) {
            recoveryLastFailureReason = "GPS 정확도가 ${accuracy.toInt()}m로 충분하지 않습니다."
            repository.setRecoveryStatus(RecoveryStatus(journeyId, "GPS 확인 중", recoveryLastFailureReason))
            return
        }

        val subwayIndex = route.legs.indexOfFirst { it.kind == TransportKind.SUBWAY }
        val subwayPoint = RouteCatalog.subwayBoardingPoint(journeyId)
        // A 100m-accurate fix cannot truthfully establish a 50m station-exit
        // boundary, so subway recovery needs both a 50m coordinate distance
        // and 50m-or-better reported accuracy.
        val subwayNearby = subwayIndex >= 0 && subwayPoint != null &&
            accuracy <= SUBWAY_RECOVERY_RADIUS_METERS &&
            RouteGeometry.distance(fix.latitude, fix.longitude, subwayPoint.latitude, subwayPoint.longitude) <= SUBWAY_RECOVERY_RADIUS_METERS
        val ktxIndex = route.legs.indexOfFirst { it.kind == TransportKind.KTX }
        val ktxProjection = ktxIndex.takeIf { it >= 0 }?.let { index ->
            RouteGeometry.project(route.legs[index].routePoints, fix.latitude, fix.longitude)
        }
        val ktxSchedule = repository.snapshot().ktxSchedules[journeyId]
        val ktxDeparture = ktxSchedule?.let { KtxScheduleResolver.departureEpochMillis(it, now) }
        val ktxArrival = ktxSchedule?.let { schedule ->
            ktxDeparture?.let { KtxScheduleResolver.arrivalEpochMillis(schedule, it) }
        }
        val ktxMatched = ktxIndex >= 0 && TransitGpsRules.isKtxBoardingFix(
            fix, now, ktxDeparture, ktxArrival
        )
        val busMatch = route.legs.mapIndexedNotNull { index, leg ->
            if (leg.kind != TransportKind.BUS) null else RouteGeometry.project(leg.routePoints, fix.latitude, fix.longitude)
                ?.takeIf { it.distanceMeters <= BUS_ROUTE_CORRIDOR_METERS }
                ?.let { index to it }
        }.minByOrNull { it.second.distanceMeters }

        val applied = when {
            subwayNearby -> repository.recoverAt(journeyId, subwayIndex, LegPhase.PLANNED, 0, now)
            ktxMatched -> repository.recoverAt(
                journeyId, ktxIndex, LegPhase.ONBOARD,
                ktxProjection?.stopIndex(route.legs[ktxIndex].stops.size) ?: 0, now
            )
            busMatch != null -> {
                val (index, projection) = busMatch
                repository.recoverAt(
                    journeyId, index, LegPhase.ONBOARD,
                    TransitGpsRules.confirmedBusPassedStopIndex(route.legs[index].routePoints, projection), now
                )
            }
            else -> null
        }
        when (applied) {
            is com.cobra.dev1new.domain.TransitionResult.Applied -> {
                recoveryJourneyId = null
                recoveryRequestedElapsedRealtime = 0L
                clearRecoveryStatus()
                repository.setRecoveryStatus(RecoveryStatus(
                    journeyId,
                    "GPS 위치 수신 정상화",
                    "${route.legs[applied.snapshot.activeProgress()!!.legIndex].displayName} 경로로 복구했습니다."
                ))
                postEvent("GPS 위치 수신 정상화", "현재 위치로 ${route.legs[applied.snapshot.activeProgress()!!.legIndex].displayName} 경로를 복구했습니다")
            }
            else -> {
                recoveryLastFailureReason = "현재 위치가 해당 경로와 맞지 않습니다."
                repository.setRecoveryStatus(RecoveryStatus(journeyId, "GPS 확인 중", recoveryLastFailureReason))
                return
            }
        }
        reconcileLocationSubscription(repository.snapshot())
    }

    private fun handleBusFix(
        snapshot: TravelSnapshot,
        progress: com.cobra.dev1new.domain.LegProgress,
        leg: com.cobra.dev1new.domain.TransportLeg,
        fix: com.cobra.dev1new.domain.GeoFix,
        projection: com.cobra.dev1new.domain.RouteProjection,
        now: Long
    ) {
        if (progress.phase == LegPhase.PLANNED) {
            val probe = busProbe
            if (probe == null) {
                if (TransitGpsRules.canStartBusProbe(fix, projection)) {
                    busProbe = BusBoardingProbe(fix, projection)
                }
                return
            }
            if (fix.elapsedRealtimeMillis - probe.firstFix.elapsedRealtimeMillis < BUS_PROBE_WINDOW_MS) return
            if (TransitGpsRules.confirmsBusBoarding(probe, fix, projection)) {
                busProbe = null
                if (repository.autoBoard(now) is com.cobra.dev1new.domain.TransitionResult.Applied) {
                    postEvent("${busLabel(leg.routeNumber)} 탑승 확인", "GPS 이동 확인 · 자동 탑승 처리")
                }
            } else {
                busProbe = if (TransitGpsRules.canStartBusProbe(fix, projection)) BusBoardingProbe(fix, projection) else null
            }
            return
        }
        if (progress.phase != LegPhase.ONBOARD) return
        if (progress.journeyId == com.cobra.dev1new.domain.JourneyId.RETURN && leg.id == "return_donggu4") {
            val shortLegIndex = TransitGpsRules.confirmedBusPassedStopIndex(leg.routePoints, projection)
                .coerceAtLeast(-1)
            if (shortLegIndex > progress.stopIndex) repository.updateStopIndex(shortLegIndex, now)
            val destination = leg.routePoints.lastOrNull() ?: return
            val ansimExit = com.cobra.dev1new.domain.GeoPoint(35.8724067, 128.7335650)
            if (TransitGpsRules.isReturnDonggu4TransferFix(
                    fix, projection, leg.stops.size, destination, ansimExit
                ) && repository.autoAlight(now) is com.cobra.dev1new.domain.TransitionResult.Applied
            ) {
                postEvent("송정삼거리3 도착", "안심역 환승 구역 · 지하철 탑승예정 전환")
            }
            return
        }
        val accuracy = fix.accuracyMeters ?: return
        if (accuracy > BUS_MAX_ACCURACY_METERS || projection.distanceMeters > BUS_ROUTE_CORRIDOR_METERS) return
        val index = TransitGpsRules.confirmedBusPassedStopIndex(leg.routePoints, projection)
        if (index > progress.stopIndex) repository.updateStopIndex(index, now)
        val remaining = (leg.stops.lastIndex - index).coerceAtLeast(0)
        // The two alerts are tied to exactly the same 50m-confirmed progress
        // as the compact bus line.  Persisting each marker makes them one-shot
        // even if Android recreates the foreground service.
        if (leg.stops.size >= 4 && remaining == 2 &&
            repository.markBusPreArrivalAlert(2, now) is com.cobra.dev1new.domain.TransitionResult.Applied
        ) postEvent("하차 2정류장 전", "${leg.destination}까지 2정류장 남았습니다", 2)
        if (leg.stops.size >= 4 && remaining == 1 &&
            repository.markBusPreArrivalAlert(1, now) is com.cobra.dev1new.domain.TransitionResult.Applied
        ) postEvent("하차 1정류장 전", "다음 정류장은 ${leg.destination}입니다", 1)
        val destination = leg.routePoints.lastOrNull() ?: return
        if (TransitGpsRules.isAccurateBusDestinationFix(fix, destination)) {
            busDestinationFixCount += 1
            if (busDestinationFixCount >= 2) {
                busDestinationFixCount = 0
                if (repository.autoAlight(now) is com.cobra.dev1new.domain.TransitionResult.Applied) {
                    postEvent("목적지 도착", "${leg.destination} · 자동 하차 처리")
                }
            }
        } else {
            busDestinationFixCount = 0
        }
    }

    private fun handleKtxFix(
        snapshot: TravelSnapshot,
        progress: com.cobra.dev1new.domain.LegProgress,
        leg: com.cobra.dev1new.domain.TransportLeg,
        fix: com.cobra.dev1new.domain.GeoFix,
        projection: com.cobra.dev1new.domain.RouteProjection,
        now: Long
    ) {
        val schedule = snapshot.ktxSchedules[progress.journeyId] ?: return
        val departure = KtxScheduleResolver.departureEpochMillis(schedule, progress.plannedAtEpochMillis ?: now)
        val arrival = departure?.let { KtxScheduleResolver.arrivalEpochMillis(schedule, it) }
        val usableForKtxProgress = fix.accuracyMeters != null &&
            fix.accuracyMeters <= KTX_MAX_ACCURACY_METERS &&
            projection.distanceMeters <= KTX_ROUTE_CORRIDOR_METERS
        val previousUsableFixAt = lastUsableKtxFixAt
        if (usableForKtxProgress) lastUsableKtxFixAt = now
        if (progress.phase == LegPhase.PLANNED) {
            if (TransitGpsRules.isKtxBoardingFix(fix, now, departure, arrival)) {
                if (repository.autoBoard(now) is com.cobra.dev1new.domain.TransitionResult.Applied) {
                    postEvent("KTX 탑승 확인", "출발·도착 시각과 시속 20km 조건 확인 · 자동 탑승 처리")
                }
            } else if (
                usableForKtxProgress &&
                departure != null &&
                now >= departure + KTX_GPS_RECOVERY_GAP_MS &&
                (previousUsableFixAt == 0L || now - previousUsableFixAt >= KTX_GPS_RECOVERY_GAP_MS)
            ) {
                postEvent("GPS 위치 수신 정상화", "KTX 위치를 다시 확인했습니다")
            }
            return
        }
        if (progress.phase != LegPhase.ONBOARD) return
        if (fix.accuracyMeters == null || fix.accuracyMeters > KTX_MAX_ACCURACY_METERS || projection.distanceMeters > KTX_ROUTE_CORRIDOR_METERS) return
        val index = projection.stopIndex(leg.stops.size)
        if (index > progress.stopIndex) repository.updateStopIndex(index, now)
        val destination = leg.routePoints.lastOrNull() ?: return
        if (TransitGpsRules.isKtxArrivalFix(fix, destination)) {
            ktxDestinationFixCount += 1
            if (ktxDestinationFixCount >= 2) {
                ktxDestinationFixCount = 0
                if (repository.autoAlight(now) is com.cobra.dev1new.domain.TransitionResult.Applied) {
                    postEvent("KTX 목적역 도착", "${leg.destination} · 감속 확인 · 자동 하차")
                }
            }
        } else {
            ktxDestinationFixCount = 0
        }
    }

    /**
     * GPS is useful for confirming an actual KTX boarding and arrival, but it
     * must not hide the next bus when the train's in-car reception remains
     * unavailable. The configured arrival time is the fallback boundary.
     */
    private fun advancePlannedKtxAtScheduledArrivalIfDue(
        snapshot: TravelSnapshot,
        progress: com.cobra.dev1new.domain.LegProgress
    ) {
        val schedule = snapshot.ktxSchedules[progress.journeyId] ?: return
        val now = System.currentTimeMillis()
        val departure = KtxScheduleResolver.departureEpochMillis(
            schedule,
            progress.plannedAtEpochMillis ?: now
        ) ?: return
        val arrival = KtxScheduleResolver.arrivalEpochMillis(schedule, departure) ?: return
        if (now < arrival) return
        if (repository.advancePlannedKtxAtScheduledArrival(now) is com.cobra.dev1new.domain.TransitionResult.Applied) {
            ktxGpsRecoveryAnnouncementPending = true
            postEvent("KTX 도착 시각 경과", "GPS 미확정 · 다음 버스 탑승예정으로 전환")
            repository.snapshot().activeProgress()?.let { next ->
                val nextLeg = RouteCatalog.journey(next.journeyId).legs[next.legIndex]
                if (next.phase == LegPhase.PLANNED && nextLeg.kind == TransportKind.BUS) {
                    refreshBusArrivalIfDue(next)
                }
            }
        }
    }

    private fun refreshBusArrivalIfDue(progress: com.cobra.dev1new.domain.LegProgress) {
        val now = System.currentTimeMillis()
        if (busFetchInFlight || now - lastBusFetchAt < BUS_API_INTERVAL_MS) return
        if (!keyVault.isConfigured()) return
        busFetchInFlight = true
        lastBusFetchAt = now
        serviceScope.launch {
            try {
                val snapshot = busApi.fetchFor(progress.journeyId, progress.legIndex)
                repository.saveBusArrivals(snapshot.arrivals, snapshot.fetchedAtEpochMillis)
                lastBusApiError = ""
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastBusApiError = error.message.orEmpty()
            } finally {
                busFetchInFlight = false
                mainHandler.post { postCurrentNotification() }
            }
        }
    }

    private fun refreshPlannedSubwayIfDue(progress: com.cobra.dev1new.domain.LegProgress) {
        val now = System.currentTimeMillis()
        if (subwayFetchInFlight || now - lastSubwayFetchAt < SUBWAY_REFRESH_INTERVAL_MS) return
        subwayFetchInFlight = true
        lastSubwayFetchAt = now
        serviceScope.launch {
            val label = try {
                val trains = subwayApi.upcoming(progress.journeyId, now)
                if (trains.isEmpty()) "오늘 운행 종료 · 시간표 확인 필요"
                else trains.joinToString("/") { it.label(now) }
            } catch (_: Exception) {
                "지하철 시간표 갱신 대기"
            }
            repository.savePlannedSubwayText(progress.journeyId, label, now)
            subwayFetchInFlight = false
            mainHandler.post { postCurrentNotification() }
        }
    }

    private fun evaluateSubwayTimetable(snapshot: TravelSnapshot) {
        val progress = snapshot.activeProgress() ?: return
        val trip: SubwayTripSelection = progress.selectedSubwayTrip ?: return
        val stops = RouteCatalog.journey(progress.journeyId).legs[progress.legIndex].stops
        if (trip.arrivalsEpochMillis.size != stops.size) return
        val now = System.currentTimeMillis()
        val targetIndex = trip.departuresEpochMillis.indexOfFirst { now <= it }
            .let { if (it < 0) stops.lastIndex else it }
            .coerceIn(0, stops.lastIndex)
        if (targetIndex > progress.stopIndex) repository.updateStopIndex(targetIndex, now)
        val finalArrival = trip.arrivalsEpochMillis.lastOrNull() ?: return
        // The selected live schedule is immutable after the user taps board; do not pick another train.
        if (now > finalArrival) {
            val result = repository.autoAlight(now)
            if (result is com.cobra.dev1new.domain.TransitionResult.Applied) {
                postEvent("지하철 목적역 도착", "${stops.last()} · 시간표 기준 자동 하차")
            }
        }
    }

    private fun postCurrentNotification() {
        if (!foregroundStarted) return
        val snapshot = repository.snapshot()
        if (snapshot.activeProgress() == null) return
        val notification = TransitNotificationFactory.build(
            this, snapshot, keyVault.isConfigured(),
            snapshot.activeProgress()?.plannedSubwayText?.takeIf(String::isNotBlank) ?: "지하철 시간표 계산 중"
        )
        getSystemService(android.app.NotificationManager::class.java)
            .notify(TransitNotificationFactory.NOTIFICATION_ID, notification)
    }

    private fun postEvent(title: String, message: String, eventId: Int = TransitNotificationFactory.EVENT_NOTIFICATION_ID) {
        val generation = (eventGenerations[eventId] ?: 0L) + 1L
        eventGenerations[eventId] = generation
        TransitNotificationFactory.postEvent(this, title, message, eventId)
        mainHandler.postDelayed({
            if (eventGenerations[eventId] == generation) {
                getSystemService(android.app.NotificationManager::class.java)
                    .cancel(eventId)
            }
        }, EVENT_NOTIFICATION_DURATION_MS)
    }

    private fun acquireTrackingWakeLock() {
        try {
            val manager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = trackingWakeLock ?: manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Dev1Native:TransitTracking"
            ).also {
                it.setReferenceCounted(false)
                trackingWakeLock = it
            }
            if (!lock.isHeld) lock.acquire()
        } catch (_: Exception) {
            // The foreground location service continues; the OS may batch updates if wake locks are unavailable.
        }
    }

    private fun releaseTrackingWakeLock() {
        try {
            trackingWakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        } finally {
            trackingWakeLock = null
        }
    }

    private fun stopForegroundAndSelf() {
        mainHandler.removeCallbacks(tickRunnable)
        removeLocationUpdates()
        releaseTrackingWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foregroundStarted = false
        stopSelf()
    }

    private fun busLabel(routeNumber: String?): String =
        (routeNumber ?: "버스").removeSuffix("번")

    companion object {
        private const val LOCATION_INTERVAL_MS = 5_000L
        private const val TICK_INTERVAL_MS = 20_000L
        private const val BUS_API_INTERVAL_MS = 30_000L
        private const val SUBWAY_REFRESH_INTERVAL_MS = 20_000L
        private const val BUS_PROBE_WINDOW_MS = 7_000L
        private const val BUS_MAX_ACCURACY_METERS = 80.0f
        private const val BUS_ROUTE_CORRIDOR_METERS = 250.0
        private const val KTX_MAX_ACCURACY_METERS = 250.0f
        private const val KTX_ROUTE_CORRIDOR_METERS = 5_000.0
        private const val KTX_GPS_RECOVERY_GAP_MS = 30_000L
        private const val RECOVERY_MAX_ACCURACY_METERS = 100.0f
        private const val SUBWAY_RECOVERY_RADIUS_METERS = 50.0
        private const val RECOVERY_TIMEOUT_MS = 90_000L
        private const val RECOVERY_PROGRESS_INTERVAL_MS = 2_000L
        // Event cards must remain readable after the short two-pulse vibration.
        // This affects visibility only; it does not add or lengthen vibrations.
        private const val EVENT_NOTIFICATION_DURATION_MS = 6_000L
        private const val EXTRA_RECOVERY_JOURNEY_ID = "recovery_journey_id"

        fun start(context: Context) {
            val intent = Intent(context, TransitTrackingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun recoverCurrentPosition(context: Context, journeyId: JourneyId) {
            val intent = Intent(context, TransitTrackingService::class.java)
                .putExtra(EXTRA_RECOVERY_JOURNEY_ID, journeyId.name)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
