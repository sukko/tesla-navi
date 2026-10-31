package dev.teslanavi.drive

import dev.teslanavi.protocol.DriveSnapshot
import dev.teslanavi.protocol.Gear

/** Keep driving/unknown state responsive. Only a confirmed P permits slower reads. */
internal class VehiclePoll {
    private var parked = false
    private var locationDueAt = Long.MIN_VALUE
    val intervalMillis get() = if (parked) PARKED_INTERVAL else DRIVING_INTERVAL

    fun locationDue(now: Long) = !parked || now >= locationDueAt

    fun received(snapshot: DriveSnapshot, locationRequested: Boolean, now: Long) {
        parked = snapshot.gear == Gear.PARK && snapshot.speedKph?.let { it.isFinite() && it >= 3 } != true
        if (locationRequested) locationDueAt = now + PARKED_LOCATION_INTERVAL
    }

    companion object {
        const val DRIVING_INTERVAL = 5_000L
        const val PARKED_INTERVAL = 15_000L
        const val PARKED_LOCATION_INTERVAL = 60_000L
    }
}
