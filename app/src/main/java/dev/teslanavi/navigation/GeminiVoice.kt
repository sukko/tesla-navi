package dev.teslanavi.navigation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest

/** Whole-phrase disk cache and coalesced, bounded cloud work; driving falls back promptly when cloud audio is late. */
class GeminiVoice(
    private val apiKey: () -> String,
    private val cacheDir: File,
    private val synthesize: (String, String, GeminiSpeaker) -> Pcm = GeminiTtsClient()::synthesize,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitMillis: Long = 2500,
    private val speaker: () -> GeminiSpeaker = { GeminiSpeaker.Kore },
) : PcmVoice {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(1)
    private val lock = Any()
    private val requests = mutableMapOf<String, Deferred<Pcm?>>()
    private val speculative = mutableSetOf<String>()
    @Volatile private var generation = 0
    @Volatile private var retryAfter = 0L
    private val mutable = MutableStateFlow<String?>(null)
    val status = mutable.asStateFlow()
    override val ready: Boolean get() = apiKey().isNotBlank()

    override suspend fun load(): String? = if (ready) null else "Gemini API 키를 먼저 등록해 주세요."

    /** Settings tests may wait for a first synthesis; time-critical driving guidance must not. */
    val preview: PcmVoice = object : PcmVoice {
        override val ready get() = this@GeminiVoice.ready
        override suspend fun load() = this@GeminiVoice.load()
        override fun render(text: String, done: (Pcm?) -> Unit) = renderWithin(text, 15_000, done)
        override fun prefetch(text: String) = this@GeminiVoice.prefetch(text)
    }

    override fun render(text: String, done: (Pcm?) -> Unit) = renderWithin(text, waitMillis, done)

    private fun renderWithin(text: String, timeout: Long, done: (Pcm?) -> Unit) {
        val token = generation
        scope.launch {
            val pcm = try { withTimeoutOrNull(timeout) { request(text, urgent = true, token)?.await() } }
                catch (_: CancellationException) { null }
            if (pcm == null && generation == token && mutable.value == null)
                mutable.value = "Gemini 응답을 기다리는 중입니다. 이번 안내는 기본 음성으로 재생합니다."
            done(pcm.takeIf { generation == token })
        }
    }

    override fun prefetch(text: String) {
        if (!ready) return
        val token = generation
        scope.launch { request(text, urgent = false, token) }
    }

    fun reset() = synchronized(lock) {
        generation++
        requests.values.toList().forEach { it.cancel() }
        requests.clear()
        speculative.clear()
        retryAfter = 0
        mutable.value = null
    }

    private fun request(raw: String, urgent: Boolean, expectedGeneration: Int): Deferred<Pcm?>? = synchronized(lock) {
        if (!ready || generation != expectedGeneration) return@synchronized null
        val text = KoreanSpeechText.normalize(raw)
        val voice = speaker()
        val hash = MessageDigest.getInstance("SHA-256").digest("${GeminiTtsClient.MODEL}/${voice.name}/v2/$text".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val file = File(cacheDir, "$hash.pcm")
        readCache(file)?.let { return@synchronized CompletableDeferred(it) }
        requests[hash]?.let {
            if (urgent) speculative.remove(hash)
            return@synchronized it
        }
        if (clock() < retryAfter) return@synchronized null
        // A queued future-camera prefetch must not occupy the slot needed by an alert due now.
        if (urgent && requests.size >= 2) speculative.firstOrNull()?.let { queued ->
            speculative.remove(queued)
            requests.remove(queued)?.cancel()
        }
        if (requests.size >= 2) return@synchronized null
        val key = apiKey()
        val token = generation
        val job = scope.async(start = CoroutineStart.LAZY) {
            gate.withPermit {
                synchronized(lock) { speculative.remove(hash) }
                if (generation != token || clock() < retryAfter) return@withPermit null
                try {
                    currentCoroutineContext().ensureActive()
                    val pcm = synthesize(key, text, voice)
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) cache@{
                        if (generation != token) return@cache null
                        runCatching { writeCache(file, pcm) }
                        mutable.value = null
                        pcm
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    synchronized(lock) {
                        if (generation == token) {
                            retryAfter = clock() + 60_000
                            mutable.value = (error as? GeminiTtsFailure)?.safeMessage
                                ?: "Gemini에 연결하지 못했습니다. 기본 음성으로 안내합니다."
                        }
                    }
                    null
                }
            }
        }
        requests[hash] = job
        if (!urgent) speculative.add(hash)
        job.invokeOnCompletion { synchronized(lock) { if (requests[hash] === job) { requests.remove(hash); speculative.remove(hash) } } }
        job.start()
        job
    }

    private fun readCache(file: File): Pcm? = runCatching {
        if (file.length() !in 6..8_640_004) return null
        java.io.DataInputStream(file.inputStream().buffered()).use {
            val rate = it.readInt()
            val bytes = it.readBytes()
            require(rate in 8000..96000 && bytes.size % 2 == 0)
            val pcm = Pcm(bytes, rate)
            require(pcm.millis in 100..45_000 && bytes.any { value -> value != 0.toByte() })
            file.setLastModified(System.currentTimeMillis())
            pcm
        }
    }.getOrNull()

    private fun writeCache(file: File, pcm: Pcm) {
        require(pcm.sampleRate in 8000..96000 && pcm.bytes.size % 2 == 0 && pcm.millis in 100..45_000)
        cacheDir.mkdirs()
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            val output = java.io.DataOutputStream(stream)
            output.writeInt(pcm.sampleRate); output.write(pcm.bytes); output.flush()
            atomic.finishWrite(stream)
        } catch (error: Throwable) { atomic.failWrite(stream); throw error }
        var size = 0L
        cacheDir.listFiles()?.filter { it.extension == "pcm" }?.sortedByDescending { it.lastModified() }?.forEachIndexed { i, cached ->
            size += cached.length()
            if (i >= 32 || size > 16_000_000) cached.delete()
        }
    }
}
