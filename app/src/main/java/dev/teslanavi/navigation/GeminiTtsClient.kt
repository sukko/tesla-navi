package dev.teslanavi.navigation

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

internal class GeminiTtsFailure(val status: Int, val safeMessage: String,
    val dailyQuota: Boolean = false, val retryMillis: Long = 60_000) : IOException(safeMessage)

/** The key is sent only in the HTTPS header. Neither response bodies nor request headers are logged. */
class GeminiTtsClient(private val connect: (GeminiTtsModel) -> HttpURLConnection = { model ->
    URL(endpoint(model)).openConnection() as HttpURLConnection
}) {
    fun synthesize(apiKey: String, text: String, speaker: GeminiSpeaker = GeminiSpeaker.Kore,
        model: GeminiTtsModel = GeminiTtsModel.PRIMARY): Pcm {
        require(apiKey.isNotBlank())
        require(text.isNotBlank() && text.length <= 1000)
        val body = request(text, speaker, model).toString().toByteArray(Charsets.UTF_8)
        val connection = connect(model)
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3000
            connection.readTimeout = 12_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.setFixedLengthStreamingMode(body.size)
            android.util.Log.i("TeslaNaviAudio", "Gemini request model=${model.id}")
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status != 200) {
                val quota = if (status == 429) quotaFailure(connection) else false to 60_000L
                android.util.Log.i("TeslaNaviAudio", "Gemini response model=${model.id} status=$status dailyQuota=${quota.first}")
                throw GeminiTtsFailure(status, when (status) {
                401, 403 -> "Gemini 키 또는 프로젝트 권한을 확인해 주세요. 기본 음성으로 안내합니다."
                429 -> "Gemini 사용 한도에 도달했습니다. 잠시 기본 음성으로 안내합니다."
                404 -> "Gemini 음성 모델을 사용할 수 없습니다. 기본 음성으로 안내합니다."
                else -> "Gemini 요청에 실패했습니다($status). 기본 음성으로 안내합니다."
                }, quota.first, quota.second)
            }
            val bytes = connection.inputStream.use { it.readBytesLimited(8_000_000) }
            return decodeResponse(bytes.toString(Charsets.UTF_8), model).also {
                android.util.Log.i("TeslaNaviAudio", "Gemini response model=${model.id} status=200 readyMs=${android.os.SystemClock.elapsedRealtime() - started}")
            }
        } finally { connection.disconnect() }
    }

    private fun request(text: String, speaker: GeminiSpeaker, model: GeminiTtsModel): JSONObject {
        if (model == GeminiTtsModel.FALLBACK) {
            val prompt = "다음 안내 문장만 또렷한 한국어 내비게이션 음성으로 읽으세요. 긴 침묵 없이 일정한 보통 속도로 읽으세요.\n안내 문장:\n$text"
            return JSONObject().put("contents", JSONArray().put(JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject().put("voiceName", speaker.name)))))
        }
        val style = JSONObject().put("type", "speech_metadata")
            .put("style", "또렷한 한국어 내비게이션 안내. 일정한 보통 속도로 문장 사이 긴 침묵 없이 입력한 문장만 그대로 읽으세요.")
        val transcript = JSONObject().put("type", "text").put("text", text).put("annotations", JSONArray().put(style))
        return JSONObject().put("model", model.id).put("store", false)
            .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(transcript))))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", speaker.name))))
    }

    /** Read only structured quota identifiers/delay; remote messages and bodies are never exposed. */
    private fun quotaFailure(connection: HttpURLConnection): Pair<Boolean, Long> = runCatching {
        val bytes = connection.errorStream?.use { it.readBytesLimited(32_768) } ?: return false to 60_000L
        val details = JSONObject(bytes.toString(Charsets.UTF_8)).optJSONObject("error")?.optJSONArray("details") ?: JSONArray()
        var daily = false
        var retry = 60_000L
        for (i in 0 until details.length()) {
            val detail = details.getJSONObject(i)
            if (detail.optString("@type").endsWith("QuotaFailure")) {
                val violations = detail.optJSONArray("violations") ?: JSONArray()
                for (j in 0 until violations.length()) {
                    val violation = violations.getJSONObject(j)
                    val id = violation.optString("quotaId") + " " + violation.optString("quotaMetric")
                    if (id.contains("PerDay", true) || id.contains("per_day", true)) daily = true
                }
            }
            if (detail.optString("@type").endsWith("RetryInfo")) {
                val seconds = detail.optString("retryDelay").removeSuffix("s").toDoubleOrNull()
                if (seconds != null && seconds.isFinite()) retry = maxOf(retry, (seconds.coerceIn(0.0, 3600.0) * 1000).toLong())
            }
        }
        daily to retry
    }.getOrDefault(false to 60_000L)

    internal fun decodeResponse(json: String, model: GeminiTtsModel = GeminiTtsModel.PRIMARY): Pcm {
        val response = JSONObject(json)
        if (model == GeminiTtsModel.FALLBACK) return decodeLegacy(response)
        require(response.optString("status", "completed") == "completed") { "Gemini 음성 생성 미완료" }
        val steps = response.getJSONArray("steps")
        var audio: String? = null
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            if (step.optString("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.getJSONObject(j)
                if (part.optString("type") == "audio") audio = part.optString("data").takeIf(String::isNotBlank)
            }
        }
        val pcm = SystemSpeechWave.decode(Base64.getDecoder().decode(checkNotNull(audio) { "Gemini 음성 응답 없음" }))
        require(pcm.millis in 100..45_000) { "Gemini 음성 길이 오류" }
        return pcm
    }

    private fun decodeLegacy(response: JSONObject): Pcm {
        val candidate = response.getJSONArray("candidates").getJSONObject(0)
        require(candidate.optString("finishReason") == "STOP") { "Gemini 음성 생성 미완료" }
        val parts = candidate.getJSONObject("content").getJSONArray("parts")
        val output = java.io.ByteArrayOutputStream()
        var rate: Int? = null
        for (i in 0 until parts.length()) {
            val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
            val mime = inline.getString("mimeType").lowercase(java.util.Locale.ROOT)
            require(mime.substringBefore(';').trim() in setOf("audio/l16", "audio/pcm")) { "Gemini PCM 형식 오류" }
            val parameters = mime.split(';').drop(1).map { it.trim().split('=', limit = 2) }
                .filter { it.size == 2 }.associate { it[0] to it[1] }
            val sampleRate = parameters["rate"]?.toInt() ?: 24_000
            require((parameters["channels"]?.toInt() ?: 1) == 1 && parameters["codec"] in setOf(null, "pcm"))
            require(sampleRate in 8000..96000 && (rate == null || rate == sampleRate))
            rate = sampleRate
            val bytes = Base64.getDecoder().decode(inline.getString("data"))
            require(output.size() + bytes.size <= 8_640_000)
            output.write(bytes)
        }
        val bytes = output.toByteArray()
        val pcm = Pcm(bytes, checkNotNull(rate) { "Gemini 음성 응답 없음" })
        require(bytes.size % 2 == 0 && pcm.millis in 100..45_000 && bytes.any { it != 0.toByte() }) { "Gemini 음성 길이 또는 무음 오류" }
        return pcm
    }

    companion object {
        const val MODEL = "gemini-3.8-flash-lite-tts"
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        fun endpoint(model: GeminiTtsModel) = if (model != GeminiTtsModel.FALLBACK) ENDPOINT
            else "https://generativelanguage.googleapis.com/v1beta/models/${model.id}:generateContent"
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16_384)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "Gemini 응답 크기 초과" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
