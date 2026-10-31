package dev.teslanavi.drive

import dev.teslanavi.protocol.*
import org.junit.Assert.*
import org.junit.Test

class VehiclePollTest {
    private fun snapshot(gear: Gear, speed: Double? = 0.0) = DriveSnapshot(NavigationDestination.None, speed, null, null, gear)

    @Test fun parkingSlowsStateReadsAndSeparatesLocationFromEveryPoll() {
        val poll = VehiclePoll()
        assertTrue(poll.locationDue(0))
        poll.received(snapshot(Gear.PARK), true, 0)
        assertEquals(15_000L, poll.intervalMillis)
        for (now in listOf(15_000L, 30_000L, 45_000L)) {
            assertFalse(poll.locationDue(now))
            poll.received(snapshot(Gear.PARK), false, now)
        }
        assertTrue(poll.locationDue(60_000))
        poll.received(snapshot(Gear.PARK), true, 60_000)
        assertFalse(poll.locationDue(75_000))
    }

    @Test fun departureImmediatelyRestoresFastStateAndLocationCadence() {
        val poll = VehiclePoll()
        poll.received(snapshot(Gear.PARK), true, 0)
        assertFalse(poll.locationDue(15_000))
        // This drive read detects departure even though the optional location query was skipped.
        poll.received(snapshot(Gear.DRIVE, 30.0), false, 15_000)
        assertEquals(5_000L, poll.intervalMillis)
        assertTrue(poll.locationDue(20_000))
        poll.received(snapshot(Gear.DRIVE, 30.0), true, 20_000)
        assertTrue(poll.locationDue(25_000))
    }

    @Test fun trafficStopsAndUnknownGearDoNotEnterParkingCadence() {
        for (gear in listOf(Gear.DRIVE, Gear.NEUTRAL, Gear.REVERSE, Gear.UNKNOWN)) {
            val poll = VehiclePoll()
            poll.received(snapshot(gear), true, 0)
            assertEquals(5_000L, poll.intervalMillis)
            assertTrue(poll.locationDue(5_000))
        }
        val poll = VehiclePoll()
        poll.received(snapshot(Gear.PARK, 10.0), true, 0)
        assertEquals(5_000L, poll.intervalMillis)
    }
}
