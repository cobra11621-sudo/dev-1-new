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
    fun denseRouteUsesEvenMilestonesInsteadOfRawSegmentNumber() {
        val denseRoute = (0..48).map { GeoPoint(36.0, 127.0 + it * 0.001) }
        val early = RouteGeometry.project(denseRoute, 36.0, 127.004)!!
        val middle = RouteGeometry.project(denseRoute, 36.0, 127.024)!!
        val nearEnd = RouteGeometry.project(denseRoute, 36.0, 127.046)!!

        // The screen has six labels (five intervals).  Raw segment numbers
        // would have clipped the fourth recorded segment straight to index 5.
        assertEquals(0, early.stopIndex(6))
        assertEquals(2, middle.stopIndex(6))
        assertEquals(4, nearEnd.stopIndex(6))
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
    fun ktxBoardingRequires_travel_time_window_route_distance_and_speed() {
        val fix = GeoFix(36.0, 127.005, 999f, 20f, 1L)
        val departure = 10_000L
        val arrival = 20_000L

        assertFalse(TransitGpsRules.isKtxBoardingFix(fix, 2_000.0, departure - 1, departure, arrival))
        assertTrue(TransitGpsRules.isKtxBoardingFix(fix, 2_000.0, departure, departure, arrival))
        assertTrue(TransitGpsRules.isKtxBoardingFix(fix.copy(accuracyMeters = null), 1_999.0, departure, departure, arrival))
        assertFalse(TransitGpsRules.isKtxBoardingFix(fix, 100.0, departure, null, arrival))
        assertFalse(TransitGpsRules.isKtxBoardingFix(fix, 100.0, departure, departure, null))
        assertFalse(TransitGpsRules.isKtxBoardingFix(fix, 100.0, arrival, departure, arrival))
        assertFalse(TransitGpsRules.isKtxBoardingFix(fix.copy(speedKmh = 19f), 100.0, departure, departure, arrival))
        assertFalse(TransitGpsRules.isKtxBoardingFix(fix, 2_001.0, departure, departure, arrival))
    }

    @Test
    fun dongdaegu_transfer_recovery_requires_an_accurate_area_and_directional_ktx_time() {
        val transfer = RouteCatalog.dongdaeguTransferPoint()
        val fix = GeoFix(transfer.latitude, transfer.longitude, 80f, 25f, 1L)

        assertTrue(TransitGpsRules.isDongdaeguTransferArea(fix, transfer))
        assertFalse(TransitGpsRules.isDongdaeguTransferArea(fix.copy(accuracyMeters = 101f), transfer))
        // About 0.003° latitude is roughly 330m, which is inside the agreed
        // 500m transfer area.  Keep the rejection probe clearly outside it.
        assertFalse(TransitGpsRules.isDongdaeguTransferArea(fix.copy(latitude = transfer.latitude + 0.005), transfer))
        assertTrue(TransitGpsRules.isKtxPreDepartureRecovery(9_999L, 10_000L))
        assertFalse(TransitGpsRules.isKtxPreDepartureRecovery(10_000L, 10_000L))
        assertFalse(TransitGpsRules.isKtxPreDepartureRecovery(9_999L, null))
        assertTrue(TransitGpsRules.isKtxPostArrivalTransferRecovery(10_000L, 10_000L))
        assertFalse(TransitGpsRules.isKtxPostArrivalTransferRecovery(9_999L, 10_000L))
        assertFalse(TransitGpsRules.isKtxPostArrivalTransferRecovery(10_000L, null))
        assertTrue(TransitGpsRules.isDongdaeguTransferRecoveryAllowed(
            JourneyId.COMMUTE, true, 10_000L, 9_000L, 10_000L
        ))
        assertFalse(TransitGpsRules.isDongdaeguTransferRecoveryAllowed(
            JourneyId.COMMUTE, true, 9_999L, 9_000L, 10_000L
        ))
        assertFalse(TransitGpsRules.isDongdaeguTransferRecoveryAllowed(
            JourneyId.COMMUTE, true, 10_000L, 9_000L, null
        ))
        assertTrue(TransitGpsRules.isDongdaeguTransferRecoveryAllowed(
            JourneyId.RETURN, true, 9_999L, 10_000L, 9_000L
        ))
        assertFalse(TransitGpsRules.isDongdaeguTransferRecoveryAllowed(
            JourneyId.RETURN, true, 10_000L, 10_000L, 9_000L
        ))
    }

    @Test
    fun dongdaegu_transfer_resumes_the_direction_specific_planned_leg() {
        val commuteIndex = requireNotNull(RouteCatalog.dongdaeguTransferPlannedLegIndex(JourneyId.COMMUTE))
        val returnIndex = requireNotNull(RouteCatalog.dongdaeguTransferPlannedLegIndex(JourneyId.RETURN))

        assertEquals(TransportKind.SUBWAY, RouteCatalog.commute.legs[commuteIndex].kind)
        assertEquals(TransportKind.KTX, RouteCatalog.returning.legs[returnIndex].kind)
        assertEquals("대전역", RouteCatalog.commute.legs[requireNotNull(
            RouteCatalog.commute.legs.indexOfFirst { it.kind == TransportKind.KTX }.takeIf { it >= 0 }
        )].origin)
    }
}
