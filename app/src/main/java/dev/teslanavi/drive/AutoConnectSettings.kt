package dev.teslanavi.drive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AutoConnectSettings(context: Context) {
    val proximity = AutoVehicleDiscovery()
    private val prefs = context.getSharedPreferences("auto-connect", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled = _enabled.asStateFlow()
    private val _quiet = MutableStateFlow(_enabled.value && prefs.getBoolean("quiet-waiting", false))
    val quietWaiting = _quiet.asStateFlow()
    private val _status = MutableStateFlow(if (!_enabled.value) "자동 연결 꺼짐" else if (_quiet.value)
        "알림 없이 다음 차량 연결을 기다리고 있습니다" else "자동 연결을 준비하고 있습니다")
    val status = _status.asStateFlow()
    private val _verified = MutableStateFlow(prefs.getStringSet("verified-vins", emptySet())!!.toSet())
    val verified = _verified.asStateFlow()

    fun setEnabled(value: Boolean) {
        check(prefs.edit().putBoolean("enabled", value).commit())
        _enabled.value = value
        quiet(false)
        if (!value) proximity.reset()
        if (!value) _status.value = "자동 연결 꺼짐"
    }

    fun quiet(value: Boolean) {
        if (_quiet.value == value) return
        check(prefs.edit().putBoolean("quiet-waiting", value).commit())
        _quiet.value = value
        if (value) _status.value = "알림 없이 다음 차량 연결을 기다리고 있습니다"
    }

    fun verified(vin: String) {
        if (vin in _verified.value) return
        val values = _verified.value + vin
        check(prefs.edit().putStringSet("verified-vins", values).commit())
        _verified.value = values
    }

    fun forgetVerification(vin: String) {
        val values = _verified.value - vin
        check(prefs.edit().putStringSet("verified-vins", values).commit())
        _verified.value = values
    }

    fun report(message: String) { _status.value = message }

    companion object {
        fun ready(context: Context): Boolean {
            fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            return granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
                (Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) &&
                (Build.VERSION.SDK_INT < 31 || (granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT))) &&
                (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
}
