package com.zhousl.aether.phoneuse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PhoneUseTextEntryTest {
    @Test fun packagesAndAliases() {
        assertTrue(isWeChatPackage("com.tencent.mm"))
        assertFalse(isWeChatPackage("com.tencent.mm.plugin"))
        assertFalse(isWeChatPackage("com.microsoft.emmx"))
        assertEquals("text", normalizePhoneUseAction("fill_text"))
        assertEquals("tap", normalizePhoneUseAction("tap"))
    }

    @Test fun readbackRejectsUnchangedAndAppendedText() {
        assertFalse(nodeReadbackVerified("4", "4", "你好"))
        assertFalse(nodeReadbackVerified("4", "4你好", "你好"))
        assertFalse(nodeReadbackVerified(null, null, "你好"))
        assertFalse(nodeReadbackVerified("", "", ""))
        assertTrue(nodeReadbackVerified("4", "", ""))
        assertTrue(nodeReadbackVerified("4", "你好", "你好"))
        assertTrue(nodeReadbackVerified(null, "你好", "你好"))
    }

    @Test fun pastePointsStayAboveTheKeyboard() {
        val points = pasteTapCandidates(500, 1900, density = 2f, screenWidth = 1080, screenHeight = 2400, keyboardTop = 2000)
        assertEquals(listOf(500 to 1804, 500 to 1756), points)
        assertTrue(points.all { it.second < 2000 })
        val keyboard = Rect(0, 2000, 1080, 2400)
        assertTrue(shouldRejectKeyboardTap(true, keyboard, 100, 2100))
        assertFalse(shouldRejectKeyboardTap(true, keyboard, 100, 1800))
        assertFalse(shouldRejectKeyboardTap(false, keyboard, 100, 2100))
    }

    @Test fun rejectedSetTextIsNotSuccessUntilPasteReadbackMatches() = runTest {
        val host = FakeHost()
        host.acceptSetText = true
        host.applySetText = false
        host.acceptPaste = true
        host.applyPaste = true
        val outcome = PhoneUseTextEntry(host).enter("你好")
        assertTrue(outcome.verified)
        assertEquals("你好", host.text)
        assertEquals("你好", host.clip)
        assertTrue(outcome.method == "paste" || outcome.method == "paste_overwrite")
        assertTrue(host.presses.isEmpty())
    }

    @Test fun appendedPasteIsSelectedAndOverwritten() = runTest {
        val host = FakeHost()
        host.pasteBehavior = { attempt, clip ->
            when (attempt) {
                1 -> false
                2 -> {
                    host.text = "4$clip"
                    true
                }
                else -> {
                    host.text = clip
                    true
                }
            }
        }
        val outcome = PhoneUseTextEntry(host).enter("你好")
        assertTrue(outcome.reflection, outcome.verified)
        assertEquals("paste_overwrite", outcome.method)
        assertEquals("你好", host.text)
        assertTrue(host.presses.isEmpty())
    }

    @Test fun clearFallsBackToCutWhenSetTextDoesNotChangeTheField() = runTest {
        val host = FakeHost()
        host.acceptSetText = true
        host.applySetText = false
        host.acceptCut = true
        val outcome = PhoneUseTextEntry(host).enter("")
        assertTrue(outcome.verified)
        assertEquals("cut_selection", outcome.method)
        assertEquals("", host.text)
        assertTrue(host.presses.isEmpty())
    }

    @Test fun longPressTapsPasteLabelWhenNodeActionsFail() = runTest {
        val host = FakeHost()
        host.labels = mapOf("粘贴" to (420 to 1700))
        val outcome = PhoneUseTextEntry(host).enter("你好")
        assertTrue(outcome.reflection, outcome.verified)
        assertEquals("gesture_paste", outcome.method)
        assertEquals("tree", outcome.verifiedBy)
        assertEquals("你好", host.text)
        assertTrue(host.presses.isNotEmpty())
        assertTrue(host.taps.contains(420 to 1700))
    }

    @Test fun failedPasteReturnsCopyableChineseReflection() = runTest {
        val host = FakeHost()
        val outcome = PhoneUseTextEntry(host).enter("你好")
        assertFalse(outcome.verified)
        assertTrue(outcome.reflection.contains("不能当作成功"))
        assertTrue(outcome.reflection.contains("微信 com.tencent.mm"))
        assertTrue(outcome.reflection.contains("不要点软键盘退格，也不要请用户手动粘贴"))
        assertTrue(outcome.reflection.contains("输入法"))
        assertFalse(outcome.reflection.contains("请人手粘贴"))
        assertEquals(keyboardTapReflection().contains("不要点软键盘退格"), true)
    }

    @Test fun glyphLocatorFindsRenderedTextAndIgnoresABlankField() {
        val locator = PhoneUseGlyphLocator()
        val template = locator.renderTemplate("Paste", 32f)
        assertNotNull(template)
        val ink = darkInkCount(template!!)
        if (ink < 8) return
        val scene = Bitmap.createBitmap(240, 140, Bitmap.Config.ARGB_8888)
        Canvas(scene).apply {
            drawColor(Color.WHITE)
            drawBitmap(template, 40f, 28f, null)
        }
        val found = locator.find(scene, "Paste", Rect(0, 0, 240, 140), floatArrayOf(32f))
        assertNotNull(found)
        assertTrue(kotlin.math.abs(found!!.left - 40) <= 4)
        assertTrue(kotlin.math.abs(found.top - 28) <= 4)
        val blank = Bitmap.createBitmap(240, 140, Bitmap.Config.ARGB_8888)
        Canvas(blank).drawColor(Color.WHITE)
        assertNull(locator.find(blank, "Paste", Rect(0, 0, 240, 140), floatArrayOf(32f)))
        template.recycle()
        scene.recycle()
        blank.recycle()
    }

    @Test fun screenshotBandIgnoresIdenticalPixels() {
        fun solid(color: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(IntArray(60 * 40) { color }, 0, 60, 0, 0, 60, 40)
            return bitmap
        }
        val before = solid(Color.WHITE)
        val same = solid(Color.WHITE)
        val region = Rect(0, 0, 60, 40)
        assertFalse(bitmapRegionChanged(before, same, region))
        val changed = solid(Color.WHITE)
        val pixels = IntArray(60 * 40) { Color.WHITE }
        for (y in 4 until 30) for (x in 4 until 50) pixels[y * 60 + x] = Color.BLACK
        changed.setPixels(pixels, 0, 60, 0, 0, 60, 40)
        assertTrue(bitmapRegionChanged(before, changed, region))
        before.recycle()
        same.recycle()
        changed.recycle()
    }

    @Test fun inkOverlapScoresAnExactShape() {
        val width = 6
        val height = 6
        val haystack = BooleanArray(width * height)
        val needle = BooleanArray(9)
        needle[0] = true
        needle[1] = true
        needle[2] = true
        needle[4] = true
        needle[7] = true
        for (y in 0 until 3) for (x in 0 until 3) {
            if (needle[y * 3 + x]) haystack[(y + 2) * width + (x + 1)] = true
        }
        assertEquals(1f, inkOverlapScore(haystack, width, height, needle, 3, 3, 1, 2), 0.001f)
        assertTrue(inkOverlapScore(haystack, width, height, needle, 3, 3, 0, 0) < 0.5f)
    }

    private fun darkInkCount(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { pixel ->
            Color.alpha(pixel) > 40 && (Color.red(pixel) * 3 + Color.green(pixel) * 6 + Color.blue(pixel)) / 10 < 150
        }
    }

    private class FakeHost : PhoneUseTextHost {
        var text: String? = "4"
        var clip: String = ""
        var acceptSetText = false
        var applySetText = false
        var acceptPaste = false
        var applyPaste = false
        var acceptSelect = true
        var acceptCut = false
        var pasteBehavior: ((attempt: Int, clip: String) -> Boolean)? = null
        var pastes = 0
        val taps = mutableListOf<Pair<Int, Int>>()
        val presses = mutableListOf<Pair<Int, Int>>()
        var labels: Map<String, Pair<Int, Int>> = emptyMap()
        override val weChat = true
        override val emptyTree = true
        override val density = 2f
        override val screenWidth = 1080
        override val screenHeight = 2400
        override fun writeClipboard(text: String) { clip = text }
        override fun fieldText(): String? = text
        override fun trySetText(text: String): Boolean {
            if (!acceptSetText) return false
            if (applySetText) this.text = text
            return true
        }
        override fun trySelectAll(): Boolean = acceptSelect
        override fun tryPaste(): Boolean {
            pastes++
            pasteBehavior?.let { return it(pastes, clip) }
            if (!acceptPaste) return false
            if (applyPaste) text = clip
            return true
        }
        override fun tryCut(): Boolean {
            if (!acceptCut) return false
            text = ""
            return true
        }
        override fun labelCenter(labels: List<String>): Pair<Int, Int>? =
            labels.firstNotNullOfOrNull { this.labels[it] }
        override fun anchor(): Pair<Int, Int> = 500 to 1800
        override fun keyboardTop(): Int? = 2000
        override suspend fun tap(x: Int, y: Int) {
            taps += x to y
            val label = labels.entries.firstOrNull { it.value == x to y }?.key ?: return
            if (label == "粘贴" || label == "Paste") text = clip
            if (label == "剪切" || label == "Cut") text = ""
        }
        override suspend fun longPress(x: Int, y: Int) { presses += x to y }
        override suspend fun capture(): Bitmap? = null
    }
}
