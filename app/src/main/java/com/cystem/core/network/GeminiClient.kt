package com.cystem.core.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class WebSource(
    val title: String,
    val url: String,
    val date: String? = null,
    val snippet: String? = null,
)

data class SearchResponse(
    val answerContext: String,
    val sources: List<WebSource>,
    val searchQueries: List<String>,
)

data class ImageSearchItem(
    val imageUrl: String,
    val sourceUrl: String?,
    val title: String?,
)

data class ImageSearchResponse(
    val textContext: String,
    val images: List<ImageSearchItem>,
    val sources: List<WebSource>,
    val searchQueries: List<String>,
    val searchSuggestionsHtml: String? = null,
)

class GeminiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) {
    suspend fun search(apiKey: String, query: String): SearchResponse =
        retryPolicy.execute {
            val payload = baseContents(query)
                .put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put("google_search", JSONObject()),
                    ),
                )
                .put(
                    "generationConfig",
                    JSONObject().put("temperature", 0.1),
                )

            parseGroundedText(postGenerate(apiKey, "gemini-3.8-flash", payload))
        }

    suspend fun imageSearch(apiKey: String, query: String): ImageSearchResponse =
        retryPolicy.execute {
            val searchTypes = JSONObject()
                .put("web_search", JSONObject())
                .put("image_search", JSONObject())

            val payload = baseContents(
                "Find useful web and image references for: " + query +
                    ". Return concise factual context for a separate assistant.",
            ).put(
                "tools",
                JSONArray().put(
                    JSONObject().put(
                        "google_search",
                        JSONObject().put("search_types", searchTypes),
                    ),
                ),
            )

            parseImageGrounding(
                postGenerate(apiKey, "gemini-3.1-flash-image", payload),
            )
        }

    suspend fun checkModel(apiKey: String, modelId: String): Result<Boolean> =
        runCatching {
            retryPolicy.execute {
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models")
                    .header("x-goog-api-key", apiKey)
                    .get()
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw ProviderException(
                            "Gemini",
                            providerErrorMessage(response.code, response.body?.string().orEmpty()),
                            httpStatus = response.code,
                        )
                    }

                    val models = JSONObject(response.body?.string().orEmpty())
                        .optJSONArray("models") ?: JSONArray()

                    (0 until models.length()).any {
                        val name = models.optJSONObject(it)?.optString("name").orEmpty()
                        name == "models/" + modelId || name.endsWith("/" + modelId)
                    }
                }
            }
        }

    private fun baseContents(text: String): JSONObject =
        JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", text)),
                ),
            ),
        )

    private fun postGenerate(
        apiKey: String,
        model: String,
        payload: JSONObject,
    ): JSONObject {
        val request = Request.Builder()
            .url(
                "https://generativelanguage.googleapis.com/v1beta/models/" +
                    model + ":generateContent",
            )
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", JSON)
            .post(payload.toString().toRequestBody(JSON.toMediaType()))
            .build()

        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ProviderException(
                    "Gemini",
                    providerErrorMessage(response.code, response.body?.string().orEmpty()),
                    httpStatus = response.code,
                    retryAfterMs = response.headers["Retry-After"]?.toLongOrNull()?.times(1_000),
                )
            }
            JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun parseGroundedText(json: JSONObject): SearchResponse {
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
        val parts = candidate?.optJSONObject("content")
            ?.optJSONArray("parts") ?: JSONArray()

        val text = buildString {
            for (index in 0 until parts.length()) {
                parts.optJSONObject(index)?.optString("text")?.let(::append)
            }
        }.trim()

        val metadata = candidate?.optJSONObject("groundingMetadata")
        return SearchResponse(
            answerContext = text,
            sources = parseWebSources(metadata),
            searchQueries = metadata?.optJSONArray("webSearchQueries")?.toStringList().orEmpty(),
        )
    }

    private fun parseImageGrounding(json: JSONObject): ImageSearchResponse {
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
        val parts = candidate?.optJSONObject("content")
            ?.optJSONArray("parts") ?: JSONArray()

        val text = buildString {
            for (index in 0 until parts.length()) {
                parts.optJSONObject(index)?.optString("text")?.let(::append)
            }
        }.trim()

        val metadata = candidate?.optJSONObject("groundingMetadata")
        val chunks = metadata?.optJSONArray("groundingChunks") ?: JSONArray()
        val images = ArrayList<ImageSearchItem>()

        for (index in 0 until chunks.length()) {
            val chunk = chunks.optJSONObject(index) ?: continue
            val image = chunk.optJSONObject("image") ?: continue
            val imageUrl = image.optString("imageUri")
            if (imageUrl.isBlank()) continue

            images += ImageSearchItem(
                imageUrl = imageUrl,
                sourceUrl = image.optString("webUri")
                    .ifBlank { image.optString("url") }
                    .ifBlank { null },
                title = image.optString("title").ifBlank { null },
            )
        }

        val suggestions = metadata
            ?.optJSONObject("searchEntryPoint")
            ?.optString("renderedContent")
            ?.ifBlank { null }

        return ImageSearchResponse(
            textContext = text,
            images = images.distinctBy { it.imageUrl }.take(12),
            sources = parseWebSources(metadata),
            searchQueries = metadata?.optJSONArray("webSearchQueries")?.toStringList().orEmpty(),
            searchSuggestionsHtml = suggestions,
        )
    }

    private fun parseWebSources(metadata: JSONObject?): List<WebSource> {
        if (metadata == null) return emptyList()
        val chunks = metadata.optJSONArray("groundingChunks") ?: return emptyList()
        val result = ArrayList<WebSource>()

        for (index in 0 until chunks.length()) {
            val web = chunks.optJSONObject(index)?.optJSONObject("web") ?: continue
            val url = web.optString("uri")
            if (url.isBlank()) continue
            result += WebSource(
                title = web.optString("title").ifBlank { url },
                url = url,
                date = web.optString("date").ifBlank { null },
                snippet = web.optString("snippet").ifBlank { null },
            )
        }

        return result.distinctBy { it.url }
    }

    private fun providerErrorMessage(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")

        return when (code) {
            400 -> detail.ifBlank { "Gemini rejected the request." }
            401, 403 -> "Gemini API key was rejected. Check the key in Settings."
            404 -> "The selected Gemini model was not found."
            429 -> "Gemini rate limit reached. CYSTEM will retry when safe."
            in 500..599 -> "Gemini is temporarily unavailable."
            else -> detail.ifBlank { "Gemini request failed (HTTP " + code + ")." }
        }
    }

    companion object {
        private const val JSON = "application/json"
    }
}

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { optString(it).ifBlank { null } }
