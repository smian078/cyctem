package com.cystem.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cystem.core.di.AppContainer
import com.cystem.core.model.Conversation
import com.cystem.core.model.Message
import com.cystem.core.model.MessageRole
import com.cystem.core.storage.ConversationTitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

data class CystemUiState(
    val conversations: List<Conversation> = emptyList(),
    val activeConversationId: String? = null,
    val draft: String = "",
    val customInstructions: String = "",
    val accentArgb: Long = 0xFF7C5CFC,
    val darkMode: Boolean = true,
    val bootVisible: Boolean = true,
)

class CystemViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val mutable = MutableStateFlow(CystemUiState())
    val uiState: StateFlow<CystemUiState> = mutable.asStateFlow()

    init {
        refreshConversations()
        viewModelScope.launch {
            container.settingsStore.settings.collectLatest { settings ->
                mutable.value = mutable.value.copy(
                    customInstructions = settings.customInstructions,
                    accentArgb = settings.accentArgb,
                    darkMode = settings.darkMode,
                )
            }
        }
        viewModelScope.launch {
            delay(1200)
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

    fun setDraft(value: String) {
        mutable.value = mutable.value.copy(draft = value)
    }

    fun newConversation() {
        viewModelScope.launch(Dispatchers.IO) {
            val conversation = container.conversations.createConversation(System.currentTimeMillis())
            mutable.value = mutable.value.copy(
                conversations = container.conversations.listConversations(),
                activeConversationId = conversation.id,
                draft = "",
            )
        }
    }

    fun selectConversation(id: String) {
        mutable.value = mutable.value.copy(activeConversationId = id)
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

    fun deleteConversation(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.conversations.deleteConversation(id)
            mutable.value = mutable.value.copy(
                conversations = container.conversations.listConversations(),
                activeConversationId = mutable.value.activeConversationId.takeUnless { it == id },
            )
        }
    }

    fun importSharedText(text: String) {
        ensureConversationThen { conversationId ->
            insertUserMessage(conversationId, text)
        }
    }

    fun sendDraft() {
        val text = mutable.value.draft.trim()
        if (text.isBlank()) return
        ensureConversationThen { conversationId ->
            insertUserMessage(conversationId, text)
            mutable.value = mutable.value.copy(draft = "")
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
            )
            action(conversation.id)
        }
    }

    private fun insertUserMessage(conversationId: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            container.conversations.insertMessage(
                Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = MessageRole.USER,
                    content = text,
                    createdAt = now,
                ),
            )
            container.conversations.renameConversation(
                id = conversationId,
                title = ConversationTitles.fromFirstMessage(text),
                now = now,
            )
            mutable.value = mutable.value.copy(
                conversations = container.conversations.listConversations(),
            )
        }
    }
}
