package dev.teslanavi.navigation

/** 16-bit little-endian mono PCM shared by cloud and phone speech playback. */
class Pcm(val bytes: ByteArray, val sampleRate: Int) {
    val frames get() = bytes.size / 2
    val millis get() = frames * 1000L / sampleRate
}

/** Rendering and preparation run off the main thread. */
interface PcmVoice {
    val ready: Boolean
    suspend fun load(): String?
    fun render(text: String, done: (Pcm?) -> Unit)
    fun prefetch(text: String)
    /** Saves [STORED_CAMERA_PHRASE] in this voice while the service is reachable. */
    fun prepareFallback() {}
    /** The saved phrase, or null if none could be made yet. Reads storage: call off the main thread. */
    fun fallback(): Pcm? = null
}

/** Replaces a camera announcement the selected cloud voice could not deliver. It states no distance or limit. */
const val STORED_CAMERA_PHRASE = "전방에 과속 카메라가 있습니다"

/** Only an upcoming-camera announcement may use the stored phrase: it would misstate a trip start or speed warning. */
internal fun isCameraPresenceSpeech(text: String) = "카메라가 있습니다" in text
