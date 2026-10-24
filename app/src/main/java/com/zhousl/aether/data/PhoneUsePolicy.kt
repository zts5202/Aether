package com.zhousl.aether.data

import com.zhousl.aether.phoneuse.PhoneUseGridCell
import com.zhousl.aether.phoneuse.phoneUseGridCellFromArguments
import org.json.JSONArray
import org.json.JSONObject

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

internal enum class PhoneUseRealScreenObservation {
    ElementTree,
    GridScreenshot,
}

internal sealed interface PhoneUseRealScreenTarget {
    data class Element(val eid: String) : PhoneUseRealScreenTarget
    data class GridCell(val cell: PhoneUseGridCell) : PhoneUseRealScreenTarget
}

/** A node the model can target. Generic accessibility-focus actions do not count. */
internal fun phoneUseObservationHasUsableEid(observation: JSONObject): Boolean {
    if (observation.optInt("node_count", -1) == 0) return false
    val windows = observation.optJSONArray("windows") ?: return false
    for (index in 0 until windows.length()) {
        if (phoneUseNodeHasUsableEid(windows.optJSONObject(index)?.optJSONObject("root"), depth = 0)) return true
    }
    return false
}

internal fun phoneUseExplicitGridRequested(action: String, arguments: JSONObject): Boolean {
    if (action == "screenshot") return true
    if (arguments.optBoolean("grid", false)) return true
    val mode = arguments.optString("observe_mode").trim().lowercase()
    if (mode == "grid" || mode == "grid_screenshot") return true
    val strategy = arguments.optString("input_strategy").trim().lowercase()
    return strategy == "grid" || strategy == "grid_screenshot"
}

/**
 * Real-screen observation. A usable tree stays on eid. An empty or non-interactive tree,
 * and an explicit grid observe, use the 8×12 screenshot. `screenshot_first` is still rejected
 * by [resolvePhoneUseInputStrategy] so a strategy switch cannot skip the tree.
 */
internal fun resolvePhoneUseRealScreenObservation(
    action: String,
    arguments: JSONObject,
    treeUsable: Boolean,
): PhoneUseRealScreenObservation {
    if (phoneUseExplicitGridRequested(action, arguments) || !treeUsable) {
        return PhoneUseRealScreenObservation.GridScreenshot
    }
    return PhoneUseRealScreenObservation.ElementTree
}

/** eid wins when the tree named one. Otherwise a grid cell. Pixels and 0..1000 are rejected. */
internal fun resolvePhoneUseRealScreenPointAction(
    action: String,
    arguments: JSONObject,
): PhoneUseRealScreenTarget {
    require(action in setOf("tap", "click", "long_click")) {
        "Grid cells only target tap, click, and long_click."
    }
    if (realScreenModelCoordinatesSupplied(arguments)) {
        throw IllegalArgumentException(
            "Real-screen $action rejects raw pixels and normalized 0..1000 coordinates. " +
                "Pass eid, or a grid cell such as r3c5 when the tree has no usable eid.",
        )
    }
    val eid = phoneUseTargetElementId(arguments)
    if (eid.isNotBlank()) return PhoneUseRealScreenTarget.Element(eid)
    val cell = phoneUseGridCellFromArguments(arguments)
        ?: throw IllegalArgumentException(
            "Real-screen $action requires eid from the element tree. " +
                "If the tree has no usable eid, pass cell (r3c5), grid_id, or row and col from the 8×12 grid. " +
                "Do not compute coordinates.",
        )
    return PhoneUseRealScreenTarget.GridCell(cell)
}

internal fun resolvePhoneUseInputStrategy(
    settings: AppSettings,
    action: String,
    override: String,
): PhoneUseInputStrategy {
    if (phoneUseTargetsRealScreen(settings)) {
        if (override == PhoneUseInputStrategy.ScreenshotFirst.storageValue) {
            throw IllegalArgumentException(
                "Real-screen Phone Use forces element_tree_first. " +
                    "screenshot_first and strategy switching cannot bypass the element tree. " +
                    "An empty tree uses the host 8×12 grid; pass a cell id, not coordinates.",
            )
        }
        val allowed = setOf(
            "",
            PhoneUseInputStrategy.ElementTreeFirst.storageValue,
            "grid",
            "grid_screenshot",
        )
        if (override !in allowed) throw IllegalArgumentException("Invalid input_strategy.")
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
                "Real-screen $action rejects raw pixels and normalized 0..1000 coordinates. " +
                    "Pass eid from the element tree, or a grid cell such as r3c5 when that tree has no usable eid.",
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

private fun phoneUseNodeHasUsableEid(node: JSONObject?, depth: Int): Boolean {
    if (node == null || depth > 48) return false
    val id = node.optString("eid").ifBlank { node.optString("node_id") }
    val interactive = node.optBoolean("clickable") ||
        node.optBoolean("long_clickable") ||
        node.optBoolean("editable") ||
        node.optBoolean("scrollable") ||
        node.optBoolean("checkable")
    if (id.isNotBlank() && node.optBoolean("visible") && node.optBoolean("enabled") && interactive) return true
    val children = node.optJSONArray("children") ?: JSONArray()
    for (index in 0 until children.length()) {
        if (phoneUseNodeHasUsableEid(children.optJSONObject(index), depth + 1)) return true
    }
    return false
}
