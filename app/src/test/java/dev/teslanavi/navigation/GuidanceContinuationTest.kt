package dev.teslanavi.navigation

import dev.teslanavi.drive.DrivePhase
import dev.teslanavi.drive.DriveState
import dev.teslanavi.protocol.*
import org.junit.Assert.*
import org.junit.Test

class GuidanceContinuationTest {
    @Test fun vehicleFramesCannotExtendDisconnectedPhoneGuidanceTimer() {
        val session = GuidanceContinuation()
        session.update(connected, true, 0)
        session.update(lost, false, 0)
        session.observe(frame(599_000, 50, 500.0).copy(source = PositionSource.VEHICLE), 599_000)
        assertNull(session.update(lost, false, 600_000))
        assertTrue(session.expired)
    }
    private val goal = NavigationDestination.Active("목적지", GeoPoint(37.5, 127.0))
    private val connected = DriveState(vin = "a", phase = DrivePhase.CONNECTED,
        snapshot = DriveSnapshot(goal, 30.0, null, null, Gear.DRIVE))
    private val lost = DriveState(vin = "a", phase = DrivePhase.ERROR)

    private fun frame(time: Long, speed: Int?, x: Double = 0.0) =
        NavigationEvent.Frame(emptyList(), speed, time, position = NavigationPosition(x, 0.0))

    @Test fun freshDisplacementCountsWhenGpsSpeedIsUnavailableButStationaryJitterDoesNot() {
        val session = GuidanceContinuation()
        session.update(connected, true, 0)
        session.observe(frame(0, null), 0)
        assertEquals(goal, session.update(lost, false, 0))
        session.observe(frame(300_000, null, 35.0), 300_000)
        assertEquals(goal, session.update(lost, false, 600_000))
        session.observe(frame(800_000, null, 39.0), 800_000) // 4m drift cannot postpone stopping
        assertNull(session.update(lost, false, 900_000))
        assertTrue(session.expired)
    }

    @Test fun StaleDuplicateAndInvalidGpsCannotExtendDisconnectedTrip() {
        val session = GuidanceContinuation()
        session.update(connected, true, 0)
        session.update(lost, false, 0)
        session.observe(frame(1_000, 50), 1_000)
        session.observe(frame(1_000, 50), 10_000) // same fix delivered by a safety callback
        session.observe(frame(1_000, 50), 590_000) // stale fix
        session.observe(frame(599_000, null, Double.NaN), 599_000)
        assertNotNull(session.update(lost, false, 600_999))
        assertNull(session.update(lost, false, 601_000))
    }

    @Test fun expiredTripCannotRestartFromLateFramesOrRepeatedConnectionFailures() {
        val session = GuidanceContinuation()
        session.update(connected, true, 0)
        session.update(lost, false, 0)
        assertNull(session.update(lost, false, 600_000))
        session.observe(frame(601_000, 50), 601_000)
        assertNull(session.update(lost.copy(phase = DrivePhase.CONNECTING), false, 602_000))
        session.update(connected, true, 603_000)
        assertFalse(session.expired)
        assertEquals(goal, session.update(lost, false, 604_000))
    }

    @Test fun confirmedParkingOverridesMotionAndNeverStartsDisconnectedGuidance() {
        val session = GuidanceContinuation()
        session.update(connected, true, 0)
        session.update(connected.copy(snapshot = connected.snapshot!!.copy(gear = Gear.PARK)), true, 1000)
        assertNull(session.update(lost, false, 2000))
        assertFalse(session.usingPhone)
        assertFalse(session.expired)
    }

    @Test fun sensorStepsWithStaleGpsDoNotMeanExitOrExtendTheIdleTimeout() {
        val session = GuidanceContinuation()
        session.update(connected, true, 0)
        session.update(lost, false, 0)
        session.observe(frame(0, 4), 0)
        repeat(600) { second ->
            val at = second * 1000L
            session.step(at, at)
            session.observe(frame(0, 4), at)
        }
        assertNull(session.update(lost, false, 600_000))
        assertEquals(ContinuationEndReason.NO_MOVEMENT, session.endReason)
    }
}
