package com.example.aiadventchatbot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.example.aiadventchatbot.data.ChatRepositoryImpl
import com.example.aiadventchatbot.data.network.YandexGPT
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    MaterialTheme {
        val yandexGPT = remember { YandexGPT(apiKey = "API_KEY", folderId = "CATALOG_ID") }
        val repository = remember { ChatRepositoryImpl(yandexGPT) }
        val viewModel = remember { ChatViewModel(repository) }

        LaunchedEffect(Unit) {
            viewModel.initChat()
        }

        ChatScreen(viewModel)
    }
}