package com.zhousl.aether.phoneuse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.zhousl.aether.AetherApplication
import com.zhousl.aether.R
import com.zhousl.aether.MainActivity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.inputmethod.TextAttribute
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** User-enabled service shared by foreground Phone Use and privileged Virtual Display sessions. */
class PhoneUseAccessibilityService : AccessibilityService() {
    private var overlayController: PhoneUseOverlayController? = null
    private var destroyed = false
    private var observationGeneration = 0L
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val overlayUpdateQueued = AtomicBoolean(false)
    private val refreshOverlay = Runnable {
        overlayUpdateQueued.set(false)
        refreshForegroundOverlay()
    }
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { scheduleOverlayRefresh() }
    }

    private fun scheduleOverlayRefresh() {
        if (overlayUpdateQueued.compareAndSet(false, true)) overlayHandler.postDelayed(refreshOverlay, 16)
    }

    @Suppress("DEPRECATION")
    private fun foregroundPackage(): String? {
        val applications = displayWindows(Display.DEFAULT_DISPLAY)
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isFocused }.thenByDescending { it.layer })
        for (window in applications) {
            val root = window.root ?: continue
            try { root.packageName?.toString()?.let { return it } } finally { root.recycle() }
        }
        val root = rootInActiveWindow ?: return null
        try {
            if (isOverlayRoot(root)) return null
            return root.packageName?.toString()
        } finally { root.recycle() }
    }

    private fun refreshForegroundOverlay() {
        val state = requestedOverlayState
        val available = getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val show = shouldShowPhoneUseOverlay(state, if (state != null) foregroundPackage() else null, packageName, available)
        overlayController?.update(state, show)
        val keyboard = rawDisplayWindows(Display.DEFAULT_DISPLAY)
            .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .maxByOrNull { it.layer }
        val keyboardBounds = keyboard?.let { Rect().also(it::getBoundsInScreen) }?.takeUnless { it.isEmpty }
        overlayController?.updateKeyboardBounds(keyboardBounds)
    }

    private fun isOwnOverlay(window: AccessibilityWindowInfo): Boolean =
        window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
            window.title?.toString()?.startsWith(PhoneUseOverlayController.WINDOW_PREFIX) == true

    @Suppress("DEPRECATION")
    private fun isOverlayRoot(root: AccessibilityNodeInfo): Boolean {
        val window = root.window ?: return false
        return try { isOwnOverlay(window) } finally { window.recycle() }
    }

    private data class Identity(
        val packageName: String,
        val className: String,
        val viewId: String,
        val label: String,
        val description: String,
        val hint: String,
        val editable: Boolean,
        val password: Boolean,
        val checkable: Boolean,
        val inputType: Int,
    ) {
        val canRebind: Boolean get() = viewId.isNotBlank() || label.isNotBlank() || description.isNotBlank() || hint.isNotBlank()
    }

    private data class AncestorAnchor(val viewId: String, val className: String, val label: String)

    private fun ancestorAnchor(node: AccessibilityNodeInfo): AncestorAnchor = AncestorAnchor(
        node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(),
        if (node.isEditable || node.isPassword) "" else node.contentDescription?.toString().orEmpty()
            .ifBlank { node.text?.toString().orEmpty() },
    )

    private fun anchorKey(anchor: AncestorAnchor, editableTarget: Boolean): String? {
        val label = if (editableTarget) "" else anchor.label
        if (anchor.viewId.isBlank() && label.isBlank()) return null
        return JSONObject().put("view_id", anchor.viewId).put("class", anchor.className).put("label", label).toString()
    }

    private data class ObservedNode(
        var node: AccessibilityNodeInfo,
        val identity: Identity,
        val ancestors: List<String>,
        val windowId: Int,
        val windowPackage: String,
        var rebound: Boolean = false,
    )

    private val nodes = mutableMapOf<String, ObservedNode>()
    private val windowUpdates = mutableMapOf<Int, Int>()
    private var observedDisplayId: Int? = null
    private var actionInvalidated = true
    private var observedWindowIds = emptySet<Int>()

    override fun onServiceConnected() {
        instance = this
        _connected.value = true
        overlayController?.dispose()
        overlayController = PhoneUseOverlayController(this) { target, text ->
            (application as AetherApplication).runtime.sessionExecutionManager
                .steerFromPhoneUse(target.sessionId, target.runId, text)
        }
        if (!screenReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(screenReceiver, filter)
            screenReceiverRegistered = true
        }
        if (Build.VERSION.SDK_INT >= 33) {
            serviceInfo = serviceInfo?.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
            }
        }
        scheduleOverlayRefresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) scheduleOverlayRefresh()
        if (observedDisplayId == null) return
        // Events are hints about local changes, not proof that every element is stale.
        // In particular, clocks/notifications in System UI must not disable Chrome controls.
        if (event.windowId in observedWindowIds) {
            windowUpdates[event.windowId] = (windowUpdates[event.windowId] ?: 0) + 1
        }
    }

    override fun onInterrupt() {
        clearNodes()
        overlayController?.update(null, false)
    }

    override fun onDestroy() {
        destroyed = true
        clearNodes()
        overlayHandler.removeCallbacksAndMessages(null)
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false }
        overlayController?.dispose()
        overlayController = null
        if (instance === this) {
            instance = null
            _connected.value = false
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun clearNodes() {
        observationGeneration++
        nodes.values.forEach { it.node.recycle() }
        windowUpdates.clear()
        nodes.clear()
        observedDisplayId = null
        observedWindowIds = emptySet()
        actionInvalidated = true
    }

    private fun rawDisplayWindows(displayId: Int): List<AccessibilityWindowInfo> =
        if (Build.VERSION.SDK_INT >= 30) windowsOnAllDisplays[displayId].orEmpty() else {
            check(displayId == Display.DEFAULT_DISPLAY) { "Virtual Display accessibility requires Android 11 or later." }
            windows
        }

    private fun displayWindows(displayId: Int, excludeKeyboard: Boolean = overlayController?.isEditing == true): List<AccessibilityWindowInfo> =
        rawDisplayWindows(displayId).filterNot {
            isOwnOverlay(it) || (excludeKeyboard && it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD)
        }

    private data class ObservationSnapshot(
        val json: JSONObject,
        val nodes: Map<String, ObservedNode>,
        val windowIds: Set<Int>,
    )

    private data class ObservationRequest(val updates: Map<Int, Int>, val excludeKeyboard: Boolean, val generation: Long)

    private fun beginObservation(displayId: Int): ObservationRequest {
        check(!destroyed) { "Accessibility service disconnected." }
        val updates = windowUpdates.toMap()
        clearNodes()
        observedDisplayId = displayId
        return ObservationRequest(updates, overlayController?.isEditing == true, observationGeneration)
    }

    private fun installObservation(snapshot: ObservationSnapshot, generation: Long): JSONObject {
        check(!destroyed && observationGeneration == generation) { "Accessibility observation invalidated before completion." }
        nodes.putAll(snapshot.nodes)
        observedWindowIds = snapshot.windowIds
        actionInvalidated = false
        return snapshot.json
    }

    fun observe(displayId: Int, width: Int, height: Int): JSONObject {
        val request = beginObservation(displayId)
        return installObservation(buildObservation(displayId, width, height, request.updates, request.excludeKeyboard), request.generation)
    }

    /** Binder tree reads and JSON construction must not block the overlay's main-thread frames. */
    internal suspend fun observeAsync(displayId: Int, width: Int, height: Int,
        readDispatcher: CoroutineDispatcher = Dispatchers.IO): JSONObject {
        val request = withContext(Dispatchers.Main.immediate) { beginObservation(displayId) }
        var snapshot: ObservationSnapshot? = null
        var installed = false
        try {
            withContext(readDispatcher) {
                snapshot = buildObservation(displayId, width, height, request.updates, request.excludeKeyboard)
            }
            return withContext(Dispatchers.Main.immediate) {
                installObservation(requireNotNull(snapshot), request.generation).also { installed = true }
            }
        } finally {
            if (!installed) snapshot?.nodes?.values?.forEach { it.node.recycle() }
        }
    }

    @Suppress("DEPRECATION")
    private fun buildObservation(displayId: Int, width: Int, height: Int, updates: Map<Int, Int>, excludeKeyboard: Boolean): ObservationSnapshot {
        val snapshotNodes = mutableMapOf<String, ObservedNode>()
        var windowIds = emptySet<Int>()
        try {
            val snapshotId = UUID.randomUUID().toString().take(8)
            val eidAssigner = PhoneUseEidAssigner()
            var count = 0
            var truncated = false
            fun visit(node: AccessibilityNodeInfo, depth: Int, windowId: Int, windowPackage: String, digest: MessageDigest,
                path: String, parents: List<AncestorAnchor> = emptyList()): JSONObject? {
                if (count >= 1200 || depth >= 48) { truncated = true; return null }
                count++
                val eid = eidAssigner.assign(phoneUseElementFingerprint(
                    packageName = node.packageName?.toString().orEmpty().ifBlank { windowPackage },
                    className = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty(),
                    editable = node.isEditable,
                    password = node.isPassword,
                    text = if (node.isPassword) "" else node.text?.toString().orEmpty(),
                    description = if (node.isPassword) "" else node.contentDescription?.toString().orEmpty(),
                    hint = node.hintText?.toString().orEmpty(),
                    path = path,
                ))
                snapshotNodes[eid] = ObservedNode(AccessibilityNodeInfo.obtain(node), identity(node, windowPackage),
                    parents.mapNotNull { anchorKey(it, node.isEditable) }.take(2), windowId, windowPackage)
                val rect = Rect().also(node::getBoundsInScreen)
                val actions = node.actionList.map { it.id }.toSet()
                val result = JSONObject().apply {
                    put("class", node.className?.toString().orEmpty())
                    put("view_id", node.viewIdResourceName.orEmpty())
                    put("text", if (node.isPassword) "" else node.text?.toString().orEmpty().take(2000))
                    put("description", if (node.isPassword) "" else node.contentDescription?.toString().orEmpty().take(2000))
                    put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                    put("visible", node.isVisibleToUser)
                    put("enabled", node.isEnabled)
                    put("clickable", AccessibilityNodeInfo.ACTION_CLICK in actions)
                    put("long_clickable", AccessibilityNodeInfo.ACTION_LONG_CLICK in actions)
                    put("editable", node.isEditable)
                    put("focused", node.isFocused)
                    put("scrollable", node.isScrollable)
                    put("selected", node.isSelected)
                    put("checkable", node.isCheckable)
                    put("checked", node.isChecked)
                    put("password", node.isPassword)
                    put("actionable", node.isVisibleToUser && node.isEnabled && !rect.isEmpty && actions.isNotEmpty())
                }
                // The digest excludes eid values. The same control keeps its eid across observations.
                digest.update(result.toString().toByteArray(Charsets.UTF_8))
                digest.update('['.code.toByte())
                result.put("eid", eid)
                result.put("node_id", eid)
                val children = JSONArray()
                val childParents = listOf(ancestorAnchor(node)) + parents.take(5)
                for (index in 0 until node.childCount) {
                    if (count >= 1200) { truncated = true; break }
                    val child = node.getChild(index) ?: continue
                    try { visit(child, depth + 1, windowId, windowPackage, digest, "$path/$index", childParents)?.let(children::put) } finally { child.recycle() }
                }
                digest.update(']'.code.toByte())
                result.put("children", children)
                return result
            }
            val windowTrees = JSONArray()
            val targetWindows = displayWindows(displayId, excludeKeyboard).sortedWith(
                compareBy<AccessibilityWindowInfo> { !it.isFocused }.thenBy { it.type != AccessibilityWindowInfo.TYPE_APPLICATION }
            )
            windowIds = targetWindows.map { it.id }.toSet()
            targetWindows.forEach { window ->
                val root = window.root ?: return@forEach
                try {
                    windowTrees.put(JSONObject().apply {
                        put("window_id", window.id)
                        put("title", window.title?.toString().orEmpty())
                        put("package", root.packageName?.toString().orEmpty())
                        put("focused", window.isFocused)
                        put("type", window.type)
                        val digest = MessageDigest.getInstance("SHA-256")
                        val windowPackage = root.packageName?.toString().orEmpty()
                        put("root", visit(root, 0, window.id, windowPackage, digest, "t${window.type}:$windowPackage"))
                        put("content_hash", digest.digest().joinToString("") { "%02x".format(it) })
                        put("updates_since_last_observe", updates[window.id] ?: 0)
                    })
                } finally { root.recycle() }
            }
            // Some services/older OS releases expose the active root before the windows list.
            if (windowTrees.length() == 0 && displayId == Display.DEFAULT_DISPLAY) {
                rootInActiveWindow?.let { root ->
                    if (isOverlayRoot(root)) { root.recycle(); return@let }
                    windowIds = windowIds + root.windowId
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        windowTrees.put(JSONObject().apply {
                            put("window_id", root.windowId)
                            put("package", root.packageName?.toString().orEmpty())
                            val windowPackage = root.packageName?.toString().orEmpty()
                            put("root", visit(root, 0, root.windowId, windowPackage, digest, "active:$windowPackage"))
                            put("content_hash", digest.digest().joinToString("") { "%02x".format(it) })
                            put("updates_since_last_observe", updates[root.windowId] ?: 0)
                        })
                    } finally { root.recycle() }
                }
            }
            val json = JSONObject().apply {
                put("ok", true)
                put("display_id", displayId)
                put("mode", if (displayId == Display.DEFAULT_DISPLAY) "foreground" else "virtual_display")
                put("width", width)
                put("height", height)
                val realScreen = displayId == Display.DEFAULT_DISPLAY
                put("coordinate_space", if (realScreen) "host_internal" else "normalized_0_1000")
                put("model_click_coordinates", if (realScreen) "forbidden" else "normalized_0_1000")
                put("bounds_coordinate_space", "display_pixels")
                put("element_id", "eid")
                put("id_migration", PhoneUseElementIdMigration)
                put("windows", windowTrees)
                put("snapshot_id", snapshotId)
                put("node_count", count)
                put("truncated", truncated)
                put("stdout", when {
                    count == 0 && realScreen ->
                        "没有可读的无障碍节点，也没有可用 eid。真机观察会附带 8×12 网格截图。点击或长按请传 cell（如 r3c5）、grid_id 或 row,col。禁止像素和 0..1000 坐标。文字仍调用 text：宿主先走无障碍 commitText，再 ACTION_SET_TEXT，剪贴板长按粘贴只是显式回退，不是微信或空树的默认路径。不要把未校验的 fill_text 当成成功，不要逐键打拼音，不要换输入法，不要请用户打字或发送，也不要在气泡确认前声称已发送。"
                    count == 0 ->
                        "没有可读的无障碍节点。文字仍调用 text：宿主先走无障碍 commitText，再 ACTION_SET_TEXT，剪贴板长按粘贴只是显式回退，不是微信或空树的默认路径。不要提交 0..1000 坐标。不要把未校验的 fill_text 当成成功，不要逐键打拼音，不要换输入法，不要请用户打字或发送，也不要在气泡确认前声称已发送。"
                    else ->
                        "Phone Use element tree. Use eid for tap, click, long_click, scroll, and text. node_id is a deprecated alias of the same eid. Do not send normalized 0..1000 click coordinates."
                })
            }
            return ObservationSnapshot(json, snapshotNodes, windowIds)
        } catch (cause: Throwable) {
            snapshotNodes.values.forEach { it.node.recycle() }
            throw cause
        }
    }

    private fun identity(node: AccessibilityNodeInfo, windowPackage: String): Identity = Identity(
        packageName = node.packageName?.toString().orEmpty().ifBlank { windowPackage },
        className = node.className?.toString().orEmpty(),
        viewId = node.viewIdResourceName.orEmpty(),
        // Input values, selection, focus and coordinates are mutable state, not element identity.
        label = if (node.isEditable || node.isPassword) "" else node.text?.toString().orEmpty(),
        description = if (node.isPassword || (node.isEditable && !node.viewIdResourceName.isNullOrBlank())) ""
            else node.contentDescription?.toString().orEmpty(),
        hint = node.hintText?.toString().orEmpty(),
        editable = node.isEditable,
        password = node.isPassword,
        checkable = node.isCheckable,
        inputType = node.inputType,
    )

    @Suppress("DEPRECATION")
    private fun ancestorAnchors(node: AccessibilityNodeInfo): List<String> {
        val anchors = mutableListOf<String>()
        var parent = node.parent
        var depth = 0
        while (parent != null && depth++ < 6 && anchors.size < 2) {
            val current = parent
            try {
                anchorKey(ancestorAnchor(current), node.isEditable)?.let(anchors::add)
                parent = current.parent
            } finally { current.recycle() }
        }
        parent?.recycle()
        return anchors
    }

    private fun elementError(code: String, message: String): Nothing = throw PhoneUseElementException(code, message)

    @Suppress("DEPRECATION")
    private fun requireNode(id: String, displayId: Int): ObservedNode {
        if (actionInvalidated || observedDisplayId != displayId) {
            elementError("stale_observation", "A previous action or a display change invalidated this observation. Use the fresh observation returned with this error.")
        }
        val reference = nodes[id] ?: elementError(
            "unknown_node",
            "Unknown eid. $PhoneUseElementIdMigration",
        )
        val currentWindows = displayWindows(displayId)
        val window = currentWindows.firstOrNull { it.id == reference.windowId }
        // Older Android releases can expose only the active root, before a windows list is available.
        val root = window?.root ?: if (displayId == Display.DEFAULT_DISPLAY && currentWindows.isEmpty()) {
            rootInActiveWindow?.let { active ->
                if (active.windowId == reference.windowId) active else { active.recycle(); null }
            }
        } else null
        if (root == null) elementError("window_changed", "The target window disappeared. Choose a target from the fresh observation.")
        try {
            if (root.packageName?.toString().orEmpty() != reference.windowPackage) {
                elementError("window_changed", "The target window now belongs to another app. Choose a target from the fresh observation.")
            }
            if (window?.type == AccessibilityWindowInfo.TYPE_APPLICATION && currentWindows.any {
                    it.id != reference.windowId && it.isFocused && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
                }) {
                elementError("window_obscured", "Another window has focus over the target. Handle it using the fresh observation.")
            }
            val node = reference.node
            if (node.refresh()) {
                if (identity(node, reference.windowPackage) != reference.identity || ancestorAnchors(node) != reference.ancestors) {
                    elementError("target_changed", "The target element changed meaning. Choose a target from the fresh observation.")
                }
            } else {
                if (!reference.identity.canRebind) {
                    elementError("target_gone", "The target element disappeared and has no stable identity. Choose a target from the fresh observation.")
                }
                // Rebind only an exact, unique semantic match in the same live window. Never use position/index proximity.
                val matches = mutableListOf<AccessibilityNodeInfo>()
                var count = 0
                var truncated = false
                fun search(candidate: AccessibilityNodeInfo, depth: Int) {
                    if (count++ >= 1200 || depth >= 48) { truncated = true; return }
                    if (candidate.isVisibleToUser && candidate.isEnabled && identity(candidate, reference.windowPackage) == reference.identity &&
                        ancestorAnchors(candidate) == reference.ancestors) matches += AccessibilityNodeInfo.obtain(candidate)
                    for (index in 0 until candidate.childCount) {
                        if (count >= 1200) { truncated = true; break }
                        val child = candidate.getChild(index) ?: continue
                        try { search(child, depth + 1) } finally { child.recycle() }
                    }
                }
                try { search(root, 0) }
                catch (cause: Throwable) { matches.forEach { it.recycle() }; throw cause }
                if (matches.size != 1 || truncated) {
                    matches.forEach { it.recycle() }
                    elementError(if (matches.size > 1 || truncated) "target_ambiguous" else "target_gone",
                        "The old target cannot be uniquely identified. Choose a target from the fresh observation; do not guess by index or coordinates.")
                }
                reference.node.recycle()
                reference.node = matches.single()
                reference.rebound = true
            }
            val target = reference.node
            val bounds = Rect().also(target::getBoundsInScreen)
            if (!target.isVisibleToUser || !target.isEnabled || bounds.isEmpty) {
                elementError("target_not_interactable", "The target is hidden, disabled or has empty bounds. Choose a visible, actionable target from the fresh observation.")
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val targetWindow = target.window ?: elementError("window_changed", "The target no longer belongs to a live window.")
                try {
                    if (targetWindow.id != reference.windowId || targetWindow.displayId != displayId) {
                        elementError("window_changed", "The target moved to another window or display. Choose a target from the fresh observation.")
                    }
                } finally { targetWindow.recycle() }
            }
            return reference
        } finally { root.recycle() }
    }

    fun nodeAction(displayId: Int, id: String, action: String, text: String, direction: String): Boolean {
        val reference = requireNode(id, displayId)
        val node = reference.node
        val actionId = when (action) {
            "tap", "click" -> AccessibilityNodeInfo.ACTION_CLICK
            "long_click" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
            "text" -> AccessibilityNodeInfo.ACTION_SET_TEXT
            "scroll" -> when (direction) {
                "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "down", "up", "left", "right" -> {
                    val directional = when (direction) {
                        "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                        "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                        "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                        else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                    }
                    if (node.actionList.any { it.id == directional }) directional
                    else if (direction in setOf("up", "left")) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                else -> error("direction must be forward, backward, down, up, left, or right.")
            }
            else -> error("Unsupported element action '$action'.")
        }
        if (action == "text" && tryAccessibilityCommitText(node, text)) {
            actionInvalidated = true
            return reference.rebound
        }
        val arguments = if (action == "text") Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        } else null
        if (node.actionList.none { it.id == actionId }) {
            elementError("action_unsupported", "The target does not support $action. Choose a suitable actionable element from the fresh observation.")
        }
        if (!node.performAction(actionId, arguments)) {
            elementError("action_rejected", "The target rejected $action. Inspect the fresh observation and choose another eid.")
        }
        actionInvalidated = true
        return reference.rebound
    }

    @Suppress("DEPRECATION")
    fun setFocusedText(displayId: Int, text: String) {
        val focused = displayWindows(displayId).asSequence().mapNotNull { window ->
            val root = window.root ?: return@mapNotNull null
            try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } finally { root.recycle() }
        }.firstOrNull() ?: error("No focused input on this display. Use text with an editable node_id.")
        try {
            if (tryAccessibilityCommitText(focused, text)) {
                actionInvalidated = true
                return
            }
            check(focused.isEditable && focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })) { "Focused field rejected text. Use an editable node_id." }
        } finally { focused.recycle() }
        actionInvalidated = true
    }

    /**
     * Accessibility input channel. API 33+ exposes an [android.accessibilityservice.InputMethod]
     * whose [android.accessibilityservice.InputMethod.AccessibilityInputConnection.commitText]
     * writes through the editor's InputConnection. Older releases have no such channel.
     */
    internal fun tryAccessibilityCommitText(node: AccessibilityNodeInfo, text: CharSequence): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching { commitTextOnInputConnection(node, text) }.getOrDefault(false)
    }

    @Suppress("NewApi")
    private fun commitTextOnInputConnection(node: AccessibilityNodeInfo, text: CharSequence): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val method = getInputMethod() ?: return false
        if (!method.currentInputStarted) return false
        val connection = method.currentInputConnection ?: return false
        connection.commitText(text, 1, null as TextAttribute?)
        return true
    }

    suspend fun gesture(displayId: Int, x1: Int, y1: Int, x2: Int = x1, y2: Int = y1, duration: Long = 80) {
        check(displayId == Display.DEFAULT_DISPLAY || Build.VERSION.SDK_INT >= 30) { "Display gestures require Android 11 or later." }
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            if (x1 != x2 || y1 != y2) lineTo(x2.toFloat(), y2.toFloat())
        }
        val builder = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(displayId)
        val dispatch: suspend () -> Unit = { withTimeout(duration + 5000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val accepted = dispatchGesture(builder.build(), object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Accessibility gesture cancelled."))
                    }
                }, null)
                if (!accepted && continuation.isActive) continuation.resumeWithException(IllegalStateException("Accessibility gesture rejected."))
            }
        }
        }
        val overlay = overlayController.takeIf { displayId == Display.DEFAULT_DISPLAY }
        if (overlay != null) overlay.withAgentGesture(y1, y2, dispatch) else dispatch()
        actionInvalidated = true
    }

    internal fun requestReturnToAether() {
        // Launch through the bound Accessibility service, which is allowed to bring up an Activity.
        overlayController?.update(null, false)
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ))
    }

    suspend fun returnToAether(): Boolean = withContext(Dispatchers.Main.immediate) {
        requestReturnToAether()
        withTimeoutOrNull(2500) {
            while (foregroundPackage() != packageName) delay(50)
            true
        } ?: false
    }

    suspend fun navigationKey(displayId: Int, key: String) {
        val overlay = overlayController.takeIf { displayId == Display.DEFAULT_DISPLAY }
        val name = key.uppercase().removePrefix("KEYCODE_")
        if (overlay != null && name !in setOf("ENTER", "66")) {
            overlay.withAgentNavigation { key(displayId, key) }
        } else key(displayId, key)
    }

    @Suppress("DEPRECATION")
    fun key(displayId: Int, key: String) {
        val name = key.uppercase().removePrefix("KEYCODE_")
        if (name in setOf("ENTER", "66")) {
            check(Build.VERSION.SDK_INT >= 30) { "Accessibility IME enter requires Android 11 or later." }
            val focused = displayWindows(displayId).asSequence().mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } finally { root.recycle() }
            }.firstOrNull() ?: error("No focused input on the selected display.")
            try {
                check(focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) { "Focused field rejected IME enter." }
            } finally { focused.recycle() }
        } else {
            // Global actions are not display-scoped. Refuse them in the background rather than
            // sending a back/home event to the user's foreground app.
            check(displayId == Display.DEFAULT_DISPLAY) { "Global navigation is unavailable on Virtual Display. Use app elements or launch another app." }
            val action = when (name) {
                "BACK", "4" -> GLOBAL_ACTION_BACK
                "HOME", "3" -> GLOBAL_ACTION_HOME
                "APP_SWITCH", "RECENTS", "187" -> GLOBAL_ACTION_RECENTS
                "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
                "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
                else -> error("Accessibility supports BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, and ENTER.")
            }
            check(performGlobalAction(action)) { "Accessibility navigation rejected." }
        }
        actionInvalidated = true
    }

    /**
     * WeChat and empty trees often accept ACTION_SET_TEXT without changing the field.
     * Paste is success only after the node text or a screenshot shows the target.
     */
    internal suspend fun enterVerifiedText(
        displayId: Int,
        text: String,
        anchorX: Int?,
        anchorY: Int?,
        screenWidth: Int,
        screenHeight: Int,
        sendAfterVerify: Boolean = false,
        elementId: String? = null,
    ): TextEntryOutcome {
        val host = LiveTextEntryHost(displayId, anchorX, anchorY, screenWidth, screenHeight, elementId)
        try {
            val outcome = PhoneUseTextEntry(host).enter(text, sendAfterVerify)
            android.util.Log.i(
                "PhoneUseText",
                "verified=${outcome.verified} sent=${outcome.sent} method=${outcome.method} via=${outcome.verifiedBy} attempts=${outcome.attempts.size}",
            )
            return outcome
        } finally {
            host.close()
            actionInvalidated = true
        }
    }

    /** KEYCODE_ENTER, then the Send button. Used after a verified WeChat draft. */
    internal suspend fun sendVerifiedWeChat(
        displayId: Int,
        expected: String,
        screenWidth: Int,
        screenHeight: Int,
    ): TextEntryOutcome {
        val host = LiveTextEntryHost(displayId, null, null, screenWidth, screenHeight)
        try {
            return PhoneUseTextEntry(host).sendOnly(expected)
        } finally {
            host.close()
            actionInvalidated = true
        }
    }

    internal fun isWeChatContext(displayId: Int): Boolean = displayContainsWeChat(displayId)

    internal fun needsVerifiedClipboardPaste(displayId: Int): Boolean {
        if (displayContainsWeChat(displayId)) return true
        val candidates = applicationWindows(displayId)
        if (candidates.isEmpty()) {
            if (displayId != Display.DEFAULT_DISPLAY) return true
            val root = rootInActiveWindow ?: return true
            try {
                if (isOverlayRoot(root)) return true
                if (isWeChatPackage(root.packageName?.toString())) return true
                return !treeHasContent(root)
            } finally { root.recycle() }
        }
        val window = candidates.firstOrNull { it.isFocused } ?: candidates.maxByOrNull { it.layer } ?: return true
        val root = window.root ?: return true
        try {
            if (isWeChatPackage(root.packageName?.toString())) return true
            return !treeHasContent(root)
        } finally { root.recycle() }
    }

    /** WeChat stays on the paste path even when the focused window is the IME or its package is blank. */
    private fun displayContainsWeChat(displayId: Int, keep: AccessibilityNodeInfo? = null): Boolean {
        for (window in rawDisplayWindows(displayId)) {
            if (isOwnOverlay(window)) continue
            if (mentionsWeChat(null, window.title)) return true
            val root = window.root ?: continue
            try {
                if (shallowPackageIsWeChat(root, keep)) return true
            } finally { if (root !== keep) root.recycle() }
        }
        if (displayId != Display.DEFAULT_DISPLAY) return false
        val root = rootInActiveWindow ?: return false
        try {
            if (isOverlayRoot(root)) return false
            return shallowPackageIsWeChat(root, keep)
        } finally { if (root !== keep) root.recycle() }
    }

    private fun shallowPackageIsWeChat(
        node: AccessibilityNodeInfo,
        keep: AccessibilityNodeInfo?,
        depth: Int = 0,
        budget: IntArray = intArrayOf(32),
    ): Boolean {
        if (isWeChatPackage(node.packageName?.toString())) return true
        if (depth >= 5 || budget[0] <= 0) return false
        for (index in 0 until node.childCount) {
            if (budget[0] <= 0) return false
            budget[0]--
            val child = node.getChild(index) ?: continue
            try {
                if (shallowPackageIsWeChat(child, keep, depth + 1, budget)) return true
            } finally { if (child !== keep) child.recycle() }
        }
        return false
    }

    private fun runInputCommand(command: Array<String>): Boolean {
        val process = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        val finished = process.waitFor(700, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0
    }

    private class KeyboardWindow(val bounds: Rect, val packageName: String, val ime: Boolean)

    private fun keyboardWindow(displayId: Int): KeyboardWindow? {
        val ime = rawDisplayWindows(displayId)
            .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .maxByOrNull { it.layer }
        if (ime != null) {
            val bounds = Rect().also(ime::getBoundsInScreen)
            val root = ime.root
            val pkg = try { root?.packageName?.toString().orEmpty() } finally { root?.recycle() }
            if (!bounds.isEmpty) return KeyboardWindow(bounds, pkg, ime = true)
        }
        val wetype = rawDisplayWindows(displayId).filter { window ->
            if (isOwnOverlay(window)) return@filter false
            val root = window.root ?: return@filter false
            try { isWeTypePackage(root.packageName?.toString()) } finally { root.recycle() }
        }.maxByOrNull { it.layer } ?: return null
        val bounds = Rect().also(wetype::getBoundsInScreen)
        return bounds.takeUnless { it.isEmpty }?.let { KeyboardWindow(it, WeTypePackageName, ime = false) }
    }

    internal fun inputMethodBounds(displayId: Int): Rect? {
        val window = rawDisplayWindows(displayId)
            .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .maxByOrNull { it.layer } ?: return null
        return Rect().also(window::getBoundsInScreen).takeUnless { it.isEmpty }
    }

    internal fun nodeInInputMethod(displayId: Int, nodeId: String): Boolean {
        val reference = nodes[nodeId] ?: return false
        return rawDisplayWindows(displayId).any {
            it.id == reference.windowId && it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
    }

    private fun applicationWindows(displayId: Int): List<AccessibilityWindowInfo> {
        val all = displayWindows(displayId, excludeKeyboard = true)
        val apps = all.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (apps.isNotEmpty()) return apps
        return all.filter {
            it.type != AccessibilityWindowInfo.TYPE_SYSTEM &&
                it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        }
    }

    private fun treeHasContent(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (node.isVisibleToUser && node.isEditable) return true
        if (node.isVisibleToUser && (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())) return true
        if (depth >= 48) return false
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (treeHasContent(child, depth + 1)) return true
            } finally { child.recycle() }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun isTextTarget(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return false
        if (node.isEditable) return true
        val actions = node.actionList.map { it.id }
        if (AccessibilityNodeInfo.ACTION_SET_TEXT in actions || AccessibilityNodeInfo.ACTION_PASTE in actions) return true
        return node.className?.toString().orEmpty().endsWith("EditText")
    }

    /** Returns the live node. Caller recycles it. Window roots that are not selected are recycled here. */
    @Suppress("DEPRECATION")
    private fun findTextTarget(displayId: Int): AccessibilityNodeInfo? {
        data class Candidate(val node: AccessibilityNodeInfo, val bottom: Int, val focused: Boolean)
        val candidates = mutableListOf<Candidate>()
        var seen = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (seen++ >= 1200 || depth > 48) return
            if (isTextTarget(node) && node.isVisibleToUser) {
                val rect = Rect().also(node::getBoundsInScreen)
                if (!rect.isEmpty) candidates += Candidate(node, rect.bottom, node.isFocused)
            }
            for (index in 0 until node.childCount) {
                if (seen >= 1200) return
                val child = node.getChild(index) ?: continue
                walk(child, depth + 1)
                if (candidates.none { it.node === child }) child.recycle()
            }
        }
        val roots = mutableListOf<AccessibilityNodeInfo>()
        for (window in displayWindows(displayId, excludeKeyboard = true)) {
            window.root?.let { roots += it }
        }
        if (roots.isEmpty() && displayId == Display.DEFAULT_DISPLAY) {
            rootInActiveWindow?.takeUnless { isOverlayRoot(it) }?.let { roots += it }
        }
        try {
            roots.forEach { walk(it, 0) }
        } catch (cause: Throwable) {
            val keep = candidates.map { it.node }.toSet()
            roots.filter { it !in keep }.forEach { it.recycle() }
            candidates.filter { it.node !in roots }.forEach { it.node.recycle() }
            throw cause
        }
        val picked = candidates.firstOrNull { it.focused }?.node
            ?: candidates.maxByOrNull { it.bottom }?.node
        candidates.filter { it.node !== picked && it.node !in roots }.forEach { it.node.recycle() }
        roots.filter { it !== picked }.forEach { it.recycle() }
        return picked
    }

    private fun labelMatches(text: String?, description: String?, labels: List<String>): Boolean {
        val values = listOf(text.orEmpty(), description.orEmpty())
        return labels.any { label -> values.any { value -> value == label || (label.length >= 2 && value.contains(label)) } }
    }

    @Suppress("DEPRECATION")
    private inner class LiveTextEntryHost(
        private val displayId: Int,
        private val anchorX: Int?,
        private val anchorY: Int?,
        requestedWidth: Int,
        requestedHeight: Int,
        elementId: String? = null,
    ) : PhoneUseTextHost {
        private var field: AccessibilityNodeInfo? = fieldFor(elementId)

        private fun fieldFor(elementId: String?): AccessibilityNodeInfo? {
            val requested = elementId?.trim()?.takeIf { it.isNotEmpty() }?.let { nodes[it]?.node }
            if (requested != null && isTextTarget(requested)) return AccessibilityNodeInfo.obtain(requested)
            return findTextTarget(displayId)
        }
        override val weChat: Boolean
        override val emptyTree: Boolean
        override val density: Float = resources.displayMetrics.density.coerceAtLeast(1f)
        override val screenWidth: Int = requestedWidth.coerceAtLeast(1)
        override val screenHeight: Int = requestedHeight.coerceAtLeast(1)

        init {
            weChat = isWeChatPackage(field?.packageName?.toString()) || displayContainsWeChat(displayId, field)
            emptyTree = field == null
        }

        fun close() {
            field?.recycle()
            field = null
        }

        override fun writeClipboard(text: String) {
            val manager = getSystemService(ClipboardManager::class.java)
                ?: error("Clipboard is unavailable.")
            val clip = ClipData.newPlainText("aether-phone-use", text)
            // HyperOS ignores clipboard writes from a background app. Focus a 1px overlay,
            // copy from there, then drop it before the WeChat long-press.
            val focusedCopy = runCatching { overlayController?.copyToClipboard(text) == true }.getOrDefault(false)
            try {
                manager.setPrimaryClip(clip)
            } catch (error: Exception) {
                if (!focusedCopy) throw error
            }
        }

        override fun fieldText(): String? {
            val node = field ?: findTextTarget(displayId)?.also { field = it } ?: return null
            runCatching { node.refresh() }
            return node.text?.toString().orEmpty()
        }

        override fun tryCommitText(text: String): Boolean {
            val node = field ?: findTextTarget(displayId)?.also { field = it } ?: return false
            return tryAccessibilityCommitText(node, text)
        }

        override fun trySetText(text: String): Boolean = performTextAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })

        override fun trySelectAll(): Boolean {
            val node = field ?: return false
            val end = (node.text?.length ?: 0).coerceAtLeast(4096)
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            })
        }

        override fun tryPaste(): Boolean = performTextAction(AccessibilityNodeInfo.ACTION_PASTE, null)

        override fun tryCut(): Boolean = performTextAction(AccessibilityNodeInfo.ACTION_CUT, null)

        override fun labelCenter(labels: List<String>): Pair<Int, Int>? {
            fun walk(node: AccessibilityNodeInfo, depth: Int): Pair<Int, Int>? {
                if (depth > 48) return null
                if (node.isVisibleToUser && labelMatches(node.text?.toString(), node.contentDescription?.toString(), labels)) {
                    val rect = Rect().also(node::getBoundsInScreen)
                    if (!rect.isEmpty) return rect.centerX() to rect.centerY()
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try {
                        walk(child, depth + 1)?.let { return it }
                    } finally { if (child !== field) child.recycle() }
                }
                return null
            }
            for (window in displayWindows(displayId, excludeKeyboard = false)) {
                val root = window.root ?: continue
                try {
                    walk(root, 0)?.let { return it }
                } finally { if (root !== field) root.recycle() }
            }
            return null
        }

        override fun anchor(): Pair<Int, Int> {
            val keyboardTop = keyboardTop()
            val fromField = field?.let { node ->
                val rect = Rect().also(node::getBoundsInScreen)
                if (rect.isEmpty) null else rect.centerX() to rect.centerY()
            }
            val raw = when {
                anchorX != null && anchorY != null -> anchorX to anchorY
                fromField != null -> fromField
                else -> bottomInputBarPoint(screenWidth, screenHeight, density, keyboardTop)
            }
            val normalTop = keyboardTop?.takeIf { it > (screenHeight * 0.45f).roundToInt() }
            val liftedY = if (normalTop != null && raw.second >= normalTop) {
                (normalTop - 32f * density).roundToInt()
            } else raw.second
            val ceiling = ((normalTop ?: screenHeight) - 1).coerceAtLeast(0)
            return raw.first.coerceIn(0, (screenWidth - 1).coerceAtLeast(0)) to liftedY.coerceIn(0, ceiling.coerceAtMost((screenHeight - 1).coerceAtLeast(0)))
        }

        override fun keyboardTop(): Int? = keyboardWindow(displayId)?.bounds?.top

        override fun keyboardBounds(): Rect? = keyboardWindow(displayId)?.bounds

        override fun keyboardPackage(): String? = keyboardWindow(displayId)?.packageName?.ifBlank { null }

        override suspend fun dismissKeyboard(): Boolean {
            val window = keyboardWindow(displayId) ?: return false
            // A full-screen non-IME window is not a keyboard; Back would leave the chat.
            if (!window.ime && window.bounds.height() > screenHeight / 2) return false
            val accepted = performGlobalAction(GLOBAL_ACTION_BACK)
            kotlinx.coroutines.delay(320)
            return accepted && keyboardBounds() == null
        }

        override fun overlayBounds(): Rect? = overlayController?.touchBounds()

        override suspend fun tryKeycodeEnter(): Boolean = withContext(Dispatchers.IO) {
            runCatching { runInputCommand(arrayOf("input", "keyevent", "66")) }.getOrDefault(false)
        }

        override fun bubbleContains(text: String): Boolean {
            val sample = text.trim()
            if (sample.isEmpty()) return false
            fun matches(node: AccessibilityNodeInfo): Boolean {
                if (!node.isVisibleToUser || node.isEditable || node === field) return false
                val value = node.text?.toString().orEmpty()
                val description = node.contentDescription?.toString().orEmpty()
                return value.contains(sample) || description.contains(sample)
            }
            fun walk(node: AccessibilityNodeInfo, depth: Int): Boolean {
                if (depth > 48) return false
                if (matches(node)) return true
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try {
                        if (walk(child, depth + 1)) return true
                    } finally { if (child !== field) child.recycle() }
                }
                return false
            }
            for (window in displayWindows(displayId, excludeKeyboard = true)) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val root = window.root ?: continue
                try {
                    if (isWeTypePackage(root.packageName?.toString())) continue
                    if (walk(root, 0)) return true
                } finally { if (root !== field) root.recycle() }
            }
            return false
        }

        override suspend fun tap(x: Int, y: Int) {
            gesture(displayId, x, y, duration = 60)
        }

        override suspend fun longPress(x: Int, y: Int) {
            val endX = if (x < screenWidth - 2) x + 1 else (x - 1).coerceAtLeast(0)
            gesture(displayId, x, y, endX, y, duration = 1000)
        }

        override suspend fun trySystemPasteShortcut(): Boolean = withContext(Dispatchers.IO) {
            // Ctrl+A first so a following paste replaces existing draft text.
            runCatching { runInputCommand(arrayOf("input", "keycombination", "113", "29")) }
            val paste = runCatching { runInputCommand(arrayOf("input", "keyevent", "279")) }.getOrDefault(false)
            val chord = runCatching { runInputCommand(arrayOf("input", "keycombination", "113", "50")) }.getOrDefault(false)
            paste || chord
        }

        override suspend fun capture(): Bitmap? {
            if (Build.VERSION.SDK_INT < 30) return null
            return try {
                withTimeoutOrNull(2_500) { captureDisplayBitmap(displayId) }
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                null
            }
        }

        private fun performTextAction(action: Int, arguments: Bundle?): Boolean {
            val node = field ?: return false
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return node.performAction(action, arguments)
        }
    }

    private suspend fun captureDisplayBitmap(displayId: Int): Bitmap {
        check(Build.VERSION.SDK_INT >= 30) { "Accessibility screenshots require Android 11 or later. Element actions remain available." }
        val capture: suspend () -> Bitmap = { withTimeout(5000) {
            suspendCancellableCoroutine { continuation ->
                takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardware = screenshot.hardwareBuffer
                        try {
                            val wrapped = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                                ?: error("Unable to read screenshot buffer.")
                            val copy = try { wrapped.copy(Bitmap.Config.ARGB_8888, false) } finally { wrapped.recycle() }
                                ?: error("Unable to copy screenshot.")
                            if (continuation.isActive) continuation.resume(copy) { _, value, _ -> value.recycle() }
                            else copy.recycle()
                        } catch (cause: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(cause)
                        } finally { hardware.close() }
                    }
                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Accessibility screenshot failed ($errorCode). Secure windows cannot be captured."))
                    }
                })
            }
        } }
        val overlay = overlayController.takeIf { displayId == Display.DEFAULT_DISPLAY }
        return if (overlay != null) overlay.withoutVisuals(capture) else capture()
    }

    suspend fun screenshot(
        displayId: Int,
        output: File,
        maxEdge: Int,
        quality: Int,
        drawGrid: Boolean = false,
    ) {
        val bitmap = captureDisplayBitmap(displayId)
        try {
            withContext(Dispatchers.IO) {
                val scale = (maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
                val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
                val framed = if (drawGrid && displayId == Display.DEFAULT_DISPLAY) {
                    drawPhoneUseGrid(scaled).also { drawn ->
                        if (drawn !== scaled && scaled !== bitmap) scaled.recycle()
                    }
                } else scaled
                try {
                    output.outputStream().use { check(framed.compress(Bitmap.CompressFormat.JPEG, quality, it)) { "Screenshot encoding failed." } }
                } finally { if (framed !== bitmap) framed.recycle() }
            }
        } finally { bitmap.recycle() }
    }

    companion object {
        @Volatile private var instance: PhoneUseAccessibilityService? = null
        @Volatile private var applicationContext: Context? = null

        fun attachApplication(context: Context) {
            applicationContext = context.applicationContext
        }
        private val _connected = MutableStateFlow(false)
        @Volatile private var requestedOverlayState: PhoneUseOverlayState? = null
        val connected = _connected.asStateFlow()
        fun requireConnected(): PhoneUseAccessibilityService = instance
            ?: error(
                (instance?.applicationContext ?: applicationContext)
                    ?.getString(R.string.agent_mode_enable_accessibility_return)
                    ?: "Enable Aether in Android Accessibility settings, then return to Aether.",
            )
        fun openSettings(context: Context) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, PhoneUseAccessibilityService::class.java)
            return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        }

        /** Updates the foreground affordance without requiring an app-owned overlay permission. */
        internal fun updateForegroundOverlay(state: PhoneUseOverlayState?) {
            if (requestedOverlayState == state) return
            requestedOverlayState = state
            instance?.scheduleOverlayRefresh()
        }
    }
}

/** A recoverable target failure; no element action has been reported as successful. */
class PhoneUseElementException(val code: String, message: String) : IllegalStateException(message)
