package dev.teslanavi.drive

import dev.teslanavi.navigation.CameraState
import dev.teslanavi.protocol.DriveSnapshot
import dev.teslanavi.protocol.Gear
import dev.teslanavi.protocol.NavigationDestination
import org.junit.Assert.*
import org.junit.Test

class SessionIdleTimeoutTest {
    private val disconnected = BluetoothStartState(selectedDevices = mapOf("car" to "Car audio"))
    private val connected = disconnected.copy(devices = listOf(StartBluetoothDevice("car", "Car audio", true)))
    private fun drive(gear: Gear, speed: Double = 0.0) = DriveState(vin = "vehicle-a", phase = DrivePhase.CONNECTED,
        snapshot = DriveSnapshot(NavigationDestination.None, speed, null, null, gear))

    @Test fun zeroSpeedDriveAndReverseNeverMeanDepartureEvenWithoutAudioBluetooth() {
        val idle = SessionIdleTimeout()
        assertNull(idle.deadline(drive(Gear.DRIVE, 40.0), CameraState(), disconnected, 0))
        assertNull(idle.deadline(drive(Gear.DRIVE), CameraState(), disconnected, 600_000))
        assertNull(idle.deadline(drive(Gear.REVERSE), CameraState(), disconnected, 900_000))
    }

    @Test fun parkedPlusSelectedBluetoothLossStartsOneStableDeadlineAndReconnectCancelsIt() {
        val idle = SessionIdleTimeout()
        assertNull(idle.deadline(drive(Gear.PARK), CameraState(), connected, 0))
        assertEquals(121_000L, idle.deadline(drive(Gear.PARK), CameraState(), disconnected, 1000))
        assertEquals(121_000L, idle.deadline(drive(Gear.PARK), CameraState(), disconnected, 61_000))
        assertNull(idle.deadline(drive(Gear.PARK), CameraState(), connected, 90_000))
        assertEquals(220_000L, idle.deadline(drive(Gear.PARK), CameraState(), disconnected, 100_000))
        assertNull(idle.deadline(drive(Gear.DRIVE), CameraState(), disconnected, 110_000))
    }

    @Test fun missingSnapshotAfterKnownParkingStillPermitsExitButActivePhoneFallbackIsProtected() {
        val idle = SessionIdleTimeout()
        idle.deadline(drive(Gear.PARK), CameraState(), connected, 0)
        val lost = DriveState(vin = "vehicle-a", phase = DrivePhase.RECONNECTING)
        assertEquals(121_000L, idle.deadline(lost, CameraState(), disconnected, 1000))
        assertNull(idle.deadline(lost, CameraState(phoneFallback = true), disconnected, 50_000))
        assertEquals(220_000L, idle.deadline(lost, CameraState(continuationExpired = true), disconnected, 100_000))
    }

    @Test fun withoutSelectedAudioDeviceKeepConnectedParkingUntilBleActuallyEnds() {
        val idle = SessionIdleTimeout()
        assertNull(idle.deadline(drive(Gear.PARK), CameraState(), BluetoothStartState(), 0))
        val ended = drive(Gear.PARK).copy(phase = DrivePhase.ERROR)
        assertEquals(121_000L, idle.deadline(ended, CameraState(), BluetoothStartState(), 1000))
        assertNull(idle.deadline(ended, CameraState(phoneFallback = true), BluetoothStartState(), 30_000))
    }
}
