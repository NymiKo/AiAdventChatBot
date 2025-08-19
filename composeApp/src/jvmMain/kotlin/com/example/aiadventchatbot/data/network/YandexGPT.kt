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

class YandexGPT(
    var apiKey: String,
    private val folderId: String,
) {
    private val client: HttpClient by lazy { createHttpClient() }
    private val baseUrl = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"

    suspend fun sendMessage(
        messages: List<MessageInfo>,
        modelUri: String = "gpt://$folderId/yandexgpt/latest",
    ): String {
        return try {
            val request = ChatRequest(
                modelUri = modelUri,
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
            responseBody.result.alternatives.first().message.text.removeSurrounding("```").trim()
        } catch (e: Exception) {
            "Request failed: ${e.message ?: "Unknown error"}"
        }
    }
}