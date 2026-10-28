package dev.teslanavi.navigation

import dev.teslanavi.protocol.GeoPoint
import dev.teslanavi.protocol.Gear
import dev.teslanavi.protocol.VehicleLocation
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class VehicleRouteAssistTest {
    private val wall = 1_790_000_000_000L
    private fun point(x: Double, y: Double) = GeoPoint(37.5 + y / 111_195, 127 + x / (111_195 * cos(Math.toRadians(37.5))))
    private fun fix(x: Double, y: Double, at: Long = 0, estimated: Boolean? = null,
        gpsAt: Long? = wall / 1000 + at / 1000, speed: Double? = 50.0, gear: Gear = Gear.DRIVE, heading: Int? = null) =
        VehicleGuidanceFix(VehicleLocation(point(x, y), heading, gpsAt, estimated), speed, gear, at, wall + at)
    private val camera = CameraReading("camera", CameraKind.SPEED, 60, 0)

    @Test fun distanceFollowsRouteBendsAndPassedCamerasDisappear() {
        val geometry = RouteGeometry(listOf(point(0.0, 0.0), point(100.0, 0.0), point(100.0, 100.0)))
        val assist = VehicleRouteAssist(geometry, listOf(RouteCamera(camera, 180.0)))
        assist.anchor(point(50.0, 0.0), 50.0, 0)
        val match = assist.locate(fix(60.0, 0.0, 1000), 1000)!!
        assertEquals(60.0, match.meters, 0.1)
        assertEquals(120, assist.camerasAhead(match.meters).single().distanceMeters)
        assertTrue(assist.camerasAhead(181.0).isEmpty())
    }

    @Test fun missingAnchorOffRouteWrongHeadingAndExpiredAnchorCannotGuide() {
        val geometry = RouteGeometry(listOf(point(0.0, 0.0), point(1000.0, 0.0)))
        val assist = VehicleRouteAssist(geometry, emptyList())
        assertNull(assist.locate(fix(50.0, 0.0), 0))
        assist.anchor(point(50.0, 0.0), 50.0, 0)
        assertNull(assist.locate(fix(60.0, 40.0, 1000), 1000))
        assertNull(assist.locate(fix(60.0, 0.0, 1000, heading = 270), 1000))
        assertNull(assist.locate(fix(60.0, 0.0, 31_000), 31_000))
    }

    @Test fun ambiguousParallelRoadsAndCrossingsAreRejected() {
        val geometry = RouteGeometry(listOf(point(0.0, 0.0), point(500.0, 0.0), point(500.0, 10.0), point(0.0, 10.0)))
        assertNull(geometry.match(point(200.0, 5.0)))
        assertNotNull(geometry.match(point(200.0, 0.0), 150.0..250.0, heading = 90))
    }

    @Test fun repeatedFixDoesNotExtrapolateDistanceOrExtendItsAnchor() {
        val geometry = RouteGeometry(listOf(point(0.0, 0.0), point(2000.0, 0.0)))
        val assist = VehicleRouteAssist(geometry, emptyList())
        assist.anchor(point(50.0, 0.0), 50.0, 0)
        val fix = fix(60.0, 0.0, 1000)
        assertEquals(60.0, assist.locate(fix, 1000)!!.meters, 0.1)
        assertEquals(60.0, assist.locate(fix, 9000)!!.meters, 0.1)
        assertNull(assist.locate(fix(1200.0, 0.0, 10_000), 10_000))
        assertNull(assist.locate(fix, 32_000))
    }

    @Test fun frozenMovingCoordinatesExpireEvenWhenPacketsAndGpsClockKeepUpdating() {
        val tracker = VehicleFixTracker()
        tracker.update(fix(0.0, 0.0))
        assertNotNull(tracker.current(0))
        tracker.update(fix(0.0, 0.0, 5000))
        assertNotNull(tracker.current(9000))
        tracker.update(fix(0.0, 0.0, 10_000))
        assertNull(tracker.current(10_001))
        tracker.update(fix(50.0, 0.0, 15_000))
        assertNotNull(tracker.current(15_000))
    }

    @Test fun vehicleEstimatedPositionCanUpdateWhileRawGpsClockStopsInTunnel() {
        val tracker = VehicleFixTracker()
        tracker.update(fix(0.0, 0.0))
        tracker.update(fix(50.0, 0.0, 5000, estimated = true, gpsAt = wall / 1000 - 60))
        assertNotNull(tracker.current(5000))
        tracker.update(fix(100.0, 0.0, 10_000, estimated = true, gpsAt = wall / 1000 - 60))
        assertNotNull(tracker.current(10_000))
        tracker.update(fix(100.0, 0.0, 15_000, estimated = true, gpsAt = wall / 1000 - 60))
        assertNull(tracker.current(15_000))
    }

    @Test fun staleUnknownInvalidOrUnreasonableVehicleDataCannotGuide() {
        for (fix in listOf(fix(0.0, 0.0, gpsAt = null), fix(0.0, 0.0, gpsAt = wall / 1000 - 60),
            fix(0.0, 0.0, gpsAt = wall / 1000 + 60), fix(0.0, 0.0, estimated = false),
            fix(0.0, 0.0, speed = Double.NaN), fix(0.0, 0.0, speed = null), fix(0.0, 0.0, gear = Gear.REVERSE),
            fix(0.0, 0.0, gear = Gear.PARK))) {
            val tracker = VehicleFixTracker(); tracker.update(fix)
            assertNull(tracker.current(0))
        }
        val tracker = VehicleFixTracker(); tracker.update(fix(0.0, 0.0))
        assertNull(tracker.current(10_001))
        assertNull(tracker.current(-1))
        tracker.update(fix(2000.0, 0.0, 1000))
        assertNull(tracker.current(1000))
        tracker.update(null)
        assertNull(tracker.current(1000))
    }
}
