package com.example.aiadventchatbot.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatResponse(
    val choices: List<ChatMessage> = emptyList()
)

@Serializable
data class ChatMessage(
    val message: MessageInfo,
)

@Serializable
data class MessageInfo(
    val role: String,
    val content: String,
    val isVisible: Boolean = true,
) {
    constructor(role: Roles, text: String) : this(role.role, text, role.isVisible)
}