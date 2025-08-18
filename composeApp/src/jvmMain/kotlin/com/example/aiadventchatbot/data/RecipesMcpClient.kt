package com.example.aiadventchatbot.data

import com.example.aiadventchatbot.data.network.createHttpClient
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport

class RecipesMcpClient(
    private val baseUrl: String = "http://localhost:8001/mcp"
) {
    private val client = Client(
        clientInfo = Implementation(name = "recipes-client", version = "1.0.0")
    )

    suspend fun connect() {
        val transport = StreamableHttpClientTransport(
            client = createHttpClient(),
            url = baseUrl
        )
        client.connect(transport)
        println("🚀 Подключились к recipes-mcp")
    }

    suspend fun listTools(): List<String> {
        val toolsResponse = client.listTools()
        return toolsResponse?.tools?.map { it.name } ?: emptyList()
    }

    suspend fun getRecipes(): String {
        val result = client.callTool(
            name = "get_recipes",
            arguments = mapOf(),
        )
        return result?.content?.toString() ?: "Ошибка: пустой ответ"
    }

    suspend fun searchByIngredients(ingredients: List<String>): String {
        val result = client.callTool(
            name = "search_recipe_by_ingredients",
            arguments = mapOf("ingredients" to ingredients),
        )
        return result?.content?.toString() ?: "Ошибка: пустой ответ"
    }

    suspend fun getRecipeByName(name: String): String {
        val result = client.callTool(
            name = "get_recipe_by_name",
            arguments = mapOf("recipe_name" to name),
        )
        return result?.content?.toString() ?: "Ошибка: пустой ответ"
    }
}