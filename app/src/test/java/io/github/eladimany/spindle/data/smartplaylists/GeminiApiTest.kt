package io.github.eladimany.spindle.data.smartplaylists

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiApiTest {
    private val key = "AIzaSyD3x4mpl3K3yF0rT3st1ngOnly_abc-k3Fq"

    @Test
    fun `finds the key in copied text`() {
        assertEquals(key, GeminiApi.findKey(key))
        assertEquals(key, GeminiApi.findKey("  \"$key\"\n"))
        assertNull(GeminiApi.findKey("hello"))
        assertNull(GeminiApi.findKey(null))
        assertNull(GeminiApi.findKey("AIzashort"))
        // Newer AI Studio keys don't start with "AIza".
        val newKey = "AQ.Ab8RN6Lx3mpl3K3yF0rT3st1ngOnly_abc-k3Fq9Zt"
        assertEquals(newKey, GeminiApi.findKey("Key: $newKey "))
    }

    @Test
    fun `masks all but the ends`() {
        assertEquals("AIza••••••••k3Fq", GeminiApi.mask(key))
        assertFalse(GeminiApi.mask(key).contains("D3x4"))
    }

    @Test
    fun `the request carries both prompt halves and asks for JSON`() {
        val body = JSONObject(GeminiApi.requestBody("SYSTEM", "USER"))
        assertEquals("SYSTEM", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val content = body.getJSONArray("contents").getJSONObject(0)
        assertEquals("user", content.getString("role"))
        assertEquals("USER", content.getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("application/json", body.getJSONObject("generationConfig").getString("responseMimeType"))
    }

    @Test
    fun `reads the reply text and skips thoughts`() {
        val reply = """
            {"candidates":[{"content":{"role":"model","parts":[
              {"text":"thinking...","thought":true},
              {"text":"{\"genres\":[\"Jazz\"],"},
              {"text":"\"decades\":[]}"}
            ]}}]}
        """.trimIndent()
        assertEquals("""{"genres":["Jazz"],"decades":[]}""", GeminiApi.replyText(reply))
        assertNull(GeminiApi.replyText("""{"candidates":[{"finishReason":"SAFETY"}]}"""))
        assertNull(GeminiApi.replyText("not json"))
    }

    @Test
    fun `classifies Google's errors`() {
        val badKey = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.",
            "status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""
        assertEquals(GeminiApi.Error.BadKey, GeminiApi.error(400, badKey))
        assertEquals(GeminiApi.Error.BadKey, GeminiApi.error(403, """{"error":{"status":"PERMISSION_DENIED"}}"""))
        assertEquals(GeminiApi.Error.QuotaUsedUp, GeminiApi.error(429, """{"error":{"status":"RESOURCE_EXHAUSTED"}}"""))
        assertEquals(GeminiApi.Error.ModelGone, GeminiApi.error(404, """{"error":{"status":"NOT_FOUND"}}"""))
        val overloaded = """{"error":{"code":503,"message":"The model is overloaded. Please try again later.","status":"UNAVAILABLE"}}"""
        assertEquals(GeminiApi.Error.Busy, GeminiApi.error(503, overloaded))
        assertEquals(GeminiApi.Error.Busy, GeminiApi.error(500, "<html>oops</html>"))
        val other = GeminiApi.error(418, "<html>oops</html>")
        assertTrue(other is GeminiApi.Error.Other && other.httpStatus == 418)
        assertTrue(GeminiApi.error(400, """{"error":{"status":"INVALID_ARGUMENT","message":"bad field"}}""") is GeminiApi.Error.Other)
    }

    @Test
    fun `Nano wins whenever Android offers it, the key only fills in`() {
        val nanoOff = AiEngineStatus.Unavailable("status 0")
        val noKey = AiEngineStatus.Unavailable("No Gemini key added")
        assertEquals(AiEngineStatus.Ready, AiEngineRouter.pick(nanoOff, AiEngineStatus.Ready))
        assertEquals(nanoOff, AiEngineRouter.pick(nanoOff, noKey))
        assertEquals(AiEngineStatus.Ready, AiEngineRouter.pick(AiEngineStatus.Ready, noKey))
        // A downloadable Nano (v2 or v3) is still Nano's — a saved key doesn't skip it.
        assertEquals(AiEngineStatus.Downloadable, AiEngineRouter.pick(AiEngineStatus.Downloadable, AiEngineStatus.Ready))
    }
}
