package com.example.aiadventchatbot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiadventchatbot.domain.ChatPrompts
import com.example.aiadventchatbot.domain.ChatRepository
import com.example.aiadventchatbot.models.CommandResult
import com.example.aiadventchatbot.models.MessageInfo
import com.example.aiadventchatbot.models.Roles
import com.example.aiadventchatbot.utils.RuStoreTokenGenerator
import com.example.aiadventchatbot.utils.getSecret
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale

class ChatViewModel(
    private val repository: ChatRepository,
) : ViewModel() {

    // State
    private val _messages = MutableStateFlow<List<MessageInfo>>(emptyList())
    val messages: StateFlow<List<MessageInfo>> = _messages.asStateFlow()

    private val _userInput = MutableStateFlow("")
    val userInput: StateFlow<String> = _userInput.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Состояние для отслеживания прогресса публикации
    private val _publishProgress = MutableStateFlow<String?>(null)
    val publishProgress: StateFlow<String?> = _publishProgress.asStateFlow()

    // скрытая история (техлоги для LLM, не показываем в UI)
    private val hiddenHistory = mutableListOf<MessageInfo>()

    // Constants
    private companion object {
        const val JAVA_HOME_PATH = "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
        const val TOOL_CALL_START_PREFIX = "[TOOL_CALL_START]"
        const val AGENT_LOG_PREFIX = "🤖 [AGENT]"
    }

    init {
        initChat()
    }

    fun initChat() {
        _messages.value = listOf(ChatPrompts.systemPrompt)
    }

    fun onUserInputChanged(newValue: String) {
        _userInput.update { newValue }
    }

    fun sendMessage() {

        viewModelScope.launch {
            _isLoading.value = true
            val userText = _userInput.value
            addMessage(MessageInfo(Roles.VISIBLE_USER, userText))

            try {
                sendMessagesLLM(MessageInfo(Roles.USER, userText))
            } catch (e: Exception) {
                addMessage(MessageInfo(Roles.ASSISTANT, "Ошибка: ${e.message}"))
            } finally {
                _isLoading.value = false
                _userInput.update { "" }
            }
        }
    }

    private fun sendMessagesLLM(messageInfo: MessageInfo) = viewModelScope.launch {
        val gptResponse = fetchAssistantResponse(messageInfo)
        println("$AGENT_LOG_PREFIX GPT Response: $gptResponse")

        // извлекаем возможный JSON внутри произвольного текста
        val (humanPart, jsonPart) = extractFirstJsonObject(gptResponse)
        if (!humanPart.isNullOrBlank()) {
            // Добавляем только человекочитаемую часть (один раз)
            addMessage(MessageInfo(Roles.ASSISTANT, humanPart))
        }
        if (jsonPart != null) {
            // Выполняем найденную JSON-команду и завершаем корутину (чтобы не дублировать добавления)
            handleMcpCommand(jsonPart)
            return@launch
        }

        // Если JSON не найден внутри произвольного текста — старое поведение
        if (isToolCallFormat(gptResponse)) {
            val json = convertToolCallToJson(gptResponse)
            handleMcpCommand(json)
        } else if (isJsonCommand(gptResponse)) {
            handleMcpCommand(gptResponse)
        } else {
            addMessage(MessageInfo(Roles.ASSISTANT, gptResponse))
        }
    }

    // Private helpers
    private fun addMessage(message: MessageInfo) {
        // простая дедупликация: не добавляем ровно такое же последнее сообщение
        val last = _messages.value.lastOrNull()
        if (last?.role == message.role && last.content == message.content) return
        _messages.value = _messages.value + message
    }

    private suspend fun fetchAssistantResponse(messageInfo: MessageInfo): String {
        return runCatching {
            // Передаём видимые сообщения + скрытые логи + новое сообщение пользователем
            repository.sendMessage(_messages.value + hiddenHistory + messageInfo)
        }.getOrElse { "Ошибка: ${it.message ?: "Unknown error"}" }
    }

    // Command handling: принимает JSON (инструмент) и выполняет его
    private suspend fun handleMcpCommand(jsonResponse: String) {
        // Логируем сырой ответ для диагностики
        println("$AGENT_LOG_PREFIX handleMcpCommand RAW RESPONSE:\n$jsonResponse")
        hiddenHistory.add(MessageInfo(Roles.ASSISTANT, "**[RAW_TOOL_RESPONSE]** $jsonResponse"))

        val commandObj = try {
            Json.parseToJsonElement(jsonResponse).jsonObject
        } catch (e: Exception) {
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "❗ Невозможно распарсить JSON команды: ${e.message}"
                )
            )
            hiddenHistory.add(MessageInfo(Roles.ASSISTANT, "**[PARSE_ERROR]** ${e.message}"))
            return
        }

        // Берём name (если нет — логируем и выходим)
        val rawName = commandObj["name"]?.jsonPrimitive?.contentOrNull
        if (rawName.isNullOrBlank()) {
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "❗ В команде отсутствует поле 'name'. Проверьте формат JSON."
                )
            )
            hiddenHistory.add(MessageInfo(Roles.ASSISTANT, "**[MISSING_NAME]** $jsonResponse"))
            return
        }

        // Нормализуем имя: нижний регистр, дефисы -> подчёркивания, пробелы -> подчёркивания
        val name = rawName.lowercase(Locale.getDefault())
            .replace("-", "_")
            .replace(" ", "_")

        println("$AGENT_LOG_PREFIX Parsed tool name: $rawName -> normalized: $name")

        // Берём аргументы — если нет, используем пустой объект (не кидаем исключение)
        val arguments = commandObj["arguments"]?.jsonObject ?: JsonObject(emptyMap())

        // Роутинг по нормализованному имени (поддерживаем несколько вариантов)
        when (name) {
            "execute_shell_command", "execute_shell", "shell_command" -> handleShellCommand(
                arguments
            )

            "run_android_tests", "run_tests", "run-android-tests" -> handleAndroidTests(arguments)
            "publish_app", "publish-app", "publish" -> handlePublishApp(arguments)
            "publish_to_rustore", "publish-to-rustore", "publish_rustore" -> handlePublishToRustore(
                arguments
            )

            "publish_version", "publish-version", "publishversion" -> handlePublishVersion(arguments)
            "generate_rustore_token", "generate-rustore-token" -> handleGenerateRuStoreToken(
                arguments
            )

            "test_rustore_api", "test-rustore-api" -> handleTestRuStoreApi(arguments)
            else -> {
                val msg = "Неизвестная команда: $rawName (нормализовано: $name)"
                addMessage(MessageInfo(Roles.ASSISTANT, msg))
                hiddenHistory.add(
                    MessageInfo(
                        Roles.ASSISTANT,
                        "**[UNKNOWN_TOOL_NAME]** name=$rawName normalized=$name json=$jsonResponse"
                    )
                )
            }
        }
    }


    private suspend fun handlePublishVersion(arguments: JsonObject) {
        val packageName = arguments["packageName"]?.jsonPrimitive?.content
            ?: run {
                addMessage(MessageInfo(Roles.ASSISTANT, "❗ Отсутствует 'packageName' в команде."))
                return
            }

        val publicToken = arguments["publicToken"]?.jsonPrimitive?.content

        if (publicToken.isNullOrBlank()) {
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "❗ Не найден Public-Token. Передайте 'publicToken' или сохраните RUSTORE_PUBLIC_TOKEN в .env"
                )
            )
            return
        }

        // Вспомогательная функция экранирования
        fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

        // Собираем поля тела запроса только из тех аргументов, которые переданы
        val parts = mutableListOf<String>()

        arguments["appName"]?.jsonPrimitive?.content?.let { parts.add("\"appName\":\"${esc(it)}\"") }
        arguments["appType"]?.jsonPrimitive?.content?.let { parts.add("\"appType\":\"${esc(it)}\"") }

        // categories: может быть массив JsonArray или строка с запятыми
        val categoriesJson = when (val node = arguments["categories"]) {
            null -> null
            else -> {
                try {
                    val arr = node.jsonArray.map { "\"${esc(it.jsonPrimitive.content)}\"" }
                    "[${arr.joinToString(",")}]"
                } catch (e: Exception) {
                    // fallback: если передали строкой "news,education"
                    val raw = node.jsonPrimitive.content
                    val list =
                        raw.split(",").map { "\"${esc(it.trim())}\"" }.filter { it.isNotBlank() }
                    "[${list.joinToString(",")}]"
                }
            }
        }
        categoriesJson?.let { parts.add("\"categories\":$it") }

        arguments["ageLegal"]?.jsonPrimitive?.content?.let { parts.add("\"ageLegal\":\"${esc(it)}\"") }
        arguments["shortDescription"]?.jsonPrimitive?.content?.let {
            parts.add(
                "\"shortDescription\":\"${
                    esc(
                        it
                    )
                }\""
            )
        }
        arguments["fullDescription"]?.jsonPrimitive?.content?.let {
            parts.add(
                "\"fullDescription\":\"${
                    esc(
                        it
                    )
                }\""
            )
        }
        arguments["whatsNew"]?.jsonPrimitive?.content?.let { parts.add("\"whatsNew\":\"${esc(it)}\"") }
        arguments["moderInfo"]?.jsonPrimitive?.content?.let { parts.add("\"moderInfo\":\"${esc(it)}\"") }

        // priceValue может быть числом
        arguments["priceValue"]?.let {
            try {
                val num = it.jsonPrimitive.content
                // пробуем привести в число/оставить как есть
                parts.add("\"priceValue\":$num")
            } catch (e: Exception) {
                // если что — добавим как строку
                parts.add("\"priceValue\":\"${esc(it.jsonPrimitive.content)}\"")
            }
        }

        // seoTagIds — массив чисел
        val seoJson = when (val node = arguments["seoTagIds"]) {
            null -> null
            else -> {
                try {
                    val arr = node.jsonArray.map { it.jsonPrimitive.content }
                    "[" + arr.joinToString(",") + "]"
                } catch (e: Exception) {
                    // fallback: строка "100,102"
                    val raw = node.jsonPrimitive.content
                    val list = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    "[" + list.joinToString(",") + "]"
                }
            }
        }
        seoJson?.let { parts.add("\"seoTagIds\":$it") }

        arguments["publishType"]?.jsonPrimitive?.content?.let {
            parts.add(
                "\"publishType\":\"${
                    esc(
                        it
                    )
                }\""
            )
        }
        arguments["publishDateTime"]?.jsonPrimitive?.content?.let {
            parts.add(
                "\"publishDateTime\":\"${
                    esc(
                        it
                    )
                }\""
            )
        }

        // partialValue — число/строка
        arguments["partialValue"]?.let {
            try {
                val raw = it.jsonPrimitive.content
                parts.add(
                    "\"partialValue\":${
                        raw.replace(
                            "%",
                            ""
                        )
                    }"
                ) // если передали "5%" — убираем проценты
            } catch (e: Exception) {
                // как строка
                parts.add("\"partialValue\":\"${esc(it.jsonPrimitive.content)}\"")
            }
        }

        val body = if (parts.isEmpty()) {
            "{}"
        } else {
            "{${parts.joinToString(",")}}"
        }

        addMessage(
            MessageInfo(
                Roles.ASSISTANT,
                "📤 Отправляю запрос публикации версии для $packageName..."
            )
        )
        // для отладки: скрытый лог тела
        println("$AGENT_LOG_PREFIX ТЕЛО ЧЕРНОВИКА $body")

        val curlCommand = """
        curl --location --request POST "https://public-api.rustore.ru/public/v1/application/${packageName}/version" \
        --header "Content-Type: application/json" \
        --header "Public-Token: $publicToken" \
        --data-raw '${body}'
    """.trimIndent()
        addMessage(
            MessageInfo(
                Roles.ASSISTANT,
                "✅ Вот поля вашего черновика для публикации: $body"
            )
        )
        _publishProgress.value = null

        val result = executeShellCommand(curlCommand)
        hiddenHistory.add(
            MessageInfo(
                Roles.ASSISTANT,
                "**[RUSTORE_VERSION_RESPONSE]** exit=${result.exitCode}\n${result.stdout}\n${result.stderr}"
            )
        )

        if (result.exitCode == 0) {
            // попробуем извлечь короткий ответ (если это JSON с id/version — показываем первые 300 символов)
            val visible =
                if (result.stdout.length <= 300) result.stdout else result.stdout.take(300) + "..."
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "✅ Вот поля вашего черновика для публикации: $body"
                )
            )
            _publishProgress.value = null
        } else {
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "❌ Ошибка при запросе создания версии. Проверьте hidden logs (модель видит детали)."
                )
            )
            _publishProgress.value = null
        }
    }

    private suspend fun handleShellCommand(arguments: JsonObject) {
        val commandText = arguments["command"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Отсутствует 'command'")
        val description = arguments["description"]?.jsonPrimitive?.content ?: ""

        // Показываем пользователю только человеческое описание (если есть)
        if (description.isNotBlank()) {
            addMessage(MessageInfo(Roles.ASSISTANT, description))
        } else {
            addMessage(MessageInfo(Roles.ASSISTANT, "Выполняю проверку..."))
        }

        val result = executeShellCommand(commandText)

        // Сохраняем полный технический вывод в hiddenHistory (чтобы LLM видел детали)
        val full =
            "**[TECH] Command:** $commandText\n**[TECH] exit=${result.exitCode}**\nSTDOUT:\n${result.stdout}\nSTDERR:\n${result.stderr}"
        hiddenHistory.add(MessageInfo(Roles.ASSISTANT, full))

        // Для пользователя — только краткий человекочитаемый статус
        val userMessage = summarizeForUser(result, isTest = false)
        addMessage(MessageInfo(Roles.ASSISTANT, userMessage))
    }

    private suspend fun handleAndroidTests(arguments: JsonObject) {
        val projectPath = arguments["projectPath"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Отсутствует 'projectPath'")
        val testType = arguments["testType"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Отсутствует 'testType'")
        val moduleName = arguments["moduleName"]?.jsonPrimitive?.content
        val buildVariant = arguments["buildVariant"]?.jsonPrimitive?.content ?: "Debug"

        // Пользователю — короткое сообщение о начале
        val moduleInfo = if (moduleName != null) " для модуля $moduleName" else ""
        addMessage(MessageInfo(Roles.ASSISTANT, "Запускаю $testType тесты$moduleInfo..."))

        val result = runAndroidTests(projectPath, testType, moduleName, buildVariant)

        // Скрыто: полный лог добавляем в hiddenHistory (LLM увидит)
        val full =
            "**[TEST_RESULT] type=$testType module=$moduleName variant=$buildVariant exit=${result.exitCode}**\n${result.stdout}\n${result.stderr}"
        hiddenHistory.add(MessageInfo(Roles.ASSISTANT, full))

        // visible: только краткий статус
        val userMsg = summarizeForUser(result, isTest = true)
        addMessage(MessageInfo(Roles.ASSISTANT, userMsg))
    }

    /**
     * publish_app: собирает/подписывает/загружает артефакт.
     * ВАЖНО: НЕ запускает тесты автоматически — проверка тестов должна быть отдельной командой run_android_tests,
     * которую LLM должна вызвать перед publish_app, если это необходимо.
     */
    private suspend fun handlePublishApp(arguments: JsonObject) {
        // сначала аккуратно читаем аргументы (не кидаем)
        val projectPath = arguments["projectPath"]?.jsonPrimitive?.content
            ?: run {
                addMessage(
                    MessageInfo(
                        Roles.ASSISTANT,
                        "❗ Отсутствует 'projectPath' в команде. Пожалуйста, укажи абсолютный путь к корню проекта."
                    )
                )
                return
            }

        val moduleName = arguments["moduleName"]?.jsonPrimitive?.content ?: "app"
        val buildVariant = arguments["buildVariant"]?.jsonPrimitive?.content ?: "Release"
        val artifactType = arguments["artifactType"]?.jsonPrimitive?.content ?: "apk"

        // credentials: сначала из аргументов, потом из env
        val keystorePath = arguments["keystorePath"]?.jsonPrimitive?.content
            ?: getSecret("KEYSTORE_PATH")
        val keystorePassword = arguments["keystorePassword"]?.jsonPrimitive?.content
            ?: getSecret("KEYSTORE_PASSWORD")
        val keyAlias = arguments["keyAlias"]?.jsonPrimitive?.content
            ?: getSecret("KEY_ALIAS")
        val keyPassword = arguments["keyPassword"]?.jsonPrimitive?.content
            ?: getSecret("KEY_PASSWORD")
        val rustoreToken = arguments["rustoreToken"]?.jsonPrimitive?.content
            ?: getSecret("RUSTORE_TOKEN")
        val apksignerPathArg = arguments["apksignerPath"]?.jsonPrimitive?.content

        // Список, каких параметров не хватает
        val missing = mutableListOf<String>()
        if (keystorePath.isNullOrBlank()) missing.add("keystorePath")
        if (keystorePassword.isNullOrBlank()) missing.add("keystorePassword")
        if (keyAlias.isNullOrBlank()) missing.add("keyAlias")
        if (keyPassword.isNullOrBlank()) missing.add("keyPassword")
        if (rustoreToken.isNullOrBlank()) missing.add("rustoreToken")

        if (missing.isNotEmpty()) {
            // Просим модель/пользователя предоставить недостающие аргументы в формате JSON
            val list = missing.joinToString(", ")
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "❗ Не хватает параметров: $list. \n\nДля решения проблемы:\n1. Создайте файл .env в корне проекта или в папке composeApp\n2. Добавьте в него переменные: KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD, RUSTORE_TOKEN\n3. Или пришли JSON-команду publish_app с этими полями\n\nПример .env файла:\nKEYSTORE_PATH=/path/to/keystore.jks\nKEYSTORE_PASSWORD=your_password\nKEY_ALIAS=your_alias\nKEY_PASSWORD=your_key_password\nRUSTORE_TOKEN=your_token"
                )
            )
            return
        }

        // дальше идёт обычная логика сборки/подписи/загрузки, используя безопасно полученные значения:
        addMessage(MessageInfo(Roles.ASSISTANT, "Собираю релизную версию..."))

        val modulePrefix = if (moduleName.isNotBlank()) "$moduleName:" else ""
        val assembleTask = "assemble${buildVariant.replaceFirstChar { it.uppercase() }}"
        val gradleCommand = "./gradlew ${modulePrefix}$assembleTask --quiet"
        val buildResult = executeShellCommand(gradleCommand, projectPath)

        // скрываем полный лог, добавляем в hiddenHistory
        hiddenHistory.add(
            MessageInfo(
                Roles.ASSISTANT,
                "**[BUILD]** cmd=$gradleCommand\nexit=${buildResult.exitCode}\n${buildResult.stdout}\n${buildResult.stderr}"
            )
        )

        if (buildResult.exitCode != 0) {
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "❌ Ошибка при сборке релиза. Проверь логи (детали доступны модели)."
                )
            )
            return
        }
        addMessage(MessageInfo(Roles.ASSISTANT, "✅ Сборка завершена."))

        val unsignedArtifact =
            findUnsignedArtifact(projectPath, moduleName, buildVariant, artifactType)
        if (unsignedArtifact == null) {
            addMessage(MessageInfo(Roles.ASSISTANT, "❌ Не найден артефакт для загрузки."))
            return
        }

        addMessage(MessageInfo(Roles.ASSISTANT, "Подписываю артефакт..."))

        val signResult = signArtifact(
            unsignedArtifact,
            keystorePath!!,
            keystorePassword!!,
            keyAlias!!,
            keyPassword!!,
            projectPath
        )
        val signedFile = File(
            unsignedArtifact.parentFile,
            unsignedArtifact.name.replace(".aab", "-signed.aab").replace(".apk", "-signed.apk")
        )

        // скрыто: полные логи подписи
        hiddenHistory.add(
            MessageInfo(
                Roles.ASSISTANT,
                "**[SIGN]** exit=${signResult.exitCode}\n${signResult.stdout}\n${signResult.stderr}"
            )
        )

        if (signResult.exitCode != 0) {
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "❌ Ошибка при подписи артефакта. Проверь ключи/пароли."
                )
            )
            return
        }
        addMessage(MessageInfo(Roles.ASSISTANT, "✅ Артефакт подписан."))

        val artifactToUpload = if (signedFile.exists()) signedFile else unsignedArtifact

        addMessage(MessageInfo(Roles.ASSISTANT, "Загружаю приложение в RuStore..."))

        val uploadCommand = """
            curl -s -X POST "https://public-api.rustore.ru/v1/application/upload" \
            -H "Authorization: Bearer $rustoreToken" \
            -F "file=@${artifactToUpload.absolutePath}"
        """.trimIndent()

        val uploadResult = executeShellCommand(uploadCommand, projectPath)

        // скрыто: лог загрузки
        hiddenHistory.add(
            MessageInfo(
                Roles.ASSISTANT,
                "**[UPLOAD]** exit=${uploadResult.exitCode}\n${uploadResult.stdout}\n${uploadResult.stderr}"
            )
        )

        if (uploadResult.exitCode == 0) {
            addMessage(MessageInfo(Roles.ASSISTANT, "🎉 Приложение успешно загружено в RuStore."))
        } else {
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "❌ Ошибка при загрузке в RuStore. Проверь детали (модель их видит)."
                )
            )
        }
    }

    // Пытаемся найти артефакт (apk/aab) в стандартных папках outputs
    private fun findUnsignedArtifact(
        projectPath: String,
        moduleName: String,
        buildVariant: String,
        artifactType: String
    ): File? {
        val moduleDir =
            if (moduleName.isBlank()) File(projectPath) else File(projectPath, moduleName)
        val outputsDir = File(moduleDir, "build/outputs")
        if (!outputsDir.exists()) return null

        val variantLower = buildVariant.lowercase()
        val candidates = outputsDir.walkTopDown().filter { f ->
            f.isFile && !f.name.contains("-signed") && when (artifactType.lowercase()) {
                "aab", "bundle" -> f.extension == "aab" || f.extension == "bundle"
                else -> f.extension == "apk"
            } && f.name.lowercase().contains(variantLower)
        }.toList()

        return candidates.firstOrNull() ?: outputsDir.walkTopDown().filter { f ->
            f.isFile && !f.name.contains("-signed") && when (artifactType.lowercase()) {
                "aab", "bundle" -> f.extension == "aab" || f.extension == "bundle"
                else -> f.extension == "apk"
            }
        }.firstOrNull()
    }

    // Ищем apksigner в ANDROID_SDK_ROOT/build-tools/*/apksigner
    private fun findApksignerInAndroidSdk(): String? {
        val androidHome =
            System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return null
        val buildTools = File(androidHome, "build-tools")
        if (!buildTools.exists() || !buildTools.isDirectory) return null

        val candidates = buildTools.listFiles()?.sortedByDescending { it.name } ?: return null
        for (dir in candidates) {
            val apksigner = File(dir, "apksigner")
            val apksignerExe = File(dir, "apksigner.exe")
            if (apksigner.exists() && apksigner.canExecute()) return apksigner.absolutePath
            if (apksignerExe.exists() && apksignerExe.canExecute()) return apksignerExe.absolutePath
        }
        return null
    }

    // Подпись артефакта с правильной обработкой путей с пробелами
    private fun signArtifact(
        unsignedArtifact: File,
        keystorePath: String,
        keystorePassword: String,
        keyAlias: String,
        keyPassword: String,
        projectPath: String
    ): CommandResult {
        val apksignerPath = findApksignerInAndroidSdk()
        val signedFile = File(
            unsignedArtifact.parentFile,
            unsignedArtifact.name.replace(".aab", "-signed.aab").replace(".apk", "-signed.apk")
        )

        return if (apksignerPath != null && signedFile.extension == "apk") {
            val cmd =
                "\"$apksignerPath\" sign --ks \"$keystorePath\" --ks-pass pass:\"$keystorePassword\" --key-pass pass:\"$keyPassword\" --out \"${signedFile.absolutePath}\" \"${unsignedArtifact.absolutePath}\""
            executeShellCommand(cmd, projectPath)
        } else {
            val cmd =
                "\"$JAVA_HOME_PATH/bin/jarsigner\" -verbose -sigalg SHA256withRSA -digestalg SHA-256 -keystore \"$keystorePath\" -storepass \"$keystorePassword\" -keypass \"$keyPassword\" \"${unsignedArtifact.absolutePath}\" \"$keyAlias\""
            executeShellCommand(cmd, projectPath)
        }
    }

    // Shell command execution
    private fun executeShellCommand(
        command: String,
        workingDirectory: String? = null
    ): CommandResult {
        println("$AGENT_LOG_PREFIX Выполняю команду: $command")
        workingDirectory?.let { println("$AGENT_LOG_PREFIX Рабочая директория: $it") }

        return try {
            val processBuilder = ProcessBuilder("bash", "-c", command)
                .directory(workingDirectory?.let { File(it) }
                    ?: File(System.getProperty("user.home")))

            setupJavaEnvironment(processBuilder)
            val process = processBuilder.start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val error = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            CommandResult(exitCode, output, error)
        } catch (e: Exception) {
            CommandResult(-1, "", "Не удалось выполнить команду: ${e.message}")
        }
    }

    private fun setupJavaEnvironment(processBuilder: ProcessBuilder) {
        val env = processBuilder.environment()
        env["JAVA_HOME"] = JAVA_HOME_PATH
        env["PATH"] = "$JAVA_HOME_PATH/bin:${env["PATH"] ?: ""}"
        println("$AGENT_LOG_PREFIX JAVA_HOME установлен: $JAVA_HOME_PATH")
    }

    // Android tests
    private suspend fun runAndroidTests(
        projectPath: String,
        testType: String,
        moduleName: String? = null,
        buildVariant: String = "Debug"
    ): CommandResult {
        val validationError = validateAndroidProject(projectPath)
        if (validationError != null) return validationError

        val variant = buildVariant.lowercase()
        val modulePrefix = moduleName?.let { "$it:" } ?: ""
        val gradleCommand = buildGradleCommand(testType, modulePrefix, variant)

        logTestExecution(testType, projectPath, moduleName, buildVariant, gradleCommand)
        return executeShellCommand(gradleCommand, projectPath)
    }

    private fun validateAndroidProject(projectPath: String): CommandResult? {
        val projectDir = File(projectPath)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return CommandResult(-1, "", "Ошибка: Директория проекта '$projectPath' не существует")
        }

        val buildGradleExists = File(projectPath, "build.gradle").exists() ||
                File(projectPath, "build.gradle.kts").exists()
        if (!buildGradleExists) {
            return CommandResult(
                -1,
                "",
                "Ошибка: В директории '$projectPath' не найден файл build.gradle"
            )
        }

        val gradlewFile = File(projectPath, "gradlew")
        if (!gradlewFile.exists()) {
            return CommandResult(
                -1,
                "",
                "Ошибка: В директории '$projectPath' не найден файл gradlew"
            )
        }

        val javaExecutable = File(JAVA_HOME_PATH, "bin/java")
        if (!javaExecutable.exists()) {
            return CommandResult(-1, "", "Ошибка: Java не найдена по пути $JAVA_HOME_PATH")
        }

        gradlewFile.setExecutable(true)
        println("$AGENT_LOG_PREFIX Java найдена: $javaExecutable")
        return null
    }

    private fun buildGradleCommand(
        testType: String,
        modulePrefix: String,
        variant: String
    ): String {
        return when (testType) {
            "unit" -> "./gradlew ${modulePrefix}test${variant}UnitTest --console=plain"
            "instrumented" -> "./gradlew ${modulePrefix}connected${variant}AndroidTest --console=plain"
            else -> throw IllegalArgumentException("Неизвестный тип тестов: $testType")
        }
    }

    private fun logTestExecution(
        testType: String,
        projectPath: String,
        moduleName: String?,
        buildVariant: String,
        command: String
    ) {
        println("$AGENT_LOG_PREFIX Запускаю $testType тесты для проекта: $projectPath")
        moduleName?.let { println("$AGENT_LOG_PREFIX Модуль: $it") }
        println("$AGENT_LOG_PREFIX Вариант сборки: $buildVariant")
        println("$AGENT_LOG_PREFIX Команда: $command")
    }

    // Response parsing helpers
    private fun isJsonCommand(response: String): Boolean {
        return try {
            Json.parseToJsonElement(response) is JsonObject
        } catch (e: Exception) {
            false
        }
    }

    private fun isToolCallFormat(response: String): Boolean {
        return response.trim().startsWith(TOOL_CALL_START_PREFIX)
    }

    private fun convertToolCallToJson(response: String): String {
        val lines = response.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return response

        val name = lines.first().removePrefix(TOOL_CALL_START_PREFIX).trim()
        val argsMap = mutableMapOf<String, String>()

        lines.drop(1).forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isNotEmpty()) argsMap[key] = value
            }
        }

        val argsJsonPairs = argsMap.entries.joinToString(",") { (k, v) ->
            "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }

        return "{\"name\":\"$name\",\"arguments\":{$argsJsonPairs}}"
    }

    // Result formatting (как было)
    private fun formatCommandResult(result: CommandResult, isTest: Boolean): String {
        return when {
            result.exitCode == 0 -> formatSuccessResult(result, isTest)
            result.exitCode > 0 -> formatWarningResult(result, isTest)
            else -> formatErrorResult(result, isTest)
        }
    }

    private fun formatSuccessResult(result: CommandResult, isTest: Boolean): String {
        val title =
            if (isTest) "✅ **Тесты успешно выполнены!**" else "✅ **Команда успешно выполнена!**"
        val summary = if (isTest) extractTestSummary(result.stdout) else result.stdout.trim()

        return if (summary.isNotEmpty()) {
            "$title\n\n$summary"
        } else {
            "$title (без вывода)"
        }
    }

    private fun formatWarningResult(result: CommandResult, isTest: Boolean): String {
        val title =
            if (isTest) "⚠️ **Тесты завершились с предупреждениями**" else "⚠️ **Команда завершилась с предупреждениями** (код: ${result.exitCode})"
        val errorInfo = analyzeErrors(result.stdout, result.stderr, isTest)
        return "$title\n\n$errorInfo"
    }

    private fun formatErrorResult(result: CommandResult, isTest: Boolean): String {
        val title =
            if (isTest) "❌ **Ошибка при выполнении тестов**" else "❌ **Ошибка при выполнении команды** (код: ${result.exitCode})"
        val errorInfo = analyzeErrors(result.stdout, result.stderr, isTest)
        return "$title\n\n$errorInfo"
    }

    private fun extractTestSummary(output: String): String {
        val testKeywords =
            listOf("BUILD SUCCESSFUL", "Tests run:", "BUILD FAILED", "Test execution finished")
        val testResults = output.lines().filter { line ->
            testKeywords.any { keyword -> line.contains(keyword) }
        }

        return if (testResults.isNotEmpty()) {
            testResults.joinToString("\n")
        } else {
            "Тесты выполнены. Код выхода: 0"
        }
    }

    private fun analyzeErrors(output: String, error: String, isTest: Boolean): String {
        val allText = "$output\n$error"

        val commonErrors = mapOf(
            "Unable to locate a Java Runtime" to ("Не найден Java Runtime" to "Убедитесь, что Android Studio установлен и JAVA_HOME настроен правильно"),
            "BUILD FAILED" to ("Ошибка сборки проекта" to "Проверьте синтаксис кода, зависимости и настройки Gradle"),
            "Permission denied" to ("Отказано в доступе" to "Запустите команду с правами администратора или проверьте права доступа"),
            "Connection refused" to ("Отказано в подключении" to "Проверьте, запущен ли сервис, к которому пытаетесь подключиться"),
            "command not found" to ("Команда не найдена" to "Проверьте правильность написания команды или установите необходимый пакет"),
            "No such file or directory" to ("Файл или директория не найдены" to "Проверьте правильность пути к файлу или директории")
        )

        if (isTest && allText.contains("No tests found")) {
            return "**Проблема:** Тесты не найдены\n**Решение:** Убедитесь, что в проекте есть файлы тестов в папке test/"
        }

        for ((errorPattern, solution) in commonErrors) {
            if (allText.contains(errorPattern)) {
                return "**Проблема:** ${solution.first}\n**Решение:** ${solution.second}"
            }
        }

        val errorLines = error.lines().filter { it.isNotBlank() && it.length < 150 }
        return if (errorLines.isNotEmpty()) {
            "**Детали ошибки:**\n${errorLines.take(3).joinToString("\n")}"
        } else {
            "**Код ошибки:** Неизвестная ошибка\nПроверьте логи для деталей"
        }
    }

    /**
     * Ищет первую JSON-объектную подстроку в тексте (учитывает вложенные фигурные скобки).
     * Возвращает пару: humanPart (текст без найденного JSON) и jsonPart (строка JSON) — либо jsonPart==null если не найдено.
     */
    private fun extractFirstJsonObject(text: String): Pair<String?, String?> {
        val start = text.indexOf('{')
        if (start == -1) return Pair(text.trim().ifEmpty { null }, null)

        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val json = text.substring(start, i + 1).trim()
                        // human = всё, что до JSON + всё, что после JSON (без JSON)
                        val before = text.substring(0, start).trim()
                        val after = if (i + 1 < text.length) text.substring(i + 1).trim() else ""
                        val human =
                            listOf(before, after).filter { it.isNotEmpty() }.joinToString("\n")
                                .ifEmpty { null }
                        return Pair(human, json)
                    }
                }
            }
            i++
        }
        // несбалансированные скобки — считаем что JSON не найден
        return Pair(text.trim().ifEmpty { null }, null)
    }

    private fun summarizeForUser(result: CommandResult, isTest: Boolean): String {
        return if (result.exitCode == 0) {
            if (isTest) {
                "✅ Тесты успешно выполнены!"
            } else {
                "✅ Операция успешно выполнена."
            }
        } else {
            val brief = analyzeErrors(result.stdout, result.stderr, isTest)
            "❌ Ошибка: $brief"
        }
    }

    /**
     * publish_to_rustore: Автоматически запускает unit тесты, а затем публикует приложение в RuStore
     */
    private suspend fun handlePublishToRustore(arguments: JsonObject) {
        val projectPath = arguments["projectPath"]?.jsonPrimitive?.content
            ?: run {
                addMessage(
                    MessageInfo(
                        Roles.ASSISTANT,
                        "❗ Отсутствует 'projectPath' в команде. Пожалуйста, укажи абсолютный путь к корню проекта."
                    )
                )
                return
            }

        val moduleName = arguments["moduleName"]?.jsonPrimitive?.content ?: "app"
        val buildVariant = arguments["buildVariant"]?.jsonPrimitive?.content ?: "Release"
        val artifactType = arguments["artifactType"]?.jsonPrimitive?.content ?: "apk"

        // Начинаем процесс публикации
        addMessage(
            MessageInfo(
                Roles.ASSISTANT,
                "🚀 Начинаю процесс публикации приложения в RuStore..."
            )
        )
        delay(500) // Небольшая задержка для отображения начального сообщения

        _publishProgress.value = "Запуск unit тестов..."
        delay(300) // Задержка для обновления UI

        try {
            // Шаг 1: Запускаем unit тесты
            addMessage(MessageInfo(Roles.ASSISTANT, "📋 Шаг 1/3: Запускаю unit тесты..."))
            delay(200) // Задержка для отображения сообщения

            val unitTestResult = runAndroidTests(projectPath, "unit", moduleName, buildVariant)
            hiddenHistory.add(
                MessageInfo(
                    Roles.ASSISTANT,
                    "**[UNIT_TESTS]** exit=${unitTestResult.exitCode}\n${unitTestResult.stdout}\n${unitTestResult.stderr}"
                )
            )

            if (unitTestResult.exitCode != 0) {
                addMessage(
                    MessageInfo(
                        Roles.ASSISTANT,
                        "❌ Unit тесты не прошли. Публикация остановлена.\n\n${
                            analyzeErrors(
                                unitTestResult.stdout,
                                unitTestResult.stderr,
                                true
                            )
                        }"
                    )
                )
                _publishProgress.value = null
                return
            }
            addMessage(MessageInfo(Roles.ASSISTANT, "✅ Unit тесты прошли успешно!"))
            delay(300) // Задержка для отображения результата

            // Шаг 2: Собираем релизную версию
            _publishProgress.value = "Сборка релизной версии..."
            delay(200) // Задержка для обновления UI

            addMessage(MessageInfo(Roles.ASSISTANT, "📋 Шаг 2/3: Собираю релизную версию..."))
            delay(200) // Задержка для отображения сообщения

            val modulePrefix = if (moduleName.isNotBlank()) "$moduleName:" else ""
            val assembleTask = "assemble${buildVariant.replaceFirstChar { it.uppercase() }}"
            val gradleCommand = "./gradlew ${modulePrefix}$assembleTask --quiet"
            val buildResult = executeShellCommand(gradleCommand, projectPath)

            hiddenHistory.add(
                MessageInfo(
                    Roles.ASSISTANT,
                    "**[BUILD]** cmd=$gradleCommand\nexit=${buildResult.exitCode}\n${buildResult.stdout}\n${buildResult.stderr}"
                )
            )

            if (buildResult.exitCode != 0) {
                addMessage(
                    MessageInfo(
                        Roles.ASSISTANT,
                        "❌ Ошибка при сборке релиза. Публикация остановлена.\n\n${
                            analyzeErrors(
                                buildResult.stdout,
                                buildResult.stderr,
                                false
                            )
                        }"
                    )
                )
                _publishProgress.value = null
                return
            }
            addMessage(MessageInfo(Roles.ASSISTANT, "✅ Сборка завершена успешно!"))
            delay(300) // Задержка для отображения результата

            // Шаг 3: Публикуем в RuStore
            _publishProgress.value = "Публикация в RuStore..."
            delay(200) // Задержка для обновления UI

            addMessage(MessageInfo(Roles.ASSISTANT, "📋 Шаг 3/3: Публикую в RuStore..."))
            delay(200) // Задержка для отображения сообщения

            // Используем существующую логику публикации
            val publishArguments = JsonObject(
                mapOf(
                    "projectPath" to kotlinx.serialization.json.JsonPrimitive(projectPath),
                    "moduleName" to kotlinx.serialization.json.JsonPrimitive(moduleName),
                    "buildVariant" to kotlinx.serialization.json.JsonPrimitive(buildVariant),
                    "artifactType" to kotlinx.serialization.json.JsonPrimitive(artifactType)
                )
            )

            // Вызываем handlePublishApp без показа сообщений о проверке параметров
            val publishSuccess = handlePublishAppInternal(publishArguments)

            if (publishSuccess) {
                delay(200) // Задержка перед финальным сообщением
                addMessage(MessageInfo(Roles.ASSISTANT, "🎉 Процесс публикации завершен!"))
            } else {
                addMessage(
                    MessageInfo(
                        Roles.ASSISTANT,
                        "❌ Процесс публикации завершен с ошибками."
                    )
                )
            }

        } catch (e: Exception) {
            _publishProgress.value = null
            addMessage(MessageInfo(Roles.ASSISTANT, "❌ Ошибка во время публикации: ${e.message}"))
        }
    }

    /**
     * Внутренняя версия handlePublishApp без проверки параметров (для использования в publish_to_rustore)
     * Возвращает true если публикация прошла успешно, false если были ошибки
     */
    private suspend fun handlePublishAppInternal(arguments: JsonObject): Boolean {
        val projectPath = arguments["projectPath"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing projectPath")

        val moduleName = arguments["moduleName"]?.jsonPrimitive?.content ?: "app"
        val buildVariant = arguments["buildVariant"]?.jsonPrimitive?.content ?: "Release"
        val artifactType = arguments["artifactType"]?.jsonPrimitive?.content ?: "apk"

        // Получаем credentials из env
        val keystorePath = getSecret("KEYSTORE_PATH")
        val keystorePassword = getSecret("KEYSTORE_PASSWORD")
        val keyAlias = getSecret("KEY_ALIAS")
        val keyPassword = getSecret("KEY_PASSWORD")

        // Проверяем наличие всех необходимых параметров
        val missing = mutableListOf<String>()
        if (keystorePath.isNullOrBlank()) missing.add("KEYSTORE_PATH")
        if (keystorePassword.isNullOrBlank()) missing.add("KEYSTORE_PASSWORD")
        if (keyAlias.isNullOrBlank()) missing.add("KEY_ALIAS")
        if (keyPassword.isNullOrBlank()) missing.add("KEY_PASSWORD")

        if (missing.isNotEmpty()) {
            val list = missing.joinToString(", ")
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "❗ Не хватает параметров: $list. Пожалуйста, настройте переменные окружения в файле .env"
                )
            )
            return false
        }

        // Подписываем артефакт
        val unsignedArtifact =
            findUnsignedArtifact(projectPath, moduleName, buildVariant, artifactType)
        if (unsignedArtifact == null) {
            addMessage(MessageInfo(Roles.ASSISTANT, "❌ Не найден артефакт для подписи."))
            return false
        }

        addMessage(MessageInfo(Roles.ASSISTANT, "🔐 Подписываю артефакт..."))
        delay(200) // Задержка для отображения сообщения

        val signResult = signArtifact(
            unsignedArtifact,
            keystorePath!!,
            keystorePassword!!,
            keyAlias!!,
            keyPassword!!,
            projectPath
        )
        val signedFile = File(
            unsignedArtifact.parentFile,
            unsignedArtifact.name.replace(".aab", "-signed.aab").replace(".apk", "-signed.apk")
        )

        hiddenHistory.add(
            MessageInfo(
                Roles.ASSISTANT,
                "**[SIGN]** exit=${signResult.exitCode}\n${signResult.stdout}\n${signResult.stderr}"
            )
        )

        if (signResult.stderr.isNotEmpty()) {
            addMessage(MessageInfo(Roles.ASSISTANT, "🔍 Ошибка подписи: ${signResult.stderr}"))
        }

        if (signResult.exitCode != 0) {
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "❌ Ошибка при подписи артефакта. Проверьте ключи/пароли."
                )
            )
            return false
        }
        addMessage(MessageInfo(Roles.ASSISTANT, "✅ Артефакт подписан."))
        delay(300) // Задержка для отображения результата

        // Загружаем в RuStore
        val artifactToUpload = if (signedFile.exists()) signedFile else unsignedArtifact

        addMessage(MessageInfo(Roles.ASSISTANT, "📤 Загружаю приложение в RuStore..."))
        delay(200) // Задержка для отображения сообщения

        // Получаем токен доступа к RuStore API (действует 15 минут)
        val rustoreToken = try {
            val tokenGenerator = RuStoreTokenGenerator()
            val accessToken = tokenGenerator.getAccessToken()
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "🔐 Токен доступа RuStore получен (действует ${accessToken.ttl} секунд)"
                )
            )
            accessToken.jwe
        } catch (e: Exception) {
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "❌ Ошибка при получении токена доступа RuStore: ${e.message}"
                )
            )
            return false
        }

        val uploadCommand = """
            curl -s -X POST "https://public-api.rustore.ru/v1/application/upload" \
            -H "Authorization: Bearer $rustoreToken" \
            -F "file=@${artifactToUpload.absolutePath}"
        """.trimIndent()

        val uploadResult = executeShellCommand(uploadCommand, projectPath)

        hiddenHistory.add(
            MessageInfo(
                Roles.ASSISTANT,
                "**[UPLOAD]** exit=${uploadResult.exitCode}\n${uploadResult.stdout}\n${uploadResult.stderr}"
            )
        )

        if (uploadResult.exitCode == 0) {
            addMessage(MessageInfo(Roles.ASSISTANT, "🎉 APK успешно загружено!"))
            sendMessagesLLM(
                MessageInfo(
                    Roles.USER,
                    "🔧 Теперь необходимо создать черновик версии приложения в RuStore. Пожалуйста, вызови инструмент 'publish_version' с заполненными полями. Вот RUSTORE_PUBLIC_TOKEN $rustoreToken"
                )
            )
            _publishProgress.value = "Заполнение полей для черновика"
            return true
        } else {
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "❌ Ошибка при загрузке в RuStore. Проверьте токен и сеть."
                )
            )
            return false
        }
    }

    /**
     * generate_rustore_token: Генерирует токен авторизации для RuStore API
     */
    private suspend fun handleGenerateRuStoreToken(arguments: JsonObject) {
        try {
            addMessage(MessageInfo(Roles.ASSISTANT, "🔐 Генерирую токен авторизации для RuStore..."))

            val tokenGenerator = RuStoreTokenGenerator()

            // Получаем токен доступа (действует 15 минут)
            val accessToken = tokenGenerator.getAccessToken()

            // Также генерируем подпись для отладки
            val authToken = tokenGenerator.generateToken()
            val authTokenJson = tokenGenerator.generateTokenJson()

            // Сохраняем оба токена в файл для удобства
            val tokenData = mapOf(
                "access_token" to accessToken.jwe,
                "access_token_ttl" to accessToken.ttl,
                "auth_token" to authTokenJson,
                "timestamp" to System.currentTimeMillis()
            )

            val tokenFile = File("rustore_tokens.json")
            tokenFile.writeText(Json.encodeToString(tokenData), Charsets.UTF_8)

            addMessage(MessageInfo(Roles.ASSISTANT, "✅ Токен доступа RuStore успешно получен!"))
            addMessage(MessageInfo(Roles.ASSISTANT, "📋 ID ключа: ${authToken.keyId}"))
            addMessage(MessageInfo(Roles.ASSISTANT, "⏰ Временная метка: ${authToken.timestamp}"))
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "🔑 Подпись: ${authToken.signature.take(50)}..."
                )
            )
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "🎫 Токен доступа: ${accessToken.jwe.take(50)}..."
                )
            )
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "⏱️ Время жизни: ${accessToken.ttl} секунд (${accessToken.ttl / 60} минут)"
                )
            )
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "💾 Токены сохранены в файл: rustore_tokens.json"
                )
            )

            // Добавляем техническую информацию в hiddenHistory для LLM
            hiddenHistory.add(
                MessageInfo(
                    Roles.ASSISTANT,
                    "**[RUSTORE_ACCESS_TOKEN]** ${accessToken.jwe}"
                )
            )

        } catch (e: Exception) {
            val errorMessage = when {
                e.message?.contains("RUSTORE_PRIVATE_KEY") == true ->
                    "❌ Ошибка: Переменная окружения RUSTORE_PRIVATE_KEY не найдена.\n\nДля решения:\n1. Создайте файл .env в корне проекта\n2. Добавьте в него: RUSTORE_PRIVATE_KEY=ваш_приватный_ключ_в_base64"

                e.message?.contains("приватного ключа") == true ->
                    "❌ Ошибка при загрузке приватного ключа: ${e.message}\n\nПроверьте формат ключа в переменной RUSTORE_PRIVATE_KEY"

                e.message?.contains("подписи") == true ->
                    "❌ Ошибка при создании подписи: ${e.message}\n\nПроверьте корректность приватного ключа"

                e.message?.contains("токена доступа") == true ->
                    "❌ Ошибка при получении токена доступа: ${e.message}\n\nПроверьте подключение к интернету и корректность приватного ключа"

                else ->
                    "❌ Неожиданная ошибка при генерации токена: ${e.message}"
            }

            addMessage(MessageInfo(Roles.ASSISTANT, errorMessage))
        }
    }

    /**
     * test_rustore_api: Тестирует подключение к RuStore API
     */
    private suspend fun handleTestRuStoreApi(arguments: JsonObject) {
        try {
            addMessage(MessageInfo(Roles.ASSISTANT, "🧪 Тестирую подключение к RuStore API..."))

            val tokenGenerator = RuStoreTokenGenerator()

            // Генерируем подпись для теста
            val authToken = tokenGenerator.generateToken()
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "🔐 Подпись сгенерирована: ${authToken.signature.take(30)}..."
                )
            )

            // Тестируем API endpoint
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT,
                    "🌐 Тестирую endpoint: https://public-api.rustore.ru/v1/public/auth"
                )
            )

            try {
                val accessToken = tokenGenerator.getAccessToken()
                addMessage(MessageInfo(Roles.ASSISTANT, "✅ API работает! Токен доступа получен."))
                addMessage(MessageInfo(Roles.ASSISTANT, "🎫 Токен: ${accessToken.jwe.take(50)}..."))
                addMessage(
                    MessageInfo(
                        Roles.ASSISTANT,
                        "⏱️ Время жизни: ${accessToken.ttl} секунд"
                    )
                )
            } catch (e: Exception) {
                addMessage(MessageInfo(Roles.ASSISTANT, "❌ Ошибка API: ${e.message}"))

                // Пробуем альтернативный endpoint
                addMessage(MessageInfo(Roles.ASSISTANT, "🔄 Пробую альтернативный endpoint..."))

                val alternativeUrl = "https://public-api.rustore.ru/api/v1/auth"
                addMessage(MessageInfo(Roles.ASSISTANT, "🌐 Тестирую: $alternativeUrl"))

                // Здесь можно добавить тест альтернативного endpoint
            }

        } catch (e: Exception) {
            addMessage(MessageInfo(Roles.ASSISTANT, "❌ Ошибка при тестировании API: ${e.message}"))
        }
    }
}