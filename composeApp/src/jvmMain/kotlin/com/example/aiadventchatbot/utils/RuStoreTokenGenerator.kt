package com.example.aiadventchatbot.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date

/**
 * Генератор токена авторизации для RuStore API
 */
class RuStoreTokenGenerator {

    companion object {
        private const val KEY_ID = "2351024845"
        private const val ALGORITHM = "RSA"
        private const val SIGNATURE_ALGORITHM = "SHA512withRSA"
    }

    /**
     * Генерирует токен авторизации для RuStore
     *
     * @return RuStoreToken объект с подписью
     * @throws IllegalStateException если приватный ключ не найден или произошла ошибка подписи
     */
    fun generateToken(): RuStoreToken {
        val privateKey = getPrivateKey()
        val timestamp = generateTimestamp()
        val messageToSign = KEY_ID + timestamp

        println("Message to sign: $messageToSign")

        val signature = signMessage(messageToSign, privateKey)

        return RuStoreToken(
            keyId = KEY_ID,
            timestamp = timestamp,
            signature = signature
        )
    }

    /**
     * Получает приватный ключ из переменных окружения
     */
    private fun getPrivateKey(): PrivateKey {
        val privateKeyBase64 = getSecretOrThrow("RUSTORE_PRIVATE_KEY")

        return try {
            val keyBytes = Base64.getDecoder().decode(privateKeyBase64)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance(ALGORITHM)
            keyFactory.generatePrivate(keySpec)
        } catch (e: Exception) {
            throw IllegalStateException("Ошибка при загрузке приватного ключа: ${e.message}")
        }
    }

    /**
     * Генерирует временную метку в формате с часовым поясом (как в Java документации)
     */
    private fun generateTimestamp(): String {
        val now = Date()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        return dateFormat.format(now)
    }

    /**
     * Подписывает сообщение с помощью приватного ключа
     */
    private fun signMessage(message: String, privateKey: PrivateKey): String {
        return try {
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            signature.initSign(privateKey)
            signature.update(message.toByteArray(Charsets.UTF_8))

            val signatureBytes = signature.sign()
            Base64.getEncoder().encodeToString(signatureBytes)
        } catch (e: Exception) {
            throw IllegalStateException("Ошибка при создании подписи: ${e.message}")
        }
    }

    /**
     * Генерирует JSON строку токена
     */
    fun generateTokenJson(): String {
        val token = generateToken()
        return Json.encodeToString(token)
    }

    /**
     * Получает токен доступа к RuStore API
     *
     * @return RuStoreAccessToken объект с токеном доступа
     * @throws IllegalStateException если не удалось получить токен
     */
    fun getAccessToken(): RuStoreAccessToken {
        val authToken = generateToken()
        val authTokenJson = generateTokenJson()

        // Отправляем запрос на получение токена доступа
        val response = sendAuthRequest(authTokenJson)

        return if (response.code == "OK") {
            response.body
        } else {
            throw IllegalStateException("Ошибка получения токена доступа: ${response.message}")
        }
    }

    /**
     * Отправляет запрос на получение токена доступа
     */
    private fun sendAuthRequest(authTokenJson: String): RuStoreAuthResponse {
        val url = "https://public-api.rustore.ru/public/auth"

        return try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 30000 // 30 секунд
            connection.readTimeout = 30000 // 30 секунд

            // Отправляем тело запроса
            connection.outputStream.use { os ->
                os.write(authTokenJson.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val response = if (responseCode == 200) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream.bufferedReader().readText()
            }

            connection.disconnect()

            // Добавляем отладочную информацию
            println("DEBUG: Response code: $responseCode")
            println("DEBUG: Response body: ${response.take(500)}")

            // Проверяем, что ответ начинается с '{' (JSON)
            if (!response.trim().startsWith("{")) {
                throw IllegalStateException(
                    "Получен неверный ответ от сервера. Ожидался JSON, получено: ${
                        response.take(
                            200
                        )
                    }"
                )
            }

            // Парсим ответ
            Json.decodeFromString<RuStoreAuthResponse>(response)

        } catch (e: Exception) {
            throw IllegalStateException("Ошибка при отправке запроса: ${e.message}")
        }
    }
}

/**
 * Модель токена авторизации RuStore
 */
@Serializable
data class RuStoreToken(
    val keyId: String,
    val timestamp: String,
    val signature: String
)

/**
 * Модель ответа на запрос авторизации
 */
@Serializable
data class RuStoreAuthResponse(
    val code: String,
    val message: String?,
    val body: RuStoreAccessToken,
    val timestamp: String
)

/**
 * Модель токена доступа к RuStore API
 */
@Serializable
data class RuStoreAccessToken(
    val jwe: String,
    val ttl: Int
)
