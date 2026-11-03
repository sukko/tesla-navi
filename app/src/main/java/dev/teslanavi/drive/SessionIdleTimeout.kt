package dev.teslanavi.drive

import dev.teslanavi.navigation.CameraState
import dev.teslanavi.protocol.Gear

/** A D-gear stop is never departure. Keep disconnected phone guidance until its own exit policy ends it. */
internal class SessionIdleTimeout(private val timeoutMillis: Long = TIMEOUT_MS) {
    private var vin: String? = null
    private var parked = false
    private var idleSince: Long? = null

    fun deadline(drive: DriveState, cameras: CameraState, bluetooth: BluetoothStartState, now: Long): Long? {
        if (vin != drive.vin) { vin = drive.vin; parked = false; idleSince = null }
        if (drive.phase == DrivePhase.CONNECTED) drive.snapshot?.let { snapshot ->
            when {
                snapshot.gear == Gear.PARK -> parked = true
                snapshot.gear != Gear.UNKNOWN || snapshot.speedKph?.let { it.isFinite() && it >= 3 } == true -> parked = false
            }
        }
        val hasSelectedDevice = bluetooth.selectedDevices.isNotEmpty()
        val selectedDisconnected = hasSelectedDevice && !bluetooth.allowed
        val inactive = !drive.active || (parked && selectedDisconnected)
        val eligible = inactive && !cameras.phoneFallback && (!hasSelectedDevice || selectedDisconnected)
        if (!eligible) { idleSince = null; return null }
        if (idleSince == null) idleSince = now
        return checkNotNull(idleSince) + timeoutMillis
    }

    companion object { const val TIMEOUT_MS = 2 * 60 * 1000L }
}
