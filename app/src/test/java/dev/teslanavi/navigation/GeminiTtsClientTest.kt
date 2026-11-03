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

    @Test fun thirdModelUsesItsOwnInteractionsIdAndSameSelectedVoice() {
        val connection = FakeGeminiConnection(response = geminiResponse(speechWave()))
        var connectedModel: GeminiTtsModel? = null
        val client = GeminiTtsClient { model -> connectedModel = model; connection }
        client.synthesize("test-key", "안내", GeminiSpeaker.Erinome, GeminiTtsModel.FLASH)
        val body = JSONObject(connection.body.toString("UTF-8"))
        assertEquals("gemini-3.8-flash-tts", body.getString("model"))
        assertEquals(GeminiTtsModel.FLASH, connectedModel)
        assertEquals(GeminiTtsClient.ENDPOINT, GeminiTtsClient.endpoint(GeminiTtsModel.FLASH))
        assertEquals("Erinome", body.getJSONObject("generation_config").getJSONArray("speech_config").getJSONObject(0).getString("voice"))
    }

    @Test fun previewModelUsesLegacyRequestAndDecodesRawMonoPcm() {
        val bytes = ByteArray(4800) { if (it % 2 == 0) 10 else 0 }
        val connection = FakeGeminiConnection(response = legacyResponse(bytes))
        val pcm = GeminiTtsClient { connection }.synthesize("test-key", "삼백 미터 앞", GeminiSpeaker.Zephyr, GeminiTtsModel.FALLBACK)
        val request = JSONObject(connection.body.toString("UTF-8"))
        assertFalse(request.has("input"))
        assertTrue(request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
            .getString("text").endsWith("안내 문장:\n삼백 미터 앞"))
        assertEquals("AUDIO", request.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertEquals("Zephyr", request.getJSONObject("generationConfig").getJSONObject("speechConfig")
            .getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
        assertTrue(GeminiTtsClient.endpoint(GeminiTtsModel.FALLBACK).endsWith("models/gemini-3.1-flash-tts-preview:generateContent"))
        assertArrayEquals(bytes, pcm.bytes)
        assertEquals(24_000, pcm.sampleRate)
        val invalidResponses = listOf(legacyResponse(ByteArray(4800)), legacyResponse(bytes).replace("STOP", "SAFETY"),
            legacyResponse(bytes, "audio/mp3"), legacyResponse(bytes, "audio/L16;rate=0"),
            legacyResponse(byteArrayOf(1, 2, 3)))
        for ((index, invalid) in invalidResponses.withIndex()) {
            assertTrue("invalid legacy case $index", runCatching { GeminiTtsClient().decodeResponse(invalid, GeminiTtsModel.FALLBACK) }.isFailure)
        }
    }

    @Test fun structuredQuotaMetadataDistinguishesDailyLimitWithoutExposingMessages() {
        val body = JSONObject().put("error", JSONObject().put("message", "sensitive-provider-body").put("details", JSONArray()
            .put(JSONObject().put("@type", "type.googleapis.com/google.rpc.QuotaFailure").put("violations", JSONArray()
                .put(JSONObject().put("quotaId", "GenerateRequestsPerDayPerProjectPerModel-FreeTier"))))
            .put(JSONObject().put("@type", "type.googleapis.com/google.rpc.RetryInfo").put("retryDelay", "120.5s")))).toString()
        try {
            GeminiTtsClient { FakeGeminiConnection(429, body) }.synthesize("key", "안내")
            fail("quota failure expected")
        } catch (error: GeminiTtsFailure) {
            assertTrue(error.dailyQuota)
            assertEquals(120_500L, error.retryMillis)
            assertFalse(error.toString().contains("sensitive-provider-body"))
        }
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
    override fun getErrorStream() = ByteArrayInputStream(response.toByteArray())
    override fun getResponseCode() = status
}

private fun legacyResponse(bytes: ByteArray, mime: String = "audio/L16;codec=pcm;rate=24000"): String = JSONObject().put("candidates", JSONArray().put(JSONObject()
    .put("finishReason", "STOP").put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("inlineData",
        JSONObject().put("mimeType", mime).put("data", Base64.getEncoder().encodeToString(bytes)))))))).toString()
