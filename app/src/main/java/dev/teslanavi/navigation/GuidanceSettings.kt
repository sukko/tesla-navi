package dev.teslanavi.navigation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GuidanceVoice { SYSTEM, GEMINI }

/** Volume is relative to the phone's media volume. Distances also bound overspeed alerts. */
data class GuidanceOptions(val volumePercent: Int = 100, val highwayMeters: Int = 500, val generalMeters: Int = 300,
    val voice: GuidanceVoice = GuidanceVoice.SYSTEM, val geminiSpeaker: GeminiSpeaker = GeminiSpeaker.Kore, val geminiSpeedPercent: Int = 125) {
    fun distance(road: RoadClass): Int = if (road == RoadClass.GENERAL) generalMeters else highwayMeters
}

class GuidanceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("guidance-settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(normalize(GuidanceOptions(
        prefs.getInt("volume", 100), prefs.getInt("highway", 500), prefs.getInt("general", 300),
        GuidanceVoice.entries.firstOrNull { it.name == prefs.getString("voice", null) } ?: GuidanceVoice.SYSTEM,
        GeminiSpeaker.entries.firstOrNull { it.name == prefs.getString("gemini-speaker", null) } ?: GeminiSpeaker.Kore,
        prefs.getInt("gemini-speed", 125))))
    val options = mutable.asStateFlow()
    fun volume(value: Int) = save(options.value.copy(volumePercent = value))
    fun highway(value: Int) = save(options.value.copy(highwayMeters = value))
    fun general(value: Int) = save(options.value.copy(generalMeters = value))
    fun voice(value: GuidanceVoice) = save(options.value.copy(voice = value))
    fun geminiSpeaker(value: GeminiSpeaker) = save(options.value.copy(geminiSpeaker = value))
    fun geminiSpeed(value: Int) = save(options.value.copy(geminiSpeedPercent = value))
    fun migrateRemovedVoice(hasGeminiKey: Boolean) {
        if (prefs.getString("voice", null) == "SUPERTONIC") voice(if (hasGeminiKey) GuidanceVoice.GEMINI else GuidanceVoice.SYSTEM)
        prefs.edit().remove("supertonic-speaker").apply()
    }
    /** Restores volume and distances while preserving the voice and its playback preferences. */
    fun reset() = save(GuidanceOptions(voice = options.value.voice, geminiSpeaker = options.value.geminiSpeaker, geminiSpeedPercent = options.value.geminiSpeedPercent))
    private fun save(value: GuidanceOptions) {
        val next = normalize(value)
        check(prefs.edit().putInt("volume", next.volumePercent).putInt("highway", next.highwayMeters)
            .putInt("general", next.generalMeters).putString("voice", next.voice.name)
            .putString("gemini-speaker", next.geminiSpeaker.name).putInt("gemini-speed", next.geminiSpeedPercent).commit())
        mutable.value = next
    }
    private fun normalize(value: GuidanceOptions) = value.copy(geminiSpeedPercent = ((value.geminiSpeedPercent.coerceIn(80, 150) + 2) / 5) * 5,
        volumePercent = value.volumePercent.coerceIn(0, 100),
        highwayMeters = ((value.highwayMeters.coerceIn(100, 2000) + 50) / 100) * 100,
        generalMeters = ((value.generalMeters.coerceIn(100, 1000) + 50) / 100) * 100)
}
