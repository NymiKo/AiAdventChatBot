package com.example.aiadventchatbot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.example.aiadventchatbot.data.ChatRepositoryImpl
import com.example.aiadventchatbot.data.McpRepositoryImpl
import com.example.aiadventchatbot.data.RecipesMcpClient
import com.example.aiadventchatbot.data.RecipesMcpRepositoryImpl
import com.example.aiadventchatbot.data.network.MenuGenerator
import com.example.aiadventchatbot.data.network.ObsidianMcpClient
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    MaterialTheme {
        val menuGenerator = remember { MenuGenerator(apiKey = "API_KEY", folderId = "CATALOG_ID") }
        val repository = remember { ChatRepositoryImpl(menuGenerator) }
        val obsidianMcpClient = remember { ObsidianMcpClient(obsidianApiKey = "OBSIDIAN_API_KEY") }
        val mcpRepository = remember { McpRepositoryImpl(obsidianMcpClient) }
        val recipesMcpRepository = remember { RecipesMcpRepositoryImpl(RecipesMcpClient()) }
        val viewModel = remember { ChatViewModel(repository, mcpRepository, recipesMcpRepository) }

        LaunchedEffect(Unit) {
            viewModel.initChat()
        }

        ChatScreen(viewModel)
    }
}