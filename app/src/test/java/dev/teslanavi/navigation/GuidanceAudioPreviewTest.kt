package dev.teslanavi.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuidanceAudioPreviewTest {
    @Test fun asynchronousPlaybackFailureIsShownInsteadOfReportingACompletedTest() = runTest {
        val output = PreviewSpeaker()
        val preview = GuidanceAudioPreview(backgroundScope) { output }
        preview.play(PreviewSound.CAMERA_VOICE, 100); runCurrent()
        advanceTimeBy(11_000); runCurrent()
        assertFalse("longer synthesis must not be cut off at ten seconds", output.closed)
        output.finish(false); runCurrent()
        assertTrue(output.closed)
        assertTrue(preview.state.value.message!!.contains("출력에 실패"))
        assertNull(preview.state.value.playing)
    }
    @Test fun cameraSampleUsesSelectedVolumeAndClosesAutomatically() = runTest {
        val output = PreviewSpeaker()
        val preview = GuidanceAudioPreview(backgroundScope) { output }
        preview.play(PreviewSound.CAMERA_VOICE, 35)
        runCurrent()
        assertEquals(35, output.appliedVolume)
        assertEquals(1, output.initializations)
        val expected = CameraAlertPolicy().next(CameraReading("sample", CameraKind.SPEED, 60, 300), RoadClass.GENERAL, 0)!!.text
        assertEquals(expected, output.spoken.single())
        assertEquals("삼백 미터 앞 과속 단속 카메라가 있습니다. 제한속도 시속 육십 킬로미터입니다.", KoreanSpeechText.normalize(expected))
        assertEquals(PreviewSound.CAMERA_VOICE, preview.state.value.playing)
        output.finish(true)
        advanceTimeBy(10_001); runCurrent()
        assertTrue(output.closed)
        assertNull(preview.state.value.playing)
    }

    @Test fun chimeUsesDrivingAlarmWithoutRequiringTtsAndStopsAfterSample() = runTest {
        val output = PreviewSpeaker()
        val preview = GuidanceAudioPreview(backgroundScope) { output }
        preview.play(PreviewSound.OVERSPEED_CHIME, 45)
        runCurrent()
        assertTrue(output.alarm)
        assertEquals(0, output.initializations)
        assertEquals(45, output.appliedVolume)
        preview.setVolume(70)
        assertEquals(70, output.appliedVolume)
        advanceTimeBy(6501); runCurrent()
        assertFalse(output.alarm)
        assertTrue(output.closed)
        assertNull(preview.state.value.playing)
    }

    @Test fun switchingSamplesCancelsOldInitializationAndStopSilencesImmediately() = runTest {
        val first = PreviewSpeaker().apply { initializationGate = CompletableDeferred() }
        val second = PreviewSpeaker()
        val outputs = ArrayDeque(listOf(first, second))
        val preview = GuidanceAudioPreview(backgroundScope) { outputs.removeFirst() }
        preview.play(PreviewSound.CAMERA_VOICE, 50); runCurrent()
        preview.play(PreviewSound.OVERSPEED_CHIME, 60); runCurrent()
        assertTrue(first.closed)
        assertTrue(first.spoken.isEmpty())
        assertTrue(second.alarm)
        assertEquals(PreviewSound.OVERSPEED_CHIME, preview.state.value.playing)
        preview.stop()
        assertFalse(second.alarm)
        runCurrent()
        assertTrue(second.closed)
        assertNull(preview.state.value.playing)
        advanceTimeBy(20_000); runCurrent()
        assertFalse(second.alarm)
    }

    @Test fun mutedVolumeDoesNotStartPlaybackAndChangingToZeroStopsActiveChime() = runTest {
        var created = 0
        val output = PreviewSpeaker()
        val preview = GuidanceAudioPreview(backgroundScope) { created++; output }
        preview.play(PreviewSound.CAMERA_VOICE, 0); runCurrent()
        assertEquals(0, created)
        assertTrue(preview.state.value.message!!.contains("0%"))
        preview.play(PreviewSound.OVERSPEED_CHIME, 100); runCurrent()
        preview.setVolume(0); runCurrent()
        assertFalse(output.alarm)
        assertTrue(output.closed)
        assertTrue(preview.state.value.message!!.contains("0%"))
    }

    @Test fun initializationOrSpeechFailureIsShownAndReleasesResources() = runTest {
        val failedInit = PreviewSpeaker().apply { initializationError = "한국어 음성 데이터를 설치해 주세요." }
        val failedSpeech = PreviewSpeaker().apply { acceptsSpeech = false }
        val outputs = ArrayDeque(listOf(failedInit, failedSpeech))
        val preview = GuidanceAudioPreview(backgroundScope) { outputs.removeFirst() }
        preview.play(PreviewSound.CAMERA_VOICE, 50); runCurrent()
        assertEquals(failedInit.initializationError, preview.state.value.message)
        assertTrue(failedInit.closed)
        preview.play(PreviewSound.CAMERA_VOICE, 50); runCurrent()
        assertTrue(preview.state.value.message!!.contains("재생하지 못했습니다"))
        assertTrue(failedSpeech.closed)
        assertNull(preview.state.value.playing)
    }
}

private class PreviewSpeaker : CameraSpeaker {
    private var completed: ((Boolean) -> Unit)? = null
    fun finish(success: Boolean) { completed?.invoke(success); completed = null }
    override fun speak(text: String, onComplete: (Boolean) -> Unit): Boolean {
        if (!speak(text)) return false
        completed = onComplete
        return true
    }
    var appliedVolume = -1
    var alarm = false
    var closed = false
    var initializations = 0
    var initializationGate: CompletableDeferred<Unit>? = null
    var initializationError: String? = null
    var acceptsSpeech = true
    val spoken = mutableListOf<String>()
    override suspend fun initialize(): String? {
        initializations++
        initializationGate?.await()
        return initializationError
    }
    override fun speak(text: String): Boolean { spoken += text; return acceptsSpeech }
    override fun setVolume(percent: Int) { appliedVolume = percent }
    override fun setOverSpeedAlert(active: Boolean) { alarm = active }
    override fun silence() { alarm = false }
    override fun close() { silence(); closed = true }
}
