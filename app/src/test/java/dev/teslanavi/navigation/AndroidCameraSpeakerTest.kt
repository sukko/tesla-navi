package dev.teslanavi.navigation

import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.TeslaNaviApp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.Locale
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class, shadows = [ControlledSpeech::class])
class AndroidCameraSpeakerTest {
    @Test fun geminiSelectionUsesCloudVoiceAndOnlyPreparesTheStoredPhraseAtStartup() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val cloud = FakePcmVoice()
        val speaker = AndroidCameraSpeaker(context, cloud) { GuidanceVoice.GEMINI }
        assertNull(speaker.initialize())
        try {
            assertTrue(cloud.ready)
            assertEquals(1, cloud.fallbackPreparations)
            assertTrue("guidance requests must not start just from initialization", cloud.prefetched.isEmpty())
            assertTrue(speaker.speak("300미터 앞"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("삼백 미터 앞"), cloud.rendered)
            assertNotNull(voiceTrackOf(speaker))
        } finally { speaker.close() }
    }

    @Test fun failedCameraAnnouncementPlaysStoredPhraseNeverThePhoneVoice() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val stored = Pcm(ByteArray(2 * 9_600), 24_000)
        val cloud = FakePcmVoice().apply { pcm = null; storedPcm = stored }
        val speaker = AndroidCameraSpeaker(context, cloud, playbackSpeed = { 1.25f }) { GuidanceVoice.GEMINI }
        assertNull(speaker.initialize())
        try {
            val results = mutableListOf<Boolean>()
            assertTrue(speaker.speak("300미터 앞 과속 단속 카메라가 있습니다.") { results += it })
            shadowOf(Looper.getMainLooper()).idle()
            val output = checkNotNull(voiceTrackOf(speaker))
            assertEquals(24_000, output.sampleRate)
            assertEquals(1.25f, output.playbackParams.speed, .001f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(stored.millis + 1001))
            assertEquals("completion suppresses a repeat for the same camera", listOf(true), results)
        } finally { speaker.close() }
    }

    @Test fun cameraAnnouncementWithoutAStoredPhrasePlaysTheChime() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val cloud = FakePcmVoice(loadError = "Gemini API 키를 먼저 등록해 주세요.").apply { pcm = null }
        val speaker = AndroidCameraSpeaker(context, cloud) { GuidanceVoice.GEMINI }
        assertNull(speaker.initialize())
        try {
            val results = mutableListOf<Boolean>()
            assertTrue(speaker.speak("500미터 앞 과속 단속 카메라가 있습니다.") { results += it })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(WarningChime.SAMPLE_RATE, checkNotNull(voiceTrackOf(speaker)).sampleRate)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(listOf(true), results)
        } finally { speaker.close() }
    }

    @Test fun otherSpeechNeverBecomesAFalseCameraWarningWhenGeminiFails() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val cloud = FakePcmVoice().apply { pcm = null; storedPcm = Pcm(ByteArray(9_600), 24_000) }
        val speaker = AndroidCameraSpeaker(context, cloud) { GuidanceVoice.GEMINI }
        assertNull(speaker.initialize())
        try {
            val results = mutableListOf<Boolean>()
            assertTrue(speaker.speak("제한속도 60킬로미터 초과입니다.") { results += it })
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(voiceTrackOf(speaker))
            assertEquals(listOf(false), results)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
            assertTrue(speaker.speak("주행시작") { results += it })
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(voiceTrackOf(speaker))
            assertEquals(listOf(false, false), results)
        } finally { speaker.close() }
    }

    @Test fun fasterGeminiPlaybackPreservesPitchAndFinishesBeforeOriginalDuration() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val voice = FakePcmVoice().apply { pcm = Pcm(ByteArray(480_000), 24_000) }
        val speaker = AndroidCameraSpeaker(context, voice, playbackSpeed = { 1.25f }) { GuidanceVoice.GEMINI }
        assertNull(speaker.initialize())
        try {
            val results = mutableListOf<Boolean>()
            speaker.speak("카메라 안내") { results += it }
            shadowOf(Looper.getMainLooper()).idle()
            val output = checkNotNull(voiceTrackOf(speaker))
            assertEquals(1.25f, output.playbackParams.speed, .001f)
            assertEquals(1f, output.playbackParams.pitch, .001f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8500))
            assertTrue(results.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            assertEquals(listOf(true), results)
        } finally { speaker.close() }
    }

    @Test fun systemSynthesisPlaysThroughAppTrackAndCompletesOnlyAfterPlayback() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val speaker = AndroidCameraSpeaker(context)
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
        try {
            val results = mutableListOf<Boolean>()
            assertTrue(speaker.speak("제한속도 120킬로미터입니다.") { results += it })
            assertTrue(checkNotNull(speech.lastFile).exists())
            speech.utteranceProgressListener.onDone(speech.lastId)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, checkNotNull(voiceTrackOf(speaker)).playState)
            assertTrue("synthesis completion is not playback completion", results.isEmpty())
            assertFalse(checkNotNull(speech.lastFile).exists())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            assertEquals(listOf(true), results)
            assertNull(voiceTrackOf(speaker))
        } finally { speaker.close() }
    }

    @Test fun silentSystemSynthesisIsAnErrorAndCancelledSynthesisCannotPlayLater() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val speaker = AndroidCameraSpeaker(context)
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
        try {
            val results = mutableListOf<Boolean>()
            speaker.speak("첫 안내") { results += it }
            val oldId = speech.lastId
            val file = checkNotNull(speech.lastFile)
            speaker.silence()
            assertFalse(file.exists())
            speech.utteranceProgressListener.onDone(oldId)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(voiceTrackOf(speaker))
            speech.wave = speechWave(ByteArray(4800))
            speaker.speak("다음 안내") { results += it }
            speech.utteranceProgressListener.onDone(speech.lastId)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(false, false), results)
            assertTrue(speaker.needsInitialization)
            assertNull(voiceTrackOf(speaker))
        } finally { speaker.close() }
    }
    @Test fun systemTtsReceivesSinoKoreanForEveryLimitThroughOneHundredTwenty() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val speaker = AndroidCameraSpeaker(context)
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
        try {
            listOf("십", "이십", "삼십", "사십", "오십", "육십", "칠십", "팔십", "구십", "백", "백십", "백이십")
                .forEachIndexed { index, limit ->
                    assertTrue(speaker.speak("제한속도 ${(index + 1) * 10}킬로미터 초과입니다."))
                    assertEquals("제한속도 ${limit} 킬로미터 초과입니다.", speech.lastText)
                    speech.utteranceProgressListener.onDone(speech.lastId)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
                    shadowOf(Looper.getMainLooper()).idle()
                }
        } finally { speaker.close() }
    }

    @Test fun switchingFromNeuralToFailedSystemVoiceRequiresRecovery() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        var preferred = GuidanceVoice.GEMINI
        val voice = FakePcmVoice()
        val speaker = AndroidCameraSpeaker(context, voice) { preferred }
        assertNull(speaker.initialize())
        try {
            assertFalse(speaker.needsInitialization)
            preferred = GuidanceVoice.SYSTEM
            assertTrue(speaker.needsInitialization)
            assertFalse(speaker.speak("제한속도 120킬로미터 초과입니다."))
            val recovery = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
            val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
            speech.onInitListener.onInit(TextToSpeech.SUCCESS)
            assertNull(recovery.await())
            assertFalse(speaker.needsInitialization)
            assertTrue(speaker.speak("제한속도 120킬로미터 초과입니다."))
            assertEquals("제한속도 백이십 킬로미터 초과입니다.", speech.lastText)
            assertTrue(voice.rendered.isEmpty())
        } finally { speaker.close() }
    }
    @Test fun neuralCompletionAndFailureAreReportedAfterPlayback() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val voice = FakePcmVoice().apply { pcm = Pcm(ByteArray(2 * 24_000 * 10), 24_000) }
        val speaker = AndroidCameraSpeaker(context, voice) { GuidanceVoice.GEMINI }
        assertNull(speaker.initialize())
        try {
            val results = mutableListOf<Boolean>()
            assertTrue(speaker.speak("카메라 안내") { results += it })
            shadowOf(Looper.getMainLooper()).idle()
            val output = checkNotNull(voiceTrackOf(speaker))
            assertEquals(24_000, output.sampleRate)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_999))
            assertTrue(results.isEmpty())
            assertNotNull(voiceTrackOf(speaker))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2))
            assertEquals(listOf(true), results)
            assertNull(voiceTrackOf(speaker))
            voice.pcm = null
            assertTrue(speaker.speak("다음 안내") { results += it })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true, false), results)
        } finally { speaker.close() }
    }

    @Test fun stalledNeuralRenderingTimesOutWithoutSilencingChimeOrAcceptingLateAudio() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val voice = FakePcmVoice().apply { defer = true }
        val speaker = AndroidCameraSpeaker(context, voice) { GuidanceVoice.GEMINI }
        assertNull(speaker.initialize())
        try {
            val results = mutableListOf<Boolean>()
            speaker.speak("카메라 안내") { results += it }
            speaker.setOverSpeedAlert(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(12_000))
            assertEquals(listOf(false), results)
            assertTrue(speaker.needsInitialization)
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, checkNotNull(trackOf(speaker)).playState)
            voice.pending?.invoke(voice.pcm)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(voiceTrackOf(speaker))
            assertEquals(listOf(false), results)
        } finally { speaker.close() }
    }
    @Test fun slowingDownFinishesCurrentBellButMuteStopsItImmediately() {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val speaker = AndroidCameraSpeaker(context)
        try {
            speaker.setOverSpeedAlert(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1700))
            val bell = checkNotNull(trackOf(speaker))
            speaker.setOverSpeedAlert(false)
            assertSame(bell, trackOf(speaker))
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, bell.playState)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            assertNull(trackOf(speaker))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertNull(trackOf(speaker))
            speaker.setOverSpeedAlert(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))
            assertNotNull(trackOf(speaker))
            speaker.silence()
            assertNull(trackOf(speaker))
        } finally { speaker.close() }
    }

    @Test fun stoppedSpeechAllowsOverspeedSpeechAndRepeatingChime() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val speaker = AndroidCameraSpeaker(context)
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
        try {
            assertTrue(speaker.speak("카메라가 있습니다"))
            speaker.setOverSpeedAlert(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1501))
            assertNull(trackOf(speaker))
            speech.utteranceProgressListener.onStop(speech.lastId, true)
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
            assertTrue(speaker.speak("제한속도 60킬로미터 초과입니다"))
            speech.utteranceProgressListener.onDone(speech.lastId)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            val firstChime = checkNotNull(trackOf(speaker))
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, firstChime.playState)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
            val nextChime = checkNotNull(trackOf(speaker))
            assertNotSame(firstChime, nextChime)
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, nextChime.playState)
            speaker.setOverSpeedAlert(false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4000))
            assertNull(trackOf(speaker))
        } finally {
            speaker.close()
        }
    }

    @Test fun lateStopFromOldSpeechDoesNotClearNewSpeech() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val speaker = AndroidCameraSpeaker(context)
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
        try {
            assertTrue(speaker.speak("첫 안내"))
            val oldId = speech.lastId
            speaker.silence()
            assertTrue(speaker.speak("새 안내"))
            speech.utteranceProgressListener.onStop(oldId, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(speaker.speak("겹치는 안내"))
            speech.utteranceProgressListener.onStop(speech.lastId, false)
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
            assertTrue(speaker.speak("다음 안내"))
        } finally { speaker.close() }
    }

    @Test fun selectedNeuralVoicePlaysPcmAndPreparesUpcomingSpeech() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val voice = FakePcmVoice()
        val speaker = AndroidCameraSpeaker(context, voice) { GuidanceVoice.GEMINI }
        assertNull(speaker.initialize())
        assertTrue(voice.prefetched.isEmpty())
        try {
            assertTrue(speaker.speak("300미터 앞 과속 단속 카메라가 있습니다."))
            shadowOf(Looper.getMainLooper()).idle()
            val output = checkNotNull(voiceTrackOf(speaker))
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, output.playState)
            assertFalse(speaker.speak("겹치는 안내"))
            speaker.prefetch("제한속도 60킬로미터 초과입니다.")
            assertEquals("제한속도 육십 킬로미터 초과입니다.", voice.prefetched.last())
            assertEquals("a phrase that failed at startup is retried", 2, voice.fallbackPreparations)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(voice.pcm!!.millis + 1001))
            assertNull(voiceTrackOf(speaker))
        } finally { speaker.close() }
    }

    @Test fun userSelectedSystemVoiceKeepsThePhoneVoice() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val voice = FakePcmVoice()
        val speaker = AndroidCameraSpeaker(context, voice) { GuidanceVoice.SYSTEM }
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
        try {
            assertTrue(speaker.speak("카메라가 있습니다"))
            assertNotNull(speech.lastId)
            assertTrue(voice.rendered.isEmpty())
            assertNull(voiceTrackOf(speaker))
        } finally { speaker.close() }
    }

    @Test fun asynchronousErrorReportsFailureAndReinitializationPreservesChime() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val speaker = AndroidCameraSpeaker(context)
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
        try {
            val results = mutableListOf<Boolean>()
            assertTrue(speaker.speak("카메라 안내") { results += it })
            assertTrue(results.isEmpty())
            speaker.setOverSpeedAlert(true)
            speech.utteranceProgressListener.onError(speech.lastId, TextToSpeech.ERROR_OUTPUT)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(false), results)
            assertTrue(speaker.needsInitialization)
            val recovery = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, checkNotNull(trackOf(speaker)).playState)
            val recovered = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
            recovered.onInitListener.onInit(TextToSpeech.SUCCESS)
            assertNull(recovery.await())
            assertFalse(speaker.needsInitialization)
            assertTrue(speaker.speak("다시 안내") { results += it })
            recovered.utteranceProgressListener.onDone(recovered.lastId)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(false, true), results)
        } finally { speaker.close() }
    }

    @Test fun missingTtsCallbackTimesOutAndReleasesChime() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val speaker = AndroidCameraSpeaker(context)
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
        try {
            val results = mutableListOf<Boolean>()
            assertTrue(speaker.speak("카메라 안내") { results += it })
            speaker.setOverSpeedAlert(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(12_000))
            assertEquals(listOf(false), results)
            assertTrue(speaker.needsInitialization)
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, checkNotNull(trackOf(speaker)).playState)
            speech.utteranceProgressListener.onDone(speech.lastId)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(false), results)
        } finally { speaker.close() }
    }

    private fun trackOf(speaker: AndroidCameraSpeaker): AudioTrack? = ReflectionHelpers.getField(speaker, "track")
    private fun voiceTrackOf(speaker: AndroidCameraSpeaker): AudioTrack? = ReflectionHelpers.getField(speaker, "voiceTrack")
}

private class FakePcmVoice(private val loadError: String? = null) : PcmVoice {
    var defer = false
    var pending: ((Pcm?) -> Unit)? = null
    var pcm: Pcm? = Pcm(ByteArray(2 * 4_800), 24_000)
    val rendered = mutableListOf<String>()
    val prefetched = mutableListOf<String>()
    var storedPcm: Pcm? = null
    var fallbackPreparations = 0
    override fun prepareFallback() { fallbackPreparations++ }
    override fun fallback() = storedPcm
    override var ready = false
    override suspend fun load(): String? { ready = loadError == null; return loadError }
    override fun render(text: String, done: (Pcm?) -> Unit) { rendered += text; if (defer) pending = done else done(pcm) }
    override fun prefetch(text: String) { prefetched += text }
}

/** Deliver actual Android TTS callbacks explicitly, including interruption without onDone. */
@Implements(TextToSpeech::class)
class ControlledSpeech : ShadowTextToSpeech() {
    var lastId: String? = null
    var lastText: String? = null
    var lastFile: File? = null
    var wave: ByteArray = speechWave()
    @Implementation
    public override fun synthesizeToFile(text: CharSequence, params: Bundle?, file: File, utteranceId: String?): Int {
        lastId = utteranceId
        lastText = text.toString()
        lastFile = file
        file.writeBytes(wave)
        return TextToSpeech.SUCCESS
    }
    @Implementation
    public override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
        lastId = utteranceId
        lastText = text.toString()
        return TextToSpeech.SUCCESS
    }
}
