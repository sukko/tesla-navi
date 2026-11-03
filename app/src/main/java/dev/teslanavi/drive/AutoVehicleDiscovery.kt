package dev.teslanavi.drive

import dev.teslanavi.protocol.VehicleIdentity

/** Called serially on the main thread; DriveState reserves the first candidate immediately. */
class AutoVehicleDiscovery {
    private var names = emptyMap<String, String>()
    private val gates = mutableMapOf<String, ProximityGate>()
    fun update(registered: Set<String>, verified: Set<String>) {
        val eligible = registered intersect verified
        names = eligible.associateBy(VehicleIdentity::localName)
        gates.keys.retainAll(eligible)
    }
    val scanNames: Set<String> get() = names.keys
    fun canWake(name: String?, rssi: Int, connectable: Boolean) = name in names && connectable && rssi >= ProximityGate.MIN_RSSI
    fun observe(name: String?, rssi: Int, connectable: Boolean, now: Long, busy: Boolean): String? {
        val vin = names[name] ?: return null
        return vin.takeIf { gates.getOrPut(vin) { ProximityGate() }.observe(rssi, connectable, now, canAttempt = !busy) }
    }
    fun awaitDeparture(vin: String, now: Long) { gates.getOrPut(vin) { ProximityGate() }.awaitDeparture(now) }
    fun reset() { names = emptyMap(); gates.clear() }
}
