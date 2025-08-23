package com.example.aiadventchatbot.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<MessageInfo> = emptyList(),
    val completionOptions: CompletionOptions = CompletionOptions(),
)

@Serializable
data class CompletionOptions(
    val stream: Boolean = false,
    val temperature: Double = 0.6,
    val maxTokens: Int = 2500,
)