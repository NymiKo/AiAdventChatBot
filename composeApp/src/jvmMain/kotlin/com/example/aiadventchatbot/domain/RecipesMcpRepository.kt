package com.example.aiadventchatbot.domain

interface RecipesMcpRepository {
    suspend fun connect()
    suspend fun listTools(): List<String>
    suspend fun getAllRecipes(): String
    suspend fun findByIngredients(ingredients: List<String>): String
    suspend fun getRecipe(name: String): String
}