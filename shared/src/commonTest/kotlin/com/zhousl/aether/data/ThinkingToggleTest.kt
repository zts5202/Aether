package com.zhousl.aether.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ThinkingToggleTest {
    @Test
    fun miMoAndDeepSeekFlashGetThinkingLevelsWithoutCatalogEntry() {
        assertEquals(ThinkingToggleLevels, effectiveThinkingLevels(null, "openai-compatible", "mimo-v2.6-flash", "https://api.xiaomimimo.com/v1"))
        assertEquals(ThinkingToggleLevels, effectiveThinkingLevels(emptyList(), "xiaomi-token-plan-cn", "mimo-v2.6-flash", ""))
        assertEquals(ThinkingToggleLevels, effectiveThinkingLevels(null, "deepseek", "deepseek-flash", "https://api.deepseek.com"))
        assertEquals(ThinkingToggleLevels, effectiveThinkingLevels(listOf("off"), "custom-openai", "mimo-v2.6-flash", "https://example.com/v1"))
        assertEquals(ThinkingToggleLevels, effectiveThinkingLevels(listOf("none"), "mimo", "MiMo-V2.6-Flash", ""))
        assertEquals(emptyList(), effectiveThinkingLevels(null, "openai", "gpt-4o", "https://api.openai.com/v1"))
        assertEquals(listOf("off", "high"), effectiveThinkingLevels(listOf("off", "high"), "deepseek", "deepseek-flash", ""))
    }
}
