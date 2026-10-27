package dev.teslanavi.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** Optional hardware step events, registered only while disconnected guidance is active. No step history is stored. */
class WalkingSteps(private val context: Context) {
    val supported get() = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
    val permitted get() = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context,
        Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    val events get() = callbackFlow<Long> {
        if (!permitted) { close(); return@callbackFlow }
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (manager == null || sensor == null) { close(); return@callbackFlow }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.firstOrNull()?.let { it > 0 } == true) trySend(event.timestamp / 1_000_000)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = try { manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
        catch (_: SecurityException) { false }
        if (!registered) { close(); return@callbackFlow }
        awaitClose { manager.unregisterListener(listener) }
    }
}
