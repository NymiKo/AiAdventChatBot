package com.example.aiadventchatbot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import org.jetbrains.compose.ui.tooling.preview.Preview

import com.example.aiadventchatbot.network.ChatBot

@Composable
@Preview
fun App() {
    MaterialTheme {
        ChatUI(
            ChatBot(
                apiKey = "API_KEY",
                folderId = "CATALOG_ID"
            )
        )
    }
}