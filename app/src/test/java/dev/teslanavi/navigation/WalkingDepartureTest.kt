package dev.teslanavi.navigation

import org.junit.Assert.*
import org.junit.Test

class WalkingDepartureTest {
    private fun frame(at: Long, speed: Int? = 4, x: Double = at / 1000.0) =
        NavigationEvent.Frame(emptyList(), speed, at, position = NavigationPosition(x, 0.0))

    @Test fun sustainedStepsAndWalkingGpsOverFiftyMetresAreRequiredTogether() {
        val detector = WalkingDeparture()
        for (second in 0..120) {
            val at = second * 1000L
            detector.step(at, at)
            val exit = detector.observe(frame(at))
            assertEquals("must wait for two minutes", second == 120, exit)
        }
    }

    @Test fun slowTrafficWithoutStepsAndStepsAtDrivingSpeedNeverMeanExit() {
        val slowTraffic = WalkingDeparture()
        val driving = WalkingDeparture()
        repeat(601) { second ->
            val at = second * 1000L
            assertFalse(slowTraffic.observe(frame(at)))
            driving.step(at, at)
            assertFalse(driving.observe(frame(at, speed = 30)))
        }
    }

    @Test fun stationarityAndSmallGpsDriftCannotCombineWithSpuriousStepsToMeanExit() {
        val detector = WalkingDeparture()
        repeat(180) { second ->
            val at = second * 1000L
            detector.step(at, at)
            assertFalse(detector.observe(frame(at, x = second % 5.0)))
        }
    }

    @Test fun gpsGapOrSpeedUpRequiresANewContinuousWalkingWindow() {
        for (gap in listOf(false, true)) {
            val detector = WalkingDeparture()
            for (second in 0..100) {
                val at = second * 1000L
                detector.step(at, at); assertFalse(detector.observe(frame(at)))
            }
            if (!gap) assertFalse(detector.observe(frame(101_000, speed = 15)))
            for (second in 120..230) {
                val at = second * 1000L
                detector.step(at, at); assertFalse(detector.observe(frame(at)))
            }
        }
    }

    @Test fun oldOrBurstStepEventsCannotFabricateSustainedWalking() {
        val detector = WalkingDeparture()
        repeat(121) { second ->
            val at = second * 1000L
            repeat(100) { detector.step(0, at) }
            assertFalse(detector.observe(frame(at)))
        }
    }
}
