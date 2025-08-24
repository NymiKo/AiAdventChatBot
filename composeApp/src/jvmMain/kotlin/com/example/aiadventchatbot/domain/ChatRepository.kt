package com.example.aiadventchatbot.domain

import com.example.aiadventchatbot.models.MessageInfo

interface ChatRepository {
    suspend fun sendMessage(messages: List<MessageInfo>): String
    suspend fun validateMenu(messagesForValidator: List<MessageInfo>, prompt: String): String
}