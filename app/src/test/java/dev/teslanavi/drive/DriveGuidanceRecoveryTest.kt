package dev.teslanavi.drive

import dev.teslanavi.navigation.CameraState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DriveGuidanceRecoveryTest {
    @Test fun exhaustedBleRetriesKeepCameraServiceAliveAndExpiryCancelsPendingReconnect() = runTest {
        val camera = MutableStateFlow(CameraState(phoneFallback = true))
        var attempts = 0
        var closed = false
        var retryNotices = 0
        val job = launch {
            runWithGuidanceRecovery(camera, runVehicle = {
                attempts++
                if (attempts == 1) throw IOException("BLE retries exhausted")
                try { awaitCancellation() } finally { closed = true }
            }, onFailure = {}, onRetry = { retryNotices++ })
        }
        runCurrent()
        assertTrue(job.isActive)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, retryNotices)
        assertFalse(closed)
        camera.value = camera.value.copy(phoneFallback = false, continuationExpired = true)
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(closed)
    }

    @Test fun failureBeforeADriveDoesNotHoldTheServiceOrStartMoreRetries() = runTest {
        val camera = MutableStateFlow(CameraState())
        var failures = 0
        runWithGuidanceRecovery(camera,
            runVehicle = { throw IOException("initial connection failed") },
            onFailure = { failures++ }, onRetry = { fail("no trip to continue") })
        assertEquals(1, failures)
        assertEquals(0, testScheduler.currentTime)
    }

    @Test fun cancellationDuringRetryDelayStopsServiceWithoutAnotherConnection() = runTest {
        val camera = MutableStateFlow(CameraState(phoneFallback = true))
        var attempts = 0
        val job = launch {
            runWithGuidanceRecovery(camera,
                runVehicle = { attempts++; throw IOException("BLE lost") },
                onFailure = {}, onRetry = { fail("service was stopped") })
        }
        runCurrent()
        advanceTimeBy(10_000)
        job.cancelAndJoin()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, attempts)
    }

    @Test fun reconnectedVehicleRunsNormallyUntilTheUserStopsTheSession() = runTest {
        val camera = MutableStateFlow(CameraState(phoneFallback = true))
        var attempts = 0
        var cleanedUp = false
        val job = launch {
            runWithGuidanceRecovery(camera, runVehicle = {
                if (++attempts == 1) throw IOException("lost")
                camera.value = CameraState(phoneFallback = false)
                try { awaitCancellation() } finally { cleanedUp = true }
            }, onFailure = {}, onRetry = {})
        }
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        advanceTimeBy(600_000); runCurrent()
        assertTrue(job.isActive)
        assertEquals(2, attempts)
        job.cancelAndJoin()
        assertTrue(cleanedUp)
    }
}
