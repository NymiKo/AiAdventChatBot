package com.example.aiadventchatbot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiadventchatbot.domain.ChatPrompts
import com.example.aiadventchatbot.domain.ChatRepository
import com.example.aiadventchatbot.models.MessageInfo
import com.example.aiadventchatbot.models.Roles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: ChatRepository
) : ViewModel() {

    private val _messages = MutableStateFlow<List<MessageInfo>>(emptyList())
    val messages: StateFlow<List<MessageInfo>> = _messages.asStateFlow()

    private val _validationMenu = MutableStateFlow("")
    val validationMenu: StateFlow<String> = _validationMenu.asStateFlow()

    private val _userInput = MutableStateFlow("")
    val userInput: StateFlow<String> = _userInput.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var validatorMessages: List<MessageInfo> = emptyList()

    fun initChat() {
        _messages.value = listOf(ChatPrompts.systemMessage)
        validatorMessages = listOf(ChatPrompts.validatorMessage)
    }

    fun onUserInputChanged(newValue: String) {
        _userInput.update { newValue }
    }

    fun sendMessage() {
        if (_userInput.value.isBlank() || _isLoading.value) return

        _isLoading.value = true
        val userMessage = MessageInfo(Roles.USER.role, _userInput.value)
        addMessage(userMessage)

        viewModelScope.launch {
            val response = fetchAssistantResponse()
            handleAssistantResponse(response)
            _userInput.value = ""
            _isLoading.value = false
        }
    }

    private fun addMessage(message: MessageInfo) {
        _messages.value = _messages.value + message
    }

    private suspend fun fetchAssistantResponse(): String {
        return runCatching {
            repository.sendMessage(_messages.value)
        }.getOrElse { "Ошибка: ${it.message ?: "Unknown error"}" }
    }

    private suspend fun handleAssistantResponse(response: String) {
        if (response.trim().endsWith("[VALIDATE_MENU]")) {
            val validated = repository.validateMenu(validatorMessages, response)
            _validationMenu.value = validated
            addMessage(MessageInfo(Roles.ASSISTANT.role, response.replace("[VALIDATE_MENU]", "")))
        } else {
            addMessage(MessageInfo(Roles.ASSISTANT.role, response))
        }
    }
}