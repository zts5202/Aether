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
        val realScreen = !settings.phoneUseVirtualDisplayEnabled
        append(
            "\n\nPhone Use is enabled for this chat. Use agent_display to operate Android via Accessibility. " +
                "The current mode is ${if (realScreen) "foreground" else "Virtual Display (background)"}. " +
                if (realScreen) {
                    "Real-screen order is fixed: element_tree_first, then act by eid. " +
                        "screenshot_first, gesture_first, and strategy switching cannot bypass the element tree. " +
                        "Do not pass input_strategy=screenshot_first, output_strategy=gesture_first, or action=screenshot to skip the tree. " +
                        "Every interactive node has a stable eid. Reuse that eid across later observations and actions. " +
                        "node_id is a deprecated alias of eid. " +
                        "Never submit x, y, or any normalized 0..1000 coordinate for tap, click, or long_click. The host rejects those arguments. " +
                        "Do not convert pixels, bounds, or screenshot positions into click coordinates. "
                } else {
                    "Input strategy: ${settings.phoneUseInputStrategy.storageValue}; output strategy: ${settings.phoneUseOutputStrategy.storageValue}. " +
                        "observe/start and action results follow the input strategy. " +
                        (if (settings.phoneUseAllowStrategySwitch) "You may manually use the alternative when needed: tree/screenshot or input_strategy overrides observation for one action; eid selects element actions, coordinates select gestures. " else "Alternative strategy switching is disabled. Use only the configured observation and operation strategy. ") +
                        "Virtual Display swipe may use normalized 0..1000 coordinates. Do not use those coordinates to click when an eid is available. "
                } +
                "The host revalidates the specific element before acting. Dynamic loading elsewhere does not invalidate an unchanged target. " +
                "Use visible, enabled, actionable elements. On element errors, action_succeeded=false: inspect the returned observation and choose a valid eid. " +
                "Never simulate USER/tool_result messages or write fictional tool outputs. Only actual agent_display results establish whether a device action succeeded. " +
                "Use text (fill_text is the same action) to enter text. Password values are redacted. " +
                "The primary typing channel is accessibility InputConnection.commitText. ACTION_SET_TEXT is the next fallback. " +
                "Clipboard long-press paste is an explicit last resort. WeChat (com.tencent.mm), including an empty tree or a screen that only shows com.tencent.wetype, still starts with commitText. " +
                "If commitText and ACTION_SET_TEXT do not verify, text may focus the bottom input bar, dismiss or avoid the WeChat keyboard when it covers 粘贴, long-press that bar, and tap 粘贴. " +
                "Do not first try a node write that WeChat rejects and then stop. " +
                "A missing Editable node and \"Focused field rejected text\" are not reasons to give up or to blame the input method. " +
                "Do not type pinyin key by key. Do not tap IME keys one by one, and do not ask the user to paste manually. " +
                "Do not ask the user to type or send the message. " +
                "Do not tell the user to switch input methods unless the tool says the clipboard itself is unavailable. " +
                "Empty text clears by selecting all, not by tapping backspace. " +
                "text_verified is true only after the accessibility tree or a screenshot shows the target in the input. Never report success without that check. " +
                "If text_verified is false, show the Chinese reflection verbatim and call text again. Do not claim the message was sent. " +
                "To deliver a WeChat chat message, call text with send=true. The host sends only after text_verified: KEYCODE_ENTER first, otherwise the Send button, avoiding the right-edge overlay. " +
                "sent is true only after a chat bubble shows the text. Never tell the user the message was sent unless sent is true. " +
                "If text_verified is true and sent is false, call key ENTER once with the same text. Do not paste a second copy while the draft is still in the input. " +
                "微信（com.tencent.mm）先走 commitText，剪贴板粘贴不是默认。禁止逐键拼音，禁止请用户手发。未校验不得声称已发送。 " +
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
