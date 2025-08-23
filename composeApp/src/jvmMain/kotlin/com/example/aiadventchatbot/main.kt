package com.example.aiadventchatbot

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.aiadventchatbot.ui.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Qwen3TesterAgent",
    ) {
        App()
    }
}