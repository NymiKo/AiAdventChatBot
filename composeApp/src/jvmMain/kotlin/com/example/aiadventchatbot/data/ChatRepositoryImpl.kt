package com.example.aiadventchatbot.data

import com.example.aiadventchatbot.data.network.Qwen3Coder
import com.example.aiadventchatbot.domain.ChatRepository
import com.example.aiadventchatbot.models.MessageInfo
import com.example.aiadventchatbot.models.Roles

class ChatRepositoryImpl(
    private val qwen3Coder: Qwen3Coder
) : ChatRepository {
    override suspend fun sendMessage(messages: List<MessageInfo>): String {
        return qwen3Coder.sendMessage(messages)
    }

    override suspend fun validateMenu(
        messagesForValidator: List<MessageInfo>,
        menu: String
    ): String {
        return qwen3Coder.sendMessage(messagesForValidator + MessageInfo(Roles.USER.role, menu))
    }
}