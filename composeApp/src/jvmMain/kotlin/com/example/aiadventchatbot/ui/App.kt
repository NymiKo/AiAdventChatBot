package com.example.aiadventchatbot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.example.aiadventchatbot.network.MenuGenerator
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    MaterialTheme {
        ChatUI(
            MenuGenerator(
                apiKey = "API_KEY",
                folderId = "CATALOG_ID"
            )
        )
    }
}