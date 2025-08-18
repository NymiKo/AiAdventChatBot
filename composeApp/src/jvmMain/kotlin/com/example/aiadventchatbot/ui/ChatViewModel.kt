package com.example.aiadventchatbot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiadventchatbot.data.RecipesMcpRepositoryImpl
import com.example.aiadventchatbot.domain.ChatPrompts
import com.example.aiadventchatbot.domain.ChatRepository
import com.example.aiadventchatbot.domain.McpRepository
import com.example.aiadventchatbot.models.MessageInfo
import com.example.aiadventchatbot.models.Roles
import com.example.aiadventchatbot.models.mcp.CreateNoteResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatViewModel(
    private val repository: ChatRepository,
    private val mcpRepository: McpRepository,
    private val recipesMcpRepository: RecipesMcpRepositoryImpl,
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
        viewModelScope.launch {
            try {
                recipesMcpRepository.connect()
                val tools = recipesMcpRepository.listTools()
                println("✅ Доступные инструменты MCP: $tools")
            } catch (e: Exception) {
                println("❌ Ошибка подключения MCP: ${e.message}")
            }
        }
        _messages.value = listOf(ChatPrompts.systemPromptsForMCP)
    }

    fun onUserInputChanged(newValue: String) {
        _userInput.update { newValue }
    }

    fun sendMessage() {
        if (_userInput.value.isBlank() || _isLoading.value) return

        viewModelScope.launch {
            _isLoading.value = true
            addMessage(MessageInfo(Roles.VISIBLE_USER, _userInput.value))

            try {
                val gptResponse = fetchAssistantResponse(MessageInfo(Roles.USER, _userInput.value))

                when {
                    isJsonCommand(gptResponse) -> handleMcpCommand(gptResponse)
                    else -> addMessage(MessageInfo(Roles.ASSISTANT, gptResponse))
                }
            } catch (e: Exception) {
                addMessage(MessageInfo(Roles.ASSISTANT, "Ошибка: ${e.message}"))
            } finally {
                _isLoading.value = false
                _userInput.update { "" }
            }
        }
    }

    private fun addMessage(message: MessageInfo) {
        _messages.value = _messages.value + message
    }

    private suspend fun fetchAssistantResponse(messageInfo: MessageInfo): String {
        return runCatching {
            repository.sendMessage(_messages.value + messageInfo)
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

    private suspend fun handleMcpCommand(jsonResponse: String) {
        println("jsonResponse: $jsonResponse")
        val command = Json.parseToJsonElement(jsonResponse).jsonObject
        println("Command: $command")

        when (command["method"]?.jsonPrimitive?.content) {
            "obsidian_update_note" -> {
                println("obsidian_update_note")
                val json = Json { ignoreUnknownKeys = true }
                val params = command["parameters"]?.jsonObject
                    ?: throw IllegalArgumentException("Missing 'note' object")
                val noteInfo =
                    json.decodeFromJsonElement<CreateNoteResponse>(params)
                val mcpResult =
                    mcpRepository.createNote(noteInfo.targetIdentifier, noteInfo.content)
                println("Obsidian: $mcpResult")
                addMessage(MessageInfo(Roles.ASSISTANT, mcpResult))
            }

            "get_recipe" -> {
                val params = command["parameters"]?.jsonObject
                    ?: throw IllegalArgumentException("Missing parameters")
                val query = params["query"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Missing query")
                val recipe = recipesMcpRepository.getRecipe(query)
                println("recipe: $recipe")
                val result = fetchAssistantResponse(
                    MessageInfo(
                        Roles.USER,
                        "Сохрани это в заметку предварительно отформатировав: $recipe"
                    )
                )
                println("result: $result")
                handleMcpCommand(result)
            }
        }
    }

    private fun isJsonCommand(response: String): Boolean {
        return try {
            Json.parseToJsonElement(response) is JsonObject
        } catch (e: Exception) {
            false
        }
    }
}