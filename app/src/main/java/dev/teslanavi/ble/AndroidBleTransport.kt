@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
package dev.teslanavi.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.teslanavi.protocol.BleFraming
import dev.teslanavi.protocol.VehicleIdentity
import dev.teslanavi.protocol.VehicleTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("MissingPermission")
class AndroidBleTransport private constructor(private val context: Context) : VehicleTransport {
    override val incoming = Channel<ByteArray>(32)
    private val connected = CompletableDeferred<Unit>()
    private val services = CompletableDeferred<Unit>()
    private val configured = CompletableDeferred<Unit>()
    private val negotiatedMtu = CompletableDeferred<Int>()
    private val framing = BleFraming()
    private val writeMutex = Mutex()
    @Volatile private var pendingWrite: CompletableDeferred<Unit>? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    private var tx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    @Volatile private var rxMode: GattRxMode? = null
    @Volatile private var rxSupportsBoth = false
    private val receivedChunks = AtomicLong()
    private val receivedMessages = AtomicLong()
    private val lastReceivedAt = AtomicLong()
    override fun diagnostics(): String {
        val last = lastReceivedAt.get()
        val age = if (receivedChunks.get() == 0L) "없음" else "${SystemClock.elapsedRealtime() - last}ms"
        return "MTU=$mtu, RX=${rxMode?.name ?: "미설정"}, 수신조각=${receivedChunks.get()}, 완성메시지=${receivedMessages.get()}, 마지막수신=$age"
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i("TeslaNaviBle", "connection state=$newState status=$status closed=$closed")
            if (closed) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail(IOException("차량 Bluetooth 연결이 종료되었습니다. (GATT $status)"))
                g.close()
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) services.complete(Unit)
            else services.completeExceptionally(IOException("차량 서비스 검색 실패 ($status)"))
        }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            negotiatedMtu.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else 23)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (closed || descriptor.uuid != CCCD || descriptor.characteristic.uuid != RX) return
            Log.i("TeslaNaviBle", "RX descriptor callback status=$status mode=${rxMode?.name}")
            if (status == BluetoothGatt.GATT_SUCCESS) configured.complete(Unit)
            else configured.completeExceptionally(GattRxSubscriptionException("차량 알림 구독 실패 ($status)",
                rxSupportsBoth && rxMode == GattRxMode.INDICATION && status !in setOf(5, 8, 15)))
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != TX) return
            if (status == BluetoothGatt.GATT_SUCCESS) pendingWrite?.complete(Unit)
            else pendingWrite?.completeExceptionally(IOException("차량 메시지 전송 실패 ($status)"))
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == RX) receive(characteristic.value.copyOf())
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == RX) receive(value.copyOf())
        }
    }

    private fun receive(chunk: ByteArray) {
        if (closed) return
        receivedChunks.incrementAndGet()
        lastReceivedAt.set(SystemClock.elapsedRealtime())
        try {
            for (message in framing.accept(chunk, SystemClock.elapsedRealtime())) {
                receivedMessages.incrementAndGet()
                if (!incoming.trySend(message).isSuccess) throw IOException("차량 응답 처리가 지연되었습니다. 다시 연결해 주세요.")
            }
        } catch (error: Exception) {
            fail(error)
            gatt?.disconnect()
        }
    }

    private fun fail(error: Exception) {
        connected.completeExceptionally(error)
        services.completeExceptionally(error)
        configured.completeExceptionally(error)
        negotiatedMtu.completeExceptionally(error)
        pendingWrite?.completeExceptionally(error)
        incoming.close(error)
    }

    private suspend fun open(device: BluetoothDevice, onStatus: (String) -> Unit, preferNotification: Boolean) {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Bluetooth 연결을 시작하지 못했습니다.")
        connectionStep(15000, "차량은 발견했지만 Bluetooth 연결을 완료하지 못했습니다. 차량 가까이에서 다시 시도해 주세요.") { connected.await() }
        val connection = checkNotNull(gatt)
        onStatus("차량 Bluetooth 서비스 확인 중")
        check(connection.discoverServices()) { "차량 서비스 검색을 시작하지 못했습니다." }
        connectionStep(10000, "차량 Bluetooth 서비스 응답 시간이 초과되었습니다.") { services.await() }
        val service = connection.getService(SERVICE) ?: error("테슬라 BLE 서비스를 찾지 못했습니다.")
        tx = service.getCharacteristic(TX) ?: error("차량 전송 채널이 없습니다.")
        val rx = service.getCharacteristic(RX) ?: error("차량 수신 채널이 없습니다.")
        onStatus("차량 메시지 채널 설정 중")
        // Subscribe before MTU negotiation, as in Tesla's BLE connector. Do not let the MTU operation
        // precede the critical RX setup during recovery from a dropped link.
        check(connection.setCharacteristicNotification(rx, true)) { "차량 알림을 활성화하지 못했습니다." }
        val descriptor = rx.getDescriptor(CCCD) ?: error("차량 알림 설정을 찾지 못했습니다.")
        rxSupportsBoth = GattRxMode.supportsBoth(rx.properties)
        val mode = GattRxMode.select(rx.properties, preferNotification).also { rxMode = it }
        val fallbackAllowed = rxSupportsBoth && mode == GattRxMode.INDICATION
        onStatus("차량 응답 수신 설정 중 · ${mode.label}")
        var writeCode = BluetoothStatusCodes.SUCCESS
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            writeCode = retryBusyGattWrite { connection.writeDescriptor(descriptor, mode.value) }
            writeCode == BluetoothStatusCodes.SUCCESS
        } else { descriptor.value = mode.value; connection.writeDescriptor(descriptor) }
        Log.i("TeslaNaviBle", "RX descriptor requested mode=${mode.name} properties=${rx.properties} accepted=$accepted code=$writeCode")
        if (!accepted) throw GattRxSubscriptionException("차량 알림 구독을 시작하지 못했습니다. (Android write $writeCode)", fallbackAllowed)
        awaitRxSubscription(configured, mode, rxSupportsBoth)
        onStatus("차량 응답 수신 설정 완료 · ${mode.label}")
        if (connection.requestMtu(512)) mtu = connectionStep(8000, "Bluetooth 전송 크기 협상 시간이 초과되었습니다.") { negotiatedMtu.await() }.coerceAtLeast(23)
        onStatus("Bluetooth 전송 크기 확인 · MTU $mtu")
    }

    override suspend fun send(message: ByteArray) = writeMutex.withLock {
        check(!closed) { "차량 연결이 닫혔습니다." }
        val connection = checkNotNull(gatt)
        val characteristic = checkNotNull(tx)
        val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        for (chunk in BleFraming.encode(message, mtu)) {
            val completion = CompletableDeferred<Unit>()
            pendingWrite = completion
            try {
                var writeCode = 0
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    writeCode = retryBusyGattWrite { connection.writeCharacteristic(characteristic, chunk, writeType) }
                    writeCode == BluetoothStatusCodes.SUCCESS
                }
                else {
                    characteristic.writeType = writeType
                    characteristic.value = chunk
                    connection.writeCharacteristic(characteristic)
                }
                check(accepted) { "차량 전송을 시작하지 못했습니다. (Android write $writeCode)" }
                connectionStep(5000, "차량으로 메시지를 전송하는 중 응답 시간이 초과되었습니다.") { completion.await() }
            } finally { pendingWrite = null }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        fail(IOException("차량 연결을 종료했습니다."))
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("00000211-b2d1-43f0-9b88-960cebf8b91e")
        private val TX = UUID.fromString("00000212-b2d1-43f0-9b88-960cebf8b91e")
        private val RX = UUID.fromString("00000213-b2d1-43f0-9b88-960cebf8b91e")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        suspend fun connect(context: Context, vin: String, onStatus: (String) -> Unit): AndroidBleTransport {
            val manager = context.getSystemService(BluetoothManager::class.java)
            val adapter = manager?.adapter ?: error("이 기기는 Bluetooth LE를 지원하지 않습니다.")
            check(adapter.isEnabled) { "Bluetooth를 켜 주세요." }
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                "차량 검색을 위해 정확한 위치 권한을 허용해 주세요."
            }
            check(LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))) {
                "휴대폰의 위치 기능을 켠 뒤 차량 검색을 다시 시도해 주세요."
            }
            onStatus("차량 검색 중")
            val device = scan(adapter, VehicleIdentity.localName(vin), onStatus)
            return connectWithRxFallback(onRetry = {
                onStatus("차량 응답 수신 설정 실패 · 일반 알림 방식으로 다시 연결합니다")
            }) { preferNotification ->
                onStatus("차량 연결 중")
                val transport = AndroidBleTransport(context.applicationContext)
                try { transport.open(device, onStatus, preferNotification); transport }
                catch (error: Throwable) { transport.close(); throw error }
            }
        }

        private suspend fun scan(adapter: BluetoothAdapter, name: String, onStatus: (String) -> Unit): BluetoothDevice {
            val scanner = adapter.bluetoothLeScanner ?: error("Bluetooth 검색을 시작할 수 없습니다.")
            val result = CompletableDeferred<BluetoothDevice>()
            val evidence = ScanEvidence(name)
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, item: ScanResult) {
                    if (evidence.observe(item.scanRecord?.deviceName, item.isConnectable)) result.complete(item.device)
                }
                override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onScanResult(0, it) } }
                override fun onScanFailed(errorCode: Int) {
                    result.completeExceptionally(IOException("Bluetooth 검색 실패 ($errorCode)"))
                }
            }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build()
            try {
                scanner.startScan(listOf(ScanFilter.Builder().setDeviceName(name).build()),
                    settings, callback)
                withTimeoutOrNull(10000) { result.await() }?.let { return it }
            } finally { scanner.stopScan(callback) }
            onStatus("차량 보조 검색 중 · 휴대폰 화면을 켜 두세요")
            try {
                // Bypass device-name offload filtering, but only connect to the exact VIN-derived name.
                scanner.startScan(null, settings, callback)
                return withTimeoutOrNull(20000) { result.await() } ?: throw IOException(evidence.timeoutMessage())
            } finally { scanner.stopScan(callback) }
        }
    }
}

private suspend fun <T> connectionStep(timeoutMs: Long, message: String, block: suspend () -> T): T =
    try { withTimeout(timeoutMs) { block() } }
    catch (error: TimeoutCancellationException) {
        // Preserve cancellation of the containing service; translate only this step's timeout.
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        throw IOException(message, error)
    }
