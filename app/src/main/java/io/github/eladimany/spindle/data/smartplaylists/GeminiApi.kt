package io.github.eladimany.spindle.data.smartplaylists

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The Gemini API's wire format (generativelanguage.googleapis.com, v1beta) — request
 * bodies, reply and error parsing. Pure, so it's unit tested; [GeminiKeyEngine] does
 * the HTTP.
 *
 * Only Flash-Lite models: they're on Google's free plan, so a key without billing can
 * never be charged — Google refuses with 429 instead.
 */
object GeminiApi {
    const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

    /** Where "Get my free key" sends the user: AI Studio's key page. */
    const val KEY_PAGE_URL = "https://aistudio.google.com/app/apikey"

    /**
     * Tried in order; the next one only when Google answers 404 (a retired name).
     * The alias follows Google's current Flash-Lite; the fixed names cover an alias
     * that's been withdrawn.
     */
    val MODELS = listOf("gemini-flash-lite-latest", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite")

    /** Listing one model costs nothing and needs a valid key — the key check. */
    const val CHECK_URL = "$BASE_URL/models?pageSize=1"

    fun generateUrl(model: String) = "$BASE_URL/models/$model:generateContent"

    /**
     * Any key-shaped run of characters. No fixed prefix: AI Studio has handed out both
     * "AIza…" keys and newer "AQ.…" ones, and Google may change it again — the check
     * against Google decides whether it actually works.
     */
    private val KEY_PATTERN = Regex("[0-9A-Za-z._~+=-]{30,200}")

    /** The key inside copied text (AI Studio's Copy gives just the key, but tolerate spaces/quotes). */
    fun findKey(text: CharSequence?): String? = text?.let { t -> KEY_PATTERN.findAll(t).maxByOrNull { it.value.length }?.value }

    /** "AIza••••••••k3Fq" — enough to recognise, not enough to use. */
    fun mask(key: String): String = if (key.length <= 8) "••••" else key.take(4) + "••••••••" + key.takeLast(4)

    fun requestBody(systemInstruction: String, userText: String, temperature: Double = 0.2): String = JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
        .put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", userText))),
            ),
        )
        .put(
            "generationConfig",
            JSONObject()
                // Low: classification, not creative writing.
                .put("temperature", temperature)
                // Roomy: newer models may spend part of it thinking before the JSON.
                .put("maxOutputTokens", 1024)
                .put("responseMimeType", "application/json"),
        )
        .toString()

    /** The model's text from a generateContent reply; null if it holds none (e.g. blocked). */
    fun replyText(body: String): String? = try {
        val parts = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        (0 until parts.length())
            .mapNotNull { parts.optJSONObject(it) }
            // Thought summaries, if a model sends them, aren't the answer.
            .filterNot { it.optBoolean("thought") }
            .joinToString("") { it.optString("text") }
            .ifEmpty { null }
    } catch (e: JSONException) {
        null
    }

    sealed interface Error {
        /** Wrong, deleted or restricted key. */
        data object BadKey : Error

        /** The free plan's per-minute or per-day limit. Never retried: wait it out. */
        data object QuotaUsedUp : Error

        /** This model name isn't served (retired) — try the next in [MODELS]. */
        data object ModelGone : Error

        /** 503 "model is overloaded" (or another temporary server error) — worth retrying. */
        data object Busy : Error

        data class Other(val httpStatus: Int, val message: String?) : Error
    }

    private val BUSY_STATUSES = setOf(500, 502, 503, 504)

    fun error(httpStatus: Int, body: String): Error {
        val error = try {
            JSONObject(body).optJSONObject("error")
        } catch (e: JSONException) {
            null
        }
        val status = error?.optString("status").orEmpty()
        val message = error?.optString("message")?.ifEmpty { null }
        return when {
            httpStatus == 429 || status == "RESOURCE_EXHAUSTED" -> Error.QuotaUsedUp
            httpStatus == 404 || status == "NOT_FOUND" -> Error.ModelGone
            httpStatus in BUSY_STATUSES || status == "UNAVAILABLE" || status == "DEADLINE_EXCEEDED" -> Error.Busy
            httpStatus == 401 || httpStatus == 403 || status == "PERMISSION_DENIED" || status == "UNAUTHENTICATED" -> Error.BadKey
            // A bad key comes back as 400 INVALID_ARGUMENT with reason API_KEY_INVALID.
            httpStatus == 400 && body.contains("API_KEY_INVALID") -> Error.BadKey
            else -> Error.Other(httpStatus, message)
        }
    }
}
