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
        if (intent.action != ProximityScan.ACTION && intent.action != ProximityScan.WATCH_EXPIRED) return
        val app = context.applicationContext as TeslaNaviApp
        // A registration left behind after the user turned the feature off must not keep waking the app.
        if (!app.autoConnect.enabled.value || !AutoConnectSettings.ready(context)) {
            ProximityScan.disarm(context)
            return
        }
        if (intent.action == ProximityScan.WATCH_EXPIRED) {
            // A running service owns its own scan and follows the same expiry.
            if (!AutoConnectService.running && !AutoConnectService.allowance(app).allowed) ProximityScan.disarm(context)
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
            // A queued result from a stopped scan, or the car just left, must not reopen the notification.
            val allowance = AutoConnectService.allowance(app)
            if (results.none { app.autoConnect.proximity.canWake(it.scanRecord?.deviceName, it.rssi, it.isConnectable, allowance) }) return
            // The waiting service owns vehicle connections; restore it and let it handle the next sightings.
            try {
                AutoConnectService.start(context)
                app.drive.recordAutomaticStart("등록 차량 신호 감지 · 대기 서비스 복원")
            } catch (error: Exception) {
                app.drive.recordAutomaticStart("차량 감지 후 서비스 시작 실패 · ${error.javaClass.simpleName}")
                app.autoConnect.report("앱에서 자동 연결을 다시 켜 주세요")
            }
            return
        }
        results.forEach { AutoConnectService.onAdvertisement(context, it.scanRecord?.deviceName, it.rssi, it.isConnectable) }
    }
}
