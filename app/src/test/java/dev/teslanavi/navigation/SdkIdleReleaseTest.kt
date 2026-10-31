package dev.teslanavi.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SdkIdleReleaseTest {
    @Test fun briefParkingKeepsSdkButLongIdleReleasesOnce() = runTest {
        val idle = SdkIdleRelease(backgroundScope)
        var releases = 0
        idle.idle { releases++ }
        runCurrent(); advanceTimeBy(15_000); runCurrent()
        idle.active()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, releases)
        idle.idle { releases++ }
        runCurrent(); advanceTimeBy(30_001); runCurrent()
        assertEquals(1, releases)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, releases)
    }

    @Test fun newGuidanceCancelsCleanupWaitingForOldInitialization() = runTest {
        val idle = SdkIdleRelease(backgroundScope)
        val initialization = CompletableDeferred<Unit>()
        var releases = 0
        idle.idle { initialization.await(); releases++ }
        runCurrent(); advanceTimeBy(30_001); runCurrent()
        idle.active()
        initialization.complete(Unit); runCurrent()
        assertEquals(0, releases)
    }
}
