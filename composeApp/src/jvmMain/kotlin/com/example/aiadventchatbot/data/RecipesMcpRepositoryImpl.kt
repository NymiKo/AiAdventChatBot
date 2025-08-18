package com.example.aiadventchatbot.data

import com.example.aiadventchatbot.domain.RecipesMcpRepository

class RecipesMcpRepositoryImpl(
    private val client: RecipesMcpClient,
) : RecipesMcpRepository {
    override suspend fun connect() = client.connect()
    override suspend fun listTools(): List<String> = client.listTools()

    override suspend fun getAllRecipes(): String = client.getRecipes()
    override suspend fun findByIngredients(ingredients: List<String>): String =
        client.searchByIngredients(ingredients)

    override suspend fun getRecipe(name: String): String = client.getRecipeByName(name)
}