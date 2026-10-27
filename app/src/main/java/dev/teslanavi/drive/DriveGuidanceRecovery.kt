package dev.teslanavi.drive

import dev.teslanavi.navigation.CameraState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** Keep the camera service alive after BLE retries are exhausted. Its own GPS inactivity timer owns the end
 * of a disconnected trip; vehicle recovery must neither end it early nor outlive it. */
internal suspend fun runWithGuidanceRecovery(
    cameras: StateFlow<CameraState>,
    runVehicle: suspend () -> Unit,
    onFailure: (Exception) -> Unit,
    onRetry: () -> Unit,
) = coroutineScope {
    val expired = async { cameras.first { it.continuationExpired } }
    val vehicle = async {
        while (isActive) {
            try { runVehicle(); return@async }
            catch (error: CancellationException) {
                if (error !is TimeoutCancellationException) throw error
                currentCoroutineContext().ensureActive()
                onFailure(error)
            } catch (error: Exception) { onFailure(error) }
            // Wait for the camera observer's decision; yielding alone can end the service before combine()
            // has processed a terminal BLE failure and enabled continuation.
            cameras.first { !it.vehicleAvailable }
            if (!cameras.value.phoneFallback) return@async
            delay(30_000)
            if (!cameras.value.phoneFallback) return@async
            onRetry()
        }
    }
    try { select<Unit> { vehicle.onAwait { }; expired.onAwait { } } }
    finally { vehicle.cancel(); expired.cancel() }
}
