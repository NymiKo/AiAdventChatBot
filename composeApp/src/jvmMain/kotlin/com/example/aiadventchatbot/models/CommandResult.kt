package com.example.aiadventchatbot.models

import kotlinx.serialization.Serializable

@Serializable
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    fun toFormattedString(): String {
        return """
            |**Exit Code:** $exitCode
            |**Standard Output:**
            |```
            |$stdout
            |```
            |**Standard Error:**
            |```
            |$stderr
            |```
        """.trimMargin()
    }
}
