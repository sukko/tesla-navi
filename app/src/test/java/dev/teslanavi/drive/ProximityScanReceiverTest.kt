package dev.teslanavi.drive

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.TeslaNaviApp
import dev.teslanavi.protocol.VehicleIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothLeScanner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class)
class ProximityScanReceiverTest {
    @Test fun pendingIntentAdvertisementCannotBypassSelectedBluetoothDevices() {
        readyVehicle()
        app.bluetoothStart.select(mapOf("00:11:22:33:44:66" to "Car audio"))
        AutoConnectService.onAdvertisement(app, VehicleIdentity.localName(vin), -50, true)
        assertFalse(app.drive.state.value.active)
        assertNull(shadowOf(app).nextStartedService)
    }
    private val app get() = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private val vin = "5YJYGDEE1NF123456"

    private fun grantPermissions() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun readyVehicle() {
        grantPermissions()
        app.vehicles.add("Model Y", vin)
        app.autoConnect.verified(vin)
        app.autoConnect.setEnabled(true)
    }

    // A complete-local-name advertising structure, as the vehicle sends it.
    private fun advertisement(name: String): ScanResult {
        val bytes = name.toByteArray()
        val record = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
            .invoke(null, byteArrayOf((bytes.size + 1).toByte(), 0x09) + bytes) as ScanRecord
        return ScanResult(adapter.getRemoteDevice("00:11:22:33:44:55"), 0x01, 1, 0, 0xff, 127, -50, 0, record, 0L)
    }

    private fun delivery(vararg names: String) = Intent(app, ProximityScanReceiver::class.java).setAction(ProximityScan.ACTION)
        .putParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ArrayList(names.map(::advertisement)))

    @Test fun deliveryAfterProcessDeathRestoresWaitingServiceInsteadOfConnectingDirectly() {
        readyVehicle()
        assertFalse(AutoConnectService.running)
        ProximityScanReceiver().onReceive(app, delivery(VehicleIdentity.localName(vin)))
        assertEquals(AutoConnectService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(app.drive.state.value.active)
    }

    @Test fun quietWaitDoesNotReopenNotificationFromAQueuedScanOfDisconnectedSelectedCar() {
        readyVehicle()
        app.bluetoothStart.select(mapOf("00:11:22:33:44:55" to "Car audio"))
        app.autoConnect.quiet(true)
        ProximityScanReceiver().onReceive(app, delivery(VehicleIdentity.localName(vin)))
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(app.autoConnect.enabled.value)
        assertTrue(app.autoConnect.quietWaiting.value)
    }

    @Test fun quietSystemScanWithoutAudioSelectionStillWakesForVerifiedNearbyVehicle() {
        readyVehicle()
        app.autoConnect.quiet(true)
        ProximityScanReceiver().onReceive(app, delivery("unregistered"))
        assertNull(shadowOf(app).nextStartedService)
        ProximityScanReceiver().onReceive(app, delivery(VehicleIdentity.localName(vin)))
        assertEquals(AutoConnectService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
    }

    @Test fun scanErrorReachesTheWaitingServiceRetryLoop() = runTest {
        readyVehicle()
        val failure = async(UnconfinedTestDispatcher(testScheduler)) { ProximityScan.failures.first() }
        ProximityScanReceiver().onReceive(app, Intent(app, ProximityScanReceiver::class.java)
            .setAction(ProximityScan.ACTION).putExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 2))
        assertEquals(2, failure.await())
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun registrationLeftAfterTurningOffIsRemovedWithoutWakingAnything() {
        grantPermissions()
        shadowOf(adapter).setEnabled(true)
        ProximityScan.arm(app, setOf(VehicleIdentity.localName(vin)))
        ProximityScanReceiver().onReceive(app, delivery(VehicleIdentity.localName(vin)))
        assertTrue(Shadow.extract<ShadowBluetoothLeScanner>(adapter.bluetoothLeScanner).activeScans.isEmpty())
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun unrelatedIntentIsIgnored() {
        readyVehicle()
        ProximityScanReceiver().onReceive(app, Intent("other").putExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 2))
        assertNull(shadowOf(app).nextStartedService)
    }
}
