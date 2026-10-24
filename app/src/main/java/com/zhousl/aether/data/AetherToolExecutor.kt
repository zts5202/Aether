package com.zhousl.aether.data

import com.zhousl.aether.runtime.RuntimeRouter
import org.json.JSONArray
import org.json.JSONObject

data class AetherToolExecutionResult(
    val toolName: String,
    val argumentsJson: String,
    val rawOutput: String,
    val visibleOutput: String = AetherToolExecutor.sanitizeToolOutputForConversation(toolName, rawOutput),
) {
    val isError: Boolean = !AetherToolExecutor.inferToolOutputOk(visibleOutput)
}

/**
 * Adapter for Aether-owned host capabilities only. Pi Coding Agent owns all
 * filesystem, shell, search, Skill, Extension, and image-reading mechanics.
 */
class AetherToolExecutor(
    private val runtimeRouter: RuntimeRouter,
    private val agentModeController: AgentModeController? = null,
) {
    suspend fun execute(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        toolName: String,
        argumentsJson: String,
        selfManagementTool: AetherSelfManagementTool? = null,
        agentModeEnabled: Boolean = false,
        currentRuntimeId: LocalRuntimeId = settings.defaultRuntimeId ?: LocalRuntimeId.Alpine,
        onRuntimeChanged: suspend (LocalRuntimeId) -> Unit = {},
        onProgress: (suspend (String) -> Unit)? = null,
    ): AetherToolExecutionResult {
        val rawOutput = when (toolName) {
            "agent_display" -> if (agentModeEnabled) {
                agentModeController?.execute(
                    settings = settings,
                    workspaceDirectory = workspaceDirectory,
                    termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                    argumentsJson = argumentsJson,
                ) ?: unavailableToolOutput(toolName)
            } else {
                JSONObject()
                    .put("ok", false)
                    .put("errmsg", "Phone Use is not enabled for this chat.")
                    .toString()
            }

            "aether_runtime_manage" -> executeRuntimeManage(
                settings = settings,
                currentRuntimeId = currentRuntimeId,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                argumentsJson = argumentsJson,
                onRuntimeChanged = onRuntimeChanged,
            )

            in SelfManagementToolNames -> selfManagementTool?.execute(
                toolName = toolName,
                argumentsJson = argumentsJson,
            ) ?: unavailableToolOutput(toolName)

            else -> JSONObject()
                .put("ok", false)
                .put("error", "Unknown Aether host tool '$toolName'.")
                .toString()
        }
        return AetherToolExecutionResult(toolName, argumentsJson, rawOutput)
    }

    private suspend fun executeRuntimeManage(
        settings: AppSettings,
        currentRuntimeId: LocalRuntimeId,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        argumentsJson: String,
        onRuntimeChanged: suspend (LocalRuntimeId) -> Unit,
    ): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("errmsg", "Invalid JSON arguments.").toString()
        val action = arguments.optString("action").trim()
        if (action == "status") {
            val states = LocalRuntimeId.entries.associateWith { runtimeId ->
                runtimeRouter.runtimeById(runtimeId).inspectSetup()
            }
            return JSONObject().apply {
                put("ok", true)
                put("action", "status")
                put("runtime", currentRuntimeId.storageValue)
                put("cwd", runtimeCwd(currentRuntimeId, workspaceDirectory, termuxWorkspaceDirectory))
                put(
                    "available",
                    JSONObject().apply {
                        states.forEach { (runtimeId, state) -> put(runtimeId.storageValue, state.isReady) }
                    },
                )
            }.toString()
        }
        if (action != "set") {
            return JSONObject().put("ok", false).put("errmsg", "action must be 'status' or 'set'.").toString()
        }
        val requested = LocalRuntimeId.fromStorage(arguments.optString("runtime"))
            ?: return JSONObject().put("ok", false).put("errmsg", "runtime must be 'alpine' or 'termux'.").toString()
        val setup = runtimeRouter.runtimeById(requested).inspectSetup()
        val enabled = settings.enabledRuntimeIds.isEmpty() || requested in settings.enabledRuntimeIds
        if (!setup.isReady || !enabled) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "${requested.displayName} runtime is unavailable.")
                put("detail", setup.detail)
                put("runtime", currentRuntimeId.storageValue)
                put("cwd", runtimeCwd(currentRuntimeId, workspaceDirectory, termuxWorkspaceDirectory))
            }.toString()
        }
        onRuntimeChanged(requested)
        return JSONObject().apply {
            put("ok", true)
            put("action", "set")
            put("runtime", requested.storageValue)
            put("cwd", runtimeCwd(requested, workspaceDirectory, termuxWorkspaceDirectory))
        }.toString()
    }

    companion object {
        val hostToolNames: Set<String> = setOf("agent_display", *SelfManagementToolNames.toTypedArray())

        fun supports(toolName: String): Boolean = toolName in hostToolNames

        fun hostToolDefinitions(
            selfManagementTool: AetherSelfManagementTool? = null,
            agentModeEnabled: Boolean = false,
        ): JSONArray = JSONArray().apply {
            selfManagementTool?.toolDefinitions()?.forEach { definition ->
                put(
                    flattenOpenAiToolDefinition(
                        definition = definition,
                        executionMode = if (
                            definition.optJSONObject("function")?.optString("name") == "aether_config_get"
                        ) "parallel" else "sequential",
                    ),
                )
            }
            if (agentModeEnabled) put(agentModeToolDefinition())
        }

        fun sanitizeToolOutputForConversation(toolName: String, output: String): String {
            if (toolName != "agent_display") return output
            val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return output
            if (!parsed.has("screenshot_base64")) return output
            parsed.remove("screenshot_base64")
            parsed.put("screenshot_injected_into_next_model_request", true)
            return parsed.toString()
        }

        fun inferToolOutputOk(output: String): Boolean {
            val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return true
            return parsed.optBoolean("ok", !parsed.optBoolean("err", false))
        }
    }
}

private fun runtimeCwd(
    runtimeId: LocalRuntimeId,
    workspaceDirectory: String,
    termuxWorkspaceDirectory: String,
): String = if (runtimeId == LocalRuntimeId.Termux) termuxWorkspaceDirectory else workspaceDirectory

private val SelfManagementToolNames = setOf(
    "aether_config_get",
    "aether_config_set",
    "aether_skill_manage",
    "aether_termux_manage",
    "aether_runtime_manage",
    "aether_agent_mode_manage",
    "aether_scheduled_task_manage",
    "aether_extension_manage",
    "aether_developer_manage",
)

private fun unavailableToolOutput(toolName: String): String = JSONObject()
    .put("ok", false)
    .put("errmsg", "Host dependency for '$toolName' is not available.")
    .toString()

private fun flattenOpenAiToolDefinition(
    definition: JSONObject,
    executionMode: String,
): JSONObject {
    val function = definition.optJSONObject("function") ?: JSONObject()
    return JSONObject().apply {
        put("name", function.optString("name"))
        put("description", function.optString("description"))
        put("parameters", relaxStrictOptionalParameters(function.optJSONObject("parameters")))
        put("execution_mode", executionMode)
    }
}

private fun relaxStrictOptionalParameters(parameters: JSONObject?): JSONObject {
    val relaxed = JSONObject((parameters ?: JSONObject().put("type", "object")).toString())
    val properties = relaxed.optJSONObject("properties") ?: return relaxed
    val required = relaxed.optJSONArray("required") ?: return relaxed
    relaxed.put(
        "required",
        JSONArray().apply {
            for (index in 0 until required.length()) {
                val name = required.optString(index)
                if (name.isNotBlank() && !properties.optJSONObject(name).allowsNull()) put(name)
            }
        },
    )
    return relaxed
}

private fun JSONObject?.allowsNull(): Boolean = when (val type = this?.opt("type")) {
    "null" -> true
    is JSONArray -> (0 until type.length()).any { type.optString(it) == "null" }
    else -> false
}

private fun agentModeToolDefinition(): JSONObject = JSONObject().apply {
    put("name", "agent_display")
    put(
        "description",
        "Operate Phone Use through Android Accessibility on the foreground screen, or a Virtual Display when configured. " +
            "On the real screen, observations and actions are element_tree_first. screenshot_first, gesture_first, and strategy switching cannot bypass the tree. " +
            "Every interactive node has a stable eid. Pass eid for tap, click, long_click, scroll, and text. " +
            "node_id is a deprecated alias of eid. Do not send normalized 0..1000 coordinates for clicks; the real screen rejects them. " +
            "Typing uses accessibility InputConnection.commitText first, then ACTION_SET_TEXT. Clipboard paste is an explicit last resort, including for WeChat and an empty tree. " +
            "Unrelated content events do not expire an eid: the target is refreshed and checked at execution. Target errors include a fresh observation when permitted. Use visible, enabled, actionable elements; never guess a replacement by index. " +
            "Virtual Display may still use its configured strategy. " +
            "When finished, stop with return_to_aether=true can bring the user back to Aether (foreground mode only). Omit it to leave the result visible in the controlled app. " +
            "Use only when Phone Use is selected in the composer; foreground actions change the screen visible to the user.",
    )
    put(
        "parameters",
        JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put("action", stringProperty("One of: list_apps, start, observe, tree, status, launch, tap, click, long_click, scroll, swipe, key, text, screenshot, stop. fill_text is an alias of text."))
                    put("return_to_aether", booleanProperty("For stop, foreground mode only: optionally return the user to Aether after finishing. Defaults to false; omit to leave the controlled app visible."))
                    put("input_strategy", stringProperty("Real screen forces element_tree_first. screenshot_first is rejected there and cannot bypass the tree. Virtual Display may pass element_tree_first or screenshot_first when strategy switching is allowed."))
                    put("output_strategy", stringProperty("Real screen forces element actions by eid. gesture_first is rejected there. Virtual Display may pass element_first or gesture_first when strategy switching is allowed."))
                    put("eid", stringProperty("A stable element id from the latest tree. Required for real-screen tap, click, long_click, scroll, and text. Reuse it across later observations and actions."))
                    put("node_id", stringProperty("Deprecated alias of eid. Used only when eid is omitted. Snapshot ids of the form {snapshot}:{index} are no longer issued."))
                    put("direction", stringProperty("For scroll: forward/backward, down/up, or right/left."))
                    put("query", stringProperty("For list_apps: optional app label, package, or activity filter."))
                    put("include_system", booleanProperty("For list_apps: whether to include system apps."))
                    put("max_results", integerProperty("For list_apps: maximum number of apps to return."))
                    put("target", stringProperty("For launch: package name or exact app label."))
                    put("orientation", stringProperty(
                        "For start/launch in Virtual Display mode only: device, portrait, or landscape. " +
                            "Uses the saved preference when omitted. Overrides last until stop; changing orientation recreates " +
                            "the display, so start must be followed by launch. Foreground mode rejects this parameter."
                    ).put("enum", JSONArray().put("device").put("portrait").put("landscape")))
                    listOf("x", "y", "x1", "y1", "x2", "y2").forEach { key ->
                        val axis = if (key.startsWith("x")) "width" else "height"
                        put(
                            key,
                            integerProperty(
                                "Rejected for real-screen tap, click, long_click, and text. Do not submit normalized 0..1000 " +
                                    "$axis coordinates for clicks, and do not convert screenshot pixels into them. " +
                                    "Virtual Display swipe may still pass host-normalized 0..1000 values.",
                            ),
                        )
                    }
                    put("duration_ms", integerProperty("For swipe: gesture duration in milliseconds (50..10000)."))
                    put("key", stringProperty(
                        "For key: BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS (foreground only), or ENTER. " +
                            "On WeChat, ENTER sends a draft that text already verified: KEYCODE_ENTER first, then the Send button, avoiding the right-edge overlay. " +
                            "Pass the same text so the bubble can be checked. Never claim the message was sent unless sent is true."
                    ))
                    put(
                        "text",
                        stringProperty(
                            "For text/fill_text, pass eid when the tree has an editable node. The primary channel is accessibility InputConnection.commitText. " +
                                "ACTION_SET_TEXT is the next fallback. Clipboard long-press paste is an explicit last resort, not the default for WeChat (com.tencent.mm) or an empty tree. " +
                                "If those channels do not verify, the host may focus the bottom input bar, dismiss or avoid com.tencent.wetype when it covers 粘贴, long-press, and tap 粘贴. " +
                                "Do not send normalized 0..1000 coordinates for this field. " +
                                "Do not type pinyin key by key, do not tap IME keys, and do not ask the user to type, paste, or send. " +
                                "Do not tell the user to switch input methods unless the reflection says the clipboard itself is unavailable. " +
                                "A missing Editable node or \"Focused field rejected text\" must not stop the action; the host continues with the next text channel. " +
                                "Success requires text_verified after readback or a screenshot of the target text. If text_verified is false, repeat the Chinese reflection verbatim and do not claim the message was sent. " +
                                "Empty text clears by selecting all. Unicode is supported.",
                        ),
                    )
                    put(
                        "send",
                        booleanProperty(
                            "For text/fill_text on a WeChat chat: after the paste is verified, send it. " +
                                "The host tries KEYCODE_ENTER first, then taps Send while avoiding the right-edge overlay. " +
                                "sent is true only when a chat bubble shows the text. Omit or false to fill without sending.",
                        ),
                    )
                },
            )
            put("required", JSONArray().put("action"))
            put("additionalProperties", false)
        },
    )
    put("execution_mode", "sequential")
}

private fun stringProperty(description: String): JSONObject = JSONObject()
    .put("type", "string")
    .put("description", description)

private fun integerProperty(description: String): JSONObject = JSONObject()
    .put("type", "integer")
    .put("description", description)

private fun booleanProperty(description: String): JSONObject = JSONObject()
    .put("type", "boolean")
    .put("description", description)
