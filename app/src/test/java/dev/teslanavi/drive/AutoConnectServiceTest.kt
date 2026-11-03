package dev.teslanavi.drive

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.TeslaNaviApp
import dev.teslanavi.protocol.VehicleIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothLeScanner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class)
class AutoConnectServiceTest {
    @Test fun idleNotificationIsRemovedButPendingIntentScanCanRestoreAutomation() = idleScenario(false)
    @Test fun idleWithSelectedAudioDeviceWaitsSilentlyUntilThatDeviceConnects() = idleScenario(true)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun idleScenario(selectedAudio: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(AutoConnectService::class.java).create()
        var destroyed = false
        try {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            val adapter = app.getSystemService(BluetoothManager::class.java).adapter
            shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
            shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
            val vin = "5YJYGDEE1NF123456"
            val address = "00:11:22:33:44:55"
            app.vehicles.add("Model Y", vin); app.autoConnect.verified(vin); app.autoConnect.setEnabled(true)
            if (selectedAudio) {
                shadowOf(adapter).setBondedDevices(setOf(adapter.getRemoteDevice(address)))
                app.bluetoothStart.select(mapOf(address to "Car audio"))
            }
            controller.get().onStartCommand(null, 0, 1); runCurrent()
            val manager = app.getSystemService(android.app.NotificationManager::class.java)
            assertNotNull(shadowOf(manager).getNotification(2))
            advanceTimeBy(119_999); runCurrent()
            assertTrue(AutoConnectService.running)
            advanceTimeBy(2); runCurrent()
            assertFalse(AutoConnectService.running)
            assertNull(shadowOf(manager).getNotification(2))
            assertTrue(AutoConnectSettings(app).enabled.value)
            assertTrue(AutoConnectSettings(app).quietWaiting.value)
            controller.destroy(); destroyed = true; runCurrent()
            val scanner = Shadow.extract<ShadowBluetoothLeScanner>(adapter.bluetoothLeScanner)
            if (selectedAudio) {
                assertTrue(scanner.activeScans.isEmpty())
                BluetoothStartReceiver().onReceive(app, Intent(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
                    .putExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, adapter.getRemoteDevice(address)))
                assertEquals(AutoConnectService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
            } else {
                assertEquals("the system scan must survive service destruction", 1, scanner.activeScans.size)
            }
        } finally { if (!destroyed) controller.destroy(); runCurrent(); Dispatchers.resetMain() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun activeVehicleStopsExtraScanningAndEndingSessionResumesWaitingScan() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(AutoConnectService::class.java).create()
        try {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            val adapter = app.getSystemService(BluetoothManager::class.java).adapter
            shadowOf(adapter).setEnabled(true)
            shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
            val vin = "5YJYGDEE1NF123456"
            app.vehicles.add("Model Y", vin); app.autoConnect.verified(vin); app.autoConnect.setEnabled(true)
            controller.get().onStartCommand(null, 0, 1); runCurrent()
            val scanner = Shadow.extract<ShadowBluetoothLeScanner>(adapter.bluetoothLeScanner)
            assertEquals(1, scanner.activeScans.size)
            app.drive.prepare(vin, automatic = true); runCurrent()
            assertTrue(scanner.activeScans.isEmpty())
            app.drive.clear(); runCurrent()
            assertEquals(1, scanner.activeScans.size)
        } finally {
            controller.destroy(); runCurrent(); Dispatchers.resetMain()
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun bluetoothRestartResumesScanningImmediatelyWithoutWaitingForRepairPoll() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(AutoConnectService::class.java).create()
        try {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            val adapter = app.getSystemService(BluetoothManager::class.java).adapter
            shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
            shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
            val vin = "5YJYGDEE1NF123456"
            app.vehicles.add("Model Y", vin); app.autoConnect.verified(vin); app.autoConnect.setEnabled(true)
            controller.get().onStartCommand(null, 0, 1); runCurrent()
            val scanner = Shadow.extract<ShadowBluetoothLeScanner>(adapter.bluetoothLeScanner)
            val original = scanner.activeScans.single()
            // No selected audio device: its unchanged list cannot stand in for a radio-state event.
            shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
            app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF))
            shadowOf(android.os.Looper.getMainLooper()).idle(); runCurrent()
            assertEquals("Bluetooth를 켜면 자동 연결을 다시 시작합니다", app.autoConnect.status.value)
            shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
            app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON))
            shadowOf(android.os.Looper.getMainLooper()).idle(); runCurrent()
            assertEquals(0L, testScheduler.currentTime)
            assertEquals(1, scanner.activeScans.size)
            assertNotSame(original, scanner.activeScans.single())
            val restored = scanner.activeScans.single()
            advanceTimeBy(60_001); runCurrent()
            assertSame("periodic repair must not repeatedly re-register a healthy scan", restored, scanner.activeScans.single())
            // Recover even when the preceding OFF delivery was missed or coalesced.
            app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON))
            shadowOf(android.os.Looper.getMainLooper()).idle(); runCurrent()
            assertNotSame(restored, scanner.activeScans.single())
            val location = app.getSystemService(LocationManager::class.java)
            shadowOf(location).setLocationEnabled(false)
            app.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))
            shadowOf(android.os.Looper.getMainLooper()).idle(); runCurrent()
            assertEquals("휴대폰 위치 기능을 켜면 자동 연결을 다시 시작합니다", app.autoConnect.status.value)
            assertTrue(scanner.activeScans.isEmpty())
            shadowOf(location).setLocationEnabled(true)
            app.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))
            shadowOf(android.os.Looper.getMainLooper()).idle(); runCurrent()
            assertEquals(1, scanner.activeScans.size)
            assertTrue(AutoConnectSettings(app).enabled.value)
        } finally { controller.destroy(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun idleSessionShutdownDoesNotDisableOrRestartTheWaitingService() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        val vin = "5YJYGDEE1NF123456"
        app.vehicles.add("Model Y", vin); app.autoConnect.verified(vin); app.autoConnect.setEnabled(true)
        app.drive.prepare(vin, automatic = true)
        val controller = Robolectric.buildService(DriveService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(app, DriveService::class.java).setAction("dev.teslanavi.IDLE_STOP"), 0, 1)
            assertTrue(AutoConnectSettings(app).enabled.value)
            assertFalse(app.drive.state.value.active)
            assertNull("quiet shutdown must not resurrect another ongoing notification", shadowOf(app).nextStartedService)
        } finally { controller.destroy() }
    }

    @Test fun endingCurrentConnectionKeepsAutomationEnabledButWaitsForDeparture() {
        val vin = "5YJYGDEE1NF123456"
        app.vehicles.add("Model Y", vin); app.autoConnect.verified(vin); app.autoConnect.setEnabled(true)
        app.autoConnect.proximity.update(setOf(vin), setOf(vin))
        app.drive.prepare(vin, automatic = true)
        val controller = Robolectric.buildService(DriveService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(app, DriveService::class.java).setAction("dev.teslanavi.STOP"), 0, 1)
            assertTrue(AutoConnectSettings(app).enabled.value)
            assertFalse(app.drive.state.value.active)
            assertNull(app.autoConnect.proximity.observe(VehicleIdentity.localName(vin), -50, true,
                android.os.SystemClock.elapsedRealtime() + 1, busy = false))
            assertEquals(vin, app.autoConnect.proximity.observe(VehicleIdentity.localName(vin), -50, true,
                android.os.SystemClock.elapsedRealtime() + 61_000, busy = false))
        } finally { controller.destroy() }
    }

    private val app get() = ApplicationProvider.getApplicationContext<TeslaNaviApp>()

    @Test fun automaticWaitingRunsAsVisibleForegroundServiceAndCanBeDisabled() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        app.autoConnect.setEnabled(true)
        val controller = Robolectric.buildService(AutoConnectService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull(notification)
        assertEquals(dev.teslanavi.R.drawable.ic_notification, notification.smallIcon.resId)
        assertTrue(AutoConnectService.running)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("자동 연결 끄기", notification.actions.single().title.toString())
        AutoConnectService.disable(app)
        assertFalse(app.autoConnect.enabled.value)
        assertFalse(AutoConnectSettings(app).enabled.value)
        controller.destroy()
    }

    @Test fun automaticStartUsesDetectedVerifiedVehicleEvenWhenAnotherVehicleWasSelected() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val a = "5YJ3E1EA1MF123456"
        val b = "5YJYGDEE1NF123456"
        app.vehicles.add("Model 3", a); app.vehicles.add("Model Y", b); app.vehicles.select(a)
        app.autoConnect.setEnabled(true)
        app.autoConnect.verified(b)
        val controller = Robolectric.buildService(DriveService::class.java).create()
        controller.get().onStartCommand(Intent(app, DriveService::class.java).putExtra("vin", b).putExtra("automatic", true), 0, 1)
        assertEquals(b, app.vehicles.selected.value)
        controller.destroy()
    }

    @Test fun queuedAutomaticStartCannotUseUnverifiedVehicle() {
        val a = "5YJ3E1EA1MF123456"
        val b = "5YJYGDEE1NF123456"
        app.vehicles.add("Model 3", a); app.vehicles.add("Model Y", b); app.vehicles.select(a)
        app.autoConnect.setEnabled(true)
        app.autoConnect.verified(a)
        val controller = Robolectric.buildService(DriveService::class.java).create()
        controller.get().onStartCommand(Intent(app, DriveService::class.java).putExtra("vin", b).putExtra("automatic", true), 0, 1)
        assertEquals(a, app.vehicles.selected.value)
        assertFalse(app.drive.state.value.active)
        controller.destroy()
    }

    @Test fun nearbyVerifiedVehicleAdvertisementStartsAutomaticConnection() {
        val a = "5YJ3E1EA1MF123456"
        val b = "5YJYGDEE1NF123456"
        app.vehicles.add("Model 3", a); app.vehicles.add("Model Y", b); app.vehicles.select(a)
        app.autoConnect.setEnabled(true)
        app.autoConnect.verified(b)
        AutoConnectService.onAdvertisement(app, VehicleIdentity.localName(a), -50, true)
        assertNull(shadowOf(app).nextStartedService)
        AutoConnectService.onAdvertisement(app, VehicleIdentity.localName(b), -50, true)
        val started = shadowOf(app).nextStartedService
        assertEquals(DriveService::class.java.name, started.component!!.className)
        assertEquals(b, started.getStringExtra("vin"))
        assertTrue(started.getBooleanExtra("automatic", false))
        assertEquals(b, app.vehicles.selected.value)
    }

    @Test fun advertisementIsIgnoredAfterAutomaticConnectionWasTurnedOff() {
        val b = "5YJYGDEE1NF123456"
        app.vehicles.add("Model Y", b)
        app.autoConnect.verified(b)
        AutoConnectService.onAdvertisement(app, VehicleIdentity.localName(b), -50, true)
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(app.drive.state.value.active)
    }

    @Test fun missingPermissionsNeverStartForegroundOrVehicleConnection() {
        app.autoConnect.setEnabled(true)
        val controller = Robolectric.buildService(AutoConnectService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertNull(shadowOf(service).lastForegroundNotification)
        assertFalse(app.drive.state.value.active)
        controller.destroy()
    }
}
