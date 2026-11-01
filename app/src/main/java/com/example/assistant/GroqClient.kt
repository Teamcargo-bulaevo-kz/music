package com.example.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class GroqResult {
    data class FinalText(val text: String) : GroqResult()
}

class GroqApiException(val code: Int, body: String) : Exception("Groq API error $code: $body")

/** Запросы к модели для разметки музыки. Ключ пока вшит в сборку; позже запросы пойдут через свой сервер. */
class GroqClient(private val apiKey: String) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private val model = "openai/gpt-oss-120b"
    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"

    /**
     * effort — глубина рассуждения модели, maxTokens — лимит ответа.
     * Если API не принял эти необязательные параметры (ошибка 400), запрос повторяется без них.
     */
    suspend fun sendConversation(
        messages: JSONArray,
        tools: JSONArray?,
        effort: String? = null,
        maxTokens: Int? = null
    ): GroqResult = withContext(Dispatchers.IO) {
        fun body(withExtras: Boolean) = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
            if (withExtras) {
                if (effort != null) put("reasoning_effort", effort)
                if (maxTokens != null) put("max_completion_tokens", maxTokens)
            }
        }
        val extras = effort != null || maxTokens != null
        try {
            parseResponse(execute(body(extras)))
        } catch (e: GroqApiException) {
            if (extras && e.code == 400) parseResponse(execute(body(false))) else throw e
        }
    }

    private fun execute(body: JSONObject): JSONObject {
        val requestBody = body.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GroqApiException(resp.code, raw)
            }
            return JSONObject(raw)
        }
    }

    private fun parseResponse(response: JSONObject): GroqResult {
        val choices = response.optJSONArray("choices") ?: JSONArray()
        if (choices.length() == 0) {
            return GroqResult.FinalText("Модель не вернула ответ")
        }
        val message = choices.getJSONObject(0).optJSONObject("message") ?: JSONObject()
        val text = message.optString("content", "")
        return GroqResult.FinalText(if (text.isBlank()) "Пустой ответ от модели" else text)
    }
}
