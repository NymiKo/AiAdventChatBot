package com.example.aiadventchatbot.utils

import java.io.File

private val env: Map<String, String> by lazy {
    // Пытаемся найти .env файл в разных местах
    val possiblePaths = listOf(
        ".env",                    // Корень проекта
        "../.env",                 // Родительская директория
        "composeApp/.env",         // В папке composeApp
        "env.example",             // Пример файла
        "composeApp/env.example",  // Пример файла в composeApp
        System.getProperty("user.dir") + "/.env"  // Абсолютный путь
    )

    for (path in possiblePaths) {
        val file = File(path)
        if (file.exists() && file.isFile) {
            println("Found environment file at: ${file.absolutePath}")
            return@lazy file.readLines()
                .filter { it.contains("=") && !it.trim().startsWith("#") }
                .associate {
                    val (k, v) = it.split("=", limit = 2)
                    k.trim() to v.trim().removeSurrounding("\"")
                }
        }
    }

    println("Warning: Environment file (.env or env.example) not found in any of the expected locations: $possiblePaths")
    println("Current working directory: ${System.getProperty("user.dir")}")
    println("Please create a .env file with your credentials or copy env.example to .env")
    emptyMap<String, String>()
}

fun getSecret(key: String): String? {
    val value = env[key] ?: System.getenv(key)
    if (value == null) {
        println("Warning: Environment variable '$key' not found in .env file or system environment")
    }
    return value
}

fun getSecretOrThrow(key: String): String {
    return getSecret(key)
        ?: throw IllegalStateException("Required environment variable '$key' not found. Please check your .env file or system environment variables.")
}
