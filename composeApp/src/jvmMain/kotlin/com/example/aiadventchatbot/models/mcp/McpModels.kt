package com.example.aiadventchatbot.models.mcp

import kotlinx.serialization.Serializable

@Serializable
data class CreateNoteResponse(
    val fileName: String,
    val content: String,
)