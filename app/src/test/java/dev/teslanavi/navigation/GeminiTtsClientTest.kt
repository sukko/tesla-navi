package dev.teslanavi.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiTtsClientTest {
    @Test fun sendsExactTranscriptAsStatelessAudioAndKeyOnlyInHeader() {
        val connection = FakeGeminiConnection(response = geminiResponse(speechWave()))
        val client = GeminiTtsClient { connection }
        val pcm = client.synthesize("local-test-key", "삼백 미터 앞 카메라가 있습니다.", GeminiSpeaker.Puck)
        val request = JSONObject(connection.body.toString("UTF-8"))
        assertEquals(GeminiTtsClient.MODEL, request.getString("model"))
        assertFalse(request.getBoolean("store"))
        assertEquals("Puck", request.getJSONObject("generation_config").getJSONArray("speech_config").getJSONObject(0).getString("voice"))
        assertEquals("삼백 미터 앞 카메라가 있습니다.", request.getJSONArray("input").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("local-test-key", connection.getRequestProperty("x-goog-api-key"))
        assertFalse(connection.body.toString("UTF-8").contains("local-test-key"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
        assertEquals(24_000, pcm.sampleRate)
        assertEquals(2400, pcm.frames)
    }

    @Test fun quotaAndPermissionFailuresNeverExposeProviderResponse() {
        for (status in listOf(400, 401, 403, 404, 429, 503)) {
            val connection = FakeGeminiConnection(status, "sensitive-provider-body")
            try { GeminiTtsClient { connection }.synthesize("key", "안내"); fail("failure accepted") }
            catch (error: GeminiTtsFailure) {
                assertEquals(status, error.status)
                assertFalse(error.safeMessage.contains("sensitive-provider-body"))
                assertTrue(connection.disconnected)
            }
        }
    }

    @Test fun rejectsMissingAudioIncompleteMalformedAndSilentOutput() {
        val client = GeminiTtsClient()
        for (response in listOf("{}", "{\"steps\":[]}", geminiResponse(speechWave(ByteArray(4800))),
            geminiResponse(byteArrayOf(1, 2, 3)), geminiResponse(speechWave()).replace("completed", "in_progress"))) {
            assertTrue(runCatching { client.decodeResponse(response) }.isFailure)
        }
    }
}

private fun geminiResponse(wave: ByteArray): String = JSONObject().put("status", "completed")
    .put("steps", JSONArray().put(JSONObject().put("type", "model_output").put("content", JSONArray()
        .put(JSONObject().put("type", "audio").put("data", Base64.getEncoder().encodeToString(wave)))))).toString()

private class FakeGeminiConnection(private val status: Int = 200, private val response: String) :
    HttpURLConnection(URL(GeminiTtsClient.ENDPOINT)) {
    val body = ByteArrayOutputStream()
    var disconnected = false
    override fun connect() = Unit
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun getOutputStream() = body
    override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
    override fun getResponseCode() = status
}
