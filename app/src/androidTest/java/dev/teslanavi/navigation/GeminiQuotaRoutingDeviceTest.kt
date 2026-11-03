package dev.teslanavi.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.teslanavi.TeslaNaviApp
import dev.teslanavi.drive.DriveService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One uncached announcement through the production quota router, with phone TTS on provider exhaustion. */
@RunWith(AndroidJUnit4::class)
class GeminiQuotaRoutingDeviceTest {
    @Test fun guidanceFinishesAndRealApiAttemptsAreAccountedPerModel(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TeslaNaviApp
        assumeTrue(app.geminiSettings.state.value.hasKey)
        assumeTrue(!app.drive.state.value.active && !DriveService.busy && !app.cameras.state.value.phoneFallback)
        fun counts() = app.geminiUsage.state.value.let { listOf(it.primary.rpd, it.fallback.rpd, it.flash.rpd, it.flash25.rpd) }
        val before = counts()
        val output = withContext(Dispatchers.Main) {
            AndroidCameraSpeaker(app, app.gemini.preview,
                playbackSpeed = { app.guidance.options.value.geminiSpeedPercent / 100f },
                synthesisTimeoutMillis = 20_000) { GuidanceVoice.GEMINI }
        }
        try {
            withContext(Dispatchers.Main) {
                output.setVolume(app.guidance.options.value.volumePercent.coerceAtLeast(30))
                assertNull(output.initialize())
            }
            val done = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                assertTrue(output.speak("음성 안내 사용량 자동 전환 기능을 확인합니다.") { done.complete(it) })
            }
            assertTrue(withTimeout(45_000) { done.await() })
            val after = counts()
            assertTrue(after.zip(before).all { (a, b) -> a - b in 0..1 })
            assertEquals(after, GeminiTtsUsage(app).state.value.let { listOf(it.primary.rpd, it.fallback.rpd, it.flash.rpd, it.flash25.rpd) })
            android.util.Log.i("TeslaNaviAudio", "Gemini quota device counts=$after model=${app.geminiUsage.state.value.model.id}")
        } finally { withContext(Dispatchers.Main) { output.close() } }
    }
}
