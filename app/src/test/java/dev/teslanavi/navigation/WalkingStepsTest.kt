package dev.teslanavi.navigation

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WalkingStepsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun deniedPermissionNeverRegistersSensorOrPreventsNormalGuidance() = runTest {
        val manager = context.getSystemService(SensorManager::class.java)
        shadowOf(manager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_STEP_DETECTOR))
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val monitor = WalkingSteps(context)
        assertTrue(monitor.supported)
        assertFalse(monitor.permitted)
        monitor.events.collect { fail("permission denied") }
        assertTrue(shadowOf(manager).listeners.isEmpty())
    }

    @Test fun sensorIsReleasedWhenDisconnectedGuidanceEnds() = runTest {
        val manager = context.getSystemService(SensorManager::class.java)
        shadowOf(manager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_STEP_DETECTOR))
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val received = mutableListOf<Long>()
        val job = launch { WalkingSteps(context).events.collect { received += it } }
        runCurrent()
        assertEquals(1, shadowOf(manager).listeners.size)
        val event = ShadowSensorManager.createSensorEvent(1, Sensor.TYPE_STEP_DETECTOR)
        event.values[0] = 1f
        event.timestamp = 12_345_000_000
        shadowOf(manager).sendSensorEventToListeners(event)
        runCurrent()
        assertEquals(listOf(12_345L), received)
        job.cancel(); runCurrent()
        assertTrue(shadowOf(manager).listeners.isEmpty())
    }
}
