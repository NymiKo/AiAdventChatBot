package com.example.aiadventchatbot.data.network

import com.example.aiadventchatbot.models.ChatRequest
import com.example.aiadventchatbot.models.ChatResponse
import com.example.aiadventchatbot.models.MessageInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

class Qwen3Coder(
    private val apiKey: String,
) {
    private val client: HttpClient by lazy { createHttpClient() }
    private val baseUrl = "https://api.cometapi.com/v1/chat/completions"

    suspend fun sendMessage(
        messages: List<MessageInfo>,
        model: String = "qwen3-coder",
    ): String {
        return try {
            val request = ChatRequest(
                model = model,
                messages = messages,
            )

            val response = client.post(baseUrl) {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status == HttpStatusCode.Companion.UnprocessableEntity) {
                val errorBody = response.bodyAsText()
                return "API Validation Error: $errorBody"
            }

            val responseBody = response.body<ChatResponse>()
            print("RESPONSE_QWEN: $responseBody")
            responseBody.choices.first().message.content.removeSurrounding("```").trim()
        } catch (e: Exception) {
            "Request failed: ${e.message ?: "Unknown error"}"
        }
    }
}