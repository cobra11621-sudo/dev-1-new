package com.cobra.dev1new.domain

import java.time.Duration
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** Location values normalized at the Android boundary; accuracy is null if the provider omitted it. */
data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val speedKmh: Float,
    val elapsedRealtimeMillis: Long
)

data class RouteProjection(
    val segmentIndex: Int,
    val fraction: Double,
    val distanceMeters: Double,
    val progressMeters: Double,
    val routeLengthMeters: Double
) {
    fun stopIndex(stopCount: Int): Int =
        (segmentIndex + if (fraction >= 0.9) 1 else 0).coerceIn(0, (stopCount - 1).coerceAtLeast(0))
}

object RouteGeometry {
    fun project(points: List<GeoPoint>, latitude: Double, longitude: Double): RouteProjection? {
        if (points.size < 2) return null
        var cumulative = 0.0
        var best: RouteProjection? = null
        points.zipWithNext().forEachIndexed { index, (start, end) ->
            val segmentLength = distance(start.latitude, start.longitude, end.latitude, end.longitude)
            if (segmentLength <= 0.01) return@forEachIndexed
            val meanLatitude = Math.toRadians((start.latitude + end.latitude + latitude) / 3.0)
            val scaleX = cos(meanLatitude) * 111_320.0
            val scaleY = 111_320.0
            val ax = start.longitude * scaleX
            val ay = start.latitude * scaleY
            val bx = end.longitude * scaleX
            val by = end.latitude * scaleY
            val px = longitude * scaleX
            val py = latitude * scaleY
            val dx = bx - ax
            val dy = by - ay
            val denominator = dx * dx + dy * dy
            val fraction = (((px - ax) * dx + (py - ay) * dy) / denominator).coerceIn(0.0, 1.0)
            val projectedLatitude = start.latitude + (end.latitude - start.latitude) * fraction
            val projectedLongitude = start.longitude + (end.longitude - start.longitude) * fraction
            val crossTrack = distance(latitude, longitude, projectedLatitude, projectedLongitude)
            val along = cumulative + segmentLength * fraction
            val candidate = RouteProjection(index, fraction, crossTrack, along, 0.0)
            if (best == null || candidate.distanceMeters < best.distanceMeters ||
                (candidate.distanceMeters == best.distanceMeters && candidate.progressMeters > best.progressMeters)
            ) best = candidate
            cumulative += segmentLength
        }
        val selected = best ?: return null
        return selected.copy(routeLengthMeters = cumulative)
    }

    fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val lat1 = Math.toRadians(aLat)
        val lat2 = Math.toRadians(bLat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt((1 - h).coerceAtLeast(0.0)))
    }
}

data class BusBoardingProbe(val firstFix: GeoFix, val firstProjection: RouteProjection)

object TransitGpsRules {
    private const val BUS_MAX_ACCURACY_METERS = 80.0f
    private const val BUS_ROUTE_CORRIDOR_METERS = 250.0
    private const val BUS_MIN_OBSERVATION_MILLIS = 7_000L
    private const val BUS_MIN_FORWARD_METERS = 30.0
    private const val BUS_MIN_PHYSICAL_METERS = 30.0
    private const val BUS_MIN_SPEED_KMH = 10.0f
    private const val BUS_DESTINATION_RADIUS_METERS = 140.0

    fun canStartBusProbe(fix: GeoFix, projection: RouteProjection): Boolean =
        fix.accuracyMeters != null && fix.accuracyMeters <= BUS_MAX_ACCURACY_METERS &&
            projection.distanceMeters <= BUS_ROUTE_CORRIDOR_METERS

    fun confirmsBusBoarding(probe: BusBoardingProbe, fix: GeoFix, projection: RouteProjection): Boolean {
        val elapsed = fix.elapsedRealtimeMillis - probe.firstFix.elapsedRealtimeMillis
        if (elapsed < BUS_MIN_OBSERVATION_MILLIS) return false
        val accuracyOk = fix.accuracyMeters != null && fix.accuracyMeters <= BUS_MAX_ACCURACY_METERS
        val routeOk = projection.distanceMeters <= BUS_ROUTE_CORRIDOR_METERS
        val forwardOk = projection.progressMeters - probe.firstProjection.progressMeters >= BUS_MIN_FORWARD_METERS
        val physicalMovement = RouteGeometry.distance(
            probe.firstFix.latitude, probe.firstFix.longitude, fix.latitude, fix.longitude
        )
        val movementOk = physicalMovement >= BUS_MIN_PHYSICAL_METERS
        val speedOk = probe.firstFix.speedKmh >= BUS_MIN_SPEED_KMH || fix.speedKmh >= BUS_MIN_SPEED_KMH
        return accuracyOk && routeOk && forwardOk && movementOk && speedOk
    }

    fun isAccurateBusDestinationFix(fix: GeoFix, destination: GeoPoint): Boolean {
        val accuracy = fix.accuracyMeters ?: return false
        return accuracy <= BUS_MAX_ACCURACY_METERS &&
            RouteGeometry.distance(fix.latitude, fix.longitude, destination.latitude, destination.longitude) <= BUS_DESTINATION_RADIUS_METERS
    }

    /**
     * The return Donggu4 ride is a two-stop transfer leg. Dev-1 allows one
     * accurate fix only after Songjeong Bridge and in the final route half,
     * within 140 m of Songjeong Triangle 3 or Ansim Exit 1.
     */
    fun isReturnDonggu4TransferFix(
        fix: GeoFix,
        projection: RouteProjection,
        stopCount: Int,
        destination: GeoPoint,
        ansimExit: GeoPoint
    ): Boolean {
        if (stopCount < 3 || projection.segmentIndex < 1 ||
            projection.segmentIndex < stopCount - 2 || projection.fraction < 0.5
        ) return false
        val accuracy = fix.accuracyMeters
        if (accuracy != null && accuracy > BUS_MAX_ACCURACY_METERS) return false
        val destinationDistance = RouteGeometry.distance(
            fix.latitude, fix.longitude, destination.latitude, destination.longitude
        )
        val exitDistance = RouteGeometry.distance(
            fix.latitude, fix.longitude, ansimExit.latitude, ansimExit.longitude
        )
        return minOf(destinationDistance, exitDistance) <= BUS_DESTINATION_RADIUS_METERS
    }

    fun isKtxBoardingFix(
        fix: GeoFix,
        projection: RouteProjection,
        nowEpochMillis: Long,
        scheduledDepartureEpochMillis: Long?
    ): Boolean {
        val departure = scheduledDepartureEpochMillis ?: return false
        val earliest = departure - Duration.ofMinutes(90).toMillis()
        val latest = departure + Duration.ofMinutes(120).toMillis()
        val accuracy = fix.accuracyMeters ?: return false
        return nowEpochMillis in earliest..latest &&
            fix.speedKmh >= 20.0f &&
            projection.distanceMeters <= 5_000.0 &&
            accuracy <= 100.0f
    }

    fun isKtxArrivalFix(
        fix: GeoFix,
        destination: GeoPoint
    ): Boolean {
        val accuracy = fix.accuracyMeters ?: return false
        return accuracy <= 250.0f && fix.speedKmh < 5.0f &&
            RouteGeometry.distance(fix.latitude, fix.longitude, destination.latitude, destination.longitude) <= 2_000.0
    }
}
