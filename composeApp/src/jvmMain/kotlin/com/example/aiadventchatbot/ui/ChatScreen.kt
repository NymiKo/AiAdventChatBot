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
import androidx.compose.ui.unit.sp
import com.example.aiadventchatbot.models.MessageInfo
import com.example.aiadventchatbot.models.Roles
import com.example.aiadventchatbot.network.MenuGenerator
import kotlinx.coroutines.launch

@Composable
fun ChatUI(menuGenerator: MenuGenerator) {
    var messages by remember {
        mutableStateOf(
            listOf(
                MessageInfo(
                    Roles.SYSTEM.role,
                    """
                            Ты — эксперт по питанию. Составляешь персональное меню, задавая по одному вопросу за раз и никогда не отвечая за пользователя.

                            Жёсткие правила:
                            Каждый вопрос касается только одной темы.
                            Если пользователь ответил на несколько тем сразу — всё равно задай оставшиеся вопросы по порядку.
                            Запрещено объединять несколько шагов в одном вопросе.
                            Запрещено отвечать или подсказывать за пользователя.
                            После получения всех данных сразу выдай меню в одном сообщении, без «подождите» или других промежуточных фраз.

                            Порядок вопросов:
                            Рост
                            Вес
                            Цель и срок

                            Действия:
                            После роста и веса — рассчитай норму калорий и озвучь её.
                            
                            После всех вопросов — выдай меню с:
                            калорийностью и целями
                            блюдами с временем приготовления
                            списком продуктов

                            В конце меню добавь: [VALIDATE_MENU]
                          """.trimIndent()
                )
            )
        )
    }
    var messagesForValidator by remember {
        mutableStateOf(
            listOf(
                MessageInfo(
                    Roles.SYSTEM.role,
                    """
                            Ты — AI-валидатор питания.
                            Твоя задача — проверить предложенное меню на соответствие критериям здорового рациона и выявить возможные ошибки.

                            1. Проверь баланс нутриентов (в % от общей калорийности):
                            Белки: 20–30%

                            Жиры: 20–35% (из них насыщенные — ≤10%)

                            Углеводы: 30–50% (добавленные сахара — ≤10%)

                            2. Оцени калорийность:
                            Диапазон: 1500–2500 ккал/день (если не указана цель — поддержание веса).

                            Если калорийность выходит за рамки, предложи корректировку (увеличить/уменьшить порции или заменить блюда).

                            3. Проверь разнообразие продуктов:
                            В меню должны быть:

                            Овощи/фрукты (минимум 3 разных цвета в день),

                            Источники белка (мясо, рыба, бобовые, тофу),

                            Сложные углеводы (крупы, цельнозерновые продукты),

                            Полезные жиры (орехи, авокадо, оливковое масло).

                            Избегай повторения одного продукта чаще 2 раз в день (например, курица на обед и ужин).

                            4. Выяви аллергены и пищевые ограничения:
                            Если пользователь указал непереносимость (например, лактоза, глютен), проверь, чтобы меню их не содержало.

                            Отметь скрытые аллергены (яйца в выпечке, соя в полуфабрикатах).

                            Формат ответа:
                            Если меню соответствует всем критериям:

                            ✅ Меню сбалансировано!
                            Калорийность: [Х] ккал (БЖУ: [X]%/[X]%/[X]%).
                            Разнообразие: [N] групп продуктов. Аллергены: не обнаружены.

                            Если есть ошибки:

                            ❌ Требуются исправления:

                            Белки: 18% → добавь 30 г творога или курицы.

                            Жиры: 40% → замени майонез на греческий йогурт.

                            Разнообразие: нет овощей — включи салат из огурцов и моркови.

                            Аллергены: в меню есть глютен (указать блюдо).
                          """.trimIndent()
                )
            )
        )
    }
    var validationMenu by remember { mutableStateOf("") }
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
                MessageBubble(message, MessageInfo(Roles.ASSISTANT.role, validationMenu))
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

                            val response = menuGenerator.sendMessage(messages)
                            val endsWithMarker = response.trim().endsWith("[VALIDATE_MENU]")
                            if (endsWithMarker) {
                                val responseValidation = menuGenerator.sendMessage(
                                    messagesForValidator + MessageInfo(
                                        Roles.USER.role,
                                        response
                                    )
                                )
                                messages = messages + MessageInfo(
                                    Roles.ASSISTANT.role,
                                    response.replace("[VALIDATE_MENU]", "")
                                )
                                validationMenu = responseValidation
                            } else {
                                messages = messages + MessageInfo(Roles.ASSISTANT.role, response)
                            }

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
fun MessageBubble(message: MessageInfo, validationMenu: MessageInfo) {
    val isUser = message.role == Roles.USER.role
    val bubbleColor = if (isUser) Color(0xFF4285F4) else Color(0xFFEAEAEA)
    val textColor = if (isUser) Color.White else Color.Black
    val alignment = if (isUser) Alignment.End else Alignment.Start

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
                if (validationMenu.text.isNotBlank()) {
                    Text(
                        text = "Валидатор меню",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                    Text(
                        text = validationMenu.text,
                        color = textColor,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}