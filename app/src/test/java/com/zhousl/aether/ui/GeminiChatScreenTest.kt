package com.zhousl.aether.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhousl.aether.data.AgentModeDisplayState
import com.zhousl.aether.termux.TermuxSetupState
import com.zhousl.aether.ui.theme.AetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "w411dp-h915dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class GeminiChatScreenTest {
    @get:Rule
    val rule = createComposeRule()

    /** Kyant backdrop ships Java 21 bytecode; D8 handles it for the APK, but a JVM 17 test runner cannot load it. */
    @Before
    fun requireJava21() {
        assumeTrue((System.getProperty("java.specification.version") ?: "0").substringBefore(".").toInt() >= 21)
    }

    private var menuClicks = 0
    private var newChatClicks = 0
    private var pauseClicks = 0

    private var hostView: android.view.View? = null

    private fun setScreen(messages: List<ChatMessage>, sending: Boolean, input: String = "") {
        rule.setContent {
            hostView = androidx.compose.ui.platform.LocalView.current
            AetherTheme(forceDark = true) {
                ConversationScreen(
                    conversationStateKey = "s1", messages = messages, workspaceDirectory = "",
                    pendingToolInvocations = emptyList(), pendingToolInvocationStateKey = "",
                    pendingResponseBlocks = emptyList(), pendingAssistantText = "", pendingStatusText = "",
                    pendingStatusDetail = "", activeTurnStartedAtMillis = null, isCompacting = false,
                    pendingInputs = emptyList(), inputValue = input, draftAttachments = emptyList(),
                    modelOptions = emptyList(), modelCatalogInfo = emptyMap(), selectedModelKey = "",
                    reasoningEffort = "", thinkingLevelsByProviderModel = emptyMap(),
                    thinkingLevelClampsByProviderModel = emptyMap(), availableSkills = emptyList(),
                    availableMcpServers = emptyList(), selectedSkillIds = emptyList(), selectedMcpServerIds = emptyList(),
                    agentModeAvailable = false, agentModeSelected = false, agentModeDisplayState = AgentModeDisplayState(),
                    chromeAvailable = false, chromeSelected = false, chromeDisplayState = AgentModeDisplayState(),
                    isEditing = false, termuxSetupState = TermuxSetupState(), showStarterPromptHint = false,
                    showTermuxSetupNotice = false, onInputChanged = {}, onModelSelected = { _, _ -> },
                    onModelSelectorOpened = {}, onReasoningEffortSelected = {}, onRemoveDraftAttachment = {},
                    onSetSkillSelected = { _, _ -> }, onSetMcpServerSelected = { _, _ -> }, onSetAgentModeSelected = {},
                    onToggleAgentModeSelected = {}, onSetChromeSelected = {}, onCancelEdit = {}, onSend = {},
                    onQueueFollowUp = {}, onSteerFollowUp = {}, onMenu = { menuClicks++ }, onNewChat = { newChatClicks++ },
                    onPickImages = {}, onPickFiles = {}, onSaveAttachment = {}, onOpenLink = {}, onEditMessage = {},
                    onDeleteMessage = {}, onRedoAgentMessage = {}, onRetryUserMessage = {},
                    onSwitchUserMessageBranch = { _, _ -> }, onCopyMessage = {}, onRequestTermuxPermission = {},
                    onOpenAppPermissions = {}, onOpenTermuxSettings = {}, onOpenTermux = {}, onInstallTermux = {},
                    onRefreshTermuxSetup = {}, onAttachAgentModePreviewSurface = {}, onDetachAgentModePreviewSurface = {},
                    onPauseGeneration = { pauseClicks++ }, onDismissTermuxSetupNotice = {}, onDismissStarterPromptHint = {},
                    isSending = sending,
                )
            }
        }
    }

    @Test
    fun conversationShowsGlassChromeAndThinkingGlowWhileSending() {
        setScreen(
            listOf(
                ChatMessage(id = "u1", author = MessageAuthor.User, text = "写一首诗"),
                ChatMessage(id = "a1", author = MessageAuthor.Agent, text = "风轻轻地翻过书页"),
            ),
            sending = true,
        )
        rule.onNodeWithText("写一首诗").assertIsDisplayed()
        rule.onNodeWithText("风轻轻地翻过书页").assertIsDisplayed()
        assertEquals(1, rule.onAllNodesWithTag("bottomBlueGlow").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithTag("thinkingGlow").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Menu", substring = true, ignoreCase = true).performClick()
        rule.onNodeWithContentDescription("Stop", ignoreCase = true).performClick()
        assertEquals(1, menuClicks)
        assertEquals(1, pauseClicks)
    }

    @Test
    fun idleConversationHasNoThinkingGlowAndNewChatWorks() {
        setScreen(listOf(ChatMessage(id = "u1", author = MessageAuthor.User, text = "你好")), sending = false)
        assertEquals(0, rule.onAllNodesWithTag("thinkingGlow").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("inputStars").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("New chat", ignoreCase = true).performClick()
        assertEquals(1, newChatClicks)
    }

    /**
     * 2.1.26 bug: the "+" button covered the end of the last AI reply. The composer sits above the navigation bar
     * plus an 18dp lift, but the list's bottom padding ignored the navigation bar, so the last ~navbar px of the
     * conversation stayed under the composer even when scrolled to the very bottom.
     */
    @Test
    fun lastReplyEndsAboveComposerWithNavigationBar() {
        val longReply = (1..400).joinToString(" ") { "第${it}句回答内容。" } + " 最后一句结尾"
        setScreen(
            listOf(
                ChatMessage(id = "u1", author = MessageAuthor.User, text = "问题"),
                ChatMessage(id = "a1", author = MessageAuthor.Agent, text = longReply),
            ),
            sending = false,
        )
        rule.runOnIdle {
            val navBar = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, 144))
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, 96, 0, 0))
                .build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(hostView!!, navBar)
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val plusTop = rule.onNodeWithTag("composerPlus").fetchSemanticsNode().boundsInRoot.top
        val textBottom = rule.onNodeWithText("最后一句结尾", substring = true).fetchSemanticsNode().boundsInRoot.bottom
        val bars = rule.onAllNodesWithTag("assistantActionBar").fetchSemanticsNodes()
        val contentBottom = maxOf(textBottom, bars.maxOfOrNull { it.boundsInRoot.bottom } ?: 0f)
        org.junit.Assert.assertTrue(
            "last reply content bottom=$contentBottom must stay above the + button top=$plusTop",
            contentBottom <= plusTop,
        )
    }
}
