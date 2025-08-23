package com.example.aiadventchatbot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.example.aiadventchatbot.data.ChatRepositoryImpl
import com.example.aiadventchatbot.data.network.Qwen3Coder
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    MaterialTheme {
        val qwen3Coder = remember { Qwen3Coder(apiKey = "API_KEY") }
        val repository = remember { ChatRepositoryImpl(qwen3Coder) }
        val viewModel = remember { ChatViewModel(repository) }

        LaunchedEffect(Unit) {
            viewModel.initChat()
        }

        ChatScreen(viewModel)
    }
}