package com.cystem.core.model

enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }
enum class MessageStatus { COMPLETE, STREAMING, CANCELLED, FAILED }

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
)

data class Message(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    val reasoning: String? = null,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val model: String? = null,
    val responseId: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val latencyMs: Long? = null,
)

data class Attachment(
    val id: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val sourceUrl: String? = null,
    val sourceTitle: String? = null,
)

data class Source(
    val id: String,
    val messageId: String,
    val title: String,
    val url: String,
    val date: String? = null,
    val snippet: String? = null,
)

data class ToolCallRecord(
    val id: String,
    val messageId: String,
    val name: String,
    val argumentsJson: String,
    val result: String? = null,
    val status: String,
    val createdAt: Long,
    val finishedAt: Long? = null,
)

object ModelCatalog {
    const val MAIN = "nvidia/nemotron-3-super-120b-a12b"
    const val VISION = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning"
    const val ULTRA = "nvidia/nemotron-3-ultra-550b-a55b"
    const val GEMINI_SEARCH = "gemini-3.8-flash"
    const val GEMINI_IMAGE = "gemini-3.1-flash-image"

    val all = listOf(MAIN, VISION, ULTRA, GEMINI_SEARCH, GEMINI_IMAGE)
}

data class GenerationSettings(
    val model: String = ModelCatalog.MAIN,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH,
    val seed: Long? = null,
    val temperature: Double = 0.7,
    val topP: Double = 0.95,
    val maxTokens: Int = 4096,
)

enum class ReasoningEffort { NONE, LOW, HIGH }
