package com.cystem.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cystem.core.coordinator.PipelineEvent
import com.cystem.core.coordinator.ToolConfirmation
import com.cystem.core.coordinator.UserRequest
import com.cystem.core.di.AppContainer
import com.cystem.core.model.Conversation
import com.cystem.core.model.GenerationSettings
import com.cystem.core.model.Message
import com.cystem.core.storage.ConversationTitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext

data class CystemUiState(
    val conversations: List<Conversation> = emptyList(),
    val activeConversationId: String? = null,
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    val streamingText: String = "",
    val streamingReasoning: String = "",
    val sources: List<com.cystem.core.network.WebSource> = emptyList(),
    val stage: String? = null,
    val processing: Boolean = false,
    val error: String? = null,
    val pendingConfirmation: ToolConfirmation? = null,
    val customInstructions: String = "",
    val accentArgb: Long = 0xFF7C5CFC,
    val darkMode: Boolean = true,
    val bootVisible: Boolean = true,
    val generation: GenerationSettings = GenerationSettings(),
)

class CystemViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val mutable = MutableStateFlow(CystemUiState())
    val uiState: StateFlow<CystemUiState> = mutable.asStateFlow()
    private var activeGenerationJob: Job? = null

    init {
        refreshConversations()
        viewModelScope.launch {
            container.settingsStore.settings.collectLatest { settings ->
                mutable.value = mutable.value.copy(
                    customInstructions = settings.customInstructions,
                    accentArgb = settings.accentArgb,
                    darkMode = settings.darkMode,
                    generation = settings.generation,
                )
            }
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(1200)
            skipBoot()
        }
    }

    private fun refreshConversations() {
        viewModelScope.launch(Dispatchers.IO) {
            mutable.value = mutable.value.copy(
                conversations = container.conversations.listConversations(),
            )
        }
    }

    private suspend fun readMessages(conversationId: String): List<Message> =
        withContext(Dispatchers.IO) {
            container.conversations.listMessages(conversationId)
        }

    private fun refreshMessages(conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mutable.value = mutable.value.copy(
                messages = container.conversations.listMessages(conversationId),
            )
        }
    }

    fun setDraft(value: String) {
        mutable.value = mutable.value.copy(draft = value)
    }

    fun newConversation() {
        viewModelScope.launch(Dispatchers.IO) {
            val conversation = container.conversations.createConversation(System.currentTimeMillis())
            mutable.value = mutable.value.copy(
                conversations = container.conversations.listConversations(),
                activeConversationId = conversation.id,
                messages = emptyList(),
                streamingText = "",
                streamingReasoning = "",
                sources = emptyList(),
                error = null,
                pendingConfirmation = null,
            )
        }
    }

    fun selectConversation(id: String) {
        mutable.value = mutable.value.copy(
            activeConversationId = id,
            messages = emptyList(),
            streamingText = "",
            streamingReasoning = "",
            sources = emptyList(),
            error = null,
            pendingConfirmation = null,
        )
        refreshMessages(id)
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.conversations.renameConversation(
                id = id,
                title = ConversationTitles.fromFirstMessage(title),
                now = System.currentTimeMillis(),
            )
            mutable.value = mutable.value.copy(
                conversations = container.conversations.listConversations(),
            )
        }
    }

    fun pinConversation(id: String, pinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            container.conversations.setPinned(id, pinned, System.currentTimeMillis())
            mutable.value = mutable.value.copy(
                conversations = container.conversations.listConversations(),
            )
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.conversations.deleteConversation(id)
            val conversations = container.conversations.listConversations()
            val next = conversations.firstOrNull()
            mutable.value = mutable.value.copy(
                conversations = conversations,
                activeConversationId = next?.id,
                messages = next?.let { container.conversations.listMessages(it.id) }.orEmpty(),
                pendingConfirmation = null,
            )
        }
    }

    fun importSharedText(text: String) {
        if (text.isBlank()) return
        ensureConversationThen { id -> sendToCoordinator(id, text) }
    }

    fun sendDraft() {
        val text = mutable.value.draft.trim()
        if (text.isBlank() || mutable.value.processing) return
        mutable.value = mutable.value.copy(draft = "")
        ensureConversationThen { id -> sendToCoordinator(id, text) }
    }

    fun stopGeneration() {
        val job = activeGenerationJob ?: return
        activeGenerationJob = null
        viewModelScope.launch {
            job.cancelAndJoin()
            val active = mutable.value.activeConversationId
            val pair = active?.let { readMessages(it) }
            mutable.value = mutable.value.copy(
                processing = false,
                stage = "Cancelled",
                messages = pair?.first.orEmpty(),
                messageAttachments = pair?.second.orEmpty(),
            )
        }
    }

    fun confirmTool(approved: Boolean) {
        val confirmation = mutable.value.pendingConfirmation ?: return
        mutable.value = mutable.value.copy(
            pendingConfirmation = null,
            processing = true,
            stage = if (approved) "Running confirmed action" else "Declining action",
            error = null,
        )
        viewModelScope.launch {
            container.coordinator
                .resumeToolConfirmation(confirmation.confirmationId, approved)
                .collectLatest(::handlePipelineEvent)
        }
    }

    fun skipBoot() {
        mutable.value = mutable.value.copy(bootVisible = false)
    }

    suspend fun saveInstructions(value: String) {
        container.settingsStore.setCustomInstructions(value)
    }

    suspend fun setDarkMode(value: Boolean) {
        container.settingsStore.setDarkMode(value)
    }

    suspend fun setAccent(value: Long) {
        container.settingsStore.setAccent(value)
    }

    suspend fun saveGeneration(value: GenerationSettings) {
        container.settingsStore.saveGeneration(value)
    }

    suspend fun setPhoneToolEnabled(name: String, enabled: Boolean) {
        container.settingsStore.setPhoneToolEnabled(name, enabled)
    }

    fun clearError() {
        mutable.value = mutable.value.copy(error = null)
    }

    private fun ensureConversationThen(action: (String) -> Unit) {
        val active = mutable.value.activeConversationId
        if (active != null) {
            action(active)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val conversation = container.conversations.createConversation(System.currentTimeMillis())
            mutable.value = mutable.value.copy(
                conversations = container.conversations.listConversations(),
                activeConversationId = conversation.id,
                messages = emptyList(),
            )
            action(conversation.id)
        }
    }

    private fun sendToCoordinator(conversationId: String, text: String) {
        val state = mutable.value
        if (state.processing) return

        viewModelScope.launch {
            mutable.value = mutable.value.copy(
                processing = true,
                stage = "Preparing request",
                streamingText = "",
                streamingReasoning = "",
                sources = emptyList(),
                error = null,
                pendingConfirmation = null,
            )

            val request = UserRequest(
                conversationId = conversationId,
                text = text,
                settings = state.generation,
                customInstructions = state.customInstructions,
            )

            try {
                container.coordinator.run(request).collectLatest(::handlePipelineEvent)
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(
                    processing = false,
                    stage = null,
                    error = error.message ?: "Request failed",
                )
            }
        }
    }

    private suspend fun handlePipelineEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.Stage ->
                mutable.value = mutable.value.copy(stage = event.name)

            PipelineEvent.ResetStreaming ->
                mutable.value = mutable.value.copy(
                    streamingText = "",
                    streamingReasoning = "",
                )

            is PipelineEvent.TextDelta ->
                mutable.value = mutable.value.copy(
                    streamingText = mutable.value.streamingText + event.delta,
                )

            is PipelineEvent.ReasoningDelta ->
                mutable.value = mutable.value.copy(
                    streamingReasoning = mutable.value.streamingReasoning + event.delta,
                )

            is PipelineEvent.SourceFound ->
                mutable.value = mutable.value.copy(
                    sources = (mutable.value.sources + event.source)
                        .distinctBy { it.url }
                        .take(12),
                )

            is PipelineEvent.ToolCallStarted ->
                mutable.value = mutable.value.copy(
                    stage = "Tool: " + event.name,
                )

            is PipelineEvent.ToolConfirmationRequired ->
                mutable.value = mutable.value.copy(
                    pendingConfirmation = event.confirmation,
                    processing = false,
                    stage = "Awaiting confirmation",
                )

            is PipelineEvent.ToolCallFinished ->
                mutable.value = mutable.value.copy(
                    stage = "Continuing",
                )

            is PipelineEvent.Completed -> {
                val active = mutable.value.activeConversationId
                val latest = active?.let { readMessages(it) }.orEmpty()
                mutable.value = mutable.value.copy(
                    processing = false,
                    stage = null,
                    streamingText = "",
                    streamingReasoning = "",
                    pendingConfirmation = null,
                    messages = latest,
                )
                refreshConversations()
            }

            is PipelineEvent.Failed -> {
                val active = mutable.value.activeConversationId
                val latest = active?.let { readMessages(it) }.orEmpty()
                mutable.value = mutable.value.copy(
                    processing = false,
                    stage = null,
                    pendingConfirmation = null,
                    error = event.message,
                    messages = latest,
                )
            }
        }
    }
}
