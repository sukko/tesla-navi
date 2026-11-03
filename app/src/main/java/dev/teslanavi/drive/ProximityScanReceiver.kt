package dev.teslanavi.drive

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import dev.teslanavi.TeslaNaviApp

/** Receives [ProximityScan] deliveries, whether or not the process was alive when the car advertised. */
class ProximityScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ProximityScan.ACTION) return
        val app = context.applicationContext as TeslaNaviApp
        // A registration left behind after the user turned the feature off must not keep waking the app.
        if (!app.autoConnect.enabled.value || !AutoConnectSettings.ready(context)) {
            ProximityScan.disarm(context)
            return
        }
        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (error != 0) {
            ProximityScan.failures.tryEmit(error)
            return
        }
        val results = IntentCompat.getParcelableArrayListExtra(intent, BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
            ScanResult::class.java).orEmpty()
        if (results.isEmpty()) return
        if (!AutoConnectService.running) {
            app.autoConnect.proximity.update(app.vehicles.vehicles.value.map { it.vin }.toSet(), app.autoConnect.verified.value)
            if (results.none { app.autoConnect.proximity.canWake(it.scanRecord?.deviceName, it.rssi, it.isConnectable) }) return
            // A queued result from the scan we just stopped must not reopen the exited car's notification.
            if (app.autoConnect.quietWaiting.value && app.bluetoothStart.state.value.selectedDevices.isNotEmpty() &&
                !app.bluetoothStart.state.value.allowed) return
            // The waiting service owns vehicle connections; restore it and let it handle the next sightings.
            try { AutoConnectService.start(context) }
            catch (_: Exception) { app.autoConnect.report("앱에서 자동 연결을 다시 켜 주세요") }
            return
        }
        results.forEach { AutoConnectService.onAdvertisement(context, it.scanRecord?.deviceName, it.rssi, it.isConnectable) }
    }
}
