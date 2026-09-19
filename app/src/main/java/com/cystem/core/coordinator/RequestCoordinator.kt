package com.cystem.core.coordinator

import android.util.Base64
import com.cystem.core.di.AppContainer
import com.cystem.core.model.Attachment
import com.cystem.core.model.Message
import com.cystem.core.model.MessageRole
import com.cystem.core.model.MessageStatus
import com.cystem.core.model.Source
import com.cystem.core.model.ToolCallRecord
import com.cystem.core.network.ChatTurn
import com.cystem.core.network.CompletedToolCall
import com.cystem.core.network.GeminiClient
import com.cystem.core.network.NimStreamEvent
import com.cystem.core.network.NvidiaClient
import com.cystem.core.network.ProviderException
import com.cystem.core.network.WebSource
import com.cystem.core.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RequestCoordinator(
    private val container: AppContainer,
    private val nvidia: NvidiaClient = NvidiaClient(),
    private val gemini: GeminiClient = GeminiClient(),
) {
    private data class PendingTool(
        val confirmation: ToolConfirmation,
        val request: UserRequest,
        val nvidiaKey: String,
        val turns: MutableList<ChatTurn>,
        val assistantMessageId: String,
        val toolCall: CompletedToolCall,
        val toolRecordId: String,
        val loop: Int,
        val startedAt: Long,
        val text: String,
        val reasoning: String,
        val responseId: String?,
        val inputTokens: Long?,
        val outputTokens: Long?,
        val searchSources: List<WebSource>,
        val searchAttachments: List<Attachment>,
    )

    private val pendingTools = ConcurrentHashMap<String, PendingTool>()

    fun run(request: UserRequest): Flow<PipelineEvent> = flow {
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

        val startedAt = System.currentTimeMillis()
        emit(PipelineEvent.Stage("Preparing request"))

        try {
            val attachmentContext = analyzeAttachments(
                attachments = request.attachments,
                key = nvidiaKey,
                emit = { emit(it) },
            )

            val intent = SearchIntentDetector.detect(
                request.text,
                request.forceSearch,
                request.forceImageSearch,
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

            request.attachments.forEach { attachment ->
                container.conversations.insertAttachment(attachment)
                container.conversations.attachToMessage(
                    messageId = userMessage.id,
                    attachmentId = attachment.id,
                )
            }

            val assistantMessageId = UUID.randomUUID().toString()
            container.conversations.insertMessage(
                Message(
                    id = assistantMessageId,
                    conversationId = request.conversationId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    createdAt = System.currentTimeMillis(),
                    status = MessageStatus.STREAMING,
                    model = request.settings.model,
                ),
            )

            val turns = buildTurns(
                request = request,
                attachmentContext = attachmentContext,
                searchContext = search.context,
            )

            executeGeneration(
                request = request,
                nvidiaKey = nvidiaKey,
                turns = turns,
                assistantMessageId = assistantMessageId,
                searchSources = search.sources,
                searchAttachments = search.attachments,
                loop = 0,
                startedAt = startedAt,
                initialText = "",
                initialReasoning = "",
                initialResponseId = null,
                initialInputTokens = null,
                initialOutputTokens = null,
                emit = { event -> emit(event) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ProviderException) {
            emit(PipelineEvent.Failed(error.messageForUser, error.retryable))
        } catch (error: Exception) {
            emit(PipelineEvent.Failed(error.message ?: "The request failed.", false))
        }
    }.flowOn(Dispatchers.IO)

    fun resumeToolConfirmation(
        confirmationId: String,
        approved: Boolean,
    ): Flow<PipelineEvent> = flow {
        val pending = pendingTools.remove(confirmationId)
        if (pending == null) {
            emit(PipelineEvent.Failed("That confirmation is no longer active.", false))
            return@flow
        }

        val args = runCatching {
            JSONObject(pending.toolCall.argumentsJson.ifBlank { "{}" })
        }.getOrElse {
            emit(PipelineEvent.Failed("The tool arguments are no longer valid.", false))
            return@flow
        }

        val result = if (!approved) {
            ToolResult.Failure("The user declined the requested action.")
        } else {
            container.phoneTools.executeConfirmed(
                name = pending.toolCall.name,
                arguments = args,
            )
        }

        val success = result is ToolResult.Success
        val output = when (result) {
            is ToolResult.Success -> result.output
            is ToolResult.Failure -> result.message
            is ToolResult.ConfirmationRequired -> "Confirmation was requested again."
        }

        container.conversations.updateToolCall(
            ToolCallRecord(
                id = pending.toolRecordId,
                messageId = pending.assistantMessageId,
                name = pending.toolCall.name,
                argumentsJson = pending.toolCall.argumentsJson,
                result = output,
                status = if (success) "SUCCESS" else "FAILED",
                createdAt = pending.startedAt,
                finishedAt = System.currentTimeMillis(),
            ),
        )

        emit(
            PipelineEvent.ToolCallFinished(
                id = pending.toolCall.id,
                name = pending.toolCall.name,
                result = output,
                success = success,
            ),
        )

        pending.turns += ChatTurn(
            role = "tool",
            content = output,
            toolCallId = pending.toolCall.id,
            name = pending.toolCall.name,
        )

        try {
            executeGeneration(
                request = pending.request,
                nvidiaKey = pending.nvidiaKey,
                turns = pending.turns,
                assistantMessageId = pending.assistantMessageId,
                searchSources = pending.searchSources,
                searchAttachments = pending.searchAttachments,
                loop = pending.loop + 1,
                startedAt = pending.startedAt,
                initialText = pending.text,
                initialReasoning = pending.reasoning,
                initialResponseId = pending.responseId,
                initialInputTokens = pending.inputTokens,
                initialOutputTokens = pending.outputTokens,
                emit = { event -> emit(event) },
            )
        } catch (error: ProviderException) {
            emit(PipelineEvent.Failed(error.messageForUser, error.retryable))
        } catch (error: Exception) {
            emit(PipelineEvent.Failed(error.message ?: "The request failed.", false))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeGeneration(
        request: UserRequest,
        nvidiaKey: String,
        turns: MutableList<ChatTurn>,
        assistantMessageId: String,
        searchSources: List<WebSource>,
        searchAttachments: List<Attachment>,
        loop: Int,
        startedAt: Long,
        initialText: String,
        initialReasoning: String,
        initialResponseId: String?,
        initialInputTokens: Long?,
        initialOutputTokens: Long?,
        emit: suspend (PipelineEvent) -> Unit,
    ) {
        if (loop >= MAX_TOOL_LOOPS) {
            persistAssistant(
                id = assistantMessageId,
                request = request,
                text = initialText,
                reasoning = initialReasoning,
                status = MessageStatus.FAILED,
                responseId = initialResponseId,
                inputTokens = initialInputTokens,
                outputTokens = initialOutputTokens,
                latencyMs = System.currentTimeMillis() - startedAt,
            )
            emit(PipelineEvent.Failed("Tool-call loop limit reached.", false))
            return
        }

        emit(PipelineEvent.Stage(if (loop == 0) "Generating" else "Continuing with tool results"))

        var text = initialText
        var reasoning = initialReasoning
        var responseId = initialResponseId
        var inputTokens = initialInputTokens
        var outputTokens = initialOutputTokens

        val callBuilders = linkedMapOf<Int, MutableToolCall>()
        val stream = nvidia.streamChat(
            apiKey = nvidiaKey,
            settings = request.settings,
            messages = turns,
            tools = container.phoneTools.registry.definitions(
                container.settingsStore.readEnabledPhoneTools(),
            ),
        )

        try {
            stream.retryWhen { cause, attempt ->
                val retryable = cause is ProviderException && cause.retryable && attempt < 2
                if (retryable) {
                    text = initialText
                    reasoning = initialReasoning
                    responseId = initialResponseId
                    inputTokens = initialInputTokens
                    outputTokens = initialOutputTokens
                    callBuilders.clear()
                    emit(PipelineEvent.ResetStreaming)
                    emit(PipelineEvent.Stage("Retrying connection"))
                }
                retryable
            }.collect { event ->
                event.text?.let {
                    text += it
                    emit(PipelineEvent.TextDelta(it))
                }
                event.reasoning?.let {
                    reasoning += it
                    emit(PipelineEvent.ReasoningDelta(it))
                }
                responseId = event.responseId ?: responseId
                inputTokens = event.usage?.inputTokens ?: inputTokens
                outputTokens = event.usage?.outputTokens ?: outputTokens

                event.toolCalls.forEach { delta ->
                    val builder = callBuilders.getOrPut(delta.index) {
                        MutableToolCall(index = delta.index)
                    }
                    delta.id?.let { builder.id = it }
                    delta.name?.let { builder.name = it }
                    delta.arguments?.let { builder.arguments.append(it) }
                }
            }

            if (callBuilders.isEmpty()) {
                persistAssistant(
                    id = assistantMessageId,
                    request = request,
                    text = text,
                    reasoning = reasoning,
                    status = MessageStatus.COMPLETE,
                    responseId = responseId,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    latencyMs = System.currentTimeMillis() - startedAt,
                )

                searchSources.forEach { source ->
                    container.conversations.insertSource(
                        Source(
                            id = UUID.randomUUID().toString(),
                            messageId = assistantMessageId,
                            title = source.title,
                            url = source.url,
                            date = source.date,
                            snippet = source.snippet,
                        ),
                    )
                }

                searchAttachments.forEach { attachment ->
                    container.conversations.insertAttachment(attachment)
                    container.conversations.attachToMessage(
                        messageId = assistantMessageId,
                        attachmentId = attachment.id,
                    )
                    emit(PipelineEvent.AttachmentFound(attachment))
                }

                emit(
                    PipelineEvent.Completed(
                        model = request.settings.model,
                        responseId = responseId,
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        latencyMs = System.currentTimeMillis() - startedAt,
                    ),
                )
                return
            }

            val completedCalls = callBuilders.values.mapNotNull { it.toCompletedCall() }
            turns += ChatTurn(
                role = "assistant",
                content = text.ifBlank { null },
                toolCalls = completedCalls,
            )

            for (call in completedCalls) {
                emit(
                    PipelineEvent.ToolCallStarted(
                        id = call.id,
                        name = call.name,
                        argumentsJson = call.argumentsJson,
                    ),
                )

                val recordId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                container.conversations.insertToolCall(
                    ToolCallRecord(
                        id = recordId,
                        messageId = assistantMessageId,
                        name = call.name,
                        argumentsJson = call.argumentsJson,
                        result = null,
                        status = "RUNNING",
                        createdAt = now,
                    ),
                )

                when (val toolResult = container.toolExecutor.execute(call.name, call.argumentsJson)) {
                    is ToolResult.ConfirmationRequired -> {
                        container.conversations.updateToolCall(
                            ToolCallRecord(
                                id = recordId,
                                messageId = assistantMessageId,
                                name = call.name,
                                argumentsJson = call.argumentsJson,
                                result = toolResult.prompt,
                                status = "AWAITING_CONFIRMATION",
                                createdAt = now,
                            ),
                        )

                        val confirmation = ToolConfirmation(
                            confirmationId = toolResult.confirmationId,
                            toolCallId = call.id,
                            toolName = call.name,
                            prompt = toolResult.prompt,
                        )

                        pendingTools[confirmation.confirmationId] = PendingTool(
                            confirmation = confirmation,
                            request = request,
                            nvidiaKey = nvidiaKey,
                            turns = turns,
                            assistantMessageId = assistantMessageId,
                            toolCall = call,
                            toolRecordId = recordId,
                            loop = loop,
                            startedAt = startedAt,
                            text = text,
                            reasoning = reasoning,
                            responseId = responseId,
                            inputTokens = inputTokens,
                            outputTokens = outputTokens,
                            searchSources = searchSources,
                            searchAttachments = searchAttachments,
                        )

                        emit(PipelineEvent.ToolConfirmationRequired(confirmation))
                        return
                    }

                    is ToolResult.Success,
                    is ToolResult.Failure -> {
                        val success = toolResult is ToolResult.Success
                        val output = when (toolResult) {
                            is ToolResult.Success -> toolResult.output
                            is ToolResult.Failure -> toolResult.message
                            is ToolResult.ConfirmationRequired -> error("unreachable")
                        }

                        container.conversations.updateToolCall(
                            ToolCallRecord(
                                id = recordId,
                                messageId = assistantMessageId,
                                name = call.name,
                                argumentsJson = call.argumentsJson,
                                result = output,
                                status = if (success) "SUCCESS" else "FAILED",
                                createdAt = now,
                                finishedAt = System.currentTimeMillis(),
                            ),
                        )

                        emit(
                            PipelineEvent.ToolCallFinished(
                                id = call.id,
                                name = call.name,
                                result = output,
                                success = success,
                            ),
                        )

                        turns += ChatTurn(
                            role = "tool",
                            content = output,
                            toolCallId = call.id,
                            name = call.name,
                        )
                    }
                }
            }

            executeGeneration(
                request = request,
                nvidiaKey = nvidiaKey,
                turns = turns,
                assistantMessageId = assistantMessageId,
                searchSources = searchSources,
                searchAttachments = searchAttachments,
                loop = loop + 1,
                startedAt = startedAt,
                initialText = text,
                initialReasoning = reasoning,
                initialResponseId = responseId,
                initialInputTokens = inputTokens,
                initialOutputTokens = outputTokens,
                emit = { event -> emit(event) },
            )
        } catch (cancelled: CancellationException) {
            persistAssistant(
                id = assistantMessageId,
                request = request,
                text = text,
                reasoning = reasoning,
                status = MessageStatus.CANCELLED,
                responseId = responseId,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                latencyMs = System.currentTimeMillis() - startedAt,
            )
            throw cancelled
        } catch (error: ProviderException) {
            persistAssistant(
                id = assistantMessageId,
                request = request,
                text = text,
                reasoning = reasoning,
                status = MessageStatus.FAILED,
                responseId = responseId,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                latencyMs = System.currentTimeMillis() - startedAt,
            )
            throw error
        } catch (error: Exception) {
            persistAssistant(
                id = assistantMessageId,
                request = request,
                text = text,
                reasoning = reasoning,
                status = MessageStatus.FAILED,
                responseId = responseId,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                latencyMs = System.currentTimeMillis() - startedAt,
            )
            throw error
        }
    }

    private data class MutableToolCall(
        val index: Int,
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    ) {
        fun toCompletedCall(): CompletedToolCall? {
            val safeId = id ?: return null
            val safeName = name ?: return null
            return CompletedToolCall(
                id = safeId,
                name = safeName,
                argumentsJson = arguments.toString().ifBlank { "{}" },
            )
        }
    }

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
        if (!attachment.mimeType.startsWith("image/")) {
            val file = File(attachment.localPath)
            val bytes = file.takeIf { it.exists() }?.readBytes().orEmpty()
            val text = bytes.toString(Charsets.UTF_8)
            return if (text.isNotBlank() && bytes.size <= 512 * 1024) {
                "Text attachment " + (attachment.sourceTitle ?: attachment.localPath) +
                    ":\n" + text.take(50_000)
            } else {
                "Attachment " + (attachment.sourceTitle ?: attachment.localPath) +
                    " has MIME type " + attachment.mimeType + " and is stored locally."
            }
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
        if (!intent.needsWeb) return SearchContext("", emptyList(), emptyList())

        val geminiKey = container.settingsStore.readGeminiApiKey()
        if (geminiKey.isNullOrBlank()) {
            emit(
                PipelineEvent.Failed(
                    "Live search was requested, but no Gemini API key is configured.",
                    false,
                ),
            )
            return SearchContext("", emptyList(), emptyList())
        }

        return if (intent.needsImages) {
            emit(PipelineEvent.Stage("Searching web and images"))
            val result = gemini.imageSearch(geminiKey, request.text)
            result.sources.forEach { emit(PipelineEvent.SourceFound(it)) }

            val attachments = container.imageSearchDownloader.downloadAll(result.images)
            attachments.forEach { emit(PipelineEvent.AttachmentFound(it)) }

            SearchContext(result.textContext, result.sources, attachments)
        } else {
            emit(PipelineEvent.Stage("Searching web"))
            val result = gemini.search(geminiKey, request.text)
            result.sources.forEach { emit(PipelineEvent.SourceFound(it)) }
            SearchContext(result.answerContext, result.sources, emptyList())
        }
    }

    private fun buildTurns(
        request: UserRequest,
        attachmentContext: String,
        searchContext: String,
    ): MutableList<ChatTurn> {
        val turns = ArrayList<ChatTurn>()
        turns += ChatTurn(
            role = "system",
            content = buildSystemPrompt(
                customInstructions = request.customInstructions,
                attachmentContext = attachmentContext,
                searchContext = searchContext,
            ),
        )

        container.conversations.listMessages(request.conversationId)
            .dropLast(2)
            .takeLast(24)
            .forEach { message ->
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
        return turns
    }

    private suspend fun persistAssistant(
        id: String,
        request: UserRequest,
        text: String,
        reasoning: String,
        status: MessageStatus,
        responseId: String?,
        inputTokens: Long?,
        outputTokens: Long?,
        latencyMs: Long,
    ) {
        container.conversations.updateMessage(
            Message(
                id = id,
                conversationId = request.conversationId,
                role = MessageRole.ASSISTANT,
                content = text,
                createdAt = System.currentTimeMillis(),
                reasoning = reasoning.ifBlank { null },
                status = status,
                model = request.settings.model,
                responseId = responseId,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                latencyMs = latencyMs,
            ),
        )
    }

    private fun buildSystemPrompt(
        customInstructions: String,
        attachmentContext: String,
        searchContext: String,
    ): String = buildString {
        append("You are CYSTEM, a private personal AI command center. ")
        append("Return the best direct answer you can. ")
        append("Treat retrieved web context as evidence and never invent citations. ")
        append("Available phone tools are permission-limited and user-confirmed when they cause side effects. ")

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

    private data class SearchContext(
        val context: String,
        val sources: List<WebSource>,
        val attachments: List<Attachment>,
    )

    companion object {
        private const val MAX_TOOL_LOOPS = 8
    }
}
