package dev.teslanavi.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kakaomobility.knsdk.KNSDK
import dev.teslanavi.BuildConfig
import dev.teslanavi.TeslaNaviApp
import dev.teslanavi.drive.DriveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SDK teardown/reinitialization; needs an authorized local key, permissions and internet. */
@RunWith(AndroidJUnit4::class)
class KakaoIdleReleaseDeviceTest {
    @Test fun idleReleasesGpsAndNextDriveCanInitializeAgain() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TeslaNaviApp
        assumeTrue(BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank())
        assumeTrue(!app.drive.state.value.active && !DriveService.busy && !app.cameras.state.value.phoneFallback)
        val engine = withContext(Dispatchers.Main) { KakaoNavigationEngine(app, app.kakao) }
        try {
            withContext(Dispatchers.Main) {
                engine.initialize(BuildConfig.KAKAO_NATIVE_APP_KEY)
                assertFalse(engine.needsInitialization)
                assertNotNull(KNSDK.sharedGpsManager())
                engine.pause()
            }
            delay(35_000)
            withContext(Dispatchers.Main) {
                assertTrue(engine.needsInitialization)
                assertNull(KNSDK.sharedGpsManager())
                engine.initialize(BuildConfig.KAKAO_NATIVE_APP_KEY)
                assertFalse(engine.needsInitialization)
                assertNotNull(KNSDK.sharedGpsManager())
            }
        } finally {
            withContext(Dispatchers.Main) { engine.pause() }
            delay(35_000)
        }
        withContext(Dispatchers.Main) { assertNull(KNSDK.sharedGpsManager()) }
    }
}
