package dev.teslanavi.navigation

import org.junit.Assert.*
import org.junit.Test

class CameraDeadReckoningTest {
    private val camera = CameraReading("camera-60", CameraKind.SPEED, 60, 1000)

    @Test fun lostCameraMovesCloserAtTheCarsSpeed() {
        val reckoning = CameraDeadReckoning()
        reckoning.advance(0, 72.0)
        reckoning.observe(listOf(camera), 0, PositionSource.PHONE)
        assertTrue("a camera in the latest frame needs no estimate", reckoning.estimated(0).isEmpty())
        for (t in 1000L..25_000L step 1000) reckoning.advance(t, 72.0)
        assertEquals(500, reckoning.estimated(25_000).single().distanceMeters)
    }

    @Test fun unknownSpeedOrALongGapDoesNotInventTravel() {
        val reckoning = CameraDeadReckoning()
        reckoning.advance(0, 72.0)
        reckoning.observe(listOf(camera), 0, PositionSource.PHONE)
        reckoning.advance(10_000, null)
        assertEquals(1000, reckoning.estimated(10_001).single().distanceMeters)
        reckoning.advance(70_000, 72.0) // At most five seconds of travel are counted for one gap.
        assertEquals(900, reckoning.estimated(70_001).single().distanceMeters)
    }

    @Test fun cameraLeavingTheListNearItsPositionIsPassedAndReported() {
        val reckoning = CameraDeadReckoning()
        reckoning.advance(0, 36.0)
        reckoning.observe(listOf(camera.copy(distanceMeters = 120)), 0, PositionSource.VEHICLE)
        reckoning.advance(1000, 36.0)
        val passed = reckoning.observe(emptyList(), 1000, PositionSource.PHONE).single()
        assertEquals(110, passed.lastMeters)
        assertEquals(PositionSource.VEHICLE, passed.source)
        assertTrue(reckoning.estimated(2000).isEmpty())
    }

    @Test fun farCameraDroppedFromTheListIsKeptUntilEstimatedPastOrTooOld() {
        val reckoning = CameraDeadReckoning()
        reckoning.advance(0, 0.0)
        reckoning.observe(listOf(camera), 0, PositionSource.PHONE)
        assertTrue(reckoning.observe(emptyList(), 1000, PositionSource.PHONE).isEmpty())
        assertEquals(1, reckoning.estimated(1001).size)
        assertTrue(reckoning.expire(CameraDeadReckoning.MAX_AGE_MILLIS).isEmpty())
        assertEquals(camera.id, reckoning.expire(CameraDeadReckoning.MAX_AGE_MILLIS + 1).single().reading.id)
    }

    @Test fun camerasBeyondTrackingRangeAreIgnored() {
        val reckoning = CameraDeadReckoning()
        reckoning.observe(listOf(camera.copy(distanceMeters = 5000)), 0, PositionSource.PHONE)
        assertTrue(reckoning.estimated(1).isEmpty())
    }
}
