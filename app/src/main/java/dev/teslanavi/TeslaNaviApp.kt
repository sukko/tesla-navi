package dev.teslanavi

import android.app.Application
import dev.teslanavi.data.FeatureSettings
import dev.teslanavi.data.VehicleKeyStore
import dev.teslanavi.data.VehicleStore
import dev.teslanavi.drive.DriveController
import dev.teslanavi.drive.AutoConnectSettings
import dev.teslanavi.logbook.LogbookSettings
import dev.teslanavi.logbook.LogbookStore
import dev.teslanavi.logbook.TripStore
import dev.teslanavi.navigation.*

class TeslaNaviApp : Application() {
    val bluetoothStart by lazy { dev.teslanavi.drive.BluetoothStartSettings(this) }
    val vehicles by lazy { VehicleStore(this) }
    val keys by lazy { VehicleKeyStore(this) }
    val drive by lazy { DriveController(this, keys) }
    val autoConnect by lazy { AutoConnectSettings(this) }
    val kakao by lazy { KakaoSettings(this) }
    val guidance by lazy { GuidanceSettings(this) }
    val features by lazy { FeatureSettings(this) }
    val logbookSettings by lazy { LogbookSettings(this) }
    val logbook by lazy { LogbookStore(this) }
    val geminiSettings by lazy { GeminiSettings(this) }
    val gemini by lazy { GeminiVoice(geminiSettings::apiKey, java.io.File(cacheDir, "gemini-tts"), speaker = { guidance.options.value.geminiSpeaker }) }
    val trips by lazy { TripStore(this) }
    val walkingSteps by lazy { WalkingSteps(this) }
    val cameras by lazy {
        CameraController(KakaoNavigationEngine(this, kakao, drive::recordGuidance), guidanceSpeaker(), guidance.options,
            walkingSteps = walkingSteps.events, onContinuation = drive::recordGuidance,
            clock = android.os.SystemClock::elapsedRealtime)
            .also { it.configured(kakao.hasKey.value) }
    }

    override fun onCreate() {
        super.onCreate()
        guidance.migrateRemovedVoice(geminiSettings.state.value.hasKey)
        // Delete only known retired model paths; vehicle and Gemini keys share this parent.
        Thread({
            listOf("supertonic2", "supertonic2.part", "supertonic2.tar.bz2.part",
                "supertonic3", "supertonic3.part", "supertonic3.tar.bz2.part").forEach { name ->
                val file = java.io.File(noBackupFilesDir, name)
                runCatching { if (file.canonicalFile.parentFile == noBackupFilesDir.canonicalFile) file.deleteRecursively() }
            }
        }, "retired-voice-cleanup").start()
    }

    fun guidanceSpeaker(): CameraSpeaker = newSpeaker(gemini)
    fun guidancePreviewSpeaker(): CameraSpeaker = newSpeaker(gemini.preview, 20_000)
    private fun newSpeaker(voice: PcmVoice, timeout: Long = 10_000): CameraSpeaker = AndroidCameraSpeaker(this,
        cloud = voice, playbackSpeed = { guidance.options.value.geminiSpeedPercent / 100f },
        synthesisTimeoutMillis = timeout) { guidance.options.value.voice }

    fun selectGeminiSpeaker(speaker: GeminiSpeaker) {
        guidance.geminiSpeaker(speaker)
        gemini.reset()
    }

    fun selectVoice(voice: GuidanceVoice) {
        guidance.voice(voice)
        if (voice != GuidanceVoice.GEMINI) gemini.reset()
    }

    fun saveGeminiKey(key: String): String? = try {
        geminiSettings.save(key)
        gemini.reset()
        selectVoice(GuidanceVoice.GEMINI)
        null
    } catch (_: IllegalArgumentException) { "API 키를 공백 없이 정확하게 입력해 주세요." }
    catch (_: Exception) { "API 키를 안전하게 저장하지 못했습니다. 다시 시도해 주세요." }

    fun deleteGeminiKey() {
        geminiSettings.delete()
        gemini.reset()
        if (guidance.options.value.voice == GuidanceVoice.GEMINI) selectVoice(GuidanceVoice.SYSTEM)
    }

}
