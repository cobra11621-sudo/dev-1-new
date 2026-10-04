package com.cobra.dev1new.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.cobra.dev1new.MainActivity
import com.cobra.dev1new.R
import com.cobra.dev1new.domain.ArrivalFormatter
import com.cobra.dev1new.domain.JourneyId
import com.cobra.dev1new.domain.KtxScheduleResolver
import com.cobra.dev1new.domain.LegPhase
import com.cobra.dev1new.domain.RouteCatalog
import com.cobra.dev1new.domain.TravelSnapshot
import com.cobra.dev1new.domain.TransportKind

internal data class TransitNotificationText(
    val title: String,
    val shortCriticalText: String,
    val body: String,
    val progress: Int,
    val progressMax: Int,
    val indeterminate: Boolean
)

internal object TransitNotificationFactory {
    const val CHANNEL_ID = "dev1native_transit_live"
    const val ALERT_CHANNEL_ID = "dev1native_transit_events"
    const val NOTIFICATION_ID = 4200
    const val EVENT_NOTIFICATION_ID = 4201
    const val ACTION_CANCEL_PLAN = "com.cobra.dev1new.action.CANCEL_PLAN"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "${context.getString(R.string.notification_channel_transit)} · 이동 중", NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        setShowBadge(false)
                        description = "현재 대중교통 여정과 Now Bar"
                        setSound(null, null)
                        enableVibration(false)
                    }
            )
        }
        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL_ID, "${context.getString(R.string.notification_channel_transit)} · 도착 알림", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 700, 250, 700)
                        setSound(null, null)
                    }
            )
        }
    }

    fun text(
        snapshot: TravelSnapshot,
        apiKeyConfigured: Boolean,
        subwayPlannedText: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): TransitNotificationText {
        val progress = snapshot.activeProgress()
            ?: return TransitNotificationText("이동 정보", "이동이 완료되었습니다", "이동이 완료되었습니다", 0, 1, true)
        val journey = RouteCatalog.journey(progress.journeyId)
        val leg = journey.legs.getOrNull(progress.legIndex)
            ?: return TransitNotificationText("이동 정보", "경로 확인 필요", "경로 설정을 확인하세요", 0, 1, true)
        val schedule = snapshot.ktxSchedules[progress.journeyId]
        val stops = leg.stops
        val stopIndex = progress.stopIndex.coerceIn(0, (stops.size - 1).coerceAtLeast(0))
        val current = stops.getOrNull(stopIndex).orEmpty()
        val next = stops.getOrNull((stopIndex + 1).coerceAtMost((stops.size - 1).coerceAtLeast(0))).orEmpty()
        val remaining = (stops.lastIndex - stopIndex).coerceAtLeast(0)
        val transitLine = when (leg.kind) {
            TransportKind.BUS -> busName(leg.routeNumber) + "(${leg.destination})"
            TransportKind.SUBWAY -> subwayTitle(progress.journeyId, leg.stops)
            TransportKind.KTX -> ktxPlannedTitle(schedule, nowEpochMillis)
        }
        if (progress.phase == LegPhase.PLANNED) {
            return when (leg.kind) {
                TransportKind.BUS -> {
                    val arrival = when {
                        !apiKeyConfigured -> "API 키 설정 필요"
                        progress.arrivalFetchedAtEpochMillis == null -> "도착 정보 확인 중"
                        else -> ArrivalFormatter.formatPlanned(
                            progress.arrivalVehicles,
                            progress.arrivalFetchedAtEpochMillis,
                            nowEpochMillis
                        )
                    }
                    TransitNotificationText(transitLine, arrival, arrival, 0, stops.size.coerceAtLeast(1), true)
                }
                TransportKind.SUBWAY -> TransitNotificationText(
                    subwayTitle(progress.journeyId, leg.stops),
                    progress.plannedSubwayText.ifBlank { subwayPlannedText },
                    progress.plannedSubwayText.ifBlank { subwayPlannedText },
                    0,
                    stops.size.coerceAtLeast(1),
                    true
                )
                TransportKind.KTX -> {
                    val ticket = schedule?.let(::ktxSeatDetails).orEmpty().ifBlank { "KTX 승차권 정보 설정 필요" }
                    TransitNotificationText(
                        ktxPlannedTitle(schedule, nowEpochMillis), ticket, ticket,
                        0, stops.size.coerceAtLeast(1), true
                    )
                }
            }
        }
        return when (leg.kind) {
            TransportKind.BUS -> {
                val line = "${busName(leg.routeNumber)}(${leg.destination})"
                val details = "${remaining}개/현재:$current/다음:$next"
                TransitNotificationText(line, details, details, stopIndex, stops.size.coerceAtLeast(1), false)
            }
            TransportKind.SUBWAY -> {
                val train = progress.selectedSubwayTrip
                val arrival = train?.arrivalsEpochMillis?.lastOrNull()
                val destination = leg.destination.removeSuffix("역")
                val title = "${KtxScheduleResolver.displayClock(arrival)} $destination".trim()
                val direction = if (progress.journeyId == JourneyId.COMMUTE) "왼쪽" else "오른쪽"
                val lower = if (remaining == 0) "${remaining}개/$current" else "${remaining}개/$current/$next"
                // The door side is actionable only on arrival.  Showing it at
                // every intermediate station makes the compact Now Bar noisy
                // and falsely suggests that the user should alight now.
                val short = if (remaining == 0) "$lower/$direction" else lower
                TransitNotificationText(title, short, lower, stopIndex, stops.size.coerceAtLeast(1), false)
            }
            TransportKind.KTX -> {
                val depart = KtxScheduleResolver.departureEpochMillis(
                    schedule ?: return TransitNotificationText("KTX", "일정 설정 필요", "일정 설정 필요", stopIndex, stops.size, true),
                    progress.plannedAtEpochMillis ?: progress.boardedAtEpochMillis ?: nowEpochMillis
                )
                val arrival = schedule?.let { KtxScheduleResolver.arrivalEpochMillis(it, depart ?: nowEpochMillis) }
                val title = "${KtxScheduleResolver.displayClock(arrival)} ${leg.destination}".trim()
                val details = "${remaining}개/$current/$next"
                TransitNotificationText(title, details, details, stopIndex, stops.size.coerceAtLeast(1), false)
            }
        }
    }

    fun build(
        context: Context,
        snapshot: TravelSnapshot,
        apiKeyConfigured: Boolean,
        subwayPlannedText: String
    ): Notification {
        createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val model = text(snapshot, apiKeyConfigured, subwayPlannedText)
        val openApp = PendingIntent.getActivity(
            context, 4200,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transit)
            .setContentTitle(model.title)
            .setContentText(model.body)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setLocalOnly(false)
            .setDefaults(0)
            .setSound(null)
            .setVibrate(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                Notification.Builder::class.java
                    .getMethod("setShortCriticalText", String::class.java)
                    .invoke(builder, model.shortCriticalText)
            }
            requestPromotedOngoing(builder)
            applyProgressStyle(builder, model)
        } else {
            builder.setSubText(model.shortCriticalText)
        }
        if (snapshot.activeProgress()?.phase == LegPhase.PLANNED) {
            val cancelIntent = PendingIntent.getBroadcast(
                context,
                4201,
                Intent(context, TransitActionReceiver::class.java).setAction(ACTION_CANCEL_PLAN),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "취소", cancelIntent)
        }
        // Querying this also lets the screen explain that the user/device has not enabled promotion.
        if (Build.VERSION.SDK_INT >= 36) runCatching {
            NotificationManager::class.java.getMethod("canPostPromotedNotifications").invoke(manager)
        }
        return builder.build()
    }

    fun postEvent(context: Context, title: String, message: String) {
        createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transit)
            .setContentTitle(title)
            .setContentText(message)
            .setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 700, 250, 700))
            .build()
        manager.notify(EVENT_NOTIFICATION_ID, notification)
    }

    private fun requestPromotedOngoing(builder: Notification.Builder) {
        runCatching {
            Notification.Builder::class.java
                .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType!!)
                .invoke(builder, true)
        }
    }

    private fun applyProgressStyle(builder: Notification.Builder, model: TransitNotificationText) {
        runCatching {
            val styleClass = Class.forName("android.app.Notification\$ProgressStyle")
            val style = styleClass.getDeclaredConstructor().newInstance()
            if (model.indeterminate) {
                styleClass.getMethod("setProgressIndeterminate", Boolean::class.javaPrimitiveType!!).invoke(style, true)
            } else {
                styleClass.getMethod("setProgress", Int::class.javaPrimitiveType!!).invoke(style, model.progress)
                styleClass.getMethod("setStyledByProgress", Boolean::class.javaPrimitiveType!!).invoke(style, true)
                runCatching {
                    val segmentClass = Class.forName("android.app.Notification\$ProgressStyle\$Segment")
                    val segment = segmentClass.getConstructor(Int::class.javaPrimitiveType!!)
                        .newInstance(model.progressMax.coerceAtLeast(1))
                    segmentClass.getMethod("setColor", Int::class.javaPrimitiveType!!)
                        .invoke(segment, android.graphics.Color.rgb(45, 92, 190))
                    styleClass.getMethod("setProgressSegments", List::class.java).invoke(style, listOf(segment))
                }
                runCatching {
                    val pointClass = Class.forName("android.app.Notification\$ProgressStyle\$Point")
                    val points = (1 until model.progressMax).map { position ->
                        pointClass.getConstructor(Int::class.javaPrimitiveType!!).newInstance(position).also { point ->
                            pointClass.getMethod("setColor", Int::class.javaPrimitiveType!!)
                                .invoke(point, android.graphics.Color.rgb(45, 92, 190))
                        }
                    }
                    styleClass.getMethod("setProgressPoints", List::class.java).invoke(style, points)
                }
            }
            builder.setStyle(style as Notification.Style)
        }
    }

    private fun subwayTitle(journeyId: JourneyId, stops: List<String>): String {
        val nextStation = stops.getOrNull(1)?.removeSuffix("역") ?: "지하철"
        val terminal = if (journeyId == JourneyId.COMMUTE) "각산·하양 방면" else "설화명곡 방면"
        return "$nextStation·$terminal"
    }

    private fun busName(routeNumber: String?): String =
        (routeNumber ?: "버스").removeSuffix("번")

    private fun ktxPlannedTitle(schedule: com.cobra.dev1new.domain.KtxSchedule?, now: Long): String {
        if (schedule == null) return "KTX 일정 설정 필요"
        val departure = KtxScheduleResolver.departureEpochMillis(schedule, now)
        val train = KtxScheduleResolver.notificationTrainIdentifier(schedule.trainIdentifier)
        val time = KtxScheduleResolver.displayClock(departure).ifBlank { schedule.departureTime }
        return "$train, $time".trim().trimEnd(',')
    }

    private fun ktxSeatDetails(schedule: com.cobra.dev1new.domain.KtxSchedule): String = buildList {
        schedule.platform.takeIf(String::isNotBlank)?.let { add("${it}번홈") }
        schedule.car.takeIf(String::isNotBlank)?.let { add("${it}호차") }
        schedule.seat.takeIf(String::isNotBlank)?.let { add("${it}좌석") }
    }.joinToString(" · ").ifBlank { "승차홈·호차·좌석 입력 필요" }
}
