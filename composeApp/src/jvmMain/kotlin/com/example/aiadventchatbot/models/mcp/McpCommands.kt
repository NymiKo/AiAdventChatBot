package com.example.aiadventchatbot.models.mcp

sealed class MCPIntent {
    data class CreateNote(val content: String) : MCPIntent()
    object None : MCPIntent()
}