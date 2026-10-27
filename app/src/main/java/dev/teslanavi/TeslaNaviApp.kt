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
import kotlinx.coroutines.MainScope

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
    val voiceModel by lazy { SupertonicModel(noBackupFilesDir, MainScope()) }
    val supertonic by lazy { SupertonicVoice(voiceModel::installedDir, speaker = { guidance.options.value.supertonicSpeaker }) }
    val trips by lazy { TripStore(this) }
    val walkingSteps by lazy { WalkingSteps(this) }
    val cameras by lazy {
        CameraController(KakaoNavigationEngine(this, kakao), guidanceSpeaker(), guidance.options,
            walkingSteps = walkingSteps.events, onContinuation = drive::recordGuidance,
            clock = android.os.SystemClock::elapsedRealtime)
            .also { it.configured(kakao.hasKey.value) }
    }

    fun guidanceSpeaker(): CameraSpeaker = AndroidCameraSpeaker(this, supertonic) { guidance.options.value.voice }

    fun selectVoice(voice: GuidanceVoice) {
        guidance.voice(voice)
        if (voice == GuidanceVoice.SUPERTONIC) supertonic.warmUp() else supertonic.release()
    }

    fun downloadVoiceModel() = voiceModel.download { selectVoice(GuidanceVoice.SUPERTONIC) }

    fun deleteVoiceModel() {
        selectVoice(GuidanceVoice.SYSTEM)
        voiceModel.delete()
    }
}
