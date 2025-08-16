package com.example.aiadventchatbot.data

import com.example.aiadventchatbot.data.network.ObsidianMcpClient
import com.example.aiadventchatbot.domain.McpRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class McpRepositoryImpl(
    private val obsidianMcpClient: ObsidianMcpClient,
) : McpRepository {
    override suspend fun createNote(
        fileName: String,
        content: String
    ): String = withContext(Dispatchers.IO) {
        return@withContext obsidianMcpClient.createNote(fileName, content)
    }

    override suspend fun getNoteContent(path: String): String = withContext(Dispatchers.IO) {
        return@withContext obsidianMcpClient.getNoteContent(path)
    }
}