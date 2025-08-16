package com.example.aiadventchatbot.domain

interface McpRepository {
    suspend fun createNote(fileName: String, content: String): String
    suspend fun getNoteContent(path: String): String
}