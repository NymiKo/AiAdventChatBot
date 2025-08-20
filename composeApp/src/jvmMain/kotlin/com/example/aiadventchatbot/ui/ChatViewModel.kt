package com.example.aiadventchatbot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiadventchatbot.domain.ChatPrompts
import com.example.aiadventchatbot.domain.ChatRepository
import com.example.aiadventchatbot.models.CommandResult
import com.example.aiadventchatbot.models.MessageInfo
import com.example.aiadventchatbot.models.Roles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

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

    // Constants
    private companion object {
        const val JAVA_HOME_PATH = "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
        const val TOOL_CALL_START_PREFIX = "[TOOL_CALL_START]"
        const val AGENT_LOG_PREFIX = "🤖 [AGENT]"
    }

    fun initChat() {
        _messages.value = listOf(ChatPrompts.systemPrompt)
    }

    fun onUserInputChanged(newValue: String) {
        _userInput.update { newValue }
    }

    fun sendMessage() {
        if (_userInput.value.isBlank() || _isLoading.value) return

        viewModelScope.launch {
            _isLoading.value = true
            addMessage(MessageInfo(Roles.VISIBLE_USER, _userInput.value))

            try {
                val gptResponse = fetchAssistantResponse(MessageInfo(Roles.USER, _userInput.value))
                println("$AGENT_LOG_PREFIX GPT Response: $gptResponse")

                val normalizedResponse = if (isToolCallFormat(gptResponse)) {
                    convertToolCallToJson(gptResponse)
                } else gptResponse

                when {
                    isJsonCommand(normalizedResponse) -> handleMcpCommand(normalizedResponse)
                    else -> addMessage(MessageInfo(Roles.ASSISTANT, normalizedResponse))
                }
            } catch (e: Exception) {
                addMessage(MessageInfo(Roles.ASSISTANT, "Ошибка: ${e.message}"))
            } finally {
                _isLoading.value = false
                _userInput.update { "" }
            }
        }
    }

    // Private helpers
    private fun addMessage(message: MessageInfo) {
        _messages.value = _messages.value + message
    }

    private suspend fun fetchAssistantResponse(messageInfo: MessageInfo): String {
        return runCatching {
            repository.sendMessage(_messages.value + messageInfo)
        }.getOrElse { "Ошибка: ${it.message ?: "Unknown error"}" }
    }

    // Command handling
    private suspend fun handleMcpCommand(jsonResponse: String) {
        val command = Json.parseToJsonElement(jsonResponse).jsonObject
        val name = command["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'name' in tool command")
        val arguments = command["arguments"]?.jsonObject
            ?: throw IllegalArgumentException("Missing 'arguments' in tool command")

        when (name) {
            "execute_shell_command" -> handleShellCommand(arguments)
            "run_android_tests" -> handleAndroidTests(arguments)
            "generate_tests_for_file" -> handleGenerateTests(arguments)
            else -> addMessage(MessageInfo(Roles.ASSISTANT.role, "Неизвестная команда: $name"))
        }
    }

    private suspend fun handleShellCommand(arguments: JsonObject) {
        val commandText = arguments["command"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Отсутствует 'command'")
        val description = arguments["description"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Отсутствует 'description'")

        addMessage(
            MessageInfo(
                Roles.ASSISTANT.role,
                "🔧 Выполняю команду: $commandText\nПричина: $description"
            )
        )

        val result = executeShellCommand(commandText)
        val formattedResult = formatCommandResult(result, isTest = false)
        addMessage(MessageInfo(Roles.ASSISTANT.role, formattedResult))
    }

    private suspend fun handleAndroidTests(arguments: JsonObject) {
        val projectPath = arguments["projectPath"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Отсутствует 'projectPath'")
        val testType = arguments["testType"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Отсутствует 'testType'")
        val moduleName = arguments["moduleName"]?.jsonPrimitive?.content
        val buildVariant = arguments["buildVariant"]?.jsonPrimitive?.content ?: "Debug"

        val moduleInfo = if (moduleName != null) " (модуль: $moduleName)" else ""
        addMessage(
            MessageInfo(
                Roles.ASSISTANT.role,
                "🚀 Запускаю $testType тесты для проекта: $projectPath$moduleInfo в режиме $buildVariant..."
            )
        )

        val result = runAndroidTests(projectPath, testType, moduleName, buildVariant)
        val formattedResult = formatCommandResult(result, isTest = true)
        addMessage(MessageInfo(Roles.ASSISTANT.role, formattedResult))
    }

    private suspend fun handleGenerateTests(arguments: JsonObject) {
        val filePath = arguments["filePath"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Отсутствует 'filePath'")
        val testType = arguments["testType"]?.jsonPrimitive?.content ?: "unit"
        val autoRun = arguments["autoRun"]?.jsonPrimitive?.booleanOrNull ?: false

        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            addMessage(MessageInfo(Roles.ASSISTANT.role, "❌ Файл '$filePath' не найден"))
            return
        }

        val fileContent = file.readText()

        addMessage(
            MessageInfo(
                Roles.ASSISTANT.role,
                "🧪 Генерация $testType тестов для файла: $filePath..."
            )
        )

        // Промпт для LLM
        val prompt = """
        Сгенерируй $testType тесты на Kotlin для следующего кода.
        КРИТИЧЕСКИ ВАЖНО:
        1. Добавь КОРРЕКТНЫЙ package для тестового класса (на основе пути исходного файла)
        2. Добавь ВСЕ необходимые импорты, включая:
           - Импорт тестируемого класса (например: import com.example.Calculator)
           - Импорты JUnit (org.junit.Test, org.junit.Assert.*)
           - Импорты для всех других классов и типов, используемых в тестах
        3. Не пропускай ни один необходимый импорт
        
        Проверь, что в сгенерированном коде есть package и все импорты.

        Код:
        ```
        $fileContent
        ```
    """.trimIndent()

        val generatedTests = runCatching {
            repository.sendMessage(_messages.value + MessageInfo(Roles.USER, prompt))
        }.getOrElse { "Ошибка генерации тестов: ${it.message}" }

        // Сохраняем тесты в проект
        val testFilePath: String
        try {
            testFilePath = getTestFilePath(filePath, testType)
            val testFile = File(testFilePath)

            testFile.parentFile?.mkdirs()
            testFile.writeText(generatedTests)

            addMessage(
                MessageInfo(
                    Roles.ASSISTANT.role,
                    "✅ Тесты сохранены в файл: $testFilePath"
                )
            )
        } catch (e: Exception) {
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT.role,
                    "⚠️ Не удалось сохранить тесты: ${e.message}"
                )
            )
            return
        }

        // Если включён autoRun → сразу запускаем тесты
        if (autoRun) {
            addMessage(
                MessageInfo(
                    Roles.ASSISTANT.role,
                    "🚀 Запуск сгенерированных $testType тестов..."
                )
            )

            val projectRoot = File(filePath).walkUpToProjectRoot()
            val moduleName = detectModuleName(testFilePath, projectRoot)

            val result = runAndroidTests(
                projectPath = projectRoot.absolutePath,
                testType = testType,
                moduleName = moduleName,
                buildVariant = "Debug"
            )

            val formattedResult = formatCommandResult(result, isTest = true)
            addMessage(MessageInfo(Roles.ASSISTANT.role, formattedResult))
        }
    }

    private fun detectModuleName(testFilePath: String, projectRoot: File): String? {
        val relative = File(testFilePath).relativeTo(projectRoot).path
        return relative.split(File.separator).firstOrNull()
    }

    private fun getTestFilePath(sourceFilePath: String, testType: String): String {
        val file = File(sourceFilePath)
        val projectRoot = file.walkUpToProjectRoot() // ищем build.gradle вверх по дереву

        val relativePath = file.relativeTo(projectRoot).path
            .removePrefix("src/main/java/")
            .removeSuffix(".kt") + "Test.kt"

        val testFolder = when (testType) {
            "unit" -> "src/test/java"
            "instrumented" -> "src/androidTest/java"
            else -> "src/test/java"
        }

        return File(projectRoot, "$testFolder/$relativePath").path
    }

    private fun File.walkUpToProjectRoot(): File {
        var current: File? = this
        while (current != null) {
            if (File(current, "build.gradle").exists() || File(
                    current,
                    "build.gradle.kts"
                ).exists()
            ) {
                return current
            }
            current = current.parentFile
        }
        throw IllegalStateException("Не удалось найти корень проекта (build.gradle)")
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
        // Валидация проекта
        val validationError = validateAndroidProject(projectPath)
        if (validationError != null) return validationError

        // Подготовка команды
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
            "unit" -> "./gradlew ${modulePrefix}test${variant}UnitTest --quiet"
            "instrumented" -> "./gradlew ${modulePrefix}connected${variant}AndroidTest --quiet"
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

    // Response parsing
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

    // Result formatting
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
            "Тесты выполнены без ошибок. Код выхода: 0"
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
}