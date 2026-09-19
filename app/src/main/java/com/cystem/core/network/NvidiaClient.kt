package com.cystem.core.network

import com.cystem.core.model.GenerationSettings
import com.cystem.core.model.ReasoningEffort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatTurn(
    val role: String,
    val content: String? = null,
    val toolCalls: List<CompletedToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
)

data class CompletedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class NimUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
)

data class NimToolCallDelta(
    val index: Int,
    val id: String?,
    val name: String?,
    val arguments: String?,
)

data class NimStreamEvent(
    val text: String? = null,
    val reasoning: String? = null,
    val responseId: String? = null,
    val usage: NimUsage? = null,
    val toolCalls: List<NimToolCallDelta> = emptyList(),
)

class NvidiaClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    fun streamChat(
        apiKey: String,
        settings: GenerationSettings,
        messages: List<ChatTurn>,
        tools: List<JSONObject>,
    ): Flow<NimStreamEvent> = flow {
        val payload = JSONObject()
            .put("model", settings.model)
            .put("messages", messagesJson(messages))
            .put("stream", true)
            .put("temperature", settings.temperature.coerceIn(0.0, 2.0))
            .put("top_p", settings.topP.coerceIn(0.0, 1.0))
            .put("max_tokens", settings.maxTokens.coerceIn(256, 32768))

        settings.seed?.let { payload.put("seed", it) }
        if (settings.reasoningEffort != ReasoningEffort.NONE) {
            payload.put(
                "reasoning_effort",
                when (settings.reasoningEffort) {
                    ReasoningEffort.HIGH -> "high"
                    ReasoningEffort.LOW -> "low"
                    ReasoningEffort.NONE -> "none"
                },
            )
        }
        if (tools.isNotEmpty()) payload.put("tools", JSONArray(tools))

        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .header("Content-Type", JSON)
            .post(payload.toString().toRequestBody(JSON.toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw providerException(response.code, response.body?.string().orEmpty())
            }

            val source = response.body?.byteStream()?.bufferedReader()
                ?: throw ProviderException("NVIDIA", "NVIDIA returned an empty response.")

            source.useLines { lines ->
                lines.forEach { line ->
                    if (!line.startsWith("data:")) return@forEach
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") return@forEach

                    val json = runCatching { JSONObject(data) }.getOrNull() ?: return@forEach
                    parseStreamEvent(json)?.let { emit(it) }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun analyzeImage(
        apiKey: String,
        imageDataUri: String,
        prompt: String,
    ): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", imageDataUri)),
            )

        val payload = JSONObject()
            .put("model", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content),
                ),
            )
            .put("temperature", 0.2)
            .put("max_tokens", 2048)

        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", JSON)
            .post(payload.toString().toRequestBody(JSON.toMediaType()))
            .build()

        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw providerException(response.code, response.body?.string().orEmpty())
            }

            val root = JSONObject(response.body?.string().orEmpty())
            val message = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?: return@use "NVIDIA returned no analysis."

            val raw = message.opt("content")
            when (raw) {
                is String -> raw
                is JSONArray -> buildString {
                    for (index in 0 until raw.length()) {
                        val part = raw.optJSONObject(index) ?: continue
                        append(part.optString("text"))
                    }
                }.trim()
                else -> message.optString("content")
            }.ifBlank { "NVIDIA returned no analysis." }
        }
    }

    private fun parseStreamEvent(json: JSONObject): NimStreamEvent? {
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val delta = choice?.optJSONObject("delta")

        val content = delta?.optString("content")?.ifBlank { null }
        val reasoning = delta?.optString("reasoning_content")
            ?.ifBlank { null }
            ?: delta?.optString("reasoning")?.ifBlank { null }

        val toolCalls = ArrayList<NimToolCallDelta>()
        val rawCalls = delta?.optJSONArray("tool_calls") ?: JSONArray()
        for (index in 0 until rawCalls.length()) {
            val call = rawCalls.optJSONObject(index) ?: continue
            val function = call.optJSONObject("function")
            toolCalls += NimToolCallDelta(
                index = call.optInt("index", index),
                id = call.optString("id").ifBlank { null },
                name = function?.optString("name")?.ifBlank { null },
                arguments = function?.optString("arguments")?.ifBlank { null },
            )
        }

        val usageJson = json.optJSONObject("usage")
        val usage = usageJson?.let {
            NimUsage(
                inputTokens = it.optLongOrNull("prompt_tokens"),
                outputTokens = it.optLongOrNull("completion_tokens"),
            )
        }

        val responseId = json.optString("id").ifBlank { null }
        if (content == null && reasoning == null && responseId == null &&
            usage == null && toolCalls.isEmpty()
        ) {
            return null
        }

        return NimStreamEvent(
            text = content,
            reasoning = reasoning,
            responseId = responseId,
            usage = usage,
            toolCalls = toolCalls,
        )
    }

    private fun messagesJson(messages: List<ChatTurn>): JSONArray =
        JSONArray().apply {
            messages.forEach { turn ->
                val item = JSONObject().put("role", turn.role)
                if (turn.content != null) item.put("content", turn.content)
                if (turn.toolCallId != null) item.put("tool_call_id", turn.toolCallId)
                if (turn.name != null) item.put("name", turn.name)
                if (turn.toolCalls.isNotEmpty()) {
                    item.put(
                        "tool_calls",
                        JSONArray().apply {
                            turn.toolCalls.forEach { call ->
                                put(
                                    JSONObject()
                                        .put("id", call.id)
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", call.name)
                                                .put("arguments", call.argumentsJson),
                                        ),
                                )
                            }
                        },
                    )
                }
                put(item)
            }
        }

    private fun providerException(code: Int, body: String): ProviderException {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")

        return ProviderException(
            provider = "NVIDIA",
            messageForUser = when (code) {
                400 -> detail.ifBlank { "NVIDIA rejected the request." }
                401, 403 -> "NVIDIA API key was rejected. Check the key in Settings."
                404 -> "The selected NVIDIA model was not found."
                408, 409, 429 -> "NVIDIA is busy or rate-limiting requests. CYSTEM will retry when safe."
                in 500..599 -> "NVIDIA is temporarily unavailable."
                else -> detail.ifBlank { "NVIDIA request failed (HTTP $code)." }
            },
            retryable = code == 408 || code == 409 || code == 429 || code in 500..599,
            httpStatus = code,
            retryAfterMs = null,
        )
    }

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null

    companion object {
        private const val BASE_URL = "https://integrate.api.nvidia.com/v1"
        private const val JSON = "application/json"
    }
}
