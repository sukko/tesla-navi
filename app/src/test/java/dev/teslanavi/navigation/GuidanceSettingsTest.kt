package dev.teslanavi.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuidanceSettingsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun defaultsAndIndependentValuesSurviveReloadAndReset() {
        val settings = GuidanceSettings(context)
        assertEquals(GuidanceOptions(), settings.options.value)
        settings.volume(35); settings.highway(1500); settings.general(700)
        assertEquals(GuidanceOptions(35, 1500, 700), GuidanceSettings(context).options.value)
        settings.reset()
        assertEquals(GuidanceOptions(), GuidanceSettings(context).options.value)
    }
    @Test fun invalidStoredValuesAreClampedAndDistancesRounded() {
        context.getSharedPreferences("guidance-settings", Context.MODE_PRIVATE).edit()
            .putInt("volume", 900).putInt("highway", -300).putInt("general", 450).commit()
        val settings = GuidanceSettings(context)
        assertEquals(GuidanceOptions(100, 100, 500), settings.options.value)
        settings.highway(99999); settings.general(99999); settings.volume(-1)
        assertEquals(GuidanceOptions(0, 2000, 1000), GuidanceSettings(context).options.value)
    }
    @Test fun voiceChoiceSurvivesReloadAndResetButUnknownValuesFallBackToSystem() {
        val settings = GuidanceSettings(context)
        settings.voice(GuidanceVoice.GEMINI); settings.volume(40)
        assertEquals(GuidanceVoice.GEMINI, GuidanceSettings(context).options.value.voice)
        settings.reset()
        assertEquals(GuidanceOptions(voice = GuidanceVoice.GEMINI), GuidanceSettings(context).options.value)
        settings.voice(GuidanceVoice.GEMINI)
        assertEquals(GuidanceVoice.GEMINI, GuidanceSettings(context).options.value.voice)
        context.getSharedPreferences("guidance-settings", Context.MODE_PRIVATE).edit().putString("voice", "REMOVED_ENGINE").commit()
        assertEquals(GuidanceVoice.SYSTEM, GuidanceSettings(context).options.value.voice)
    }
    @Test fun retiredVoiceMigratesWithoutChangingDistancesVolumeOrExplicitSystemChoice() {
        val prefs = context.getSharedPreferences("guidance-settings", Context.MODE_PRIVATE)
        prefs.edit().putString("voice", "SUPERTONIC").putString("supertonic-speaker", "F3")
            .putInt("volume", 70).putInt("highway", 900).commit()
        val settings = GuidanceSettings(context)
        settings.migrateRemovedVoice(true)
        assertEquals(GuidanceOptions(volumePercent = 70, highwayMeters = 900, voice = GuidanceVoice.GEMINI), settings.options.value)
        assertFalse(prefs.contains("supertonic-speaker"))
        settings.voice(GuidanceVoice.SYSTEM)
        settings.migrateRemovedVoice(true)
        assertEquals(GuidanceVoice.SYSTEM, settings.options.value.voice)
        prefs.edit().putString("voice", "SUPERTONIC").commit()
        val noKey = GuidanceSettings(context)
        noKey.migrateRemovedVoice(false)
        assertEquals(GuidanceVoice.SYSTEM, noKey.options.value.voice)
    }

    @Test fun playbackSpeedIsBoundedAndInvalidVoiceFallsBack() {
        val settings = GuidanceSettings(context)
        settings.geminiSpeed(Int.MAX_VALUE)
        assertEquals(150, GuidanceSettings(context).options.value.geminiSpeedPercent)
        settings.geminiSpeed(-30)
        assertEquals(80, GuidanceSettings(context).options.value.geminiSpeedPercent)
    }

    @Test fun configuredRangesControlBothCameraPresenceAndOverspeed() {
        val options = GuidanceOptions(highwayMeters = 1200, generalMeters = 600)
        val camera = CameraReading("a", CameraKind.SPEED, 60, 1000)
        val presence = CameraAlertPolicy()
        val speed = OverSpeedPolicy()
        assertNotNull(presence.next(camera, RoadClass.HIGHWAY, 0, options))
        assertTrue(speed.isOverSpeed(camera, 65.0, RoadClass.HIGHWAY, options))
        assertNull(presence.next(camera, RoadClass.GENERAL, 0, options))
        assertFalse(speed.isOverSpeed(camera, 65.0, RoadClass.GENERAL, options))
        assertNotNull(presence.next(camera.copy(distanceMeters = 600), RoadClass.GENERAL, 0, options))
        assertFalse(speed.isOverSpeed(camera.copy(distanceMeters = 1201), 65.0, RoadClass.UNKNOWN, options))
    }

    @Test fun geminiPreferencesSurviveRestartResetAndSwitchingToSystem() {
        val settings = GuidanceSettings(context)
        settings.geminiSpeaker(GeminiSpeaker.Aoede); settings.geminiSpeed(135)
        settings.voice(GuidanceVoice.GEMINI)
        settings.reset()
        assertEquals(GeminiSpeaker.Aoede, GuidanceSettings(context).options.value.geminiSpeaker)
        assertEquals(135, GuidanceSettings(context).options.value.geminiSpeedPercent)
        settings.voice(GuidanceVoice.SYSTEM)
        assertEquals(GeminiSpeaker.Aoede, GuidanceSettings(context).options.value.geminiSpeaker)
        context.getSharedPreferences("guidance-settings", Context.MODE_PRIVATE).edit()
            .putString("gemini-speaker", "UNKNOWN").commit()
        assertEquals(GeminiSpeaker.Kore, GuidanceSettings(context).options.value.geminiSpeaker)
    }
}
