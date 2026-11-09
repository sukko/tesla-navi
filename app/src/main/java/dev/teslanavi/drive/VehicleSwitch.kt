package dev.teslanavi.drive

import dev.teslanavi.data.VehicleProfile

/** After a trip, the car just left stays excluded while another registered car may connect without its audio link. */
data class VehicleSwitchWatch(val leftVin: String, val untilMillis: Long)

/** Which vehicles automatic connection may use right now. */
internal data class ConnectionAllowance(val allowed: Boolean, val only: String? = null, val except: String? = null) {
    fun permits(vin: String) = allowed && (only == null || only == vin) && vin != except
}

internal object VehicleSwitch {
    /** Moving between two parked cars took under five minutes in real use; a longer watch reconnects to passed cars. */
    const val WATCH_MILLIS = 5 * 60 * 1000L

    /** The audio link names the car being sat in. Two cars of the same model cannot be told apart and give null. */
    fun vehicleForDevice(deviceName: String?, profiles: List<VehicleProfile>): String? {
        fun normalized(value: String) = value.filterNot(Char::isWhitespace).lowercase()
        val name = normalized(deviceName ?: return null)
        return profiles.filter { normalized(it.model) in name }.singleOrNull()?.vin
    }

    fun allowance(bluetooth: BluetoothStartState, profiles: List<VehicleProfile>, watch: VehicleSwitchWatch?, now: Long): ConnectionAllowance {
        if (bluetooth.selectedDevices.isEmpty()) return ConnectionAllowance(true)
        val connected = bluetooth.devices.filter { it.connected && it.address in bluetooth.selectedDevices }
        if (connected.isNotEmpty()) {
            val vins = connected.map { vehicleForDevice(bluetooth.selectedDevices[it.address], profiles) }.toSet()
            return ConnectionAllowance(true, only = vins.singleOrNull())
        }
        return if (watch != null && now < watch.untilMillis) ConnectionAllowance(true, except = watch.leftVin)
        else ConnectionAllowance(false)
    }

    /** The car to move to when an automatic session holds another car; a moving session is never interrupted. */
    fun switchTarget(drive: DriveState, bluetooth: BluetoothStartState, profiles: List<VehicleProfile>): String? {
        if (!drive.active || !drive.automatic) return null
        val target = allowance(bluetooth, profiles, null, 0).only ?: return null
        val moving = drive.snapshot?.speedKph?.let { it.isFinite() && it >= TripMotionGate.START_KPH } == true
        return target.takeIf { it != drive.vin && !moving }
    }
}
