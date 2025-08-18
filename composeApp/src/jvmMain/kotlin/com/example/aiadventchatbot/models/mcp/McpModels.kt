package com.example.aiadventchatbot.models.mcp

import kotlinx.serialization.Serializable

@Serializable
data class CreateNoteResponse(
    val targetIdentifier: String,
    val content: String,
)