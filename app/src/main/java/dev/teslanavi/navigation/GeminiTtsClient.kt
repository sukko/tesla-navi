package dev.teslanavi.navigation

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

internal class GeminiTtsFailure(val status: Int, val safeMessage: String) : IOException(safeMessage)

/** The key is sent only in the HTTPS header. Neither response bodies nor request headers are logged. */
class GeminiTtsClient(private val connect: () -> HttpURLConnection = {
    URL(ENDPOINT).openConnection() as HttpURLConnection
}) {
    fun synthesize(apiKey: String, text: String, speaker: GeminiSpeaker = GeminiSpeaker.Kore): Pcm {
        require(apiKey.isNotBlank())
        require(text.isNotBlank() && text.length <= 1000)
        val style = JSONObject().put("type", "speech_metadata")
            .put("style", "또렷한 한국어 내비게이션 안내. 일정한 보통 속도로 문장 사이 긴 침묵 없이 입력한 문장만 그대로 읽으세요.")
        val transcript = JSONObject().put("type", "text").put("text", text)
            .put("annotations", JSONArray().put(style))
        val input = JSONObject().put("type", "user_input").put("content", JSONArray().put(transcript))
        val body = JSONObject().put("model", MODEL).put("store", false)
            .put("input", JSONArray().put(input))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", speaker.name))))
            .toString().toByteArray(Charsets.UTF_8)
        val connection = connect()
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3000
            connection.readTimeout = 12_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status != 200) throw GeminiTtsFailure(status, when (status) {
                401, 403 -> "Gemini 키 또는 프로젝트 권한을 확인해 주세요. 기본 음성으로 안내합니다."
                429 -> "Gemini 사용 한도에 도달했습니다. 잠시 기본 음성으로 안내합니다."
                404 -> "Gemini 음성 모델을 사용할 수 없습니다. 기본 음성으로 안내합니다."
                else -> "Gemini 요청에 실패했습니다($status). 기본 음성으로 안내합니다."
            })
            val bytes = connection.inputStream.use { it.readBytesLimited(8_000_000) }
            return decodeResponse(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    internal fun decodeResponse(json: String): Pcm {
        val response = JSONObject(json)
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

    companion object {
        const val MODEL = "gemini-3.8-flash-lite-tts"
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
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
