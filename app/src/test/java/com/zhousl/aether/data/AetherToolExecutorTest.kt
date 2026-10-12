package com.zhousl.aether.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherToolExecutorTest {
    @Test fun virtualDisplayExposesExplicitOrientationOverride() {
        val definition = AetherToolExecutor.hostToolDefinitions(agentModeEnabled = true).getJSONObject(0)
        val orientation = definition.getJSONObject("parameters").getJSONObject("properties").getJSONObject("orientation")
        assertEquals(listOf("device", "portrait", "landscape"),
            (0 until orientation.getJSONArray("enum").length()).map { orientation.getJSONArray("enum").getString(it) })
        assertTrue(orientation.getString("description").contains("Virtual Display"))
        val text = definition.getJSONObject("parameters").getJSONObject("properties").getJSONObject("text").getString("description")
        assertTrue(text.contains("com.tencent.mm"))
        assertTrue(text.contains("text_verified"))
        assertTrue(text.contains("pinyin"))
        assertTrue(text.contains("do not ask the user to type, paste, or send"))
        assertTrue(text.contains("Focused field rejected text"))
        assertTrue(text.contains("bottom input"))
        assertFalse(text.contains("focused editable field is required"))
        val send = definition.getJSONObject("parameters").getJSONObject("properties").getJSONObject("send")
        assertEquals("boolean", send.getString("type"))
        assertTrue(send.getString("description").contains("KEYCODE_ENTER"))
    }

    @Test
    fun hostToolDefinitionsDoNotDuplicatePiNativeTools() {
        val definitions = AetherToolExecutor.hostToolDefinitions()
        assertEquals(0, definitions.length())
    }

    @Test
    fun sanitizeAgentDisplayOutputRemovesScreenshotBytes() {
        val sanitized = AetherToolExecutor.sanitizeToolOutputForConversation(
            toolName = "agent_display",
            output = JSONObject().apply {
                put("ok", true)
                put("screenshot_base64", "abc123")
                put("screenshot_mime_type", "image/png")
            }.toString(),
        )

        val json = JSONObject(sanitized)
        assertFalse(json.has("screenshot_base64"))
        assertTrue(json.getBoolean("screenshot_injected_into_next_model_request"))
        assertEquals("image/png", json.getString("screenshot_mime_type"))
    }

    @Test
    fun dynamicHostToolDefinitionsExcludeMcpAndChromeButIncludeAgentMode() {
        val definitions = AetherToolExecutor.hostToolDefinitions(
            selfManagementTool = null,
            agentModeEnabled = true,
        )
        val names = (0 until definitions.length())
            .map { definitions.getJSONObject(it).getString("name") }

        assertFalse("mcp_list_tools" in names)
        assertFalse("mcp__docs__search" in names)
        assertTrue("agent_display" in names)
        assertFalse("chrome" in names)
        assertEquals(
            "sequential",
            (0 until definitions.length())
                .map { definitions.getJSONObject(it) }
                .first { it.getString("name") == "agent_display" }
                .getString("execution_mode"),
        )
    }

    @Test
    fun inferToolOutputOkHonorsAetherJsonFlags() {
        assertTrue(AetherToolExecutor.inferToolOutputOk("""{"ok":true}"""))
        assertFalse(AetherToolExecutor.inferToolOutputOk("""{"ok":false}"""))
        assertFalse(AetherToolExecutor.inferToolOutputOk("""{"err":true}"""))
        assertTrue(AetherToolExecutor.inferToolOutputOk("plain text"))
    }

    @Test
    fun workspaceFileRoutingRecognizesAlpineAndTermuxRoots() {
        assertEquals(
            LocalRuntimeId.Alpine,
            resolveWorkspaceRuntimeId(
                path = "/workspace/agent-mode/capture.png",
                workingDirectory = "",
                defaultRuntimeId = LocalRuntimeId.Termux,
            ),
        )
        assertEquals(
            LocalRuntimeId.Termux,
            resolveWorkspaceRuntimeId(
                path = "/data/data/com.termux/files/home/.aether/workspace/uploads/image.png",
                workingDirectory = "",
                defaultRuntimeId = LocalRuntimeId.Alpine,
            ),
        )
        assertEquals(
            LocalRuntimeId.Alpine,
            resolveWorkspaceRuntimeId(
                path = "relative.png",
                workingDirectory = "/workspace",
                defaultRuntimeId = LocalRuntimeId.Termux,
            ),
        )
        assertEquals(
            LocalRuntimeId.Termux,
            resolveWorkspaceRuntimeId(
                path = "/data/data/com.termux/files/home/.aether/workspace/output.png",
                workingDirectory = "/workspace",
                defaultRuntimeId = LocalRuntimeId.Alpine,
            ),
        )
        assertEquals(
            LocalRuntimeId.Alpine,
            resolveWorkspaceRuntimeId(
                path = "file:///workspace/agent-mode/capture.png",
                workingDirectory = "/data/data/com.termux/files/home/.aether/workspace",
                defaultRuntimeId = LocalRuntimeId.Termux,
            ),
        )
    }

}
