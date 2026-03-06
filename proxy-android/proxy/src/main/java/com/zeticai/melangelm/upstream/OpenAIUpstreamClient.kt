package com.zeticai.melangelm.upstream

import com.zeticai.melangelm.model.ChatChoice
import com.zeticai.melangelm.model.ChatMessage
import com.zeticai.melangelm.model.ChatRequest
import com.zeticai.melangelm.model.ChatResponse
import com.zeticai.melangelm.model.TokenUsage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible upstream client.
 * Works with OpenAI, Azure OpenAI, Anthropic (via proxy), and any compatible endpoint.
 */
class OpenAIUpstreamClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val defaultModel: String? = null,
    timeoutSeconds: Long = 60
) : UpstreamClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    override suspend fun send(request: ChatRequest): ChatResponse {
        val body = buildRequestJson(request)
        val httpRequest = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val httpResponse = http.newCall(httpRequest).execute()
        val responseBody = httpResponse.body?.string()
            ?: throw RuntimeException("Empty response from upstream (HTTP ${httpResponse.code})")

        if (!httpResponse.isSuccessful) {
            throw RuntimeException("Upstream HTTP ${httpResponse.code}: $responseBody")
        }

        return parseResponse(responseBody)
    }

    private fun buildRequestJson(request: ChatRequest): JSONObject {
        val messages = JSONArray()
        request.messages.forEach { msg ->
            messages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }
        return JSONObject().apply {
            put("model", defaultModel ?: request.model)
            put("messages", messages)
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }
            if (request.stream) put("stream", true)
        }
    }

    private fun parseResponse(json: String): ChatResponse {
        val root = JSONObject(json)
        val choicesArr = root.getJSONArray("choices")
        val choices = (0 until choicesArr.length()).map { i ->
            val c = choicesArr.getJSONObject(i)
            val msg = c.getJSONObject("message")
            ChatChoice(
                index = c.optInt("index", i),
                message = ChatMessage(
                    role = msg.getString("role"),
                    content = msg.getString("content")
                ),
                finishReason = c.optString("finish_reason")
            )
        }
        val usage = root.optJSONObject("usage")?.let {
            TokenUsage(
                promptTokens = it.optInt("prompt_tokens"),
                completionTokens = it.optInt("completion_tokens"),
                totalTokens = it.optInt("total_tokens")
            )
        }
        return ChatResponse(
            id = root.optString("id", ""),
            model = root.optString("model", ""),
            choices = choices,
            usage = usage
        )
    }
}
