package com.example.aiadventchatbot.models

enum class Roles(val role: String, val isVisible: Boolean) {
    SYSTEM("system", isVisible = false),
    USER("user", isVisible = false),
    VISIBLE_USER("user", isVisible = true),
    ASSISTANT("assistant", isVisible = true),
}