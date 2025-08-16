package com.example.aiadventchatbot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.aiadventchatbot.models.MessageInfo
import com.example.aiadventchatbot.models.Roles

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val messages by viewModel.messages.collectAsState()
    val validationMenu by viewModel.validationMenu.collectAsState()
    val userInput by viewModel.userInput.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        MessagesList(
            modifier = Modifier.weight(1F),
            messages = messages,
            validationMenu = validationMenu
        )
        MessageInput(
            value = userInput,
            onValueChange = { viewModel.onUserInputChanged(it) },
            onSendClick = { viewModel.sendMessage() },
            isLoading = isLoading
        )
    }
}

@Composable
fun MessagesList(
    messages: List<MessageInfo>,
    validationMenu: String,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(8.dp),
        reverseLayout = true
    ) {
        items(messages.reversed()) { message ->
            MessageBubble(message, MessageInfo(Roles.ASSISTANT.role, validationMenu))
        }
    }
}

@Composable
fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSendClick: () -> Unit,
    isLoading: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Введите сообщение...") },
            enabled = !isLoading
        )
        IconButton(
            onClick = onSendClick,
            enabled = value.isNotBlank() && !isLoading
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
        }
    }
}

@Composable
fun MessageBubble(message: MessageInfo, validationMenu: MessageInfo) {
    val isUser = message.role == Roles.VISIBLE_USER.role
    val bubbleColor = if (isUser) Color(0xFF4285F4) else Color(0xFFEAEAEA)
    val textColor = if (isUser) Color.White else Color.Black
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
    ) {
        if (message.role == Roles.VISIBLE_USER.role && message.isVisible) {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = bubbleColor
                ),
                modifier = Modifier.widthIn(max = 300.dp).align(alignment)
            ) {
                Text(
                    text = message.text,
                    color = textColor,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else if (message.role == Roles.ASSISTANT.role && message.isVisible) {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = bubbleColor
                ),
                modifier = Modifier.widthIn(max = 300.dp).align(alignment)
            ) {
                Text(
                    text = message.text,
                    color = textColor,
                    modifier = Modifier.padding(12.dp)
                )
//                if (validationMenu.text.isNotBlank()) {
//                    Text(
//                        text = "Валидатор меню (2 агент)",
//                        color = Color.Gray,
//                        fontSize = 12.sp,
//                        modifier = Modifier.padding(12.dp)
//                    )
//                    Text(
//                        text = validationMenu.text,
//                        color = textColor,
//                        modifier = Modifier.padding(12.dp)
//                    )
//                }
            }
        }
    }
}