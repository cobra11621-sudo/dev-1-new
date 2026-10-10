package com.cobra.dev1new.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryTest {
    private val route = listOf(GeoPoint(36.0, 127.0), GeoPoint(36.0, 127.01))

    @Test
    fun projectionReportsForwardProgressAndRouteLength() {
        val projection = RouteGeometry.project(route, 36.0, 127.005)
        assertNotNull(projection)
        projection!!
        assertTrue(projection.fraction in 0.49..0.51)
        assertTrue(projection.routeLengthMeters > 800.0)
        assertTrue(projection.progressMeters in (projection.routeLengthMeters * 0.49)..(projection.routeLengthMeters * 0.51))
    }

    @Test
    fun busBoardingRequiresAccurateSustainedForwardMovement() {
        val firstFix = GeoFix(36.0, 127.0, 20f, 0f, 10_000L)
        val firstProjection = RouteGeometry.project(route, firstFix.latitude, firstFix.longitude)!!
        val probe = BusBoardingProbe(firstFix, firstProjection)
        val movingFix = GeoFix(36.0, 127.0004, 25f, 11f, 18_000L)
        val movingProjection = RouteGeometry.project(route, movingFix.latitude, movingFix.longitude)!!

        assertTrue(TransitGpsRules.canStartBusProbe(firstFix, firstProjection))
        assertTrue(TransitGpsRules.confirmsBusBoarding(probe, movingFix, movingProjection))
        assertFalse(TransitGpsRules.canStartBusProbe(firstFix.copy(accuracyMeters = 81f), firstProjection))
    }

    @Test
    fun busDoesNotBoardFromStationaryOrShortObservation() {
        val firstFix = GeoFix(36.0, 127.0, 20f, 0f, 10_000L)
        val firstProjection = RouteGeometry.project(route, firstFix.latitude, firstFix.longitude)!!
        val probe = BusBoardingProbe(firstFix, firstProjection)
        val shortFix = GeoFix(36.0, 127.0001, 20f, 2f, 15_000L)
        val shortProjection = RouteGeometry.project(route, shortFix.latitude, shortFix.longitude)!!

        assertFalse(TransitGpsRules.confirmsBusBoarding(probe, shortFix, shortProjection))
    }

    @Test
    fun busStopDoesNotAdvanceUntilFiftyMetresAfterTheStop() {
        val points = listOf(
            GeoPoint(36.0, 127.0),
            GeoPoint(36.0, 127.001),
            GeoPoint(36.0, 127.002)
        )
        val justAfterOrigin = RouteGeometry.project(points, 36.0, 127.0002)!!
        val fiftyMetresAfterOrigin = RouteGeometry.project(points, 36.0, 127.0006)!!

        assertEquals(-1, TransitGpsRules.confirmedBusPassedStopIndex(points, justAfterOrigin))
        assertEquals(0, TransitGpsRules.confirmedBusPassedStopIndex(points, fiftyMetresAfterOrigin))
    }

    @Test
    fun destinationRequiresAccurateFixWithinRadius() {
        val destination = route.last()
        assertTrue(TransitGpsRules.isAccurateBusDestinationFix(GeoFix(36.0, 127.009, 40f, 0f, 1L), destination))
        assertFalse(TransitGpsRules.isAccurateBusDestinationFix(GeoFix(36.0, 127.008, 40f, 0f, 2L), destination))
        assertFalse(TransitGpsRules.isAccurateBusDestinationFix(GeoFix(36.0, 127.009, null, 0f, 3L), destination))
    }

    @Test
    fun shortReturnDonggu4TransferNeedsFinalHalfAndAccurateTransferZoneFix() {
        val points = listOf(
            GeoPoint(35.8770672, 128.7351894),
            GeoPoint(35.8736933, 128.7336550),
            GeoPoint(35.8716084, 128.7324801)
        )
        val destination = points.last()
        val ansimExit = GeoPoint(35.8724067, 128.7335650)
        val finalHalfFix = GeoFix(35.87245, 128.7335, 50f, 0f, 10_000L)
        val finalHalfProjection = RouteGeometry.project(points, finalHalfFix.latitude, finalHalfFix.longitude)!!

        assertTrue(finalHalfProjection.segmentIndex >= 1)
        assertTrue(finalHalfProjection.fraction >= 0.5)
        assertTrue(TransitGpsRules.isReturnDonggu4TransferFix(finalHalfFix, finalHalfProjection, 3, destination, ansimExit))
        assertFalse(TransitGpsRules.isReturnDonggu4TransferFix(finalHalfFix.copy(accuracyMeters = 81f), finalHalfProjection, 3, destination, ansimExit))

        val bridgeFix = GeoFix(points[1].latitude, points[1].longitude, 30f, 0f, 11_000L)
        val bridgeProjection = RouteGeometry.project(points, bridgeFix.latitude, bridgeFix.longitude)!!
        assertFalse(TransitGpsRules.isReturnDonggu4TransferFix(bridgeFix, bridgeProjection, 3, destination, ansimExit))
    }

    @Test
    fun ktxBoardingRequiresItsScheduledTravelWindowSpeedAndAccuracyNotRailLineProximity() {
        val departure = 1_800_000_000_000L
        val arrival = departure + 75L * 60_000L
        val fix = GeoFix(36.0, 127.005, 30f, 55f, 1L)

        assertTrue(TransitGpsRules.isKtxBoardingFix(fix, departure, departure, arrival))
        assertFalse(TransitGpsRules.isKtxBoardingFix(fix.copy(speedKmh = 19f), departure, departure, arrival))
        assertFalse(TransitGpsRules.isKtxBoardingFix(fix, arrival + 1L, departure, arrival))
        assertTrue(TransitGpsRules.isKtxBoardingFix(fix.copy(latitude = 35.0, longitude = 129.0), departure, departure, arrival))
    }
}
