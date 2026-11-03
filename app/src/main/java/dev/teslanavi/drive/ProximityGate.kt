package dev.teslanavi.drive

/** Radio presence is approximate. Authentication still verifies the actual vehicle. */
class ProximityGate {
    private var lastSeen: Long? = null
    private var retryAt = 0L
    private var waitingForDeparture = false

    fun observe(rssi: Int, connectable: Boolean, now: Long, canAttempt: Boolean = true): Boolean {
        if (lastSeen?.let { now - it >= 60_000 } == true) waitingForDeparture = false
        lastSeen = now
        if (!canAttempt || !connectable || rssi < MIN_RSSI || waitingForDeparture || now < retryAt) return false
        retryAt = now + 60_000
        return true
    }

    fun awaitDeparture(now: Long) {
        waitingForDeparture = true
        lastSeen = now
    }

    fun reset() { lastSeen = null; retryAt = 0; waitingForDeparture = false }
    companion object { const val MIN_RSSI = -78 }
}
