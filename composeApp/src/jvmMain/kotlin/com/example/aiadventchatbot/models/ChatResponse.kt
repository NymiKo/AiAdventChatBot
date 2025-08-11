package com.example.aiadventchatbot.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatResponse(
    val result: ChatResult
)

@Serializable
data class ChatResult(
    val alternatives: List<ChatMessage> = emptyList()
)

@Serializable
data class ChatMessage(
    val message: MessageInfo,
)

@Serializable
data class MessageInfo(
    val role: String,
    val text: String,
)

@Serializable
data class MessageFormat(
    val header: String,
    val answer: String,
    val question: String,
)