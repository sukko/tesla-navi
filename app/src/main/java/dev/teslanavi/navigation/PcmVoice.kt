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
}
