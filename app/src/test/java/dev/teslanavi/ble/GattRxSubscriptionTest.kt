package dev.teslanavi.ble

import android.bluetooth.BluetoothGattDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GattRxSubscriptionTest {
    @Test fun dualModeKeepsTeslaIndicationFirstAndUsesNotificationOnlyWhenRequested() {
        assertEquals(GattRxMode.INDICATION, GattRxMode.select(48, false))
        assertEquals(GattRxMode.NOTIFICATION, GattRxMode.select(48, true))
        assertArrayEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE, GattRxMode.select(48, false).value)
        assertArrayEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, GattRxMode.select(48, true).value)
        assertEquals(GattRxMode.INDICATION, GattRxMode.select(32, true))
        assertEquals(GattRxMode.NOTIFICATION, GattRxMode.select(16, false))
        assertTrue(GattRxMode.supportsBoth(48))
        assertFalse(GattRxMode.supportsBoth(32))
        assertThrows(IOException::class.java) { GattRxMode.select(2, false) }
    }

    @Test fun missingSubscriptionAcknowledgementUsesOneFreshFallbackAttempt() = runTest {
        val modes = mutableListOf<Boolean>()
        var notices = 0
        val result = connectWithRxFallback(onRetry = { notices++ }) { fallback ->
            modes += fallback
            if (!fallback) throw GattRxSubscriptionException("timeout", true)
            "connected"
        }
        assertEquals("connected", result)
        assertEquals(listOf(false, true), modes)
        assertEquals(1, notices)
        assertEquals(1000, testScheduler.currentTime)
    }

    @Test fun successfulConnectionNeverEnablesBothModesOrReconnects() = runTest {
        var calls = 0
        assertEquals("connected", connectWithRxFallback(onRetry = { fail("unexpected retry") }) {
            assertFalse(it); calls++; "connected"
        })
        assertEquals(1, calls)
    }

    @Test fun disconnectPermissionAndSingleModeErrorsDoNotTriggerFallback() = runTest {
        for (error in listOf(IOException("disconnected"), SecurityException("permission"),
            GattRxSubscriptionException("single mode", false), CancellationException("stopped"))) {
            var calls = 0
            val received = runCatching {
                connectWithRxFallback<Unit>(onRetry = { fail("unexpected fallback") }) { calls++; throw error }
            }.exceptionOrNull()
            assertSame(error, received)
            assertEquals(1, calls)
        }
    }

    @Test fun fallbackFailureIsReturnedWithoutLoopingAndCancellationStopsTheNextAttempt() = runTest {
        var calls = 0
        val error = GattRxSubscriptionException("still no response", true)
        assertSame(error, runCatching {
            connectWithRxFallback<Unit>(onRetry = {}) { calls++; throw error }
        }.exceptionOrNull())
        assertEquals(2, calls)
        calls = 0
        assertNull(withTimeoutOrNull(100) {
            connectWithRxFallback<Unit>(onRetry = {}) { calls++; throw error }
        })
        assertEquals(1, calls)
    }

    @Test fun descriptorWriteAcceptanceWithoutCallbackStillTimesOutBeforeFallback() = runTest {
        var calls = 0
        val modes = mutableListOf<GattRxMode>()
        connectWithRxFallback(onRetry = {}) { fallback ->
            calls++
            val mode = GattRxMode.select(48, fallback).also { modes += it }
            val callback = CompletableDeferred<Unit>()
            if (fallback) callback.complete(Unit)
            awaitRxSubscription(callback, mode, supportsBoth = true)
        }
        assertEquals(2, calls)
        assertEquals(listOf(GattRxMode.INDICATION, GattRxMode.NOTIFICATION), modes)
        assertEquals(9000, testScheduler.currentTime)
    }

    @Test fun disconnectWhileWaitingForDescriptorIsNotMistakenForSubscriptionTimeout() = runTest {
        val disconnected = IOException("GATT 133")
        val callback = CompletableDeferred<Unit>().apply { completeExceptionally(disconnected) }
        val failure = runCatching {
            awaitRxSubscription(callback, GattRxMode.INDICATION, supportsBoth = true)
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(failure is GattRxSubscriptionException)
        assertEquals(disconnected.message, failure?.message)
    }

    @Test fun cancellingSubscriptionWaitDoesNotStartFallback() = runTest {
        var calls = 0
        assertNull(withTimeoutOrNull(100) {
            connectWithRxFallback(onRetry = { fail("cancelled session must not reconnect") }) {
                calls++
                awaitRxSubscription(CompletableDeferred(), GattRxMode.INDICATION, supportsBoth = true)
            }
        })
        assertEquals(1, calls)
    }
}
