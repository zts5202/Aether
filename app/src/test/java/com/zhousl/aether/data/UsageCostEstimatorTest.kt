package com.zhousl.aether.data

import com.zhousl.aether.ui.ChatMessage
import com.zhousl.aether.ui.ChatUsageStatistics
import com.zhousl.aether.ui.MessageAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class UsageCostEstimatorTest {
    @Test
    fun mimoFlashBillsCacheHitMissAndOutputInCny() {
        assertCny("0.02", cost("mimo-v2.6-flash", cached = 1_000_000))
        assertCny("1.00", cost("mimo-v2.6-flash", input = 1_000_000))
        assertCny("2.00", cost("mimo-v2.6-flash", output = 1_000_000))
        assertCny(
            "0.133544",
            cost("MiMo-V2.6-Flash", input = 115_700, cached = 792_200, output = 1_000),
        )
    }

    @Test
    fun deepSeekFlashUsesPeakUsdRatesTimesFixedFx() {
        assertEquals(BigDecimal("7.2"), UsdToCnyRate)
        assertCny("0.0432", cost("deepseek-flash", cached = 1_000_000))
        assertCny("2.16", cost("deepseek-v4-flash", input = 1_000_000))
        assertCny("8.64", cost("DEEPSEEK-FLASH", output = 1_000_000))
        assertCny(
            "0.19872",
            cost(
                modelId = "accounts/fireworks/models/deepseek-v4-flash-vision-exp",
                input = 50_000,
                cached = 100_000,
                output = 10_000,
            ),
        )
        assertCny("10.8432", cost("deepseek-v4.1-flash", input = 1_000_000, cached = 1_000_000, output = 1_000_000))
    }

    @Test
    fun cacheOnlyAndZeroOutputStayOnTheHitOrMissRate() {
        assertCny("0.01", cost("mimo-v2.6-flash", cached = 500_000, output = null))
        assertCny("0.216", cost("deepseek-flash", input = 100_000, output = 0))
        assertCny(
            "0.02",
            estimateTurnCostCny(
                ChatUsageStatistics(
                    cachedInputTokens = 1_000_000,
                    cacheWriteTokens = 5_000_000,
                ),
                "mimo-v2.6-flash",
            ),
        )
        assertCny(
            "0",
            estimateTurnCostCny(
                ChatUsageStatistics(cacheWriteTokens = 5_000_000),
                "deepseek-flash",
            ),
        )
    }

    @Test
    fun inputIsAlreadyTheCacheMissAndIsNotSubtractedAgain() {
        assertCny("0.1016", cost("xiaomi/mimo-v2.6-flash", input = 100_000, cached = 80_000))
    }

    @Test
    fun nullTokenCountsAreZeroAndNegativeCountsAreDropped() {
        assertCny("0", cost("deepseek-flash"))
        assertCny("0", cost("mimo-v2.6-flash", input = -5, cached = -1, output = -3))
    }

    @Test
    fun unknownModelsAreUnavailable() {
        assertNull(cost("gpt-5.4", input = 1_000))
        assertNull(cost("deepseek-v4-pro", input = 1_000))
        assertNull(cost("mimo-v2.6-pro", output = 1_000))
        assertNull(cost("mimo-v2.5-pro", input = 1_000))
        assertNull(cost("", input = 1_000))
        assertNull(cost("   ", input = 1_000))
        assertNull(usagePricingFamily("deepseek-chat"))
        assertEquals(UsagePricingFamily.DeepSeekFlash, usagePricingFamily("DeepSeek-V4-Flash"))
        assertEquals(UsagePricingFamily.MimoV26Flash, usagePricingFamily("mimo-v2.6-flash"))
    }

    @Test
    fun sessionCostSumsPricedTurnsAndSkipsUnknownModels() {
        val messages = listOf(
            usageMessage("mimo-v2.6-flash", input = 1_000_000),
            usageMessage("deepseek-flash", output = 1_000_000),
            usageMessage("gpt-5.4", input = 9_000_000),
            ChatMessage(id = "user", author = MessageAuthor.User, text = "hi"),
        )

        assertCny("9.64", estimateSessionCostCny(messages))
        assertNull(estimateSessionCostCny(listOf(usageMessage("deepseek-v4-pro", input = 10))))
        assertNull(estimateSessionCostCny(emptyList()))
    }

    @Test
    fun historicalPayloadModelIsUsedWhenUsageHasNoModelId() {
        val message = ChatMessage(
            id = "agent-1",
            author = MessageAuthor.Agent,
            text = "done",
            providerPayloadJson = """{"provider":"deepseek","model":"deepseek-flash"}""",
            usageStatistics = ChatUsageStatistics(cachedInputTokens = 1_000_000),
        )

        assertEquals("deepseek-flash", resolveUsageModelId(message))
        assertCny("0.0432", estimateMessageCostCny(message))
        assertEquals(
            "deepseek-flash",
            preferredUsageModelId(
                requestedModelId = "custom-alias",
                providerPayloadJson = message.providerPayloadJson,
            ),
        )
        assertEquals(
            "mimo-v2.6-flash",
            preferredUsageModelId(
                requestedModelId = "mimo-v2.6-flash",
                providerPayloadJson = """{"model":"gpt-5.4"}""",
            ),
        )
    }

    @Test
    fun persistedUsageModelIdRoundTripsAndBackfillsFromPayload() {
        val stored = ChatMessage(
            id = "agent-1",
            author = MessageAuthor.Agent,
            text = "done",
            usageStatistics = ChatUsageStatistics(
                inputTokens = 10,
                outputTokens = 2,
                cachedInputTokens = 4,
                modelId = "deepseek-v4-flash",
            ),
        )
        val parsed = parseMessage(stored.toJson(), 0)
        assertEquals("deepseek-v4-flash", parsed.usageStatistics?.modelId)
        assertEquals(10L, parsed.usageStatistics?.inputTokens)
        assertEquals(4L, parsed.usageStatistics?.cachedInputTokens)

        val legacy = ChatMessage(
            id = "agent-2",
            author = MessageAuthor.Agent,
            text = "done",
            providerPayloadJson = """{"model":"mimo-v2.6-flash"}""",
            usageStatistics = ChatUsageStatistics(outputTokens = 1_000_000),
        )
        val backfilled = parseMessage(legacy.toJson(), 0)
        assertEquals("mimo-v2.6-flash", backfilled.usageStatistics?.modelId)
        assertCny("2.00", estimateMessageCostCny(backfilled))
    }

    @Test
    fun formatKeepsFenPrecisionAndExtraDigitsBelowOneFen() {
        assertEquals("¥0.13", formatEstimatedCny(BigDecimal("0.133544")))
        assertEquals("¥0.20", formatEstimatedCny(BigDecimal("0.19872")))
        assertEquals("¥10.84", formatEstimatedCny(BigDecimal("10.8432")))
        assertEquals("¥0.0043", formatEstimatedCny(BigDecimal("0.00432")))
        assertEquals("¥0.00", formatEstimatedCny(BigDecimal.ZERO))
    }

    private fun cost(
        modelId: String,
        input: Long? = null,
        cached: Long? = null,
        output: Long? = null,
    ): BigDecimal? = estimateUsageCostCny(
        modelId = modelId,
        inputTokens = input,
        cachedInputTokens = cached,
        outputTokens = output,
    )

    private fun usageMessage(
        modelId: String,
        input: Long? = null,
        output: Long? = null,
    ): ChatMessage = ChatMessage(
        id = modelId,
        author = MessageAuthor.Agent,
        text = "reply",
        usageStatistics = ChatUsageStatistics(
            inputTokens = input,
            outputTokens = output,
            totalTokens = (input ?: 0L) + (output ?: 0L),
            modelId = modelId,
        ),
    )

    private fun assertCny(expected: String, actual: BigDecimal?) {
        assertEquals(0, BigDecimal(expected).compareTo(checkNotNull(actual)))
    }
}
