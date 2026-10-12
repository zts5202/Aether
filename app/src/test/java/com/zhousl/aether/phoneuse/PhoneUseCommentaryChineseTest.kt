package com.zhousl.aether.phoneuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUseCommentaryChineseTest {
    @Test fun englishThinkingBubbleBecomesShortChinese() {
        val shown = phoneUseCommentaryForOverlay(
            "The keyboard is covering the send button. Let me check the current screenshot to locate it, or press ENTER key which sends in WeChat.",
        )
        assertFalse(shown.contains(Regex("[A-Za-z]")))
        assertTrue(shown.contains("键盘挡住了发送键"))
        assertTrue(shown.contains("回车"))
        assertTrue(shown.endsWith("。"))
    }

    @Test fun chineseCommentaryIsKept() {
        val original = "正在打开 Chrome，然后检查页面。"
        assertEquals(original, phoneUseCommentaryForOverlay(original))
    }

    @Test fun openingChromeDoesNotStayInEnglish() {
        val shown = phoneUseCommentaryForOverlay("Opening Chrome")
        assertEquals("正在打开浏览器。", shown)
        assertFalse(shown.contains(Regex("[A-Za-z]")))
    }

    @Test fun unrecognizedEnglishUsesChineseFallback() {
        assertEquals("正在思考下一步。", phoneUseCommentaryForOverlay("Hello there, this is unrelated."))
    }

    @Test fun blankStaysBlank() {
        assertEquals("", phoneUseCommentaryForOverlay("  \n\t "))
    }

    @Test fun generatedChineseIsStable() {
        val once = phoneUseCommentaryForOverlay("I will paste from the clipboard, then swipe up.")
        assertEquals(once, phoneUseCommentaryForOverlay(once))
        assertTrue(once.contains("剪贴板"))
        assertFalse(once.contains(Regex("[A-Za-z]")))
    }
}
