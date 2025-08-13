package com.example.aiadventchatbot.data

import com.example.aiadventchatbot.domain.ChatRepository
import com.example.aiadventchatbot.models.MessageInfo
import com.example.aiadventchatbot.models.Roles
import com.example.aiadventchatbot.network.MenuGenerator

class ChatRepositoryImpl(
    private val menuGenerator: MenuGenerator
) : ChatRepository {
    override suspend fun sendMessage(messages: List<MessageInfo>): String {
        return menuGenerator.sendMessage(messages)
    }

    override suspend fun validateMenu(
        messagesForValidator: List<MessageInfo>,
        menu: String
    ): String {
        return menuGenerator.sendMessage(messagesForValidator + MessageInfo(Roles.USER.role, menu))
    }
}