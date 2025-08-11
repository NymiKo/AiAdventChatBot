package com.example.aiadventchatbot.network

import com.example.aiadventchatbot.models.ChatRequest
import com.example.aiadventchatbot.models.ChatResponse
import com.example.aiadventchatbot.models.MessageInfo
import com.example.aiadventchatbot.models.Roles
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

class ChatBot(
    var apiKey: String,
    private val folderId: String,
) {
    private val client: HttpClient by lazy { createHttpClient() }
    private val baseUrl = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"

    suspend fun sendMessage(
        messages: List<MessageInfo>,
        modelUri: String = "gpt://$folderId/yandexgpt-lite/latest",
    ): String {
        return try {
            val systemPrompt = MessageInfo(
                Roles.SYSTEM.role,
                """
                                        Всегда отвечай строго в формате JSON. 
                                        Структура ответа должна быть такой:
                                        {
                                            "header": "заголовок",
                                            "answer": "развернутый и подробный ответ на вопрос",
                                            "question": "мой вопрос"
                                        }
                                        Не добавляй никакого текста вне JSON-структуры. 
                                        Если ответ содержит списки или сложные данные, 
                                        форматируй их как вложенные JSON-объекты или массивы.
                                    """.trimIndent()
            )
            val request = ChatRequest(
                modelUri = modelUri,
                messages = messages + systemPrompt,
            )

            val response = client.post(baseUrl) {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(request) // Используем сериализованный объект
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