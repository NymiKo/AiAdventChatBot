package com.example.aiadventchatbot.data.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

class ObsidianMcpClient(
    private val baseUrl: String = "http://127.0.0.1:27123",
    private val obsidianApiKey: String,
) {
    private val client: HttpClient by lazy { createHttpClient() }

    suspend fun createNote(fileName: String, content: String): String {
        return try {
            client.post("$baseUrl/vault/$fileName") {
                header("Authorization", "Bearer $obsidianApiKey")
                contentType(ContentType.parse("text/markdown"))
                setBody(content)
            }
            "Заметка создана!"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    suspend fun getNoteContent(path: String): String {
        val response = client.get("$baseUrl/vault/$path") {
            header("Authorization", "Bearer $obsidianApiKey")
            contentType(ContentType.parse("text/markdown"))
        }
        return response.bodyAsText()
    }
}