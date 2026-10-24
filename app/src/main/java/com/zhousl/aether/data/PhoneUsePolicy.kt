package com.zhousl.aether.data

/** Prevents repeated permission prompts while still allowing an explicit Phone Use action to retry. */
internal class PhoneUsePermissionRequestPolicy {
    private var requested = false

    fun shouldRequest(enabled: Boolean, granted: Boolean, manuallyEnabled: Boolean = false): Boolean {
        if (!enabled || granted) return false
        if (requested && !manuallyEnabled) return false
        requested = true
        return true
    }
}

/** A menu click retries revoked access even if this conversation already selected Phone Use. */
internal fun phoneUseSelectionAfterMenuClick(selected: Boolean, enabled: Boolean, granted: Boolean): Boolean =
    enabled && (!granted || !selected)

internal fun phoneUseTargetsRealScreen(settings: AppSettings): Boolean =
    !settings.phoneUseVirtualDisplayEnabled

/** True when the model supplied a normalized coordinate argument. */
internal fun realScreenModelCoordinatesSupplied(arguments: org.json.JSONObject): Boolean =
    listOf("x", "y", "x1", "y1", "x2", "y2").any { key ->
        arguments.has(key) && !arguments.isNull(key)
    }

/** Prefer eid. node_id remains a deprecated alias when the value is the stable eid from the latest tree. */
internal fun phoneUseTargetElementId(arguments: org.json.JSONObject): String {
    val eid = arguments.optString("eid").trim()
    if (eid.isNotBlank()) return eid
    return arguments.optString("node_id").trim()
}

internal fun resolvePhoneUseInputStrategy(
    settings: AppSettings,
    action: String,
    override: String,
): PhoneUseInputStrategy {
    if (phoneUseTargetsRealScreen(settings)) {
        if (override.isNotBlank() &&
            override != PhoneUseInputStrategy.ElementTreeFirst.storageValue &&
            override != PhoneUseInputStrategy.ScreenshotFirst.storageValue
        ) {
            throw IllegalArgumentException("Invalid input_strategy.")
        }
        if (action == "screenshot" || override == PhoneUseInputStrategy.ScreenshotFirst.storageValue) {
            throw IllegalArgumentException(
                "Real-screen Phone Use forces element_tree_first. " +
                    "screenshot_first and strategy switching cannot bypass the element tree.",
            )
        }
        return PhoneUseInputStrategy.ElementTreeFirst
    }
    val explicit = when (action) {
        "tree" -> PhoneUseInputStrategy.ElementTreeFirst
        "screenshot" -> PhoneUseInputStrategy.ScreenshotFirst
        else -> null
    }
    val requested = if (override.isBlank()) explicit ?: settings.phoneUseInputStrategy else {
        PhoneUseInputStrategy.entries.firstOrNull { it.storageValue == override }
            ?: error("Invalid input_strategy.")
    }
    require(explicit == null || explicit == requested) { "input_strategy conflicts with the observation action." }
    require(settings.phoneUseAllowStrategySwitch || requested == settings.phoneUseInputStrategy) {
        "Alternative input strategy is disabled in Phone Use settings."
    }
    return requested
}

internal fun phoneUseUsesElementAction(
    settings: AppSettings,
    action: String,
    hasNode: Boolean,
    hasCoordinates: Boolean,
    override: String,
): Boolean {
    if (phoneUseTargetsRealScreen(settings)) {
        if (override.isNotBlank() && PhoneUseOutputStrategy.entries.none { it.storageValue == override }) {
            throw IllegalArgumentException("Invalid output_strategy.")
        }
        if (action != "swipe" && override == PhoneUseOutputStrategy.GestureFirst.storageValue) {
            throw IllegalArgumentException(
                "Real-screen Phone Use forces element actions. " +
                    "gesture_first and strategy switching cannot bypass the element tree.",
            )
        }
        if (action in setOf("tap", "click", "long_click", "text") && hasCoordinates) {
            throw IllegalArgumentException(
                "Real-screen $action rejects normalized 0..1000 coordinates. " +
                    "Pass eid from the element tree.",
            )
        }
        return action != "swipe"
    }
    val requested = if (override.isBlank()) settings.phoneUseOutputStrategy else {
        PhoneUseOutputStrategy.entries.firstOrNull { it.storageValue == override }
            ?: error("Invalid output_strategy.")
    }
    require(settings.phoneUseAllowStrategySwitch || requested == settings.phoneUseOutputStrategy) {
        "Alternative output strategy is disabled in Phone Use settings."
    }
    val element = when (action) {
        "swipe" -> false
        "scroll", "text" -> hasNode
        else -> hasNode && (!hasCoordinates || requested == PhoneUseOutputStrategy.ElementFirst)
    }
    if (action in setOf("tap", "click", "long_click", "scroll", "swipe")) {
        if (override.isNotBlank()) require(element == (requested == PhoneUseOutputStrategy.ElementFirst)) {
            "Operation arguments do not match output_strategy."
        }
        require(settings.phoneUseAllowStrategySwitch || element == (settings.phoneUseOutputStrategy == PhoneUseOutputStrategy.ElementFirst)) {
            "This operation uses the disabled alternative output strategy."
        }
    }
    return element
}

/** Return is an explicit foreground completion option, never a side effect of ordinary stop. */
internal fun phoneUseReturnToAetherRequested(settings: AppSettings, action: String, arguments: org.json.JSONObject): Boolean {
    if (!arguments.has("return_to_aether")) return false
    require(arguments.get("return_to_aether") is Boolean) { "return_to_aether must be a boolean." }
    val requested = arguments.getBoolean("return_to_aether")
    require(!requested || action == "stop") { "return_to_aether is only available with stop." }
    require(!requested || !settings.phoneUseVirtualDisplayEnabled) { "return_to_aether is only available in foreground Phone Use." }
    return requested
}

/** Only managed virtual displays have configurable geometry. */
internal fun phoneUseDisplayOrientationRequested(
    settings: AppSettings,
    action: String,
    arguments: org.json.JSONObject,
): AgentModeDisplayOrientation? {
    if (!arguments.has("orientation")) return null
    require(settings.phoneUseVirtualDisplayEnabled && settings.phoneUseAuthorizationMethod != AgentModeAuthorizationMethod.Accessibility) {
        "orientation is only available in Virtual Display mode."
    }
    require(action == "start" || action == "launch") { "orientation is only supported for start or launch." }
    val value = arguments.get("orientation")
    require(value is String) { "orientation must be device, portrait, or landscape." }
    return AgentModeDisplayOrientation.entries.firstOrNull { it.storageValue == value.trim().lowercase() }
        ?: throw IllegalArgumentException("orientation must be device, portrait, or landscape.")
}
