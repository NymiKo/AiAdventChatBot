package com.example.aiadventchatbot.data

import com.example.aiadventchatbot.data.network.YandexGPT
import com.example.aiadventchatbot.domain.ChatRepository
import com.example.aiadventchatbot.models.MessageInfo
import com.example.aiadventchatbot.models.Roles

class ChatRepositoryImpl(
    private val yandexGPT: YandexGPT
) : ChatRepository {
    override suspend fun sendMessage(messages: List<MessageInfo>): String {
        return yandexGPT.sendMessage(messages)
    }

    override suspend fun validateMenu(
        messagesForValidator: List<MessageInfo>,
        menu: String
    ): String {
        return yandexGPT.sendMessage(messagesForValidator + MessageInfo(Roles.USER.role, menu))
    }
}