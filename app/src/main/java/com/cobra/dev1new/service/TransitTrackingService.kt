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
            if (progress == null) {
                stopForegroundAndSelf()
                return
            }
            when (progress.phase) {
                LegPhase.PLANNED -> when (RouteCatalog.journey(progress.journeyId).legs[progress.legIndex].kind) {
                    TransportKind.BUS -> refreshBusArrivalIfDue(progress)
                    TransportKind.SUBWAY -> refreshPlannedSubwayIfDue(progress)
                    TransportKind.KTX -> Unit
                }
                LegPhase.ONBOARD -> {
                    if (RouteCatalog.journey(progress.journeyId).legs[progress.legIndex].kind == TransportKind.SUBWAY) {
                        evaluateSubwayTimetable(snapshot)
                    }
                }
                LegPhase.READY, LegPhase.COMPLETE -> Unit
            }
            postCurrentNotification()
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
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
                    if (snapshot.activeProgress() == null) stopForegroundAndSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val snapshot = repository.snapshot()
        if (snapshot.activeProgress() == null) {
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
        val shouldTrack = progress != null && leg != null &&
            progress.phase in setOf(LegPhase.PLANNED, LegPhase.ONBOARD) &&
            leg.kind != TransportKind.SUBWAY
        val nextKey = if (shouldTrack) {
            "${progress!!.journeyId}:${leg!!.id}:${progress.phase}"
        } else ""
        if (nextKey == locationSubscriptionKey) return
        locationSubscriptionKey = nextKey
        lastBusFetchAt = 0L
        busProbe = null
        busDestinationFixCount = 0
        ktxDestinationFixCount = 0
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
        when (leg.kind) {
            TransportKind.BUS -> handleBusFix(snapshot, progress, leg, fix, projection, now)
            TransportKind.KTX -> handleKtxFix(snapshot, progress, leg, fix, projection, now)
            TransportKind.SUBWAY -> Unit
        }
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
        if (progress.phase == LegPhase.PLANNED) {
            if (TransitGpsRules.isKtxBoardingFix(fix, projection, now, departure)) {
                if (repository.autoBoard(now) is com.cobra.dev1new.domain.TransitionResult.Applied) {
                    postEvent("KTX 탑승 확인", "경로·출발 시각·속도 조건 확인 · 자동 탑승 처리")
                }
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
        TransitNotificationFactory.postEvent(this, title, message, eventId)
        mainHandler.postDelayed({
            getSystemService(android.app.NotificationManager::class.java)
                .cancel(eventId)
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
        private const val EVENT_NOTIFICATION_DURATION_MS = 2_000L

        fun start(context: Context) {
            val intent = Intent(context, TransitTrackingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
