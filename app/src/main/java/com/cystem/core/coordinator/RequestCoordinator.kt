package com.cystem.core.coordinator

import android.util.Base64
import com.cystem.core.di.AppContainer
import com.cystem.core.model.Attachment
import com.cystem.core.model.Message
import com.cystem.core.model.MessageRole
import com.cystem.core.model.MessageStatus
import com.cystem.core.model.Source
import com.cystem.core.network.ChatTurn
import com.cystem.core.network.GeminiClient
import com.cystem.core.network.NimStreamEvent
import com.cystem.core.network.NvidiaClient
import com.cystem.core.network.ProviderException
import com.cystem.core.network.WebSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class UserRequest(
    val conversationId: String,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val forceSearch: Boolean = false,
    val forceImageSearch: Boolean = false,
    val settings: com.cystem.core.model.GenerationSettings,
    val customInstructions: String = "",
)

sealed interface PipelineEvent {
    data class Stage(val name: String) : PipelineEvent
    data object ResetStreaming : PipelineEvent
    data class TextDelta(val delta: String) : PipelineEvent
    data class ReasoningDelta(val delta: String) : PipelineEvent
    data class SourceFound(val source: WebSource) : PipelineEvent
    data class ToolCallStarted(
        val id: String,
        val name: String,
        val argumentsJson: String,
    ) : PipelineEvent
    data class ToolCallFinished(
        val id: String,
        val name: String,
        val result: String,
        val success: Boolean,
    ) : PipelineEvent
    data class Completed(
        val model: String,
        val responseId: String?,
        val inputTokens: Long?,
        val outputTokens: Long?,
        val latencyMs: Long,
    ) : PipelineEvent
    data class Failed(val message: String, val recoverable: Boolean) : PipelineEvent
}

class RequestCoordinator(
    private val container: AppContainer,
    private val nvidia: NvidiaClient = NvidiaClient(),
    private val gemini: GeminiClient = GeminiClient(),
) {
    fun run(request: UserRequest): Flow<PipelineEvent> = flow {
        val startedAt = System.currentTimeMillis()
        emit(PipelineEvent.Stage("Preparing request"))

        val nvidiaKey = container.settingsStore.readNvidiaApiKey()
        if (nvidiaKey.isNullOrBlank()) {
            emit(
                PipelineEvent.Failed(
                    "Add your NVIDIA API key in Settings to talk to CYSTEM.",
                    false,
                ),
            )
            return@flow
        }

        val attachmentContext = analyzeAttachments(
            attachments = request.attachments,
            key = nvidiaKey,
            emit = { emit(it) },
        )

        val intent = SearchIntentDetector.detect(
            text = request.text,
            forceWeb = request.forceSearch,
            forceImages = request.forceImageSearch,
        )

        val search = collectSearchContext(
            request = request,
            intent = intent,
            emit = { emit(it) },
        )

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            conversationId = request.conversationId,
            role = MessageRole.USER,
            content = request.text,
            createdAt = System.currentTimeMillis(),
        )
        container.conversations.insertMessage(userMessage)

        val history = container.conversations.listMessages(request.conversationId)
            .dropLast(1)
            .takeLast(24)

        val turns = ArrayList<ChatTurn>()
        turns += ChatTurn(
            role = "system",
            content = buildSystemPrompt(
                customInstructions = request.customInstructions,
                attachmentContext = attachmentContext,
                searchContext = search.context,
            ),
        )

        history.forEach { message ->
            turns += ChatTurn(
                role = when (message.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.SYSTEM -> "system"
                    MessageRole.TOOL -> "tool"
                },
                content = message.content,
            )
        }
        turns += ChatTurn(role = "user", content = request.text)

        emit(PipelineEvent.Stage("Generating"))

        var text = ""
        var reasoning = ""
        var responseId: String? = null
        var usageIn: Long? = null
        var usageOut: Long? = null

        try {
            nvidia.streamChat(
                apiKey = nvidiaKey,
                settings = request.settings,
                messages = turns,
                tools = emptyList(),
            ).retryWhen { cause, attempt ->
                val retryable = cause is ProviderException && cause.retryable && attempt < 2
                if (retryable) {
                    text = ""
                    reasoning = ""
                    responseId = null
                    usageIn = null
                    usageOut = null
                    emit(PipelineEvent.ResetStreaming)
                    emit(PipelineEvent.Stage("Retrying connection"))
                }
                retryable
            }.collect { event ->
                when (event) {
                    is NimStreamEvent -> {
                        event.text?.let {
                            text += it
                            emit(PipelineEvent.TextDelta(it))
                        }
                        event.reasoning?.let {
                            reasoning += it
                            emit(PipelineEvent.ReasoningDelta(it))
                        }
                        responseId = event.responseId ?: responseId
                        usageIn = event.usage?.inputTokens ?: usageIn
                        usageOut = event.usage?.outputTokens ?: usageOut
                    }
                }
            }

            val assistantId = UUID.randomUUID().toString()
            val assistant = Message(
                id = assistantId,
                conversationId = request.conversationId,
                role = MessageRole.ASSISTANT,
                content = text,
                createdAt = System.currentTimeMillis(),
                reasoning = reasoning.ifBlank { null },
                status = MessageStatus.COMPLETE,
                model = request.settings.model,
                responseId = responseId,
                inputTokens = usageIn,
                outputTokens = usageOut,
                latencyMs = System.currentTimeMillis() - startedAt,
            )
            container.conversations.insertMessage(assistant)

            search.sources.forEach { source ->
                container.conversations.insertSource(
                    Source(
                        id = UUID.randomUUID().toString(),
                        messageId = assistantId,
                        title = source.title,
                        url = source.url,
                        date = source.date,
                        snippet = source.snippet,
                    ),
                )
            }

            emit(
                PipelineEvent.Completed(
                    model = request.settings.model,
                    responseId = responseId,
                    inputTokens = usageIn,
                    outputTokens = usageOut,
                    latencyMs = System.currentTimeMillis() - startedAt,
                ),
            )
        } catch (error: ProviderException) {
            emit(PipelineEvent.Failed(error.messageForUser, error.retryable))
        } catch (error: Exception) {
            emit(PipelineEvent.Failed(error.message ?: "The request failed.", false))
        }
    }.flowOn(Dispatchers.IO)

    private data class SearchContext(
        val context: String,
        val sources: List<WebSource>,
    )

    private suspend fun analyzeAttachments(
        attachments: List<Attachment>,
        key: String,
        emit: suspend (PipelineEvent) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) return@withContext ""

        emit(PipelineEvent.Stage("Analyzing attachments"))

        attachments.joinToString("\n") { attachment ->
            val cached = container.conversations.findAttachmentAnalysis(attachment.id)
            if (!cached.isNullOrBlank()) {
                cached
            } else {
                val analysis = analyzeSingleAttachment(key, attachment)
                container.conversations.saveAttachmentAnalysis(
                    attachment.id,
                    analysis,
                    System.currentTimeMillis(),
                )
                analysis
            }
        }
    }

    private suspend fun analyzeSingleAttachment(
        key: String,
        attachment: Attachment,
    ): String {
        require(attachment.mimeType.startsWith("image/")) {
            "This phase only sends image attachments to the private vision layer."
        }
        val bytes = File(attachment.localPath).readBytes()
        require(bytes.size <= 10 * 1024 * 1024) {
            "Attachment is too large to analyze."
        }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return nvidia.analyzeImage(
            apiKey = key,
            imageDataUri = "data:" + attachment.mimeType + ";base64," + base64,
            prompt = "Analyze this attachment for factual context only. Describe visible text, UI elements, objects, people, charts, colors and spatial relationships. Do not guess identities or hidden context. Return concise structured facts for another model to use.",
        )
    }

    private suspend fun collectSearchContext(
        request: UserRequest,
        intent: SearchIntent,
        emit: suspend (PipelineEvent) -> Unit,
    ): SearchContext {
        if (!intent.needsWeb) return SearchContext("", emptyList())

        val geminiKey = container.settingsStore.readGeminiApiKey()
        if (geminiKey.isNullOrBlank()) {
            emit(
                PipelineEvent.Failed(
                    "Live search was requested, but no Gemini API key is configured.",
                    false,
                ),
            )
            return SearchContext("", emptyList())
        }

        return if (intent.needsImages) {
            emit(PipelineEvent.Stage("Searching web and images"))
            val result = gemini.imageSearch(geminiKey, request.text)
            result.sources.forEach { emit(PipelineEvent.SourceFound(it)) }
            SearchContext(result.textContext, result.sources)
        } else {
            emit(PipelineEvent.Stage("Searching web"))
            val result = gemini.search(geminiKey, request.text)
            result.sources.forEach { emit(PipelineEvent.SourceFound(it)) }
            SearchContext(result.answerContext, result.sources)
        }
    }

    private fun buildSystemPrompt(
        customInstructions: String,
        attachmentContext: String,
        searchContext: String,
    ): String = buildString {
        append("You are CYSTEM, a private personal AI command center. ")
        append("Return the best direct answer you can. ")
        append("Treat retrieved web context as evidence and never invent citations. ")

        if (customInstructions.isNotBlank()) {
            append("\nUser custom instructions:\n")
            append(customInstructions.take(12_000))
        }
        if (attachmentContext.isNotBlank()) {
            append("\nPrivate attachment facts from the vision layer:\n")
            append(attachmentContext)
        }
        if (searchContext.isNotBlank()) {
            append("\nCurrent web/image search context:\n")
            append(searchContext)
        }
    }
}
