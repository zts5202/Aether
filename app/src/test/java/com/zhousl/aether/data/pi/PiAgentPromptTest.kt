package com.zhousl.aether.data.pi

import com.zhousl.aether.data.AppLanguage
import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.LocalRuntimeId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiAgentPromptTest {
    @Test
    fun instructionsOnlyAppendAetherRuntimeConstraints() {
        val instructions = buildPiAgentInstructions(
            settings = AppSettings(),
            workspaceDirectory = "/workspace",
            runtimeId = LocalRuntimeId.Alpine,
            agentModeEnabled = false,
        )

        assertTrue(instructions.contains("current local runtime is alpine"))
        assertTrue(instructions.contains("use read on the provided path"))
        assertFalse(instructions.contains("analyze_image"))
        assertFalse(instructions.contains("fetch_web_url"))
        assertFalse(instructions.contains("mcp_"))
        assertFalse(instructions.contains("<active_skill"))
    }

    @Test
    fun chromeInstructionsAreOnlyAddedWhenSelected() {
        val disabledInstructions = buildPiAgentInstructions(
            settings = AppSettings(),
            workspaceDirectory = "/workspace",
            runtimeId = LocalRuntimeId.Alpine,
            agentModeEnabled = false,
        )
        val enabledInstructions = buildPiAgentInstructions(
            settings = AppSettings(),
            workspaceDirectory = "/workspace",
            runtimeId = LocalRuntimeId.Alpine,
            agentModeEnabled = false,
            chromeEnabled = true,
        )

        assertFalse(disabledInstructions.contains("Chrome Extension tool"))
        assertTrue(enabledInstructions.contains("Chrome Extension tool"))
    }

    @Test
    fun phoneUseInstructionsRequireVerifiedWeChatPaste() {
        val instructions = buildPiAgentInstructions(
            settings = AppSettings(),
            workspaceDirectory = "/workspace",
            runtimeId = LocalRuntimeId.Alpine,
            agentModeEnabled = true,
        )
        assertTrue(instructions.contains("text_verified"))
        assertTrue(instructions.contains("com.tencent.mm"))
        assertTrue(instructions.contains("粘贴"))
        assertTrue(instructions.contains("do not ask the user to paste manually"))
        assertTrue(instructions.contains("Do not type pinyin key by key"))
        assertTrue(instructions.contains("Do not ask the user to type or send the message"))
        assertTrue(instructions.contains("Focused field rejected text"))
        assertTrue(instructions.contains("禁止逐键拼音，禁止请用户手发"))
        assertTrue(instructions.contains("Never report success without that check"))
        assertTrue(instructions.contains("send=true"))
        assertTrue(instructions.contains("KEYCODE_ENTER"))
        assertTrue(instructions.contains("unless sent is true"))
        assertTrue(instructions.contains("clipboard itself is unavailable"))
        assertFalse(instructions.contains("Suggest switching input methods"))
        assertTrue(instructions.contains("commitText"))
        assertTrue(instructions.contains("element_tree_first"))
        assertTrue(instructions.contains("stable eid"))
        assertTrue(instructions.contains("0..1000"))
        assertFalse(instructions.contains("pixel_x / image_width"))
        assertFalse(instructions.contains("clipboard-only"))
    }

    @Test
    fun chinesePhoneUseAsksForChineseProgressSentences() {
        val instructions = buildPiAgentInstructions(
            settings = AppSettings(language = AppLanguage.SimplifiedChinese),
            workspaceDirectory = "/workspace",
            runtimeId = LocalRuntimeId.Alpine,
            agentModeEnabled = true,
        )
        assertTrue(instructions.contains("Simplified Chinese"))
        assertTrue(instructions.contains("Do not narrate those actions in English"))
    }
}
