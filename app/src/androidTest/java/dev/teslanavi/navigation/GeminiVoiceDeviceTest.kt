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
import java.io.File

/** Run manually with am instrument; never use connected tests on a personal phone. */
@RunWith(AndroidJUnit4::class)
class GeminiVoiceDeviceTest {
    @Test fun registeredKeyRendersTwoVoicesAndPlaysAtConfiguredSpeed() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TeslaNaviApp
        assumeTrue(app.geminiSettings.state.value.hasKey)
        assumeTrue(!app.drive.state.value.active && !DriveService.busy && !app.cameras.state.value.phoneFallback)
        var selected = GeminiSpeaker.Kore
        val voice = GeminiVoice(app.geminiSettings::apiKey, File(app.cacheDir, "gemini-tts"), speaker = { selected })
        val transcript = "300미터 앞 과속 단속 카메라가 있습니다. 제한속도 시속 60킬로미터입니다."
        val samples = mutableListOf<Pcm>()
        for (choice in listOf(GeminiSpeaker.Kore, GeminiSpeaker.Zephyr)) {
            selected = choice
            voice.reset()
            val ready = CompletableDeferred<Pcm?>()
            val started = android.os.SystemClock.elapsedRealtime()
            voice.preview.render(transcript) { ready.complete(it) }
            val pcm = withTimeout(20_000) { ready.await() }
            assertNotNull(voice.status.value ?: "No Gemini audio", pcm)
            samples += checkNotNull(pcm)
            android.util.Log.i("TeslaNaviAudio", "Gemini device voice=${choice.name} readyMs=${android.os.SystemClock.elapsedRealtime() - started} durationMs=${checkNotNull(pcm).millis}")
        }
        assertFalse("Two voice IDs must not share the same cached audio", samples[0].bytes.contentEquals(samples[1].bytes))
        val output = withContext(Dispatchers.Main) {
            AndroidCameraSpeaker(app, voice.preview, playbackSpeed = { 1.25f }, synthesisTimeoutMillis = 20_000) { GuidanceVoice.GEMINI }
        }
        try {
            withContext(Dispatchers.Main) {
                output.setVolume(app.guidance.options.value.volumePercent)
                assertNull(output.initialize())
            }
            val finished = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) { assertTrue(output.speak(transcript) { finished.complete(it) }) }
            assertTrue(withTimeout(30_000) { finished.await() })
        } finally {
            withContext(Dispatchers.Main) { output.close() }
            voice.reset()
        }
    }
}
