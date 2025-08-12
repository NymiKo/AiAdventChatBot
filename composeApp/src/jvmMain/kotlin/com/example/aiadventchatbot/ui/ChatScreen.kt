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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.aiadventchatbot.models.MessageInfo
import com.example.aiadventchatbot.models.Roles
import com.example.aiadventchatbot.network.ChatBot
import kotlinx.coroutines.launch

@Composable
fun ChatUI(chatBot: ChatBot) {
    var messages by remember {
        mutableStateOf(
            listOf(
                MessageInfo(
                    Roles.SYSTEM.role,
                    """
                              # Системный промт: AI-диетолог

Ты — эксперт по персонализированному питанию. Помогаешь составить индивидуальное меню, задавая уточняющие вопросы по одному в логичной последовательности.

Принципы работы:
Постепенность — задаешь один вопрос за раз, ждешь ответа.

Логичный порядок — начинаешь с базовых данных (рост/вес), затем переходишь к целям, ограничениям и образу жизни.

Автоматические расчеты — после получения роста и веса сразу вычисляешь норму калорий и озвучиваешь ее пользователю.

Как вести диалог:
Сначала запрашиваешь рост, вес и желаемые изменения (цель + срок).

Затем уточняешь ограничения (аллергии, диеты и т.д.).

Далее спрашиваешь про режим (время на готовку, питание вне дома).

В конце — вкусовые предпочтения (любимые продукты).

После сбора данных выдаешь недельное меню, включая:

Калорийность и цели

Блюда с пометками времени приготовления

Список продуктов (оптом и на каждый день)

Важно:

Вопросы должны быть естественными, а не шаблонными.

Не перегружай пользователя — спрашивай только то, что нужно для составления меню.

Держи структуру, но адаптируй формулировки под контекст.
                          """.trimIndent()
                )
            )
        )
    }
    var userInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(8.dp),
            reverseLayout = true
        ) {
            items(messages.reversed()) { message ->
                MessageBubble(message)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = userInput,
                onValueChange = { userInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Введите сообщение...") },
                enabled = !isLoading
            )

            IconButton(
                onClick = {
                    if (userInput.isNotBlank()) {
                        coroutineScope.launch {
                            isLoading = true
                            val userMessage = MessageInfo(Roles.USER.role, userInput)
                            messages = messages + userMessage

                            val response = chatBot.sendMessage(messages)
                            messages = messages + MessageInfo(Roles.ASSISTANT.role, response)

                            userInput = ""
                            isLoading = false
                        }
                    }
                },
                enabled = userInput.isNotBlank() && !isLoading,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
            }
        }
    }
}

@Composable
fun MessageBubble(message: MessageInfo) {
    val isUser = message.role == Roles.USER.role
    val bubbleColor = if (isUser) Color(0xFF4285F4) else Color(0xFFEAEAEA)
    val textColor = if (isUser) Color.White else Color.Black
    val alignment = if (isUser) Alignment.End else Alignment.Start
    var showFormattedMessage by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
    ) {
        if (message.role == Roles.USER.role) {
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
        } else if (message.role == Roles.ASSISTANT.role) {
            //val messageFormat = Json.decodeFromString<MessageFormat>(message.text)
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = bubbleColor
                ),
                modifier = Modifier.widthIn(max = 300.dp).align(alignment)
            ) {
//                Button(
//                    onClick = {
//                        showFormattedMessage = !showFormattedMessage
//                    },
//                    content = {
//                        Text(
//                            fontSize = 11.sp,
//                            color = Color.Gray,
//                            text = "Формат: ${if (showFormattedMessage) "текст" else "JSON"}"
//                        )
//                    },
//                    colors = ButtonDefaults.buttonColors(
//                        containerColor = Color.Transparent
//                    ),
//                    modifier = Modifier.align(Alignment.End)
//                )
                Text(
                    text = message.text,
                    color = textColor,
                    modifier = Modifier.padding(12.dp)
                )
//                if (showFormattedMessage) {
//                    Text(
//                        text = messageFormat.question,
//                        color = Color.Gray,
//                        fontSize = 11.sp,
//                        modifier = Modifier.padding(12.dp)
//                    )
//                    Text(
//                        text = messageFormat.header,
//                        color = textColor,
//                        fontSize = 18.sp,
//                        fontWeight = FontWeight.ExtraBold,
//                        textAlign = TextAlign.Center,
//                        modifier = Modifier.fillMaxWidth()
//                    )
//                    Text(
//                        text = messageFormat.answer,
//                        color = textColor,
//                        modifier = Modifier.padding(12.dp)
//                    )
//                } else {
//                    Text(
//                        text = message.text,
//                        color = textColor,
//                        modifier = Modifier.padding(12.dp)
//                    )
//                }
            }
        }
    }
}