package com.zhousl.aether.phoneuse

import android.app.Application
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.robolectric.shadows.ShadowChoreographer
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneUseOverlayControllerTest {
    @Before fun realFrameCadence() { ShadowChoreographer.setPaused(true); ShadowChoreographer.setFrameDelay(Duration.ofMillis(16)) }
    private fun <T> field(controller: PhoneUseOverlayController, name: String): T = ReflectionHelpers.getField(controller, name)
    private val state = PhoneUseOverlayState("session", 42L, "message-1", "正在打开 Chrome。🌈 这条回复应该持续展示，工具执行时也不会消失。")

    @Test fun returningToAetherRemovesBothWindowsAndDisposeCannotResurrectThem() {
        val service = Robolectric.buildService(PhoneUseAccessibilityService::class.java).create().get()
        val controller = PhoneUseOverlayController(service) { _, _ -> false }
        try {
            controller.update(state, true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            val edge = field<View>(controller, "edge")
            val capsule = field<View>(controller, "capsule")
            assertTrue(edge.isAttachedToWindow)
            assertTrue(capsule.isAttachedToWindow)
            assertTrue((edge.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue((capsule.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            controller.update(state, false)
            assertTrue((capsule.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertFalse(edge.isAttachedToWindow)
            assertFalse(capsule.isAttachedToWindow)
            controller.dispose()
            controller.update(state, true)
            assertNull(field<View?>(controller, "edge"))
        } finally { controller.dispose(); service.onDestroy() }
    }

    @Test fun doubleTapSteerUsesTheOriginalSessionAndDoesNotOpenAnActivity() {
        val service = Robolectric.buildService(PhoneUseAccessibilityService::class.java).create().get()
        var submitted: Pair<PhoneUseOverlayState, String>? = null
        val controller = PhoneUseOverlayController(service) { target, text -> submitted = target to text; true }
        try {
            controller.update(state, true)
            val text = field<View>(controller, "commentary")
            val start = android.os.SystemClock.uptimeMillis()
            listOf(0L to MotionEvent.ACTION_DOWN, 30L to MotionEvent.ACTION_UP,
                100L to MotionEvent.ACTION_DOWN, 130L to MotionEvent.ACTION_UP).forEach { (delay, action) ->
                val event = MotionEvent.obtain(start, start + delay, action, 20f, 20f, 0)
                text.dispatchTouchEvent(event)
                event.recycle()
            }
            assertTrue(controller.isEditing)
            val editor = field<EditText>(controller, "editor")
            editor.setText("请继续，但先查看页面标题。")
            controller.update(state.copy(commentaryId = "message-2", commentary = "正在读取页面。"), true)
            assertEquals("请继续，但先查看页面标题。", editor.text.toString())
            editor.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
            assertEquals(state, submitted?.first)
            assertEquals("请继续，但先查看页面标题。", submitted?.second)
            assertFalse(controller.isEditing)
            assertNull(shadowOf(service).nextStartedActivity)
        } finally { controller.dispose(); service.onDestroy() }
    }

    @Test fun keyboardDisplayBoundsLiftSteerEvenWhenTheSmallWindowHasNoImeInsets() {
        val service = Robolectric.buildService(PhoneUseAccessibilityService::class.java).create().get()
        val controller = PhoneUseOverlayController(service) { _, _ -> true }
        try {
            controller.update(state, true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            ReflectionHelpers.callInstanceMethod<Unit>(controller, "openEditor")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            val editor = field<EditText>(controller, "editor")
            editor.setText("保留这条正在输入的指令")
            val host = field<View>(controller, "capsule")
            val params = host.layoutParams as WindowManager.LayoutParams
            val restingY = params.y
            val displayHeight = field<Int>(controller, "screenHeight")
            val displayWidth = field<Int>(controller, "screenWidth")
            val keyboardTop = displayHeight / 2
            controller.updateKeyboardBounds(android.graphics.Rect(0, keyboardTop, displayWidth, displayHeight))
            // OEM overlay windows can report IME=0 even while the keyboard covers the screen.
            host.dispatchApplyWindowInsets(android.view.WindowInsets.Builder().build())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            // Complete the animator explicitly: this service-only test has no hardware vsync.
            field<android.animation.ValueAnimator?>(controller, "keyboardAnimator")?.end()
            assertTrue(params.y < restingY)
            assertTrue(params.y + host.measuredHeight <= keyboardTop - 12)
            assertEquals("保留这条正在输入的指令", editor.text.toString())
            controller.updateKeyboardBounds(null)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            field<android.animation.ValueAnimator?>(controller, "keyboardAnimator")?.end()
            assertEquals(restingY, params.y)
            assertTrue(controller.isEditing)
        } finally { controller.dispose(); service.onDestroy() }
    }

    @Test fun aShortCommentaryIsVisuallyCenteredWithinTheMinimumHeight() {
        val view = PhoneUseCommentaryView(org.robolectric.RuntimeEnvironment.getApplication())
        view.setCommentary("short", "Opening Chrome")
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val rows = (0 until bitmap.height).filter { y ->
            (0 until bitmap.width).any { x -> android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 100 }
        }
        assertTrue(rows.isNotEmpty())
        val topGap = rows.first()
        val bottomGap = bitmap.height - 1 - rows.last()
        assertTrue("Uneven text padding: top=$topGap bottom=$bottomGap", kotlin.math.abs(topGap - bottomGap) <= 3)
        bitmap.recycle()
    }

    @Test @Config(sdk = [35]) fun modernEdgeShaderRendersOnlyTheScreenPerimeter() {
        val view = PhoneUseEdgeView(org.robolectric.RuntimeEnvironment.getApplication())
        view.layout(0, 0, 320, 640)
        assertEquals(View.LAYER_TYPE_NONE, view.layerType)
        view.start()
        // Window attachment may take time. Entry must begin with the first rendered frame.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        val node = android.graphics.RenderNode("edge-test").apply { setPosition(0, 0, 320, 640) }
        view.draw(node.beginRecording())
        node.endRecording()
        val first = hardwareBitmap(node)
        assertEquals(0, android.graphics.Color.alpha(first.getPixel(1, 320)))
        first.recycle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        view.draw(node.beginRecording())
        node.endRecording()
        val bitmap = hardwareBitmap(node)
        assertTrue(android.graphics.Color.alpha(bitmap.getPixel(1, 320)) > 100)
        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(160, 320)))
        view.stop()
        bitmap.recycle()
    }

    private fun dp(service: android.content.Context, value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    private fun hardwareBitmap(node: android.graphics.RenderNode): android.graphics.Bitmap {
        val hardware = ReflectionHelpers.callStaticMethod<android.graphics.Bitmap>(
            android.graphics.HardwareRenderer::class.java, "createHardwareBitmap",
            ReflectionHelpers.ClassParameter.from(android.graphics.RenderNode::class.java, node),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 320),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 640),
        )
        val bitmap = hardware.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        hardware.recycle()
        return bitmap
    }

    @Test fun commentaryStaysOnTheRightRailClearOfTheSendZone() {
        val service = Robolectric.buildService(PhoneUseAccessibilityService::class.java).create().get()
        val controller = PhoneUseOverlayController(service) { _, _ -> false }
        try {
            controller.update(
                state.copy(
                    commentaryId = "english",
                    commentary = "The keyboard is covering the send button. Let me check the current screenshot to locate it, or press ENTER key which sends in WeChat.",
                ),
                true,
            )
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            val host = field<View>(controller, "capsule")
            val params = host.layoutParams as WindowManager.LayoutParams
            val screenWidth = field<Int>(controller, "screenWidth")
            val screenHeight = field<Int>(controller, "screenHeight")
            assertTrue(screenWidth > 0 && screenHeight > 0)
            assertTrue(params.width * 2 < screenWidth)
            assertTrue(params.x >= screenWidth / 2)
            assertTrue(params.x + params.width <= screenWidth)
            val bottom = params.y + host.height
            assertTrue("bottom=$bottom screen=$screenHeight", bottom < screenHeight - dp(service, 120))
            assertTrue(params.y < screenHeight / 2)
            val shown = ReflectionHelpers.getField<String>(field<View>(controller, "commentary"), "value")
            assertFalse(shown.contains(Regex("[A-Za-z]")))
            assertTrue(shown.contains("键盘"))
            assertTrue(shown.contains("发送"))
        } finally { controller.dispose(); service.onDestroy() }
    }

    @Test @Config(sdk = [31]) fun commentaryWindowBlursWhatIsBehindIt() {
        val service = Robolectric.buildService(PhoneUseAccessibilityService::class.java).create().get()
        val controller = PhoneUseOverlayController(service) { _, _ -> false }
        try {
            controller.update(state, true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            val host = field<View>(controller, "capsule")
            val edge = field<View>(controller, "edge")
            val params = host.layoutParams as WindowManager.LayoutParams
            val edgeParams = edge.layoutParams as WindowManager.LayoutParams
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0)
            assertTrue(params.blurBehindRadius > 0)
            assertEquals(0, edgeParams.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        } finally { controller.dispose(); service.onDestroy() }
    }

    @Test fun capsuleFillIsTranslucentGlass() {
        val view = PhoneUseCapsuleLayout(org.robolectric.RuntimeEnvironment.getApplication())
        view.layout(0, 0, 220, 72)
        val bitmap = android.graphics.Bitmap.createBitmap(220, 72, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val pixel = bitmap.getPixel(110, 36)
        val alpha = android.graphics.Color.alpha(pixel)
        assertTrue("alpha=$alpha", alpha in 80..220)
        bitmap.recycle()
    }

    @Test fun chineseEmojiAndLongUnbrokenTextHaveVisibleBoundedLayout() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val view = PhoneUseCommentaryView(app)
        val longText = "这是完整的中文段落，没有空格也必须正确换行。🌈 ".repeat(30) + "x".repeat(200)
        view.setCommentary("long", longText)
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val heightDp = view.measuredHeight / view.resources.displayMetrics.density
        assertTrue("heightDp=$heightDp", heightDp in 36f..88f)
        val layout = ReflectionHelpers.getField<android.text.StaticLayout>(view, "layout")
        assertTrue(layout.lineCount > 4)
        assertEquals(longText, layout.text.toString())
    }
}
