package dev.teslanavi.drive

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.teslanavi.TeslaNaviApp

class BluetoothStartReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TeslaNaviApp
        if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) app.bluetoothStart.radioOff()
            else {
                app.bluetoothStart.refresh()
                if (app.autoConnect.enabled.value && !AutoConnectService.running && AutoConnectSettings.ready(context)) {
                    app.drive.recordAutomaticStart("Bluetooth 켜짐 · 자동 연결 대기 서비스 복원")
                    try { AutoConnectService.start(context) }
                    catch (error: Exception) {
                        app.drive.recordAutomaticStart("자동 연결 서비스 복원 실패 · ${error.javaClass.simpleName}")
                        app.autoConnect.report("앱에서 자동 연결 대기를 다시 시작해 주세요")
                    }
                }
            }
            return
        }
        val profile = when (intent.action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> BluetoothProfile.A2DP
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> BluetoothProfile.HEADSET
            else -> null
        }
        if (profile == null && intent.action !in setOf(BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED)) return
        val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        device ?: return
        try {
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            if (profile != null && state !in setOf(BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED)) return
            val connected = if (profile == null) intent.action == BluetoothDevice.ACTION_ACL_CONNECTED else state == BluetoothProfile.STATE_CONNECTED
            if (profile == null) app.bluetoothStart.connectionChanged(device.address, connected)
            else app.bluetoothStart.profileConnectionChanged(profile, device.address, connected)
            app.bluetoothStart.refresh()
            if (device.address !in app.bluetoothStart.state.value.selectedDevices) return
            fun record(message: String) = app.drive.recordAutomaticStart(message)
            record("선택 기기 ${if (connected) "연결" else "해제"} 수신 · ${app.bluetoothStart.state.value.selectedDevices[device.address]} · ${if (profile == null) "ACL" else "프로필 $profile"} · 자동 연결 ${app.autoConnect.enabled.value}")
            if (!connected || !app.autoConnect.enabled.value) return
            if (!AutoConnectSettings.ready(context)) {
                record("권한 부족으로 자동 시작 보류")
                app.autoConnect.report("자동 시작 권한을 확인해 주세요"); return
            }
            if (AutoConnectService.running) { record("자동 연결 서비스 실행 중 · 연결 상태 반영"); return }
            try { AutoConnectService.start(context) }
            catch (error: Exception) {
                record("자동 연결 서비스 시작 실패 · ${error.javaClass.simpleName}")
                app.autoConnect.report("자동 시작을 준비하지 못했습니다. 앱을 열어 다시 켜 주세요")
            }
        } catch (_: SecurityException) { app.autoConnect.report("근처 기기 권한을 확인해 주세요") }
    }
}
