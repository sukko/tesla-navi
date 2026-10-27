package dev.teslanavi.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.IOException

internal enum class GattRxMode(val label: String, val value: ByteArray) {
    INDICATION("확인 응답 방식", BluetoothGattDescriptor.ENABLE_INDICATION_VALUE),
    NOTIFICATION("일반 알림 방식", BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);

    companion object {
        fun select(properties: Int, preferNotification: Boolean): GattRxMode {
            val notify = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val indicate = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            return when {
                preferNotification && notify -> NOTIFICATION
                indicate -> INDICATION
                notify -> NOTIFICATION
                else -> throw IOException("차량 수신 채널이 응답 알림을 지원하지 않습니다.")
            }
        }
        fun supportsBoth(properties: Int) = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 &&
            properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
    }
}

/** Only a failed CCCD setup on a dual-mode RX channel allows this fallback. */
internal class GattRxSubscriptionException(message: String, val fallbackAllowed: Boolean, cause: Throwable? = null) :
    IOException(message, cause)

internal suspend fun awaitRxSubscription(acknowledged: Deferred<Unit>, mode: GattRxMode, supportsBoth: Boolean) {
    try { withTimeout(8000) { acknowledged.await() } }
    catch (error: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw GattRxSubscriptionException("차량 응답 수신 설정에 응답이 없습니다. (${mode.label})",
            supportsBoth && mode == GattRxMode.INDICATION, error)
    }
}

/** Each attempt must own a fresh GATT and close it on failure before returning here. Never retry an accepted
 * descriptor write on the same GATT: its delayed callback could incorrectly acknowledge the replacement. */
internal suspend fun <T> connectWithRxFallback(onRetry: () -> Unit, connect: suspend (Boolean) -> T): T {
    try { return connect(false) }
    catch (error: GattRxSubscriptionException) {
        if (!error.fallbackAllowed) throw error
        onRetry()
        delay(1000)
        return connect(true)
    }
}
