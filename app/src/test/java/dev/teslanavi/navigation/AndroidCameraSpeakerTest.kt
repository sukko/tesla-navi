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
    @Test fun geminiSelectionUsesCloudVoiceWithoutStartupRequestsAndFallsBackToPhoneVoice() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val cloud = FakePcmVoice()
        val speaker = AndroidCameraSpeaker(context, cloud) { GuidanceVoice.GEMINI }
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
        try {
            assertTrue(cloud.ready)
            assertTrue("cloud requests must not start just from initialization", cloud.prefetched.isEmpty())
            assertTrue(speaker.speak("300미터 앞"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("삼백 미터 앞"), cloud.rendered)
            assertNotNull(voiceTrackOf(speaker))
            assertNull(speech.lastId)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            cloud.pcm = null
            assertTrue(speaker.speak("500미터 앞"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("오백 미터 앞", speech.lastText)
        } finally { speaker.close() }
    }

    @Test fun fasterGeminiPlaybackPreservesPitchAndFinishesBeforeOriginalDuration() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val voice = FakePcmVoice().apply { pcm = Pcm(ByteArray(480_000), 24_000) }
        val speaker = AndroidCameraSpeaker(context, voice, playbackSpeed = { 1.25f }) { GuidanceVoice.GEMINI }
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
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
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        (shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech).onInitListener.onInit(TextToSpeech.ERROR)
        assertNull(initialization.await())
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
    @Test fun neuralCompletionAndFailedSystemFallbackAreReportedAfterPlayback() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val voice = FakePcmVoice().apply { pcm = Pcm(ByteArray(2 * 24_000 * 10), 24_000) }
        val speaker = AndroidCameraSpeaker(context, voice) { GuidanceVoice.GEMINI }
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
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
            assertTrue(speaker.speak("다음 카메라") { results += it })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true), results)
            speech.utteranceProgressListener.onError(speech.lastId, TextToSpeech.ERROR_OUTPUT)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true, false), results)
            assertTrue(speaker.needsInitialization)
        } finally { speaker.close() }
    }

    @Test fun stalledNeuralRenderingTimesOutWithoutSilencingChimeOrAcceptingLateAudio() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val voice = FakePcmVoice().apply { defer = true }
        val speaker = AndroidCameraSpeaker(context, voice) { GuidanceVoice.GEMINI }
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
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
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
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

    @Test fun selectedNeuralVoicePlaysPcmAndFallsBackToSystemVoiceWhenRenderingFails() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val voice = FakePcmVoice()
        val speaker = AndroidCameraSpeaker(context, voice) { GuidanceVoice.GEMINI }
        val initialization = async(start = CoroutineStart.UNDISPATCHED) { speaker.initialize() }
        val speech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as ControlledSpeech
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertNull(initialization.await())
        assertTrue(voice.prefetched.isEmpty())
        try {
            assertTrue(speaker.speak("300미터 앞 과속 단속 카메라가 있습니다."))
            shadowOf(Looper.getMainLooper()).idle()
            val output = checkNotNull(voiceTrackOf(speaker))
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, output.playState)
            assertNull("system TTS stays silent", speech.lastId)
            assertFalse(speaker.speak("겹치는 안내"))
            speaker.prefetch("제한속도 60킬로미터 초과입니다.")
            assertEquals("제한속도 육십 킬로미터 초과입니다.", voice.prefetched.last())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(voice.pcm!!.millis + 1001))
            assertNull(voiceTrackOf(speaker))
            voice.pcm = null
            assertTrue(speaker.speak("제한속도 60킬로미터 초과입니다."))
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull("fallback used the phone voice", speech.lastId)
            assertNull(voiceTrackOf(speaker))
            assertFalse(speaker.speak("겹치는 안내"))
            speech.utteranceProgressListener.onDone(speech.lastId)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
            assertTrue(speaker.speak("다음 안내"))
        } finally { speaker.close() }
    }

    @Test fun systemVoiceSelectionOrUnavailableCloudKeepsPhoneVoice() = runTest {
        val context = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        listOf(FakePcmVoice() to GuidanceVoice.SYSTEM,
            FakePcmVoice(loadError = "Gemini API 키를 먼저 등록해 주세요.") to GuidanceVoice.GEMINI).forEach { (voice, choice) ->
            val speaker = AndroidCameraSpeaker(context, voice) { choice }
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
