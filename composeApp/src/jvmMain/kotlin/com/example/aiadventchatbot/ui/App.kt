package com.example.aiadventchatbot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.example.aiadventchatbot.data.ChatRepositoryImpl
import com.example.aiadventchatbot.network.MenuGenerator
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    MaterialTheme {
        val menuGenerator = remember { MenuGenerator(apiKey = "API_KEY", folderId = "CATALOG_ID") }
        val repository = remember { ChatRepositoryImpl(menuGenerator) }
        val viewModel = remember { ChatViewModel(repository) }

        LaunchedEffect(Unit) {
            viewModel.initChat()
        }

        ChatScreen(viewModel)
    }
}