package com.zhousl.aether.data.pi

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
    }
}
