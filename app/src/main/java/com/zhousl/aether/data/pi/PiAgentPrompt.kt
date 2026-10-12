package com.zhousl.aether.data.pi

import com.zhousl.aether.data.AppLanguage
import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.LocalRuntimeId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DynamicPromptPlaceholderRegex = Regex("""\{\{\s*([A-Za-z0-9_-]+)\s*\}\}""")

internal fun buildPiAgentInstructions(
    settings: AppSettings,
    workspaceDirectory: String,
    runtimeId: LocalRuntimeId,
    agentModeEnabled: Boolean,
    chromeEnabled: Boolean = false,
): String = buildString {
    val configuredPrompt = expandDynamicPromptPlaceholders(settings.systemPrompt).trim()
    if (configuredPrompt.isNotBlank()) {
        append(configuredPrompt)
        append("\n\n")
    }
    append(
        "You are running inside Aether on Android. " +
            "The current local runtime is ${runtimeId.storageValue} and its session cwd is $workspaceDirectory. " +
            "Aether keeps Alpine and Termux workspaces independent when the runtime changes. " +
            "User-uploaded files are placed under uploads/; use read on the provided path when image or file contents are needed. " +
            "Aether-owned configuration, Skill, runtime, Extension, Phone Use, scheduled-task, and developer operations are exposed only through available aether_* tools. " +
            "Never modify LLM provider credentials or model configuration through self-management tools. " +
            "Only claim device actions or command results that were actually observed."
    )
    if (agentModeEnabled) {
        append(
            "\n\nPhone Use is enabled for this chat. Use agent_display to operate Android via Accessibility. " +
                "The current mode is ${if (!settings.phoneUseVirtualDisplayEnabled) "foreground" else "Virtual Display (background)"}. " +
                "Input strategy: ${settings.phoneUseInputStrategy.storageValue}; output strategy: ${settings.phoneUseOutputStrategy.storageValue}. " +
                "observe/start and action results follow the input strategy. Element observations return a tree with temporary node_id values; screenshot observations return an image. " +
                "Prefer the configured output strategy. ${if (settings.phoneUseAllowStrategySwitch) "You may manually use the alternative when needed: tree/screenshot or input_strategy overrides observation for one action; node_id selects element actions, coordinates select gestures." else "Alternative strategy switching is disabled. Use only the configured observation and operation strategy."} " +
                "Node IDs are scoped to the latest observation, and the host revalidates the specific element before acting. Dynamic loading elsewhere does not invalidate an unchanged target. " +
                "Use visible, enabled, actionable elements. On element errors, action_succeeded=false: inspect the returned observation and choose a valid target. Avoid repeated observe/click loops; use an allowed real screenshot/gesture tool call if the target cannot be identified. " +
                "Never simulate USER/tool_result messages or write fictional tool outputs. Only actual agent_display results establish whether a device action succeeded. " +
                "Coordinate tap/swipe uses normalized 0..1000 coordinates: pixel_x / image_width * 1000, pixel_y / image_height * 1000. " +
                "Use text (fill_text is the same action) to enter text. Password values are redacted. " +
                "On WeChat (com.tencent.mm), or when the accessibility tree is empty or has no editable node, text is the only input path. " +
                "It writes the clipboard, then ACTION_SET_TEXT and ACTION_PASTE, then taps the field so the keyboard appears, long-presses that field, and taps 粘贴. " +
                "It can also send a system paste shortcut when the device supports Ctrl+V or KEYCODE_PASTE. " +
                "Do not first try a node write that WeChat rejects and then stop. " +
                "A missing Editable node and \"Focused field rejected text\" are not reasons to give up. " +
                "Do not type pinyin key by key. Do not tap IME keys one by one, and do not ask the user to paste manually. " +
                "Do not ask the user to type or send the message. " +
                "Empty text clears by selecting all, not by tapping backspace. " +
                "text_verified is true only after the field text or a screenshot shows the target. Never report success without that check. " +
                "If text_verified is false, show the Chinese reflection verbatim and do not propose another input method. " +
                "Suggest switching input methods only after clipboard paste, long-press 粘贴, and the system paste shortcut have failed. " +
                "微信（com.tencent.mm）或空树只能走剪贴板粘贴。禁止逐键拼音，禁止请用户手发。 " +
                "Foreground Phone Use changes the screen the user sees. Virtual Display global navigation is unavailable; use app elements. " +
                "When foreground phone work is complete, you may call agent_display with action=stop and return_to_aether=true to bring the user back to Aether for your final reply. " +
                "Leave return_to_aether omitted/false when the user should remain in the operated app to see or use the result. This choice is unavailable in Virtual Display mode."

        )
        if (settings.language == AppLanguage.SimplifiedChinese) {
            append(
                " Write every user-visible progress sentence in Simplified Chinese, in one or two short sentences. " +
                    "The on-screen Phone Use bubble shows that text to the user. Do not narrate those actions in English."
            )
        }
    }
    if (chromeEnabled) {
        append(
            "\n\nThe chat has enabled the browser tool (Chrome Extension tool). Prefer selectors and DOM-reading actions, and use coordinates only as a fallback."
        )
    }
}

private fun expandDynamicPromptPlaceholders(
    prompt: String,
    now: ZonedDateTime = ZonedDateTime.now(),
): String {
    if (!prompt.contains("{{")) return prompt
    val values = mapOf(
        "current_datetime" to now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        "current_date" to now.toLocalDate().toString(),
        "current_time" to now.toLocalTime().withNano(0).toString(),
        "timezone" to now.zone.id,
        "unix_timestamp" to now.toEpochSecond().toString(),
    )
    return DynamicPromptPlaceholderRegex.replace(prompt) { match ->
        values[match.groupValues[1].lowercase(Locale.US)] ?: match.value
    }
}
