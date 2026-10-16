package com.zhousl.aether.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ProviderConnectionTestResult(
    val success: Boolean,
    val message: String,
)

class ProviderConnectionTester(
    engine: HttpClientEngine? = null,
) {
    private val client = createClient(engine)

    suspend fun test(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        providerId: String = "",
        userAgent: String = "",
        customHeaders: List<LlmCustomHeader> = emptyList(),
    ): ProviderConnectionTestResult {
        val resolvedKey = apiKey.trim()
        val resolvedModel = modelId.trim()
        val resolvedBaseUrl = baseUrl.trim()
        if (resolvedKey.isEmpty()) {
            return ProviderConnectionTestResult(false, "请先填写 API Key。")
        }
        if (resolvedBaseUrl.isEmpty()) {
            return ProviderConnectionTestResult(false, "请先填写基础 URL。")
        }
        if (resolvedModel.isEmpty()) {
            return ProviderConnectionTestResult(false, "请先填写模型。")
        }
        xiaomiApiKeyEndpointMismatchMessage(resolvedBaseUrl, resolvedKey)?.let { message ->
            return ProviderConnectionTestResult(false, message)
        }
        return try {
            val response = client.post(chatCompletionsEndpoint(resolvedBaseUrl)) {
                contentType(ContentType.Application.Json)
                setBody(providerConnectionChatBody(resolvedBaseUrl, providerId, resolvedModel))
                headers {
                    append(HttpHeaders.Authorization, "Bearer $resolvedKey")
                    append(HttpHeaders.Accept, "application/json")
                    append(HttpHeaders.UserAgent, normalizeLlmUserAgent(userAgent))
                    customHeaders.normalizedLlmHeaders().forEach { header ->
                        remove(header.name)
                        append(header.name, header.value)
                    }
                }
            }
            val body = runCatching { response.body<String>() }.getOrDefault("")
            if (response.status.isSuccess()) {
                ProviderConnectionTestResult(true, "连接成功")
            } else {
                ProviderConnectionTestResult(
                    false,
                    providerConnectionFailureMessage(response.status.value, body),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val detail = error.message?.replace(Regex("\\s+"), " ")?.trim().orEmpty().take(180)
            ProviderConnectionTestResult(
                false,
                if (detail.isBlank()) "网络错误" else "网络错误：$detail",
            )
        }
    }

    fun close() {
        client.close()
    }

    private companion object {
        fun createClient(engine: HttpClientEngine?): HttpClient =
            if (engine == null) {
                HttpClient { configureTimeouts() }
            } else {
                HttpClient(engine) { configureTimeouts() }
            }

        fun io.ktor.client.HttpClientConfig<*>.configureTimeouts() {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 20_000
                requestTimeoutMillis = 20_000
            }
        }
    }
}

internal fun chatCompletionsEndpoint(baseUrl: String): String {
    val normalized = baseUrl.trim().trimEnd('/')
    require(normalized.isNotBlank()) { "A base URL is required to test the connection." }
    return when {
        normalized.endsWith("/chat/completions") -> normalized
        normalized.endsWith("/responses") -> normalized.removeSuffix("/responses") + "/chat/completions"
        else -> "$normalized/chat/completions"
    }
}

internal fun providerConnectionChatBody(
    baseUrl: String,
    providerId: String,
    modelId: String,
): String = buildJsonObject {
    put("model", modelId)
    put("messages", buildJsonArray {
        add(buildJsonObject {
            put("role", "user")
            put("content", "hi")
        })
    })
    put("stream", false)
    if (isXiaomiMiMoEndpoint(baseUrl, providerId)) {
        put("max_completion_tokens", 1)
        put("thinking", buildJsonObject { put("type", "disabled") })
    } else {
        put("max_tokens", 1)
    }
}.toString()

internal fun providerConnectionFailureMessage(statusCode: Int, body: String): String {
    val detail = shortProviderError(body)
    val suffix = if (detail.isBlank()) "" else "：$detail"
    return when {
        statusCode == 401 || statusCode == 403 -> "API Key 无效（HTTP $statusCode）$suffix"
        statusCode == 404 || isMissingModelError(detail, body) -> "模型不存在（HTTP $statusCode）$suffix"
        else -> "连接失败（HTTP $statusCode）$suffix"
    }
}

private fun isMissingModelError(detail: String, body: String): Boolean {
    val haystack = "$detail $body".lowercase()
    return listOf(
        "model_not_found",
        "model_not_exist",
        "model not exist",
        "model does not exist",
        "model doesn't exist",
        "unknown model",
        "invalid model",
        "no such model",
        "model not found",
        "模型不存在",
        "模型未找到",
    ).any { needle -> needle in haystack }
}

private fun shortProviderError(body: String): String {
    val parsed = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val error = parsed?.get("error")
    val message = when (error) {
        is JsonObject -> error.stringValue("message").ifBlank { error.stringValue("msg") }
        is JsonPrimitive -> error.contentOrNull.orEmpty()
        else -> parsed?.stringValue("message").orEmpty().ifBlank { parsed?.stringValue("msg").orEmpty() }
    }.ifBlank { body }
    return message.replace(Regex("\\s+"), " ").trim().take(180)
}

private fun JsonObject.stringValue(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
