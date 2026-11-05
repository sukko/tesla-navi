package dev.teslanavi.navigation

import org.junit.Assert.*
import org.junit.Test

class CameraAlertPolicyTest {
    private val camera = CameraReading("a", CameraKind.SPEED, 60, 300)
    @Test fun distanceSpeechRoundsToNearestHundredMeters() {
        val policy = CameraAlertPolicy()
        listOf(423 to "400미터", 450 to "500미터", 149 to "100미터", 49 to "잠시 후").forEach { (meters, expected) ->
            assertTrue(policy.next(camera.copy(distanceMeters = meters), RoadClass.HIGHWAY, 0)!!.text.contains(expected))
        }
    }
    @Test fun oneAnnouncementPerCameraDespiteDistanceJitter() {
        val policy = CameraAlertPolicy()
        val first = checkNotNull(policy.next(camera, RoadClass.GENERAL, 0))
        policy.commit(first)
        assertNull(policy.next(camera.copy(distanceMeters = 295), RoadClass.GENERAL, 10))
        assertNull(policy.next(camera.copy(distanceMeters = 310), RoadClass.GENERAL, 20))
        assertNull(policy.next(camera.copy(distanceMeters = 190), RoadClass.GENERAL, 30))
        assertNull(policy.next(camera.copy(distanceMeters = 70), RoadClass.GENERAL, 50))
    }
    @Test fun highwayAndGeneralRoadUseExactDifferentBoundaries() {
        val policy = CameraAlertPolicy()
        assertNull(policy.next(camera.copy(distanceMeters = 501), RoadClass.HIGHWAY, 0))
        assertNotNull(policy.next(camera.copy(distanceMeters = 500), RoadClass.HIGHWAY, 0))
        assertNull(policy.next(camera.copy(distanceMeters = 301), RoadClass.GENERAL, 0))
        assertNotNull(policy.next(camera.copy(distanceMeters = 300), RoadClass.GENERAL, 0))
        assertNull(policy.next(camera.copy(distanceMeters = -1), RoadClass.HIGHWAY, 0))
    }
    @Test fun unknownRoadUsesEarlyFallbackAndLateFirstDetectionStillAlerts() {
        val policy = CameraAlertPolicy()
        assertNotNull(policy.next(camera.copy(distanceMeters = 500), RoadClass.UNKNOWN, 0))
        assertNotNull(policy.next(camera.copy(distanceMeters = 140), RoadClass.GENERAL, 0))
    }
    @Test fun variableAndUnknownLimitsAreNotInvented() {
        val policy = CameraAlertPolicy()
        val text = checkNotNull(policy.next(camera.copy(speedLimitKph = null, variableLimit = true), RoadClass.GENERAL, 0)).text
        assertFalse(text.contains("60"))
        assertTrue(text.contains("가변 제한속도 표지판"))
    }
    @Test fun newTripResetsSuppressionAndRealRevisitExpires() {
        val policy = CameraAlertPolicy()
        policy.commit(checkNotNull(policy.next(camera, RoadClass.GENERAL, 0)))
        assertNull(policy.next(camera, RoadClass.GENERAL, 1000))
        policy.clear()
        policy.commit(checkNotNull(policy.next(camera, RoadClass.GENERAL, 2000)))
        assertNotNull(policy.next(camera, RoadClass.GENERAL, 302001))
    }
    @Test fun upcomingTextMatchesTheAnnouncementAtTheAlertDistance() {
        val policy = CameraAlertPolicy()
        val options = GuidanceOptions(highwayMeters = 800)
        val approaching = camera.copy(distanceMeters = 1500, variableLimit = true)
        val prepared = checkNotNull(policy.upcoming(approaching, RoadClass.HIGHWAY, options))
        assertEquals("800미터 앞 과속 단속 카메라가 있습니다. 제한속도 시속 60킬로미터입니다. 가변 제한속도 표지판을 확인해 주세요.", prepared)
        assertNull(policy.upcoming(approaching.copy(distanceMeters = 1801), RoadClass.HIGHWAY, options))
        assertNull("inside the alert zone next() takes over", policy.upcoming(approaching.copy(distanceMeters = 800), RoadClass.HIGHWAY, options))
        val announced = checkNotNull(policy.next(approaching.copy(distanceMeters = 790), RoadClass.HIGHWAY, 0, options))
        assertEquals(prepared, announced.text)
        policy.commit(announced)
        assertNull(policy.upcoming(approaching, RoadClass.HIGHWAY, options))
    }
}
