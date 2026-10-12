package com.zhousl.aether.phoneuse

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.text.SpannableStringBuilder
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@Suppress("DEPRECATION")
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PhoneUseAccessibilityServiceTest {
    private class QueuedReads : CoroutineDispatcher() {
        private val tasks = java.util.ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun runCurrent() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private lateinit var service: PhoneUseAccessibilityService

    @Before fun setUp() {
        service = Robolectric.buildService(PhoneUseAccessibilityService::class.java).create().get()
    }

    @After fun tearDown() { service.onDestroy() }

    private fun window(displayId: Int, windowId: Int, text: String, password: Boolean = false): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain().apply {
            this.text = text
            contentDescription = text
            packageName = "example.display.$displayId"
            isVisibleToUser = true
            isEnabled = true
            isEditable = true
            isPassword = password
            setBoundsInScreen(Rect(10, 20, 200, 80))
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
        }
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply {
            setId(windowId)
            setDisplayId(displayId)
            setRoot(node)
        }
        shadowOf(node).apply {
            setRefreshReturnValue(true)
            setAccessibilityWindowInfo(window)
        }
        shadowOf(service).setWindowsOnDisplay(displayId, listOf(window))
        return node
    }

    private fun root(displayId: Int = 0) = service.observe(displayId, 720, 1280)
        .getJSONArray("windows").getJSONObject(0).getJSONObject("root")

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected the operation to be rejected") }
        catch (_: IllegalStateException) { }
    }

    @Test fun returnToAetherTargetsTheInstalledAppAndReusesItsActivity() {
        service.requestReturnToAether()
        val intent = shadowOf(service).nextStartedActivity
        assertEquals(service.packageName, intent.component?.packageName)
        assertEquals("com.zhousl.aether.MainActivity", intent.component?.className)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test fun defaultObservationReturnsElementsWithoutCapturingAnImage() {
        window(0, 10, "Search")
        val result = service.observe(0, 720, 1280)
        assertEquals("foreground", result.getString("mode"))
        assertEquals("Search", result.getJSONArray("windows").getJSONObject(0).getJSONObject("root").getString("text"))
        assertFalse(result.has("screenshot_base64"))
        assertFalse(result.has("screenshot_path"))
        assertEquals("display_pixels", result.getString("bounds_coordinate_space"))
    }

    @Test fun backgroundObservationLeavesTheMainQueueAvailableAndPublishesUsableElements() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reads = QueuedReads()
        try {
            val node = window(0, 10, "Search")
            shadowOf(node).setOnPerformActionListener { _, _ -> true }
            val observation = async { service.observeAsync(0, 720, 1280, reads) }
            runCurrent()
            assertFalse(observation.isCompleted)
            val uiFrame = async { true }
            runCurrent()
            assertTrue(uiFrame.await())
            assertFalse(observation.isCompleted)
            reads.runCurrent()
            runCurrent()
            val result = observation.await().getJSONArray("windows").getJSONObject(0).getJSONObject("root")
            assertEquals("Search", result.getString("text"))
            service.nodeAction(0, result.getString("node_id"), "tap", "", "forward")
        } finally { Dispatchers.resetMain() }
    }

    @Test fun anInterruptedBackgroundObservationCannotRestoreInvalidatedElements() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reads = QueuedReads()
        try {
            window(0, 10, "Search")
            val observation = async { runCatching { service.observeAsync(0, 720, 1280, reads) } }
            runCurrent()
            service.onInterrupt()
            reads.runCurrent()
            runCurrent()
            assertTrue(observation.await().exceptionOrNull() is IllegalStateException)
            assertTrue(ReflectionHelpers.getField<Map<*, *>>(service, "nodes").isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun cancellationAfterReadingCannotPublishTheCompletedSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reads = QueuedReads()
        try {
            window(0, 10, "Search")
            val observation = async { service.observeAsync(0, 720, 1280, reads) }
            runCurrent()
            reads.runCurrent()
            observation.cancel()
            runCurrent()
            assertTrue(observation.isCancelled)
            assertTrue(ReflectionHelpers.getField<Map<*, *>>(service, "nodes").isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun virtualDisplayObservationNeverIncludesForegroundWindows() {
        window(0, 10, "Foreground private content")
        window(7, 20, "Background search")
        val result = service.observe(7, 720, 1280)
        assertEquals("virtual_display", result.getString("mode"))
        assertEquals(1, result.getJSONArray("windows").length())
        assertFalse(result.toString().contains("Foreground private content"))
        assertEquals("Background search", result.getJSONArray("windows").getJSONObject(0).getJSONObject("root").getString("text"))
    }

    @Test fun emptyVirtualDisplayDoesNotFallBackToTheActiveForegroundRoot() {
        val node = window(0, 10, "Private foreground")
        shadowOf(service).setRootInActiveWindow(node)
        val result = service.observe(7, 720, 1280)
        assertEquals(0, result.getInt("node_count"))
        assertEquals(0, result.getJSONArray("windows").length())
        assertFalse(result.toString().contains("Private foreground"))
    }

    @Test fun passwordTextAndDescriptionAreRedacted() {
        window(0, 10, "secret-password", password = true)
        val node = root()
        assertTrue(node.getBoolean("password"))
        assertEquals("", node.getString("text"))
        assertEquals("", node.getString("description"))
    }

    @Test fun textUsesAccessibilitySetTextWithUnicodeAndSupportsClearing() {
        val node = window(0, 10, "old")
        service.nodeAction(0, root().getString("node_id"), "text", "你好 🌍", "forward")
        var action = shadowOf(node).performedActionsWithArgs.last()
        assertEquals(AccessibilityNodeInfo.ACTION_SET_TEXT, action.first.toInt())
        assertEquals("你好 🌍", action.second.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))
        service.nodeAction(0, root().getString("node_id"), "text", "", "forward")
        action = shadowOf(node).performedActionsWithArgs.last()
        assertEquals("", action.second.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))
    }

    @Test fun aNewObservationInvalidatesOldElementIds() {
        window(0, 10, "Search")
        val oldId = root().getString("node_id")
        root()
        rejects { service.nodeAction(0, oldId, "tap", "", "forward") }
    }

    @Test fun repeatedContentUpdatesDoNotInvalidateAnUnchangedTarget() {
        val node = window(0, 10, "Search")
        val oldId = root().getString("node_id")
        repeat(30) { contentEvent(10) }
        assertFalse(service.nodeAction(0, oldId, "tap", "", "forward"))
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(node).performedActions.map { it.toInt() })
    }

    private fun contentEvent(windowId: Int) {
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            ReflectionHelpers.setField(this, "mSourceWindowId", windowId)
        })
    }

    private fun cachedNode(id: String): AccessibilityNodeInfo {
        val references = ReflectionHelpers.getField<Map<String, Any>>(service, "nodes")
        return ReflectionHelpers.getField(references.getValue(id), "node")
    }

    private fun errorCode(action: () -> Unit): String {
        try { action(); fail("Expected an element error") }
        catch (e: PhoneUseElementException) { return e.code }
        error("unreachable")
    }

    @Test fun sameTreeKeepsItsContentHashDespiteNewIdsAndNoiseEvents() {
        window(0, 10, "Search")
        val first = service.observe(0, 720, 1280)
        repeat(10) { contentEvent(10) }
        val second = service.observe(0, 720, 1280)
        assertNotEquals(first.getString("snapshot_id"), second.getString("snapshot_id"))
        assertEquals(first.getJSONArray("windows").getJSONObject(0).getString("content_hash"),
            second.getJSONArray("windows").getJSONObject(0).getString("content_hash"))
        assertEquals(10, second.getJSONArray("windows").getJSONObject(0).getInt("updates_since_last_observe"))
    }

    @Test fun aWindowDisappearingIsRejectedEvenWithoutAnEvent() {
        val node = window(0, 10, "Search")
        val id = root().getString("node_id")
        shadowOf(service).setWindowsOnDisplay(0, emptyList())
        assertEquals("window_changed", errorCode { service.nodeAction(0, id, "tap", "", "forward") })
        assertTrue(shadowOf(node).performedActions.isEmpty())
    }

    @Test fun aDifferentPackageReusingTheWindowIdCannotReceiveTheClick() {
        val node = window(0, 10, "Search")
        val id = root().getString("node_id")
        window(0, 10, "Another app").packageName = "another.package"
        assertEquals("window_changed", errorCode { service.nodeAction(0, id, "tap", "", "forward") })
        assertTrue(shadowOf(node).performedActions.isEmpty())
    }

    @Test fun aRecycledButtonChangingMeaningCannotReceiveTheOldClick() {
        val node = window(0, 10, "Save").apply { isEditable = false }
        val id = root().getString("node_id")
        cachedNode(id).text = "Delete"
        contentEvent(10)
        assertEquals("target_changed", errorCode { service.nodeAction(0, id, "tap", "", "forward") })
        assertTrue(shadowOf(node).performedActions.isEmpty())
    }

    @Test fun boundsAndEditableValueChangesKeepTheSameTargetUsable() {
        val node = window(0, 10, "Old input").apply { viewIdResourceName = "example:id/search" }
        val id = root().getString("node_id")
        cachedNode(id).apply {
            text = "New input"
            contentDescription = "New input"
            isFocused = true
            setBoundsInScreen(Rect(20, 30, 300, 100))
        }
        contentEvent(10)
        service.nodeAction(0, id, "text", "example.com", "forward")
        assertEquals(AccessibilityNodeInfo.ACTION_SET_TEXT, shadowOf(node).performedActions.last().toInt())
    }

    @Test fun aHiddenTargetIsRejectedAndReportedAsNotActionable() {
        val node = window(0, 10, "Hidden").apply { isVisibleToUser = false }
        val observation = root()
        assertFalse(observation.getBoolean("actionable"))
        assertEquals("target_not_interactable", errorCode { service.nodeAction(0, observation.getString("node_id"), "tap", "", "forward") })
        assertTrue(shadowOf(node).performedActions.isEmpty())
    }

    @Test fun anElementCannotBeClickedTwiceUsingTheSameObservation() {
        val node = window(0, 10, "Search")
        val id = root().getString("node_id")
        service.nodeAction(0, id, "tap", "", "forward")
        assertEquals("stale_observation", errorCode { service.nodeAction(0, id, "tap", "", "forward") })
        assertEquals(1, shadowOf(node).performedActions.size)
    }

    @Test fun aRebuiltControlIsReboundOnlyWhenItsIdentityIsUnique() {
        window(0, 10, "Search").apply { viewIdResourceName = "example:id/search" }
        val id = root().getString("node_id")
        shadowOf(cachedNode(id)).setRefreshReturnValue(false)
        val replacement = window(0, 10, "Search").apply { viewIdResourceName = "example:id/search" }
        assertTrue(service.nodeAction(0, id, "tap", "", "forward"))
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(replacement).performedActions.map { it.toInt() })
    }

    @Test fun aMissingTargetDoesNotMatchADifferentlyLabeledReplacement() {
        window(0, 10, "Save").apply { viewIdResourceName = "example:id/button"; isEditable = false }
        val id = root().getString("node_id")
        shadowOf(cachedNode(id)).setRefreshReturnValue(false)
        val replacement = window(0, 10, "Delete").apply { viewIdResourceName = "example:id/button"; isEditable = false }
        assertEquals("target_gone", errorCode { service.nodeAction(0, id, "tap", "", "forward") })
        assertTrue(shadowOf(replacement).performedActions.isEmpty())
    }

    @Test fun anAmbiguousReplacementIsRejectedWithoutClickingEitherCandidate() {
        window(0, 10, "Search").apply { viewIdResourceName = "example:id/search" }
        val id = root().getString("node_id")
        shadowOf(cachedNode(id)).setRefreshReturnValue(false)
        val container = window(0, 10, "").apply { isEditable = false; contentDescription = "" }
        val candidates = List(2) {
            AccessibilityNodeInfo.obtain().apply {
                text = "Search"; contentDescription = "Search"; packageName = "example.display.0"
                isEditable = true; isVisibleToUser = true; isEnabled = true
                viewIdResourceName = "example:id/search"
                setBoundsInScreen(Rect(10, 20, 200, 80))
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            }.also { shadowOf(container).addChild(it) }
        }
        assertEquals("target_ambiguous", errorCode { service.nodeAction(0, id, "tap", "", "forward") })
        candidates.forEach { assertTrue(shadowOf(it).performedActions.isEmpty()) }
    }

    private fun appWindow(node: AccessibilityNodeInfo, id: Int, type: Int, focused: Boolean): AccessibilityWindowInfo =
        AccessibilityWindowInfo.obtain().also { info ->
            shadowOf(info).apply { setId(id); setDisplayId(0); setRoot(node); setType(type); setFocused(focused) }
            shadowOf(node).setAccessibilityWindowInfo(info)
        }

    @Test fun focusedSteerOverlayIsExcludedAndDoesNotBlockUnderlyingElementActions() {
        val node = window(0, 10, "Search")
        val app = appWindow(node, 10, AccessibilityWindowInfo.TYPE_APPLICATION, false)
        val overlayNode = AccessibilityNodeInfo.obtain().apply { text = "private steer draft"; packageName = service.packageName }
        val overlay = appWindow(overlayNode, 20, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, true)
        shadowOf(overlay).setTitle("Aether Phone Use commentary")
        shadowOf(service).setWindowsOnDisplay(0, listOf(overlay, app))
        val observed = service.observe(0, 720, 1280)
        assertEquals(1, observed.getJSONArray("windows").length())
        assertFalse(observed.toString().contains("private steer draft"))
        val id = observed.getJSONArray("windows").getJSONObject(0).getJSONObject("root").getString("node_id")
        service.nodeAction(0, id, "tap", "", "forward")
        assertEquals(AccessibilityNodeInfo.ACTION_CLICK, shadowOf(node).performedActions.last().toInt())
    }

    @Test fun emptyWindowListNeverFallsBackToTheSteerDraft() {
        val overlayNode = AccessibilityNodeInfo.obtain().apply { text = "private steer draft"; packageName = service.packageName }
        val overlay = appWindow(overlayNode, 20, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, true)
        shadowOf(overlay).setTitle("Aether Phone Use commentary")
        shadowOf(service).setWindowsOnDisplay(0, emptyList())
        shadowOf(service).setRootInActiveWindow(overlayNode)
        assertEquals(0, service.observe(0, 720, 1280).getInt("node_count"))
    }

    @Test fun aNewFocusedDialogBlocksTheOldAppTarget() {
        val node = window(0, 10, "Search")
        val app = appWindow(node, 10, AccessibilityWindowInfo.TYPE_APPLICATION, true)
        shadowOf(service).setWindowsOnDisplay(0, listOf(app))
        val id = root().getString("node_id")
        val dialog = appWindow(AccessibilityNodeInfo.obtain().apply { packageName = "example.display.0" },
            11, AccessibilityWindowInfo.TYPE_APPLICATION, true)
        shadowOf(app).setFocused(false)
        shadowOf(service).setWindowsOnDisplay(0, listOf(app, dialog))
        assertEquals("window_obscured", errorCode { service.nodeAction(0, id, "tap", "", "forward") })
        assertTrue(shadowOf(node).performedActions.isEmpty())
    }

    @Test fun aKeyboardWindowDoesNotMakeTheAppInputStale() {
        val node = window(0, 10, "Search")
        val app = appWindow(node, 10, AccessibilityWindowInfo.TYPE_APPLICATION, true)
        shadowOf(service).setWindowsOnDisplay(0, listOf(app))
        val id = root().getString("node_id")
        val keyboard = appWindow(AccessibilityNodeInfo.obtain().apply { packageName = "keyboard.package" },
            11, AccessibilityWindowInfo.TYPE_INPUT_METHOD, true)
        shadowOf(service).setWindowsOnDisplay(0, listOf(app, keyboard))
        service.nodeAction(0, id, "text", "example.com", "forward")
        assertEquals(AccessibilityNodeInfo.ACTION_SET_TEXT, shadowOf(node).performedActions.last().toInt())
    }

    @Test fun foregroundUpdatesDoNotInvalidateBackgroundElements() {
        window(0, 10, "Foreground")
        val node = window(7, 20, "Background")
        val id = root(7).getString("node_id")
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { ReflectionHelpers.setField(this, "mSourceWindowId", 10) })
        service.nodeAction(7, id, "tap", "", "forward")
        assertEquals(AccessibilityNodeInfo.ACTION_CLICK, shadowOf(node).performedActions.last().toInt())
    }

    @Test fun backgroundGlobalNavigationCannotAffectTheForegroundApp() {
        rejects { service.key(7, "BACK") }
        rejects { service.key(7, "HOME") }
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }

    @Test fun anElementIdCannotBeUsedOnADifferentDisplay() {
        window(0, 10, "Foreground")
        window(7, 20, "Background")
        val id = root(7).getString("node_id")
        rejects { service.nodeAction(0, id, "tap", "", "forward") }
    }

    @Test fun coordinateGestureIsScopedToTheRequestedDisplayAndWaitsForCompletion() = runTest {
        val action = async { service.gesture(7, 120, 240, 360, 480, 500) }
        runCurrent()
        val dispatched = shadowOf(service).gesturesDispatched.single()
        assertEquals(7, dispatched.description().displayId)
        assertFalse(action.isCompleted)
        dispatched.callback().onCompleted(dispatched.description())
        action.await()
    }

    @Test fun weChatAndEmptyTreesRequireVerifiedPaste() {
        val node = window(0, 10, "4")
        node.packageName = "com.tencent.mm"
        assertTrue(service.needsVerifiedClipboardPaste(0))
        node.packageName = "com.microsoft.emmx"
        assertFalse(service.needsVerifiedClipboardPaste(0))
        node.text = ""
        node.contentDescription = ""
        node.isEditable = false
        assertTrue(service.needsVerifiedClipboardPaste(0))
        shadowOf(service).setWindowsOnDisplay(0, emptyList())
        assertTrue(service.needsVerifiedClipboardPaste(0))
    }

    @Test fun focusedInputAppDoesNotHideWeChat() {
        val chat = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.tencent.mm"
            text = "聊天记录"
            contentDescription = "聊天记录"
            isVisibleToUser = true
            isEnabled = true
        }
        val keys = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.tencent.wetype"
            text = "拼音"
            contentDescription = "拼音"
            isVisibleToUser = true
            isEnabled = true
        }
        val app = appWindow(chat, 10, AccessibilityWindowInfo.TYPE_APPLICATION, false)
        val keyboard = appWindow(keys, 11, AccessibilityWindowInfo.TYPE_APPLICATION, true)
        shadowOf(service).setWindowsOnDisplay(0, listOf(keyboard, app))
        assertTrue(service.needsVerifiedClipboardPaste(0))
    }

    @Test fun weChatActivityTitleRequiresPasteWhenTheRootPackageDiffers() {
        val node = AccessibilityNodeInfo.obtain().apply {
            packageName = "android"
            text = "聊天记录"
            contentDescription = "聊天记录"
            isVisibleToUser = true
        }
        val app = appWindow(node, 10, AccessibilityWindowInfo.TYPE_APPLICATION, true)
        shadowOf(app).setTitle("com.tencent.mm/com.tencent.mm.ui.LauncherUI")
        shadowOf(service).setWindowsOnDisplay(0, listOf(app))
        assertTrue(service.needsVerifiedClipboardPaste(0))
        shadowOf(app).setTitle("com.tencent.mm.plugin/Foo")
        node.packageName = "com.tencent.mm.plugin"
        assertFalse(service.needsVerifiedClipboardPaste(0))
    }

    @Test fun weChatChildPackageRequiresPaste() {
        val node = window(0, 10, "消息")
        node.packageName = ""
        val child = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.tencent.mm"
            isVisibleToUser = true
            text = ""
            contentDescription = ""
        }
        shadowOf(node).addChild(child)
        assertTrue(service.needsVerifiedClipboardPaste(0))
    }

    @Test fun weChatPasteDoesNotTreatRejectedSetTextAsSuccess() = runTest(timeout = 8.seconds) {
        val node = window(0, 10, "4")
        val field = SpannableStringBuilder("4")
        node.text = field
        node.packageName = "com.tencent.mm"
        shadowOf(node).setOnPerformActionListener { action, _ ->
            when (action) {
                AccessibilityNodeInfo.ACTION_SET_TEXT -> false
                AccessibilityNodeInfo.ACTION_PASTE -> {
                    field.clear()
                    field.append("你好")
                    true
                }
                else -> true
            }
        }
        val outcome = service.enterVerifiedText(0, "你好", null, null, 1080, 2400)
        assertTrue(outcome.reflection, outcome.verified)
        assertEquals("tree", outcome.verifiedBy)
        assertTrue(outcome.method == "paste" || outcome.method == "paste_overwrite")
        val clip = (service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        assertEquals("你好", clip?.getItemAt(0)?.text?.toString())
        assertTrue(shadowOf(service).gesturesDispatched.isEmpty())
    }

    @Test fun weChatClearUsesSelectionInsteadOfTheKeyboard() = runTest(timeout = 8.seconds) {
        val node = window(0, 10, "4")
        val field = SpannableStringBuilder("4")
        node.text = field
        node.packageName = "com.tencent.mm"
        shadowOf(node).setOnPerformActionListener { action, arguments ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                val value = arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString().orEmpty()
                if (value.isNotEmpty()) return@setOnPerformActionListener false
                field.clear()
            }
            true
        }
        val outcome = service.enterVerifiedText(0, "", null, null, 1080, 2400)
        assertTrue(outcome.reflection, outcome.verified)
        assertEquals("set_text_clear", outcome.method)
        assertEquals("", field.toString())
        assertTrue(shadowOf(service).gesturesDispatched.isEmpty())
    }

    @Test fun cancelledGestureReportsFailure() = runTest {
        val action = async { runCatching { service.gesture(0, 100, 200) } }
        runCurrent()
        val dispatched = shadowOf(service).gesturesDispatched.single()
        dispatched.callback().onCancelled(dispatched.description())
        assertTrue(action.await().isFailure)
    }
}
