package dev.teslanavi.drive

import dev.teslanavi.data.VehicleProfile
import dev.teslanavi.protocol.DriveSnapshot
import dev.teslanavi.protocol.NavigationDestination
import org.junit.Assert.*
import org.junit.Test

class VehicleSwitchTest {
    private val y = "5YJYGDEE1NF123456"
    private val three = "5YJ3E1EA1MF123456"
    private val profiles = listOf(VehicleProfile(y, "Model Y"), VehicleProfile(three, "Model 3"))
    private val yAudio = "4C:FC:AA:08:1D:E4"
    private val threeAudio = "4C:FC:AA:62:6F:61"
    private val selected = mapOf(yAudio to "Tesla Model Y 토마토", threeAudio to "Tesla Model 3 초코")

    private fun bluetooth(vararg connected: String) = BluetoothStartState(selected,
        selected.map { (address, name) -> StartBluetoothDevice(address, name, address in connected) })

    @Test fun audioDeviceNameIdentifiesTheCarOnlyWhenUnambiguous() {
        assertEquals(y, VehicleSwitch.vehicleForDevice("Tesla Model Y 토마토", profiles))
        assertEquals(three, VehicleSwitch.vehicleForDevice("tesla MODEL3", profiles))
        assertNull(VehicleSwitch.vehicleForDevice("토마토", profiles))
        assertNull(VehicleSwitch.vehicleForDevice(null, profiles))
        val twoOfSameModel = listOf(VehicleProfile(y, "Model Y"), VehicleProfile("5YJYGDEE1NF222222", "Model Y"))
        assertNull(VehicleSwitch.vehicleForDevice("Tesla Model Y 토마토", twoOfSameModel))
    }

    @Test fun connectedAudioLimitsConnectionToThatCar() {
        val allowance = VehicleSwitch.allowance(bluetooth(threeAudio), profiles, null, 0)
        assertTrue(allowance.permits(three))
        assertFalse("the parked car next to it must not win the scan", allowance.permits(y))
    }

    @Test fun twoConnectedOrUnknownDevicesDoNotGuessACar() {
        val both = VehicleSwitch.allowance(bluetooth(yAudio, threeAudio), profiles, null, 0)
        assertTrue(both.permits(y)); assertTrue(both.permits(three))
        val unknown = BluetoothStartState(mapOf("AA" to "Car audio"), listOf(StartBluetoothDevice("AA", "Car audio", true)))
        val allowance = VehicleSwitch.allowance(unknown, profiles, null, 0)
        assertTrue(allowance.permits(y)); assertTrue(allowance.permits(three))
    }

    @Test fun withoutAudioOnlyAnActiveWatchPermitsTheOtherCar() {
        assertFalse(VehicleSwitch.allowance(bluetooth(), profiles, null, 0).allowed)
        val watch = VehicleSwitchWatch(y, 1000)
        val active = VehicleSwitch.allowance(bluetooth(), profiles, watch, 999)
        assertTrue(active.permits(three))
        assertFalse(active.permits(y))
        assertFalse(VehicleSwitch.allowance(bluetooth(), profiles, watch, 1000).allowed)
    }

    @Test fun noSelectedDeviceKeepsEveryCarAvailable() {
        val allowance = VehicleSwitch.allowance(BluetoothStartState(), profiles, VehicleSwitchWatch(y, 1000), 0)
        assertTrue(allowance.permits(y)); assertTrue(allowance.permits(three))
    }

    private fun drive(vin: String, automatic: Boolean = true, phase: DrivePhase = DrivePhase.CONNECTED, speed: Double? = 0.0) =
        DriveState(vin, phase, automatic = automatic, snapshot = DriveSnapshot(NavigationDestination.None, speed, null, null))

    @Test fun automaticSessionMovesToTheCarWhoseAudioConnected() {
        assertEquals(three, VehicleSwitch.switchTarget(drive(y), bluetooth(threeAudio), profiles))
        assertEquals(three, VehicleSwitch.switchTarget(drive(y, phase = DrivePhase.RECONNECTING, speed = null), bluetooth(threeAudio), profiles))
    }

    @Test fun switchLeavesCorrectManualMovingAndEndedSessionsAlone() {
        assertNull(VehicleSwitch.switchTarget(drive(three), bluetooth(threeAudio), profiles))
        assertNull(VehicleSwitch.switchTarget(drive(y, automatic = false), bluetooth(threeAudio), profiles))
        assertNull(VehicleSwitch.switchTarget(drive(y, speed = 30.0), bluetooth(threeAudio), profiles))
        assertNull(VehicleSwitch.switchTarget(drive(y, phase = DrivePhase.ERROR), bluetooth(threeAudio), profiles))
        assertNull(VehicleSwitch.switchTarget(drive(y), bluetooth(), profiles))
        assertNull(VehicleSwitch.switchTarget(drive(y), bluetooth(yAudio, threeAudio), profiles))
    }
}
