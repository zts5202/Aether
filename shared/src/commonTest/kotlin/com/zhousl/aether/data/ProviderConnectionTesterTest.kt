package com.zhousl.aether.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProviderConnectionTesterTest {
    @Test
    fun mimoRequestDisablesThinkingAndUsesMaxCompletionTokens() = runTest {
        val engine = MockEngine { request ->
            assertEquals(
                "https://api.xiaomimimo.com/v1/chat/completions",
                request.url.toString(),
            )
            assertEquals("Bearer sk-test", request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("mimo-v2.6-flash", body.getValue("model").jsonPrimitive.content)
            assertEquals(1, body.getValue("max_completion_tokens").jsonPrimitive.content.toInt())
            assertEquals("disabled", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
            assertFalse(body.containsKey("max_tokens"))
            assertFalse(body.containsKey("reasoning_effort"))
            respond(
                """{"choices":[{"message":{"content":"ok"}}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = ProviderConnectionTester(engine).test(
            baseUrl = "https://api.xiaomimimo.com/v1",
            apiKey = "sk-test",
            modelId = "mimo-v2.6-flash",
            providerId = "xiaomi",
        )

        assertTrue(result.success)
        assertEquals("连接成功", result.message)
    }

    @Test
    fun deepSeekRequestUsesMaxTokens() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://api.deepseek.com/chat/completions", request.url.toString())
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("deepseek-v4-flash", body.getValue("model").jsonPrimitive.content)
            assertEquals(1, body.getValue("max_tokens").jsonPrimitive.content.toInt())
            assertFalse(body.containsKey("thinking"))
            assertFalse(body.containsKey("max_completion_tokens"))
            respond(
                """{"choices":[{"message":{"content":"ok"}}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = ProviderConnectionTester(engine).test(
            baseUrl = "https://api.deepseek.com",
            apiKey = "sk-deepseek",
            modelId = "deepseek-v4-flash",
            providerId = "deepseek",
        )

        assertEquals("连接成功", result.message)
    }

    @Test
    fun unauthorizedKeyIsDescribedInChinese() = runTest {
        val engine = MockEngine {
            respondError(
                status = HttpStatusCode.Unauthorized,
                content = """{"error":{"message":"Invalid API key","code":"invalid_api_key"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = ProviderConnectionTester(engine).test(
            baseUrl = "https://api.deepseek.com",
            apiKey = "sk-bad",
            modelId = "deepseek-v4-flash",
        )

        assertFalse(result.success)
        assertEquals("API Key 无效（HTTP 401）：Invalid API key", result.message)
    }

    @Test
    fun missingModelIsDescribedInChinese() = runTest {
        val engine = MockEngine {
            respondError(
                status = HttpStatusCode.NotFound,
                content = """{"error":{"message":"The model `missing` does not exist"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = ProviderConnectionTester(engine).test(
            baseUrl = "https://api.deepseek.com",
            apiKey = "sk-ok",
            modelId = "missing",
        )

        assertEquals(
            "模型不存在（HTTP 404）：The model `missing` does not exist",
            result.message,
        )
    }

    @Test
    fun networkFailureIsDescribedInChinese() = runTest {
        val engine = MockEngine { throw IllegalStateException("connection refused") }
        val result = ProviderConnectionTester(engine).test(
            baseUrl = "https://api.deepseek.com",
            apiKey = "sk-ok",
            modelId = "deepseek-v4-flash",
        )

        assertEquals("网络错误：connection refused", result.message)
    }

    @Test
    fun mismatchedXiaomiKeyPrefixIsRejectedBeforeTheRequest() = runTest {
        val engine = MockEngine {
            error("request should not be sent")
        }
        val tokenPlanOnPayg = ProviderConnectionTester(engine).test(
            baseUrl = "https://api.xiaomimimo.com/v1",
            apiKey = "tp-plan",
            modelId = "mimo-v2.6-flash",
            providerId = "xiaomi",
        )
        val paygOnTokenPlan = ProviderConnectionTester(engine).test(
            baseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
            apiKey = "sk-payg",
            modelId = "mimo-v2.6-flash",
            providerId = "xiaomi-token-plan-cn",
        )

        assertEquals(XiaomiTokenPlanKeyOnPayAsYouGoMessage, tokenPlanOnPayg.message)
        assertEquals(XiaomiPayAsYouGoKeyOnTokenPlanMessage, paygOnTokenPlan.message)
        assertEquals(0, engine.requestHistory.size)
        assertNull(
            xiaomiApiKeyEndpointMismatchMessage(
                "https://api.xiaomimimo.com/v1",
                "sk-payg",
            ),
        )
        assertNull(
            xiaomiApiKeyEndpointMismatchMessage(
                "https://token-plan-sgp.xiaomimimo.com/v1",
                "tp-plan",
            ),
        )
    }
}
