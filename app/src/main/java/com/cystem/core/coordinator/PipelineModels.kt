package com.cystem.core.coordinator

import com.cystem.core.model.Attachment
import com.cystem.core.model.GenerationSettings
import com.cystem.core.network.WebSource

data class UserRequest(
    val conversationId: String,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val forceSearch: Boolean = false,
    val forceImageSearch: Boolean = false,
    val settings: GenerationSettings,
    val customInstructions: String = "",
)

data class ToolConfirmation(
    val confirmationId: String,
    val toolCallId: String,
    val toolName: String,
    val prompt: String,
)

sealed interface PipelineEvent {
    data class Stage(val name: String) : PipelineEvent
    data object ResetStreaming : PipelineEvent
    data class TextDelta(val delta: String) : PipelineEvent
    data class ReasoningDelta(val delta: String) : PipelineEvent
    data class SourceFound(val source: WebSource) : PipelineEvent
    data class AttachmentFound(val attachment: Attachment) : PipelineEvent
    data class ToolCallStarted(
        val id: String,
        val name: String,
        val argumentsJson: String,
    ) : PipelineEvent
    data class ToolConfirmationRequired(
        val confirmation: ToolConfirmation,
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
