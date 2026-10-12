package com.zhousl.aether.phoneuse

import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.SessionExecutionState
import com.zhousl.aether.ui.AssistantResponseBlock
import org.junit.Assert.*
import org.junit.Test

class PhoneUseOverlayStateTest {
    private val settings = AppSettings(agentModeAuthorizationEnabled = true)
    private val speech = AssistantResponseBlock.Text("commentary-1", "正在打开 Chrome，然后检查页面。")
    private val running = SessionExecutionState("phone", isRunning = true, phoneUseEnabled = true,
        phoneUseCommentary = speech, activeTurnStartedAtMillis = 100L)

    @Test fun retainsThePublicCommentaryDuringToolsReasoningReconnectAndSteer() {
        val state = running.copy(pendingAssistantText = "", pendingResponseBlocks = emptyList(),
            pendingStatusText = "Thinking", pendingStatusDetail = "Reconnecting…")
        val overlay = foregroundPhoneUseOverlay(settings, listOf(state))!!
        assertEquals(speech.text, overlay.commentary)
        assertEquals(speech.id, overlay.commentaryId)
    }

    @Test fun streamingUpdatesKeepIdentityAndNextCommentaryReplacesIt() {
        val first = foregroundPhoneUseOverlay(settings, listOf(running))!!
        val append = foregroundPhoneUseOverlay(settings, listOf(running.copy(phoneUseCommentary = speech.copy(text = speech.text + "下一步。"))))!!
        assertEquals(first.commentaryId, append.commentaryId)
        assertTrue(append.commentary.endsWith("下一步。"))
        val next = foregroundPhoneUseOverlay(settings, listOf(running.copy(phoneUseCommentary = AssistantResponseBlock.Text("commentary-2", "已经打开。"))))!!
        assertNotEquals(append.commentaryId, next.commentaryId)
        assertEquals("已经打开。", next.commentary)
    }

    @Test fun visualFeedbackFlagsDefaultOnAndFollowSettingsWithoutStoppingTheRun() {
        val shown = foregroundPhoneUseOverlay(settings, listOf(running))!!
        assertTrue(shown.showCommentary)
        assertTrue(shown.showEdgeGlow)
        val bubbleOff = foregroundPhoneUseOverlay(settings.copy(phoneUseShowCommentary = false), listOf(running))!!
        assertFalse(bubbleOff.showCommentary)
        assertTrue(bubbleOff.showEdgeGlow)
        assertEquals(running.sessionId, bubbleOff.sessionId)
        val bothOff = foregroundPhoneUseOverlay(
            settings.copy(phoneUseShowCommentary = false, phoneUseShowEdgeGlow = false),
            listOf(running),
        )!!
        assertFalse(bothOff.showCommentary)
        assertFalse(bothOff.showEdgeGlow)
        assertEquals(100L, bothOff.runId)
    }

    @Test fun stopDisabledPreferenceAndVirtualDisplayRemoveFeedback() {
        assertNull(foregroundPhoneUseOverlay(settings, listOf(running.copy(isRunning = false))))
        assertNull(foregroundPhoneUseOverlay(settings.copy(agentModeAuthorizationEnabled = false), listOf(running)))
        assertNull(foregroundPhoneUseOverlay(settings.copy(phoneUseVirtualDisplayEnabled = true), listOf(running)))
        assertNull(foregroundPhoneUseOverlay(settings, listOf(running.copy(phoneUseEnabled = false))))
    }

    @Test fun neverDisplaysInAetherOnLockScreenOrWithoutAnIdentifiedApp() {
        val state = foregroundPhoneUseOverlay(settings, listOf(running))
        assertFalse(shouldShowPhoneUseOverlay(state, "aether", "aether", true))
        assertFalse(shouldShowPhoneUseOverlay(state, "chrome", "aether", false))
        assertFalse(shouldShowPhoneUseOverlay(state, null, "aether", true))
        assertFalse(shouldShowPhoneUseOverlay(null, "chrome", "aether", true))
        assertTrue(shouldShowPhoneUseOverlay(state, "chrome", "aether", true))
    }

    @Test fun simultaneousSessionsPinSteerAndCommentaryToTheNewestRunningPhoneUse() {
        val latest = running.copy(sessionId = "latest", activeTurnStartedAtMillis = 200, phoneUseCommentary = null)
        val chat = running.copy(sessionId = "ordinary", phoneUseEnabled = false, activeTurnStartedAtMillis = 300)
        val selected = foregroundPhoneUseOverlay(settings, listOf(running, latest, chat))!!
        assertEquals("latest", selected.sessionId)
        assertEquals(200L, selected.runId)
        assertEquals("", selected.commentary)
    }
}
